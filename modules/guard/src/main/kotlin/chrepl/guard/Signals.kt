package chrepl.guard

import chrepl.clickhouse.ClickHouseClient
import java.time.Instant

/** A query running on a DR host, as seen in `system.processes` (no query text, SPEC §4.4). */
data class RunningQuery(
    val user: String,
    val queryId: String,
    val elapsedSeconds: Double,
    val memoryBytes: Long,
    val readRows: Long,
)

/** One poll of one DR host (SPEC §4.4). A failed poll has [error] set and no signals. */
data class HostSample(
    val host: String,
    val takenAt: Instant,
    val error: String? = null,
    val serverTime: Instant? = null,
    val queries: List<RunningQuery> = emptyList(),
    /** Cumulative `InsertQuery` counter; the guard looks at its change between polls. */
    val insertQueries: Long = 0,
    /** Replicated tables the guard user can see; 0 means it cannot assess replication (missing grant). */
    val replicatedTables: Long = 1,
    val replicaDelaySeconds: Long = 0,
    val queueSize: Long = 0,
    val queueOldestAgeSeconds: Long = 0,
    val stuckEntries: Long = 0,
    val inflightFetches: Long = 0,
    val readonlyReplicas: Long = 0,
    val expiredSessions: Long = 0,
    val cpuUtilisation: Double = 0.0,
    val ioWait: Double = 0.0,
    val memoryRatio: Double = 0.0,
    val activeMerges: Long = 0,
    val mutationBytes: Long = 0,
) {
    val ok: Boolean get() = error == null
}

fun interface SignalSource {
    /** Never throws: failures are returned as a sample with [HostSample.error]. */
    fun sample(host: String): HostSample
}

/**
 * Polls each DR host directly with one fixed query over small in-memory system tables, as the
 * `repl_guard` user (`max_threads = 1`, short timeout). Never contacts any other host.
 */
class ClickHouseSignalSource(
    private val clients: Map<String, ClickHouseClient>,
    private val clock: () -> Instant = Instant::now,
) : SignalSource {
    override fun sample(host: String): HostSample {
        val now = clock()
        val client = clients[host] ?: return HostSample(host, now, error = "no client for $host")
        return try {
            val row = client.queryJson(QUERY, mapOf("max_threads" to "1", "max_execution_time" to "5")).single()
            HostSample(
                host = host,
                takenAt = now,
                serverTime = Instant.ofEpochMilli(row["server_ms"].asLong()),
                queries =
                    row["processes"].map {
                        RunningQuery(it[0].asText(), it[1].asText(), it[2].asDouble(), it[3].asLong(), it[4].asLong())
                    },
                insertQueries = row["insert_queries"].asLong(),
                replicatedTables = row["replicated_tables"].asLong(),
                replicaDelaySeconds = row["replica_delay"].asLong(),
                queueSize = row["queue_size"].asLong(),
                queueOldestAgeSeconds = if (row["queue_entries"].asLong() > 0) row["queue_oldest_age"].asLong() else 0,
                stuckEntries = row["stuck"].asLong(),
                inflightFetches = row["fetches"].asLong(),
                readonlyReplicas = row["readonly"].asLong(),
                expiredSessions = row["expired"].asLong(),
                cpuUtilisation = row["cpu"].asDouble(),
                ioWait = row["io_wait"].asDouble(),
                memoryRatio = row["mem_total"].asDouble().let { total -> if (total > 0) row["mem"].asDouble() / total else 0.0 },
                activeMerges = row["merges"].asLong(),
                mutationBytes = row["mutation_bytes"].asLong(),
            )
        } catch (e: Exception) {
            HostSample(host, now, error = e.message ?: e::class.simpleName)
        }
    }

    companion object {
        /** Every signal in one round trip; empty system tables yield zeros. */
        val QUERY =
            """
            SELECT
                toUnixTimestamp64Milli(now64(3)) AS server_ms,
                (SELECT groupArray(tuple(user, query_id, elapsed, memory_usage, read_rows)) FROM system.processes WHERE is_initial_query) AS processes,
                (SELECT sum(value) FROM system.events WHERE event = 'InsertQuery') AS insert_queries,
                (SELECT count() FROM system.replicas) AS replicated_tables,
                (SELECT max(absolute_delay) FROM system.replicas) AS replica_delay,
                (SELECT sum(queue_size) FROM system.replicas) AS queue_size,
                (SELECT max(is_readonly) FROM system.replicas) AS readonly,
                (SELECT max(is_session_expired) FROM system.replicas) AS expired,
                (SELECT count() FROM system.replication_queue) AS queue_entries,
                (SELECT dateDiff('second', min(create_time), now()) FROM system.replication_queue) AS queue_oldest_age,
                (SELECT countIf(num_tries > 3 OR last_exception != '') FROM system.replication_queue) AS stuck,
                (SELECT count() FROM system.replicated_fetches) AS fetches,
                (SELECT count() FROM system.merges) AS merges,
                (SELECT sum(bytes_on_disk) FROM system.parts WHERE active AND (database, table, name) IN
                    (SELECT database, table, arrayJoin(parts_to_do_names) FROM system.mutations WHERE NOT is_done)) AS mutation_bytes,
                (SELECT sumIf(value, metric IN ('OSUserTimeNormalized', 'OSSystemTimeNormalized')) FROM system.asynchronous_metrics) AS cpu,
                (SELECT sumIf(value, metric = 'OSIOWaitTimeNormalized') FROM system.asynchronous_metrics) AS io_wait,
                (SELECT sum(value) FROM system.metrics WHERE metric = 'MemoryTracking') AS mem,
                (SELECT minIf(value, metric IN ('CGroupMemoryTotal', 'OSMemoryTotal') AND value > 0) FROM system.asynchronous_metrics) AS mem_total
            """.trimIndent()
    }
}
