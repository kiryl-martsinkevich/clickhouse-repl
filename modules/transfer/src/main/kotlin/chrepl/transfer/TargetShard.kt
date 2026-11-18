package chrepl.transfer

import chrepl.clickhouse.ClickHouseClient
import java.time.Duration

/** No replica of a target shard is up. */
class TargetUnavailableException(
    message: String,
) : Exception(message)

/**
 * One target shard and its replicas, in configured order (SPEC §5.2). Writes (staging tables,
 * REPLACE PARTITION, `repl_meta`) go to the writer: the first replica that answers and has no
 * read-only replicated table. ReplicatedMergeTree carries the data to the other replicas; DDL is
 * applied to every reachable replica because CREATE TABLE is not replicated.
 */
class TargetShard(
    val index: Int,
    val replicas: List<ClickHouseClient>,
    private val probeTtl: Duration = Duration.ofSeconds(10),
) {
    @Volatile private var cached: ClickHouseClient? = null

    @Volatile private var cachedAt = 0L

    fun writer(): ClickHouseClient {
        if (replicas.size == 1) return replicas.single()
        cached?.let { if (System.nanoTime() - cachedAt < probeTtl.toNanos()) return it }
        val problems = mutableListOf<String>()
        for (replica in replicas) {
            val problem = probe(replica)
            if (problem == null) {
                cached = replica
                cachedAt = System.nanoTime()
                return replica
            }
            problems += "${replica.endpoint}: $problem"
        }
        throw TargetUnavailableException("no writable replica of target shard ${index + 1}: ${problems.joinToString("; ")}")
    }

    /** Replicas that answer now (all of them for a single-replica shard, which is never probed). */
    fun reachable(): List<ClickHouseClient> = if (replicas.size == 1) replicas else replicas.filter { probe(it) == null }

    private fun probe(client: ClickHouseClient): String? =
        try {
            // Read-only replicas (lost Keeper session) cannot take REPLACE PARTITION.
            val readonly = client.queryScalar("SELECT count() FROM system.replicas WHERE is_readonly", timeout = PROBE_TIMEOUT)
            if (readonly == "0") null else "$readonly read-only replicated table(s)"
        } catch (e: Exception) {
            e.message ?: e::class.simpleName
        }

    private companion object {
        val PROBE_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
