package chrepl.transfer

import chrepl.clickhouse.ClickHouseClient
import chrepl.clickhouse.Sql
import chrepl.config.model.TableSpec
import chrepl.types.ChType
import chrepl.types.scalar
import java.time.LocalDate

/** What to copy from one table: a COB slice, or everything (`full_copy`, refreshes). */
sealed interface Selection {
    data class Cob(
        val dates: List<LocalDate>,
    ) : Selection {
        init {
            require(dates.isNotEmpty()) { "a COB selection needs at least one date" }
        }
    }

    data object All : Selection

    /** Whole partitions by id (continuous mode and scheduled refreshes, SPEC §8). */
    data class Partitions(
        val ids: Set<String>,
    ) : Selection
}

/** A key range `[from, to)` on the first primary-key column; null bounds are open. */
data class KeyRange(
    val column: String,
    val type: ChType,
    val from: String?,
    val to: String?,
)

/** One read: a partition of a table on one source shard, optionally narrowed by COB and key range (SPEC §5.3). */
data class Chunk(
    val id: String,
    val shard: String,
    val partitionId: String,
    val selection: Selection,
    val range: KeyRange?,
    val estimatedBytes: Long,
    /**
     * For whole-partition reads of a COB table: the partition's own date range on the COB column.
     * It excludes nothing, but satisfies `force_index_by_date` in the reader profile, which a
     * `_partition_id` predicate alone does not (found in M5 testing).
     */
    val dateBounds: DateBounds? = null,
)

data class DateBounds(
    val column: String,
    val from: LocalDate,
    val to: LocalDate,
)

data class PartitionPlan(
    val partitionId: String,
    val chunks: List<Chunk>,
    val rows: Long,
    val bytes: Long,
)

data class ShardPlan(
    val shard: String,
    val partitions: List<PartitionPlan>,
)

data class TablePlan(
    val table: String,
    val selection: Selection,
    val shards: List<ShardPlan>,
) {
    val chunks: List<Chunk> get() = shards.flatMap { s -> s.partitions.flatMap { it.chunks } }
    val partitionIds: Set<String> get() = shards.flatMap { s -> s.partitions.map { it.partitionId } }.toSet()
    val rows: Long get() = shards.sumOf { s -> s.partitions.sumOf { it.rows } }
    val bytes: Long get() = shards.sumOf { s -> s.partitions.sumOf { it.bytes } }
}

/**
 * Plans chunks from metadata (SPEC §5.3): `system.parts` per shard maps COB dates to partitions
 * through the partition key's min/max dates; partitions larger than the chunk size are split into
 * ranges of the first primary-key column taken from the primary index (`mergeTreeIndex`).
 */
class ChunkPlanner(
    private val maxChunkBytes: Long,
) {
    fun plan(
        spec: TableSpec,
        info: TableInfo,
        selection: Selection,
        shards: Map<String, ClickHouseClient>,
    ): TablePlan {
        val (database, table) = spec.source.split('.', limit = 2)
        val shardPlans =
            shards.map { (shard, client) ->
                val parts = partitions(client, database, table)
                val selected =
                    when (selection) {
                        Selection.All -> parts
                        is Selection.Partitions -> parts.filter { it.id in selection.ids }
                        is Selection.Cob -> cobPartitions(client, spec, parts, selection)
                    }
                ShardPlan(
                    shard,
                    selected.map { p ->
                        val ranges = split(client, info, p)
                        val chunks =
                            ranges.mapIndexed {
                                i,
                                range,
                                ->
                                Chunk("$shard-${p.id}-$i", shard, p.id, selection, range, p.bytes / ranges.size, bounds(spec, selection, p))
                            }
                        PartitionPlan(p.id, chunks, p.rows, p.bytes)
                    },
                )
            }
        return TablePlan(spec.source, selection, shardPlans)
    }

    private fun bounds(
        spec: TableSpec,
        selection: Selection,
        p: Partition,
    ): DateBounds? {
        if (selection is Selection.Cob) return null
        val column = spec.cobColumn ?: return null
        return if (p.minDate != null && p.maxDate != null) DateBounds(column, p.minDate, p.maxDate) else null
    }

    internal data class Partition(
        val id: String,
        val rows: Long,
        val bytes: Long,
        val minDate: LocalDate?,
        val maxDate: LocalDate?,
        val parts: List<String>,
    )

    private fun partitions(
        client: ClickHouseClient,
        database: String,
        table: String,
    ): List<Partition> =
        client
            .queryJson(
                """
                SELECT partition_id, sum(rows) AS rows, sum(bytes_on_disk) AS bytes,
                       toString(min(min_date)) AS min_d, toString(max(max_date)) AS max_d,
                       toString(toDate(min(min_time))) AS min_t, toString(toDate(max(max_time))) AS max_t,
                       max(max_date) = '1970-01-01' AND max(max_time) = 0 AS no_dates,
                       max(max_date) != '1970-01-01' AS by_date,
                       groupArray(name) AS parts
                FROM system.parts
                WHERE active AND database = {db:String} AND table = {t:String}
                GROUP BY partition_id ORDER BY partition_id
                """.trimIndent(),
                mapOf("param_db" to database, "param_t" to table),
            ).map {
                val noDates = it["no_dates"].asInt() == 1
                // The partition key's minmax index is either on a Date column (min/max_date) or a DateTime column (min/max_time).
                val prefix = if (it["by_date"].asInt() == 1) "_d" else "_t"
                Partition(
                    id = it["partition_id"].textValue(),
                    rows = it["rows"].asLong(),
                    bytes = it["bytes"].asLong(),
                    minDate = if (noDates) null else LocalDate.parse(it["min$prefix"].textValue()),
                    maxDate = if (noDates) null else LocalDate.parse(it["max$prefix"].textValue()),
                    parts = it["parts"].map { p -> p.textValue() },
                )
            }

    /** Partitions that may hold any of the COB dates; falls back to an index-pruned query when the partition key has no date. */
    private fun cobPartitions(
        client: ClickHouseClient,
        spec: TableSpec,
        parts: List<Partition>,
        selection: Selection.Cob,
    ): List<Partition> {
        if (parts.all { it.minDate != null }) {
            return parts.filter { p -> selection.dates.any { d -> d >= p.minDate!! && d <= p.maxDate!! } }
        }
        val cob = spec.cobColumn ?: throw SchemaException("${spec.source}: COB selection needs a cob_column")
        val ids =
            client
                .queryJson(
                    "SELECT DISTINCT _partition_id AS p FROM ${Sql.table(spec.source)} WHERE ${Sql.ident(cob)} IN {cobs:Array(Date)}",
                    mapOf("param_cobs" to dateArray(selection.dates), "force_index_by_date" to "1"),
                ).map { it["p"].textValue() }
                .toSet()
        return parts.filter { it.id in ids }
    }

    /** Key ranges for a partition, or a single unbounded chunk when it is small or cannot be split. */
    private fun split(
        client: ClickHouseClient,
        info: TableInfo,
        partition: Partition,
    ): List<KeyRange?> {
        if (partition.bytes <= maxChunkBytes) return listOf(null)
        val keyColumn = info.primaryKey.firstOrNull() ?: return listOf(null)
        val column = info.columns.firstOrNull { it.name == keyColumn } ?: return listOf(null) // expression, not a column
        if (!splittable(column.type)) return listOf(null)
        val pieces = ((partition.bytes + maxChunkBytes - 1) / maxChunkBytes).toInt().coerceAtMost(MAX_PIECES)
        val marks =
            client
                .queryJson(
                    "SELECT toString(${Sql.ident(keyColumn)}) AS k FROM mergeTreeIndex({db:String}, {t:String}) " +
                        "WHERE part_name IN {parts:Array(String)} AND ${Sql.ident(keyColumn)} IS NOT NULL ORDER BY ${Sql.ident(keyColumn)}",
                    mapOf(
                        "param_db" to info.database,
                        "param_t" to info.name,
                        "param_parts" to partition.parts.joinToString(",", "[", "]") { Sql.string(it) },
                    ),
                ).map { it["k"].textValue() }
        if (marks.size < pieces * 2) return listOf(null)
        val bounds = (1 until pieces).map { marks[it * marks.size / pieces] }.distinct()
        if (bounds.isEmpty()) return listOf(null)
        val edges = listOf<String?>(null) + bounds + listOf<String?>(null)
        return edges.zipWithNext { from, to -> KeyRange(keyColumn, column.type, from, to) }
    }

    private fun splittable(type: ChType): Boolean =
        when (val t = type.scalar()) {
            is ChType.IntType, ChType.DateType, ChType.Date32Type, ChType.StringType, ChType.UuidType -> true
            else -> t is ChType.FixedStringType
        }

    companion object {
        const val MAX_PIECES = 256
    }
}

internal fun dateArray(dates: List<LocalDate>): String = dates.joinToString(",", "[", "]") { "'$it'" }
