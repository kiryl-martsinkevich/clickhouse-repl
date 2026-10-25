package chrepl.control

import chrepl.clickhouse.ClickHouseClient
import chrepl.clickhouse.Sql
import chrepl.config.cron.CronExpression
import chrepl.config.model.ContinuousJob
import chrepl.config.model.ContinuousMode
import chrepl.config.model.Verify
import chrepl.guard.GuardTimeoutException
import chrepl.transfer.CopySession
import chrepl.transfer.SchemaInspector
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean

data class SchedulerSettings(
    val tick: Duration = Duration.ofSeconds(1),
    /** How long a watermark or checksum poll waits for the guard before the poll is skipped. */
    val readWait: Duration = Duration.ofSeconds(10),
    /** How often the leader runs the target retention janitor. */
    val retentionEvery: Duration = Duration.ofHours(1),
)

/**
 * Metadata fingerprint of one source partition on one shard, from `system.parts` only. It changes
 * with inserts (new block numbers), mutations and deletes (mutation version, rows), but not with
 * merges, so merged parts do not cause re-copies (checked against 25.8 and 26.8).
 */
data class PartitionMeta(
    val id: String,
    val minDate: LocalDate?,
    val maxDate: LocalDate?,
    val fingerprint: String,
)

object SourceMetadata {
    fun partitions(
        client: ClickHouseClient,
        database: String,
        table: String,
    ): List<PartitionMeta> =
        client
            .queryJson(
                """
                SELECT partition_id AS p, sum(rows) AS r, max(max_block_number) AS b,
                       max(toUInt64OrZero(splitByChar('_', substring(name, length(partition_id) + 2))[4])) AS m,
                       max(max_date) != '1970-01-01' AS by_date,
                       if(by_date, toString(min(min_date)), toString(toDate(min(min_time)))) AS lo,
                       if(by_date, toString(max(max_date)), toString(toDate(max(max_time)))) AS hi
                FROM system.parts
                WHERE active AND database = {db:String} AND table = {t:String}
                GROUP BY partition_id
                """.trimIndent(),
                mapOf("param_db" to database, "param_t" to table),
            ).map {
                val hi = LocalDate.parse(it["hi"].textValue()).takeIf { d -> d.year > 1970 }
                val lo = if (hi == null) null else LocalDate.parse(it["lo"].textValue())
                PartitionMeta(it["p"].textValue(), lo, hi, "${it["r"].asLong()}/${it["b"].asLong()}/${it["m"].asLong()}")
            }
}

/**
 * True when [cron] (in [zone]) matches a minute in (`last`, `now`]. After an outage of more than two
 * days a schedule catches up with a single run.
 */
fun cronDue(
    cron: String,
    zone: ZoneId,
    last: Instant,
    now: Instant,
): Boolean {
    val expression = CronExpression.parse(cron)
    var minute = last.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES)
    val stop = minOf(now, last.plus(Duration.ofDays(2)))
    while (!minute.isAfter(stop)) {
        if (expression.matches(minute.atZone(zone))) return true
        minute = minute.plus(1, ChronoUnit.MINUTES)
    }
    return now.isAfter(last.plus(Duration.ofDays(2)))
}

/**
 * Continuous mode (SPEC §8): turns `continuous:` entries into scheduler jobs. One process is the
 * leader (Postgres advisory lock); the others stand by. Each (entry, table, target) is a stream
 * whose progress lives in `continuous_state`; a poll reads DR metadata (only while the guard allows
 * it), decides which partitions changed, and submits a whole-partition job. What the poll observed
 * is applied only after that job succeeds, so a failed or cancelled copy is simply retried by the
 * next poll.
 */
class Scheduler(
    private val db: Db,
    private val configs: ConfigRegistry,
    private val runtime: Runtime,
    private val jobs: JobService,
    private val settings: SchedulerSettings = SchedulerSettings(),
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var leaderConnection: Connection? = null
    private val janitor = RetentionJanitor(db, configs, runtime, clock)
    private var lastRetention: Instant? = null

    val isLeader: Boolean get() = leaderConnection != null

    fun start(): Scheduler {
        running.set(true)
        thread =
            Thread({
                while (running.get()) {
                    runCatching {
                        if (lead()) {
                            tick()
                            retention()
                        }
                    }.onFailure { log.warn("scheduler tick failed", it) }
                    runCatching { Thread.sleep(settings.tick.toMillis()) }
                }
            }, "scheduler").apply {
                isDaemon = true
                start()
            }
        return this
    }

    /** Holds the leader lock on a dedicated connection; losing the connection loses leadership. */
    private fun lead(): Boolean {
        leaderConnection?.let { conn ->
            if (runCatching { conn.isValid(2) }.getOrDefault(false)) return true
            runCatching { conn.close() }
            leaderConnection = null
        }
        val conn = db.dataSource.connection
        val acquired =
            runCatching {
                conn.prepareStatement("SELECT pg_try_advisory_lock($LEADER_LOCK)").use { st ->
                    st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
                }
            }.getOrDefault(false)
        if (acquired) {
            leaderConnection = conn
            log.info("scheduler is the leader")
        } else {
            conn.close()
        }
        return acquired
    }

    private fun retention() {
        val now = clock.instant()
        if (lastRetention?.plus(settings.retentionEvery)?.isAfter(now) == true) return
        lastRetention = now
        runCatching { janitor.run() }.onFailure { log.warn("retention janitor failed", it) }
    }

    /** One scheduling round over every stream. Exposed for deterministic tests. */
    fun tick() {
        val version = configs.currentVersion
        val config = configs.get(version)
        val paused = db.tx { query("SELECT name FROM continuous_paused") { it.getString(1) } }.toSet()
        for (entry in config.continuous) {
            for (table in entry.allTables) {
                for (target in entry.targets) {
                    val key = StreamKey(entry.name, table, target)
                    runCatching { stream(entry, key, version, entry.name in paused) }.onFailure { e ->
                        log.warn("continuous stream {} failed", key, e)
                        // A failed poll counts as a poll: the next attempt waits for the interval, never a tight retry loop.
                        db.tx {
                            update(
                                "UPDATE continuous_state SET last_error = ?, last_poll_at = now(), polls = polls + 1 " +
                                    "WHERE name = ? AND table_name = ? AND target = ?",
                                Worker.describe(e),
                                key.name,
                                key.table,
                                key.target,
                            )
                        }
                    }
                }
            }
        }
    }

    data class StreamKey(
        val name: String,
        val table: String,
        val target: String,
    ) {
        override fun toString() = "$name/$table/$target"
    }

    private class State(
        val lastPollAt: Instant?,
        val watermarks: Map<String, String>,
        val jobId: String?,
        val pending: Pending?,
    )

    /** What a poll observed, applied when its job succeeds. */
    data class Pending(
        val fingerprints: Map<String, List<String?>> = emptyMap(),
        val forget: List<String> = emptyList(),
        val watermarks: Map<String, String> = emptyMap(),
        val asOf: Instant,
    )

    private fun stream(
        entry: ContinuousJob,
        key: StreamKey,
        version: String,
        paused: Boolean,
    ) {
        val now = clock.instant()
        val state = load(key)

        // 1. Settle the previous poll's job.
        if (state.jobId != null) {
            val job =
                db.tx {
                    one("SELECT state, reason FROM jobs WHERE id = ?", state.jobId) {
                        JobState.valueOf(it.getString(1)) to
                            it.getString(2)
                    }
                }
            when {
                job == null -> clearJob(key, "job ${state.jobId} disappeared")
                !job.first.terminal -> return
                job.first == JobState.SUCCEEDED -> apply(key, state.pending, now, copied = true)
                else -> clearJob(key, "job ${state.jobId} ended ${job.first}: ${job.second ?: ""}")
            }
        }

        // 2. Is a poll due and allowed?
        if (paused) return setWait(key, "paused by an operator")
        if (!due(entry, state.lastPollAt, now)) {
            if (state.lastPollAt == null) markPolled(key, now) // scheduled refresh: start counting from now
            return
        }
        val flags = db.tx { query("SELECT name FROM ops_flags WHERE name IN ('paused', 'failover')") { it.getString(1) } }
        if (flags.isNotEmpty()) return setWait(key, "replication ${flags.joinToString(" and ")}")
        runtime.metadataBlockedReason()?.let { return setWait(key, "waiting for the source: $it") }

        // 3. Poll DR and submit what changed.
        val session = runtime.session(version, key.target) { _, _ -> }
        val asOf = now.minusSeconds(runtime.guard?.status()?.replicaDelaySeconds ?: 0)
        val (partitions, pending) =
            try {
                when (entry.mode) {
                    ContinuousMode.PARTITION_FINGERPRINT -> fingerprintPoll(entry, key, version, session, asOf)
                    ContinuousMode.APPEND_WATERMARK -> watermarkPoll(entry, key, version, session, state, asOf)
                    ContinuousMode.SCHEDULED_FULL_REFRESH -> refreshPoll(entry, key, session, asOf)
                }
            } catch (e: GuardTimeoutException) {
                return setWait(key, "waiting for the source: ${e.reasons.joinToString("; ")}")
            }
        if (partitions != null && partitions.isEmpty()) {
            apply(key, pending, now, copied = false)
            markPolled(key, now)
            return
        }
        val kind = if (entry.mode == ContinuousMode.SCHEDULED_FULL_REFRESH) "refresh" else "continuous"
        val jobId = jobs.submitSystem(kind, key.target, listOf(key.table), emptyList(), partitions, "continuous|$key", version)
        db.tx {
            update(
                "UPDATE continuous_state SET job_id = coalesce(?, job_id), pending = ?::jsonb, last_poll_at = ?, polls = polls + 1, " +
                    "wait_reason = NULL WHERE name = ? AND table_name = ? AND target = ?",
                jobId,
                Db.json.writeValueAsString(pending),
                now,
                key.name,
                key.table,
                key.target,
            )
        }
    }

    // ------------------------------------------------------------------------------------- modes

    private fun recent(
        entry: ContinuousJob,
        metas: Collection<PartitionMeta>,
    ): List<String> {
        val byId = metas.groupBy { it.id }.mapValues { (_, m) -> m.mapNotNull { it.maxDate }.maxOrNull() }
        val ordered =
            byId.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, LocalDate?>> {
                        it.value
                    }.thenByDescending { it.key },
                ).map { it.key }
        return entry.onlyRecentPartitions?.let { ordered.take(it) } ?: ordered
    }

    private fun sourceMeta(
        session: CopySession,
        table: String,
    ): Map<String, List<PartitionMeta>> {
        val (database, name) = table.split('.', limit = 2)
        return session.sourceClients.mapValues { (_, client) -> SourceMetadata.partitions(client, database, name) }
    }

    /** `shard=fingerprint` joined over shards: the partition's state across the DR cluster. */
    private fun combined(
        perShard: Map<String, List<PartitionMeta>>,
        id: String,
    ): String =
        perShard.entries
            .sortedBy { it.key }
            .mapNotNull { (shard, metas) -> metas.firstOrNull { it.id == id }?.let { "$shard=${it.fingerprint}" } }
            .joinToString(";")

    private fun stored(key: StreamKey): Map<String, Pair<String, String?>> =
        db
            .tx {
                query(
                    "SELECT partition_id, fingerprint, checksum FROM partition_fingerprints WHERE name = ? AND table_name = ? AND target = ?",
                    key.name,
                    key.table,
                    key.target,
                ) { it.getString(1) to (it.getString(2) to it.getString(3)) }
            }.toMap()

    private fun fingerprintPoll(
        entry: ContinuousJob,
        key: StreamKey,
        version: String,
        session: CopySession,
        asOf: Instant,
    ): Pair<List<String>, Pending> {
        val perShard = sourceMeta(session, key.table)
        val atSource =
            perShard.values
                .flatten()
                .map { it.id }
                .toSet()
        val window = recent(entry, perShard.values.flatten())
        val stored = stored(key)
        val current = window.associateWith { combined(perShard, it) }
        var changed = window.filter { stored[it]?.first != current[it] }
        val upsert = mutableMapOf<String, List<String?>>()
        if (entry.verify == Verify.CHECKSUM && changed.isNotEmpty()) {
            // A fingerprint change with identical content (e.g. a no-op mutation) is not worth a copy.
            val sums = changed.associateWith { checksum(version, session, key, it, perShard) }
            val same = changed.filter { stored[it]?.second != null && stored[it]?.second == sums[it] }
            same.forEach { upsert[it] = listOf(current[it], sums[it]) }
            changed = changed - same.toSet()
            changed.forEach { upsert[it] = listOf(current[it], sums[it]) }
        } else {
            changed.forEach { upsert[it] = listOf(current[it], null) }
        }
        val dropped = if (entry.syncDrops != false) stored.keys.filter { it !in atSource } else emptyList()
        val aged = stored.keys.filter { it in atSource && it !in window }
        return (changed + dropped).sorted() to Pending(upsert, dropped + aged, emptyMap(), asOf)
    }

    private fun watermarkPoll(
        entry: ContinuousJob,
        key: StreamKey,
        version: String,
        session: CopySession,
        state: State,
        asOf: Instant,
    ): Pair<List<String>, Pending> {
        val column = entry.watermarkColumn ?: error("${entry.name}: append_watermark needs watermark_column")
        val perShard = sourceMeta(session, key.table)
        val atSource =
            perShard.values
                .flatten()
                .map { it.id }
                .toSet()
        val window = recent(entry, perShard.values.flatten())
        val stored = stored(key)
        // Only partitions whose parts changed can hold new rows; untouched partitions are not read at all.
        val touched = window.filter { stored[it]?.first != combined(perShard, it) }
        val aged = stored.keys.filter { it !in window || it !in atSource }
        if (touched.isEmpty()) return emptyList<String>() to Pending(forget = aged, watermarks = state.watermarks, asOf = asOf)

        val (database, name) = key.table.split('.', limit = 2)
        val overlap = entry.overlap?.value?.seconds ?: 0
        val withNewRows = mutableSetOf<String>()
        val watermarks = state.watermarks.toMutableMap()
        for ((shard, client) in session.sourceClients) {
            val ids = touched.filter { id -> perShard[shard].orEmpty().any { it.id == id } }
            if (ids.isEmpty()) continue
            val type =
                SchemaInspector
                    .read(client, database, name)
                    ?.columns
                    ?.firstOrNull { it.name == column }
                    ?.type
                    ?.toString()
                    ?: error("${key.table} has no column $column")
            require(type.contains("Date")) { "${key.table}.$column is $type; append_watermark needs a Date or DateTime column" }
            val last = state.watermarks[shard]
            val since = if (last == null) "" else " AND ${Sql.ident(column)} > {lo:$type} - toIntervalSecond({ov:UInt32})"
            val params = mutableMapOf<String, String>()
            if (last != null) {
                params["param_lo"] = last
                params["param_ov"] = overlap.toString()
            }
            // One query per partition, bounded like a chunk read so that force_index_by_date accepts it.
            val cob = session.spec(key.table).cobColumn
            val maxima =
                runtime.guardedRead(
                    version,
                    client.endpoint.toString(),
                    "chrepl-wm-${key.name}-$shard-${System.nanoTime()}",
                    settings.readWait,
                ) {
                    ids.mapNotNull { id ->
                        val (bound, boundParams) = partitionPredicate(cob, perShard[shard].orEmpty().first { it.id == id })
                        val row =
                            client
                                .queryJson(
                                    "SELECT count() AS n, toString(max(${Sql.ident(column)})) AS m FROM ${Sql.table(key.table)} " +
                                        "WHERE $bound$since",
                                    params + boundParams + ("force_index_by_date" to "1"),
                                ).single()
                        if (row["n"].asLong() > 0) id to row["m"].textValue() else null
                    }
                }
            withNewRows += maxima.map { it.first }
            maxima.maxOfOrNull { it.second }?.let { max -> if (last == null || max > last) watermarks[shard] = max }
        }
        val upsert = touched.associateWith { listOf<String?>(combined(perShard, it), null) }
        return withNewRows.sorted() to Pending(upsert, aged, watermarks, asOf)
    }

    private fun refreshPoll(
        entry: ContinuousJob,
        key: StreamKey,
        session: CopySession,
        asOf: Instant,
    ): Pair<List<String>?, Pending> {
        val spec = session.spec(key.table)
        if (spec.fullCopy && entry.onlyRecentPartitions == null) return null to Pending(asOf = asOf)
        val perShard = sourceMeta(session, key.table)
        val partitions =
            if (entry.onlyRecentPartitions != null) {
                recent(entry, perShard.values.flatten())
            } else {
                // Everything at the source, plus target partitions the source no longer has (they are emptied).
                val (database, name) = key.table.split('.', limit = 2)
                val onTargets = session.targetClients.flatMap { c -> SourceMetadata.partitions(c, database, name).map { it.id } }
                (perShard.values.flatten().map { it.id } + onTargets).distinct().sorted()
            }
        return partitions to Pending(asOf = asOf)
    }

    /** `_partition_id = …` plus the partition's date range on the COB column (see `Chunk.dateBounds`). */
    private fun partitionPredicate(
        cobColumn: String?,
        meta: PartitionMeta,
    ): Pair<String, Map<String, String>> {
        val params = mutableMapOf("param_pid" to meta.id)
        var sql = "_partition_id = {pid:String}"
        if (cobColumn != null && meta.minDate != null && meta.maxDate != null) {
            sql += " AND ${Sql.ident(cobColumn)} BETWEEN {dlo:Date} AND {dhi:Date}"
            params["param_dlo"] = meta.minDate.toString()
            params["param_dhi"] = meta.maxDate.toString()
        }
        return sql to params
    }

    private fun checksum(
        version: String,
        session: CopySession,
        key: StreamKey,
        partition: String,
        perShard: Map<String, List<PartitionMeta>>,
    ): String =
        session.sourceClients.entries.sortedBy { it.key }.joinToString(";") { (shard, client) ->
            val meta = perShard[shard].orEmpty().firstOrNull { it.id == partition } ?: return@joinToString "$shard=absent"
            val (bound, params) = partitionPredicate(session.spec(key.table).cobColumn, meta)
            runtime.guardedRead(
                version,
                client.endpoint.toString(),
                "chrepl-sum-${key.name}-$shard-${System.nanoTime()}",
                settings.readWait,
            ) {
                val sum =
                    client.queryScalar(
                        "SELECT toString(count()) || ':' || toString(sum(cityHash64(*))) FROM ${Sql.table(key.table)} WHERE $bound",
                        params + ("force_index_by_date" to "1"),
                    )
                "$shard=$sum"
            }
        }

    // ------------------------------------------------------------------------------------- state

    private fun due(
        entry: ContinuousJob,
        lastPollAt: Instant?,
        now: Instant,
    ): Boolean {
        if (entry.mode != ContinuousMode.SCHEDULED_FULL_REFRESH) {
            val interval = entry.interval?.value ?: Duration.ofMinutes(5)
            return lastPollAt == null || !now.isBefore(lastPollAt.plus(interval))
        }
        lastPollAt ?: return false
        return cronDue(entry.cron ?: return false, ZoneId.of(entry.timezone ?: "UTC"), lastPollAt, now)
    }

    private fun load(key: StreamKey): State =
        db.tx {
            update(
                "INSERT INTO continuous_state (name, table_name, target) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                key.name,
                key.table,
                key.target,
            )
            one(
                "SELECT last_poll_at, watermarks::text, job_id, pending::text FROM continuous_state WHERE name = ? AND table_name = ? AND target = ?",
                key.name,
                key.table,
                key.target,
            ) {
                @Suppress("UNCHECKED_CAST")
                State(
                    it.instant("last_poll_at"),
                    Db.json.readValue(it.getString(2), Map::class.java) as Map<String, String>,
                    it.getString(3),
                    it.getString(4)?.let { p -> Db.json.readValue(p, Pending::class.java) },
                )
            }!!
        }

    private fun apply(
        key: StreamKey,
        pending: Pending?,
        now: Instant,
        copied: Boolean,
    ) {
        db.tx {
            if (pending != null) {
                for ((id, value) in pending.fingerprints) {
                    update(
                        "INSERT INTO partition_fingerprints (name, table_name, target, partition_id, fingerprint, checksum) " +
                            "VALUES (?, ?, ?, ?, ?, ?) " +
                            "ON CONFLICT (name, table_name, target, partition_id) DO UPDATE SET fingerprint = EXCLUDED.fingerprint, " +
                            "checksum = EXCLUDED.checksum, updated_at = now()",
                        key.name,
                        key.table,
                        key.target,
                        id,
                        value[0],
                        value[1],
                    )
                }
                for (id in pending.forget) {
                    update(
                        "DELETE FROM partition_fingerprints WHERE name = ? AND table_name = ? AND target = ? AND partition_id = ?",
                        key.name,
                        key.table,
                        key.target,
                        id,
                    )
                }
            }
            update(
                "UPDATE continuous_state SET job_id = NULL, pending = NULL, last_error = NULL, wait_reason = NULL, " +
                    "watermarks = coalesce(?::jsonb, watermarks), synced_as_of = coalesce(?, synced_as_of), " +
                    "last_success_at = ?, copies = copies + ? WHERE name = ? AND table_name = ? AND target = ?",
                pending?.let { Db.json.writeValueAsString(it.watermarks) }?.takeIf { pending.watermarks.isNotEmpty() },
                pending?.asOf,
                now,
                if (copied) 1 else 0,
                key.name,
                key.table,
                key.target,
            )
        }
    }

    private fun clearJob(
        key: StreamKey,
        error: String,
    ) {
        db.tx {
            update(
                "UPDATE continuous_state SET job_id = NULL, pending = NULL, last_error = ? WHERE name = ? AND table_name = ? AND target = ?",
                error,
                key.name,
                key.table,
                key.target,
            )
        }
    }

    private fun setWait(
        key: StreamKey,
        reason: String,
    ) {
        db.tx {
            update(
                "UPDATE continuous_state SET wait_reason = ? WHERE name = ? AND table_name = ? AND target = ?",
                reason,
                key.name,
                key.table,
                key.target,
            )
        }
    }

    private fun markPolled(
        key: StreamKey,
        now: Instant,
    ) {
        db.tx {
            update(
                "UPDATE continuous_state SET last_poll_at = ?, polls = polls + 1, wait_reason = NULL WHERE name = ? AND table_name = ? AND target = ?",
                now,
                key.name,
                key.table,
                key.target,
            )
        }
    }

    override fun close() {
        running.set(false)
        thread?.interrupt()
        thread?.join(5_000)
        runCatching { leaderConnection?.close() }
        leaderConnection = null
    }

    companion object {
        private val log = LoggerFactory.getLogger(Scheduler::class.java)
        private const val LEADER_LOCK = 7320L
    }
}
