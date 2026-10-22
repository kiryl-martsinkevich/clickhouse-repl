package chrepl.transfer

import chrepl.clickhouse.ClickHouseClient
import chrepl.clickhouse.Sql
import chrepl.config.model.TableSpec
import chrepl.masking.table.TableMasker
import chrepl.types.scalar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Builds every query sent to a source (SPEC §4.2, §5.3). This is the only place source SELECTs are
 * built: each one is pinned to a partition (`_partition_id`), tagged with a deterministic
 * query id, and carries settings within the repl_reader profile bounds.
 */
object SourceQueries {
    data class Read(
        val sql: String,
        val params: Map<String, String>,
    )

    /** Settings for data reads; the profile on DR enforces the hard limits (L1). */
    fun readSettings(fullCopy: Boolean): Map<String, String> =
        mapOf(
            "max_threads" to "2",
            "cancel_http_readonly_queries_on_client_close" to "1",
            // Refuse reads that cannot prune by partition; relaxed only for small full_copy tables (SPEC §4.2).
            "force_index_by_date" to if (fullCopy) "0" else "1",
        )

    fun queryId(
        jobId: String,
        chunkId: String,
        attempt: Int,
    ) = "chrepl-$jobId-$chunkId-$attempt"

    fun read(
        spec: TableSpec,
        columns: List<String>,
        chunk: Chunk,
    ): Read {
        val params = mutableMapOf("param_pid" to chunk.partitionId)
        val where = mutableListOf("_partition_id = {pid:String}")
        val selection = chunk.selection
        if (selection is Selection.Cob) {
            val cob = spec.cobColumn ?: throw SchemaException("${spec.source}: COB selection needs a cob_column")
            where += "${Sql.ident(cob)} IN {cobs:Array(Date)}"
            params["param_cobs"] = dateArray(selection.dates)
        }
        chunk.range?.let { r ->
            val type = r.type.scalar()
            r.from?.let {
                where += "${Sql.ident(r.column)} >= {lo:$type}"
                params["param_lo"] = it
            }
            r.to?.let {
                where += "${Sql.ident(r.column)} < {hi:$type}"
                params["param_hi"] = it
            }
        }
        val sql = "SELECT ${columns.joinToString { Sql.ident(it) }} FROM ${Sql.table(spec.source)} WHERE ${where.joinToString(" AND ")}"
        return Read(sql, params)
    }
}

/** Serialises REPLACE PARTITION per (target host, table, partition) (SPEC §5.4); Postgres advisory locks in M4. */
interface PartitionLocks {
    fun <T> withLock(
        key: String,
        block: () -> T,
    ): T

    companion object {
        val IN_PROCESS: PartitionLocks =
            object : PartitionLocks {
                private val locks = ConcurrentHashMap<String, ReentrantLock>()

                override fun <T> withLock(
                    key: String,
                    block: () -> T,
                ): T = locks.computeIfAbsent(key) { ReentrantLock() }.withLock(block)
            }
    }
}

/** Copies one chunk into its own staging table on the target (SPEC §5.4 step 1). */
internal class ChunkCopier(
    private val spec: TableSpec,
    private val source: TableInfo,
    private val gate: ReadGate,
) {
    private val columnNames = source.columns.map { it.name }
    private val types = source.columns.map { it.type }

    /** Returns the number of rows written to [staging]. */
    fun copy(
        chunk: Chunk,
        sourceClient: ClickHouseClient,
        targetClient: ClickHouseClient,
        target: TableInfo,
        staging: String,
        masker: TableMasker,
        queryId: String,
    ): Long {
        targetClient.execute("DROP TABLE IF EXISTS ${Sql.ident(target.database)}.${Sql.ident(staging)}")
        targetClient.execute(DdlRewriter.forStaging(target.createStatement, target.database, staging))
        val read = SourceQueries.read(spec, columnNames, chunk)
        return gate.withPermit(sourceClient.endpoint.host, queryId) { cancellation ->
            targetClient.insert("${Sql.ident(target.database)}.${Sql.ident(staging)}", columnNames) { writer ->
                sourceClient.queryRows(
                    read.sql,
                    read.params + SourceQueries.readSettings(spec.fullCopy),
                    queryId,
                    cancellation,
                ) { header, reader ->
                    if (header.map { it.first } != columnNames || header.map { it.second } != types) {
                        throw SchemaException("${spec.source}: columns changed on ${sourceClient.endpoint} during the copy")
                    }
                    masker.startChunk()
                    while (reader.hasMore()) writer.writeRow(masker.mask(reader.readRow(types)), types)
                }
            }
        }
    }
}

/**
 * Builds the final content of one target partition and swaps it in atomically (SPEC §5.4 steps 2–4):
 * for a COB slice, the target's other rows of the partition are kept; chunk staging partitions are
 * attached; `REPLACE PARTITION` publishes the result (an empty result empties the partition).
 */
internal class PartitionAssembler(
    private val spec: TableSpec,
    private val locks: PartitionLocks,
) {
    fun assemble(
        targetClient: ClickHouseClient,
        target: TableInfo,
        partitionId: String,
        selection: Selection,
        chunkStagings: List<String>,
        staging: String,
    ): Long {
        val db = Sql.ident(target.database)
        val table = "$db.${Sql.ident(target.name)}"
        val stg = "$db.${Sql.ident(staging)}"
        val pid = Sql.string(partitionId)
        try {
            targetClient.execute("DROP TABLE IF EXISTS $stg")
            targetClient.execute(DdlRewriter.forStaging(target.createStatement, target.database, staging))
            return locks.withLock("${targetClient.endpoint}/${target.qualified}/$partitionId") {
                if (selection is Selection.Cob) {
                    val cob = Sql.ident(spec.cobColumn!!)
                    targetClient.execute(
                        "INSERT INTO $stg SELECT * FROM $table WHERE _partition_id = {pid:String} AND NOT ($cob IN {cobs:Array(Date)})",
                        mapOf("param_pid" to partitionId, "param_cobs" to dateArray(selection.dates)),
                    )
                }
                for (chunk in chunkStagings) targetClient.execute("ALTER TABLE $stg ATTACH PARTITION ID $pid FROM $db.${Sql.ident(chunk)}")
                targetClient.execute("ALTER TABLE $table REPLACE PARTITION ID $pid FROM $stg")
                targetClient
                    .queryScalar(
                        "SELECT count() FROM $table WHERE _partition_id = {pid:String}",
                        mapOf("param_pid" to partitionId),
                    )!!
                    .toLong()
            }
        } finally {
            (chunkStagings + staging).forEach { runCatching { targetClient.execute("DROP TABLE IF EXISTS $db.${Sql.ident(it)}") } }
        }
    }
}
