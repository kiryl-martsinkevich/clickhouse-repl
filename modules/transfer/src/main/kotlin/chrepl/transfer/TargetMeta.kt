package chrepl.transfer

import chrepl.clickhouse.ClickHouseClient
import chrepl.clickhouse.ClickHouseException

/** Writing to this target is refused (SPEC §10, target safety). */
class TargetSafetyException(
    message: String,
) : Exception(message)

/**
 * The `repl_meta` database on each target host. `repl_meta.environment` is created by the DBAs
 * (deploy/target-ddl.sql) and never by the tool: its value must equal the target name before
 * every write. The tool manages `repl_meta.tables` (masking key version per table) and
 * `repl_meta.copy_history` (the "last copied" registry mirror, SPEC §7.3).
 */
object TargetMeta {
    fun checkMarker(
        client: ClickHouseClient,
        expected: String,
    ) {
        val value =
            try {
                client.queryScalar("SELECT value FROM repl_meta.environment LIMIT 1")
            } catch (e: ClickHouseException) {
                throw TargetSafetyException(
                    "${client.endpoint}: cannot read repl_meta.environment (${e.code ?: "error"}); refusing to write",
                )
            }
        if (value != expected) {
            throw TargetSafetyException(
                "${client.endpoint}: environment marker is '${value ?: "<none>"}', expected '$expected'; refusing to write",
            )
        }
    }

    fun ensureTables(client: ClickHouseClient) {
        client.execute(
            """
            CREATE TABLE IF NOT EXISTS repl_meta.tables (
                database String, table String, realm String, key_version UInt32, updated_at DateTime DEFAULT now()
            ) ENGINE = ReplacingMergeTree(updated_at) ORDER BY (database, table)
            """.trimIndent(),
        )
        client.execute(
            """
            CREATE TABLE IF NOT EXISTS repl_meta.copy_history (
                job_id String, requester String, database String, table String, partition_id String,
                cob_dates Array(Date), rows UInt64, realm String, key_version UInt32, config_version String,
                source_delay_seconds Nullable(UInt32), copied_at DateTime64(3) DEFAULT now64(3)
            ) ENGINE = MergeTree ORDER BY (database, table, partition_id, copied_at)
            """.trimIndent(),
        )
    }

    /** Refuses writes when the table was masked with another realm or key version (SPEC §6.2). */
    fun checkKeyVersion(
        client: ClickHouseClient,
        database: String,
        table: String,
        realm: String,
        version: Int,
        allowRekey: Boolean = false,
    ) {
        if (client.queryScalar("EXISTS TABLE repl_meta.tables") != "1") return
        val row =
            client
                .queryJson(
                    "SELECT realm, key_version FROM repl_meta.tables FINAL WHERE database = {db:String} AND table = {t:String}",
                    mapOf("param_db" to database, "param_t" to table),
                ).singleOrNull() ?: return
        val existingRealm = row["realm"].textValue()
        val existingVersion = row["key_version"].asInt()
        if ((existingRealm != realm || existingVersion != version) && !allowRekey) {
            throw TargetSafetyException(
                "$database.$table on ${client.endpoint} was masked with realm $existingRealm key v$existingVersion; " +
                    "this job uses $realm v$version — run a rekey job instead of mixing versions",
            )
        }
    }

    fun recordKeyVersion(
        client: ClickHouseClient,
        database: String,
        table: String,
        realm: String,
        version: Int,
    ) {
        client.execute(
            "INSERT INTO repl_meta.tables (database, table, realm, key_version) VALUES ({db:String}, {t:String}, {r:String}, {v:UInt32})",
            mapOf("param_db" to database, "param_t" to table, "param_r" to realm, "param_v" to version.toString()),
        )
    }

    fun recordCopy(
        client: ClickHouseClient,
        entry: CopyRecord,
    ) {
        client.execute(
            "INSERT INTO repl_meta.copy_history (job_id, requester, database, table, partition_id, cob_dates, rows, realm, key_version, " +
                "config_version, source_delay_seconds) VALUES ({j:String}, {u:String}, {db:String}, {t:String}, {p:String}, " +
                "{c:Array(Date)}, {n:UInt64}, {r:String}, {v:UInt32}, {cv:String}, {d:Nullable(UInt32)})",
            mapOf(
                "param_j" to entry.jobId,
                "param_u" to entry.requester,
                "param_db" to entry.database,
                "param_t" to entry.table,
                "param_p" to entry.partitionId,
                "param_c" to entry.cobDates.joinToString(",", "[", "]") { "'$it'" },
                "param_n" to entry.rows.toString(),
                "param_r" to entry.realm,
                "param_v" to entry.keyVersion.toString(),
                "param_cv" to entry.configVersion,
                "param_d" to (entry.sourceDelaySeconds?.toString() ?: "\\N"),
            ),
        )
    }
}

data class CopyRecord(
    val jobId: String,
    val requester: String,
    val database: String,
    val table: String,
    val partitionId: String,
    val cobDates: List<java.time.LocalDate>,
    val rows: Long,
    val realm: String,
    val keyVersion: Int,
    val configVersion: String,
    val sourceDelaySeconds: Long?,
)

/**
 * Permission to read from a source host now (SPEC §4.4). The Source Guard (PLAN M3) implements
 * this; reads always go through a gate.
 */
interface ReadGate {
    /** Runs [read] once the guard admits a read on [host]; [read] receives a cancellation the guard may trigger. */
    fun <T> withPermit(
        host: String,
        queryId: String,
        read: (chrepl.clickhouse.Cancellation) -> T,
    ): T
}

/** No guard: only allowed for `dc: test` sources (Testcontainers / dev). */
object UnguardedGate : ReadGate {
    override fun <T> withPermit(
        host: String,
        queryId: String,
        read: (chrepl.clickhouse.Cancellation) -> T,
    ): T = read(chrepl.clickhouse.Cancellation())
}
