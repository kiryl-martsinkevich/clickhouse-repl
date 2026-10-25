package chrepl.transfer

import chrepl.clickhouse.ClickHouseClient
import chrepl.clickhouse.Sql
import chrepl.config.expr.Identifiers
import chrepl.config.model.Target
import chrepl.masking.table.ColumnSchema
import chrepl.types.ChType
import chrepl.types.TypeParser

/** Something about a table prevents copying it; the job is BLOCKED until config or schema changes. */
class SchemaException(
    message: String,
) : Exception(message)

/** Table metadata read from `system.tables` / `system.columns` / `SHOW CREATE TABLE`. */
data class TableInfo(
    val database: String,
    val name: String,
    val engine: String,
    val partitionKey: String,
    val primaryKey: List<String>,
    val columns: List<ColumnSchema>,
    val createStatement: String,
) {
    val qualified: String get() = "$database.$name"
}

object SchemaInspector {
    private val COPYABLE = Regex("^(Replicated)?(|Replacing|Summing|Aggregating|Collapsing|VersionedCollapsing|Coalescing)MergeTree$")

    /** Reads table metadata, or null when the table does not exist. */
    fun read(
        client: ClickHouseClient,
        database: String,
        table: String,
    ): TableInfo? {
        val params = mapOf("param_db" to database, "param_t" to table)
        val row =
            client
                .queryJson(
                    "SELECT engine, partition_key, primary_key FROM system.tables WHERE database = {db:String} AND name = {t:String}",
                    params,
                ).singleOrNull() ?: return null
        val columns =
            client
                .queryJson(
                    "SELECT name, type FROM system.columns WHERE database = {db:String} AND table = {t:String} ORDER BY position",
                    params,
                ).map { ColumnSchema(it["name"].textValue(), TypeParser.parse(it["type"].textValue())) }
        val create =
            client.queryScalar(
                "SHOW CREATE TABLE ${Sql.ident(database)}.${Sql.ident(table)}",
                mapOf(
                    "output_format_tsv_crlf_end_of_line" to "0",
                ),
            )
        return TableInfo(
            database = database,
            name = table,
            engine = row["engine"].textValue(),
            partitionKey = row["partition_key"].textValue(),
            primaryKey =
                row["primary_key"]
                    .textValue()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
            columns = columns,
            createStatement = unescapeTsv(create ?: throw SchemaException("SHOW CREATE TABLE returned nothing for $database.$table")),
        )
    }

    /** Fails unless the engine is a MergeTree variant that supports partition operations. */
    fun requireCopyable(info: TableInfo) {
        if (!COPYABLE.matches(info.engine)) {
            throw SchemaException("${info.qualified}: engine ${info.engine} is not supported for copying (MergeTree family only)")
        }
        val unsupported = info.columns.filter { it.type.hasUnsupported() }
        if (unsupported.isNotEmpty()) {
            throw SchemaException(
                "${info.qualified}: column types not supported for copying: ${unsupported.joinToString { "${it.name} ${it.type}" }}",
            )
        }
    }

    private fun unescapeTsv(text: String): String =
        text
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\'", "'")
            .replace("\\\\", "\\")
}

/**
 * Rewrites `SHOW CREATE TABLE` output for another table name and engine (SPEC §5.2): replicated
 * engines get the target's ZooKeeper path template (or become non-replicated), staging tables are
 * always non-replicated, and the target's storage policy override is applied.
 */
object DdlRewriter {
    private val ENGINE = Regex("""\bENGINE\s*=\s*(\w+)""")

    fun forTarget(
        info: TableInfo,
        target: Target,
    ): String {
        val template = target.zookeeperPathTemplate
        val replicated =
            template?.let {
                it.replace("{database}", info.database).replace("{table}", info.name)
            }
        var ddl = rewrite(info.createStatement, "${Sql.ident(info.database)}.${Sql.ident(info.name)}", replicatedPath = replicated)
        target.tableOverrides?.storagePolicy?.let { ddl = setStoragePolicy(ddl, it) }
        return ddl
    }

    /** DDL of a non-replicated table with the same structure, partition key and sort key as [targetCreate]. */
    fun forStaging(
        targetCreate: String,
        database: String,
        stagingName: String,
    ): String = rewrite(targetCreate, "${Sql.ident(database)}.${Sql.ident(stagingName)}", replicatedPath = null)

    internal fun rewrite(
        create: String,
        newName: String,
        replicatedPath: String?,
    ): String {
        val bodyStart = create.indexOf('(')
        if (!create.trimStart().startsWith("CREATE TABLE") || bodyStart < 0) throw SchemaException("unexpected CREATE statement")
        var ddl = "CREATE TABLE IF NOT EXISTS $newName\n" + create.substring(bodyStart)
        val match = ENGINE.find(ddl) ?: throw SchemaException("CREATE statement has no ENGINE clause")
        val engine = match.groupValues[1]
        val argsStart = match.range.last + 1
        val (args, argsEnd) = engineArgs(ddl, argsStart)
        val baseEngine = engine.removePrefix("Replicated")
        // Replicated engines take (zk_path, replica_name, engine args...) when arguments are given.
        val engineSpecific =
            if (engine.startsWith("Replicated") &&
                args.size >= 2
            ) {
                args.drop(2)
            } else if (engine.startsWith("Replicated")) {
                emptyList()
            } else {
                args
            }
        val newEngine =
            if (replicatedPath != null) {
                "Replicated$baseEngine(${(listOf(Sql.string(replicatedPath), "'{replica}'") + engineSpecific).joinToString(", ")})"
            } else {
                "$baseEngine(${engineSpecific.joinToString(", ")})"
            }
        ddl = ddl.substring(0, match.range.first) + "ENGINE = " + newEngine + ddl.substring(argsEnd)
        return ddl
    }

    /** Splits a top-level `( … )` argument list starting at [start]; returns the arguments and the index after `)`. */
    private fun engineArgs(
        text: String,
        start: Int,
    ): Pair<List<String>, Int> {
        var i = start
        while (i < text.length && text[i] == ' ') i++
        if (i >= text.length || text[i] != '(') return emptyList<String>() to start
        val args = mutableListOf<String>()
        var depth = 0
        var current = StringBuilder()
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> {
                    current.append(c)
                    if (c == '\\' && i + 1 < text.length) {
                        current.append(text[++i])
                    } else if (c == quote) {
                        quote = null
                    }
                }

                c == '\'' || c == '`' -> {
                    quote = c
                    current.append(c)
                }

                c == '(' -> {
                    if (depth > 0) current.append(c)
                    depth++
                }

                c == ')' -> {
                    depth--
                    if (depth == 0) {
                        if (current.isNotBlank()) args += current.toString().trim()
                        return args to i + 1
                    }
                    current.append(c)
                }

                c == ',' && depth == 1 -> {
                    args += current.toString().trim()
                    current = StringBuilder()
                }

                else -> {
                    current.append(c)
                }
            }
            i++
        }
        throw SchemaException("unbalanced ENGINE arguments")
    }

    private fun setStoragePolicy(
        ddl: String,
        policy: String,
    ): String {
        val setting = "storage_policy = ${Sql.string(policy)}"
        val existing = Regex("""storage_policy\s*=\s*'[^']*'""")
        return when {
            existing.containsMatchIn(ddl) -> existing.replace(ddl, setting)
            Regex("""\bSETTINGS\b""").containsMatchIn(ddl) -> ddl.replaceFirst(Regex("""\bSETTINGS\b"""), "SETTINGS $setting,")
            else -> "$ddl\nSETTINGS $setting"
        }
    }
}

/**
 * Makes the target table match the source (SPEC §5.2): creates it if missing; otherwise adds,
 * modifies or drops columns on the target, which is disposable. The source is never altered.
 */
object SchemaSync {
    fun ensure(
        client: ClickHouseClient,
        source: TableInfo,
        target: Target,
    ): TableInfo {
        client.execute("CREATE DATABASE IF NOT EXISTS ${Sql.ident(source.database)}")
        val existing = SchemaInspector.read(client, source.database, source.name)
        if (existing == null) {
            client.execute(DdlRewriter.forTarget(source, target))
            applyTtl(client, source, target)
        } else {
            alignColumns(client, source, existing)
        }
        return SchemaInspector.read(client, source.database, source.name)
            ?: throw SchemaException("${source.qualified}: target table was not created on ${client.endpoint}")
    }

    private fun alignColumns(
        client: ClickHouseClient,
        source: TableInfo,
        existing: TableInfo,
    ) {
        if (existing.partitionKey != source.partitionKey || existing.primaryKey != source.primaryKey) {
            throw SchemaException(
                "${source.qualified}: target partition/primary key differs from the source; drop the target table to recreate it",
            )
        }
        val table = Sql.table(source.qualified)
        val targetTypes = existing.columns.associate { it.name to it.type }
        var previous: String? = null
        for (column in source.columns) {
            val position = previous?.let { "AFTER ${Sql.ident(it)}" } ?: "FIRST"
            when (targetTypes[column.name]) {
                null -> client.execute("ALTER TABLE $table ADD COLUMN ${Sql.ident(column.name)} ${column.type} $position")
                column.type -> {}
                else -> client.execute("ALTER TABLE $table MODIFY COLUMN ${Sql.ident(column.name)} ${column.type}")
            }
            previous = column.name
        }
        val sourceNames = source.columns.map { it.name }.toSet()
        for (column in existing.columns.filter { it.name !in sourceNames }) {
            client.execute("ALTER TABLE $table DROP COLUMN ${Sql.ident(column.name)}")
        }
    }

    private fun applyTtl(
        client: ClickHouseClient,
        source: TableInfo,
        target: Target,
    ) {
        val ttl = target.tableOverrides?.ttl ?: return
        val names = source.columns.map { it.name }.toSet()
        if (Identifiers.of(ttl).all { it in names || it.uppercase() in TTL_KEYWORDS }) {
            client.execute("ALTER TABLE ${Sql.table(source.qualified)} MODIFY TTL $ttl")
        }
    }

    private val TTL_KEYWORDS = setOf("INTERVAL", "DAY", "WEEK", "MONTH", "YEAR", "HOUR", "MINUTE", "SECOND", "DELETE")
}

/** True when this type, or any type nested in it, is unknown to the codec. */
internal fun ChType.hasUnsupported(): Boolean =
    when (this) {
        is ChType.Unsupported -> true
        is ChType.NullableType -> inner.hasUnsupported()
        is ChType.LowCardinalityType -> inner.hasUnsupported()
        is ChType.ArrayType -> element.hasUnsupported()
        is ChType.MapType -> key.hasUnsupported() || value.hasUnsupported()
        is ChType.TupleType -> elements.any { it.hasUnsupported() }
        else -> false
    }
