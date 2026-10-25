package chrepl.control

import chrepl.clickhouse.CancelledException
import chrepl.clickhouse.Sql
import chrepl.masking.MaskingConfigException
import chrepl.transfer.Chunk
import chrepl.transfer.CopyRecord
import chrepl.transfer.CopySession
import chrepl.transfer.DateBounds
import chrepl.transfer.KeyRange
import chrepl.transfer.PreemptedException
import chrepl.transfer.SchemaException
import chrepl.transfer.Selection
import chrepl.transfer.SourceQueries
import chrepl.transfer.TableContext
import chrepl.transfer.TargetSafetyException
import chrepl.types.TypeParser
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class WorkerSettings(
    val threads: Int = 4,
    val lease: Duration = Duration.ofSeconds(60),
    val idleSleep: Duration = Duration.ofMillis(500),
    val maxFailures: Int = 5,
    /** Backoff before re-reading a pre-empted chunk; the guard also holds the read until the source is GREEN. */
    val preemptBackoff: Duration = Duration.ofSeconds(5),
    val failureBackoff: Duration = Duration.ofSeconds(10),
)

/**
 * Executes jobs from Postgres (SPEC §3, §7.2): planning, chunk copies, partition assembly and
 * finalisation, each claimed with `FOR UPDATE SKIP LOCKED` under a lease that a heartbeat renews.
 * A crashed worker's leases expire and the reaper puts the work back. Many workers (threads or
 * processes) can run against the same database.
 */
class Worker(
    private val db: Db,
    private val configs: ConfigRegistry,
    private val runtime: Runtime,
    val id: String,
    private val settings: WorkerSettings = WorkerSettings(),
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val pool = Executors.newFixedThreadPool(settings.threads) { r -> Thread(r, "worker-$id").apply { isDaemon = true } }
    private val heartbeats: ScheduledExecutorService =
        Executors.newScheduledThreadPool(1) { r ->
            Thread(r, "heartbeat-$id").apply {
                isDaemon =
                    true
            }
        }
    private val leaseSeconds = settings.lease.seconds.toDouble()
    private var queueTurn = 0

    fun start(): Worker {
        running.set(true)
        repeat(settings.threads) { pool.submit { loop() } }
        heartbeats.scheduleWithFixedDelay({ runCatching { reap() } }, 1, maxOf(1, settings.lease.seconds / 3), TimeUnit.SECONDS)
        return this
    }

    private fun loop() {
        while (running.get()) {
            val worked = runCatching { step() }.getOrElse { false }
            if (!worked) Thread.sleep(settings.idleSleep.toMillis())
        }
    }

    /** One unit of work; true when something was done. Exposed for deterministic tests. */
    fun step(): Boolean {
        // Planning and chunk copies read from DR; while paused or failed over neither starts, even for
        // unguarded sources. Assembly and finalisation touch only targets and may finish.
        val reads = !stopped()
        // Planning reads DR metadata, which the guard allows only while GREEN or AMBER (SPEC §4.4).
        return finishOne() ||
            assembleOne() ||
            (reads && chunkOne()) ||
            (reads && runtime.metadataAllowed() && planOne()) ||
            settleCancellations()
    }

    private fun stopped(): Boolean = db.tx { long("SELECT count(*) FROM ops_flags WHERE name IN ('paused', 'failover')") > 0 }

    // ------------------------------------------------------------------------------------- planning

    private fun planOne(): Boolean {
        val job =
            db.tx {
                one(
                    "UPDATE jobs SET state = 'PLANNING', lease_owner = ?, lease_until = now() + make_interval(secs => ?), " +
                        "updated_at = now(), started_at = coalesce(started_at, now()) " +
                        "WHERE id = (SELECT id FROM jobs WHERE state = 'PENDING' ORDER BY queue = 'continuous' DESC, created_at " +
                        "FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING id, target, tables, cob_dates, partitions, rekey, config_version",
                    id,
                    leaseSeconds,
                ) {
                    Planning(
                        it.getString("id"),
                        it.getString("target"),
                        it.strings("tables"),
                        it.dates("cob_dates"),
                        it.stringsOrNull("partitions"),
                        it.getBoolean("rekey"),
                        it.getString("config_version"),
                    )
                }
            } ?: return true.let { false }
        try {
            val session = runtime.session(job.configVersion, job.target) { _, _ -> }
            val prepared = job.tables.map { session.prepare(it, job.cobDates, job.partitions, allowRekey = job.rekey) }
            db.tx {
                var seq = 0
                for (table in prepared) {
                    for (work in table.work) {
                        val staging = CopySession.stagingName(table.context.table, job.id, "p${seq++}")
                        val taskId =
                            one(
                                "INSERT INTO partition_tasks (job_id, table_name, target_index, partition_id, staging, chunk_total, " +
                                    "state) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id",
                                job.id,
                                table.context.spec.source,
                                work.targetIndex,
                                work.partitionId,
                                staging,
                                work.chunks.size,
                                if (work.chunks.isEmpty()) "READY" else "WAITING",
                            ) { it.getLong(1) }!!
                        for (chunk in work.chunks) {
                            update(
                                "INSERT INTO chunks (job_id, partition_task_id, table_name, chunk_key, shard, spec, staging, state) " +
                                    "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, 'QUEUED')",
                                job.id,
                                taskId,
                                table.context.spec.source,
                                chunk.id,
                                chunk.shard,
                                Db.json.writeValueAsString(chunkJson(chunk)),
                                CopySession.stagingName(table.context.table, job.id, "c${seq++}"),
                            )
                        }
                    }
                }
                update(
                    "UPDATE jobs SET state = 'RUNNING', lease_owner = NULL, lease_until = NULL, updated_at = now() WHERE " +
                        "id = ? AND state = 'PLANNING'",
                    job.id,
                )
                audit(
                    id,
                    "job.planned",
                    job.id,
                    mapOf(
                        "partitions" to prepared.sumOf { it.work.size },
                        "chunks" to prepared.sumOf { it.plan.chunks.size },
                    ),
                )
            }
        } catch (e: Exception) {
            val blocked = e is SchemaException || e is MaskingConfigException
            endJob(job.id, if (blocked) JobState.BLOCKED else JobState.FAILED, describe(e))
        }
        return true
    }

    private class Planning(
        val id: String,
        val target: String,
        val tables: List<String>,
        val cobDates: List<LocalDate>,
        val partitions: List<String>?,
        val rekey: Boolean,
        val configVersion: String,
    )

    // ------------------------------------------------------------------------------------- chunks

    private class Claimed(
        val id: Long,
        val jobId: String,
        val taskId: Long,
        val table: String,
        val chunkKey: String,
        val spec: Map<String, Any?>,
        val staging: String,
        val attempt: Int,
        val target: String,
        val targetIndex: Int,
        val configVersion: String,
    )

    private fun chunkOne(): Boolean {
        val queues =
            if (queueTurn++ % (weightContinuous() + weightAdhoc()) <
                weightContinuous()
            ) {
                listOf("continuous", "adhoc")
            } else {
                listOf("adhoc", "continuous")
            }
        val chunk = queues.firstNotNullOfOrNull { claimChunk(it) } ?: return false
        val queryId = SourceQueries.queryId(chunk.jobId, chunk.chunkKey, chunk.attempt)
        val beat =
            heartbeats.scheduleWithFixedDelay(
                { heartbeatChunk(chunk, queryId) },
                settings.lease.seconds / 3,
                maxOf(
                    1,
                    settings.lease.seconds / 3,
                ),
                TimeUnit.SECONDS,
            )
        try {
            val session =
                runtime.session(chunk.configVersion, chunk.target) { _, reason ->
                    db.tx { update("UPDATE chunks SET wait_reason = ?, updated_at = now() WHERE id = ?", reason, chunk.id) }
                }
            val context = session.context(chunk.table)
            val started = System.nanoTime()
            val rows = session.copyChunk(context, chunk.targetIndex, parseChunk(chunk.chunkKey, chunk.spec), chunk.staging, queryId)
            ControlMetrics.chunkDone(chunk.table, chunk.target, rows, Duration.ofNanos(System.nanoTime() - started))
            db.tx {
                update(
                    "UPDATE chunks SET state = 'DONE', rows_copied = ?, wait_reason = NULL, lease_owner = NULL, " +
                        "updated_at = now() WHERE id = ?",
                    rows,
                    chunk.id,
                )
                update(
                    "UPDATE partition_tasks SET chunk_done = chunk_done + 1, rows_copied = rows_copied + ?, " +
                        "state = CASE WHEN chunk_done + 1 >= chunk_total THEN 'READY' ELSE state END WHERE id = ?",
                    rows,
                    chunk.taskId,
                )
            }
        } catch (e: PreemptedException) {
            ControlMetrics.chunkPreempted(chunk.table)
            db.tx {
                update(
                    "UPDATE chunks SET state = 'PREEMPTED', preemptions = preemptions + 1, wait_reason = ?, lease_owner = NULL, " +
                        "not_before = now() + make_interval(secs => ?), updated_at = now() WHERE id = ? AND state = 'RUNNING'",
                    e.message,
                    settings.preemptBackoff.seconds.toDouble(),
                    chunk.id,
                )
                audit(id, "chunk.preempted", chunk.jobId, mapOf("chunk" to chunk.chunkKey, "reason" to e.message))
            }
        } catch (e: Exception) {
            if (e is CancelledException || jobState(chunk.jobId) == JobState.CANCELLING) {
                db.tx { update("UPDATE chunks SET state = 'CANCELLED', lease_owner = NULL, updated_at = now() WHERE id = ?", chunk.id) }
            } else {
                ControlMetrics.chunkFailed(chunk.table)
                failChunk(chunk, e)
            }
        } finally {
            beat.cancel(false)
        }
        return true
    }

    private fun claimChunk(queue: String): Claimed? {
        val perUser = configs.current.scheduling.maxRunningJobsPerUser
        return db.tx {
            one(
                """
                UPDATE chunks c SET state = 'RUNNING', attempts = attempts + 1, lease_owner = ?, lease_until = now() + make_interval(secs => ?),
                       wait_reason = NULL, updated_at = now()
                FROM jobs j, partition_tasks t
                WHERE c.id = (
                    SELECT c1.id FROM chunks c1 JOIN jobs j1 ON j1.id = c1.job_id
                    WHERE c1.state IN ('QUEUED', 'PREEMPTED') AND c1.not_before <= now() AND j1.state = 'RUNNING' AND j1.queue = ?
                      AND (EXISTS (SELECT 1 FROM chunks c2 WHERE c2.job_id = j1.id AND c2.state = 'RUNNING')
                           OR (SELECT count(DISTINCT c3.job_id) FROM chunks c3 JOIN jobs j3 ON j3.id = c3.job_id
                               WHERE c3.state = 'RUNNING' AND j3.requester = j1.requester) < ?)
                    ORDER BY j1.created_at, c1.id
                    FOR UPDATE OF c1 SKIP LOCKED LIMIT 1)
                  AND j.id = c.job_id AND t.id = c.partition_task_id
                RETURNING c.id, c.job_id, c.partition_task_id, c.table_name, c.chunk_key, c.spec::text AS spec, c.staging, c.attempts,
                          j.target, t.target_index, j.config_version
                """.trimIndent(),
                id,
                leaseSeconds,
                queue,
                perUser,
            ) {
                @Suppress("UNCHECKED_CAST")
                Claimed(
                    it.getLong("id"),
                    it.getString("job_id"),
                    it.getLong("partition_task_id"),
                    it.getString("table_name"),
                    it.getString("chunk_key"),
                    Db.json.readValue(it.getString("spec"), Map::class.java) as Map<String, Any?>,
                    it.getString("staging"),
                    it.getInt("attempts"),
                    it.getString("target"),
                    it.getInt("target_index"),
                    it.getString("config_version"),
                )
            }
        }
    }

    private fun heartbeatChunk(
        chunk: Claimed,
        queryId: String,
    ) {
        runCatching {
            db.tx {
                update(
                    "UPDATE chunks SET lease_until = now() + make_interval(secs => ?) WHERE id = ? AND lease_owner = ?",
                    leaseSeconds,
                    chunk.id,
                    id,
                )
            }
            runtime.renewToken(queryId)
            if (jobState(chunk.jobId) == JobState.CANCELLING) runtime.cancel(queryId)
        }
    }

    private fun failChunk(
        chunk: Claimed,
        e: Exception,
    ) {
        val reason = describe(e)
        val fatal = e is SchemaException || e is MaskingConfigException || e is TargetSafetyException
        val failures =
            db.tx {
                one(
                    "UPDATE chunks SET failures = failures + 1, error = ?, lease_owner = NULL, wait_reason = NULL, updated_at = now(), " +
                        "state = CASE WHEN ? OR failures + 1 >= ? THEN 'FAILED' ELSE 'QUEUED' END, not_before = now() + " +
                        "make_interval(secs => ?) " +
                        "WHERE id = ? RETURNING state",
                    reason,
                    fatal,
                    settings.maxFailures,
                    settings.failureBackoff.seconds.toDouble(),
                    chunk.id,
                ) { it.getString(1) }
            }
        if (failures ==
            "FAILED"
        ) {
            endJob(
                chunk.jobId,
                if (e is SchemaException ||
                    e is MaskingConfigException
                ) {
                    JobState.BLOCKED
                } else {
                    JobState.FAILED
                },
                "chunk ${chunk.chunkKey}: $reason",
            )
        }
    }

    // ------------------------------------------------------------------------------------- assembly

    private fun assembleOne(): Boolean {
        val task =
            db.tx {
                one(
                    "UPDATE partition_tasks t SET state = 'ASSEMBLING', lease_owner = ?, lease_until = now() + make_interval(secs => ?) " +
                        "FROM jobs j WHERE t.id = (SELECT t1.id FROM partition_tasks t1 JOIN jobs j1 ON j1.id = t1.job_id " +
                        "WHERE t1.state = 'READY' " +
                        "AND j1.state = 'RUNNING' ORDER BY t1.id FOR UPDATE OF t1 SKIP LOCKED LIMIT 1) AND j.id = t.job_id " +
                        "RETURNING t.id, t.job_id, t.table_name, t.target_index, t.partition_id, t.staging, t.rows_copied, " +
                        "j.target, j.cob_dates, j.partitions, " +
                        "j.config_version, j.requester",
                    id,
                    leaseSeconds,
                ) {
                    Assembly(
                        it.getLong("id"),
                        it.getString("job_id"),
                        it.getString("table_name"),
                        it.getInt("target_index"),
                        it.getString("partition_id"),
                        it.getString("staging"),
                        it.getLong("rows_copied"),
                        it.getString("target"),
                        it.dates("cob_dates"),
                        it.stringsOrNull("partitions"),
                        it.getString("config_version"),
                        it.getString("requester"),
                    )
                }
            } ?: return false
        try {
            val session = runtime.session(task.configVersion, task.target) { _, _ -> }
            val context = session.context(task.table)
            val stagings =
                db.tx {
                    query(
                        "SELECT staging FROM chunks WHERE partition_task_id = ? ORDER BY id",
                        task.id,
                    ) { it.getString(1) }
                }
            if (!stagingsExist(session, context, task.targetIndex, stagings)) {
                // A previous attempt died after dropping its stagings: copy the chunks again.
                db.tx {
                    update(
                        "UPDATE chunks SET state = 'QUEUED', rows_copied = NULL, updated_at = now() WHERE partition_task_id = ?",
                        task.id,
                    )
                    update(
                        "UPDATE partition_tasks SET state = 'WAITING', chunk_done = 0, rows_copied = 0, lease_owner = NULL WHERE id = ?",
                        task.id,
                    )
                }
                return true
            }
            val spec = session.spec(task.table)
            val selection = session.selection(spec, task.cobDates, task.partitions)
            val record =
                CopyRecord(
                    task.jobId,
                    task.requester,
                    context.database,
                    context.table,
                    task.partitionId,
                    task.cobDates.takeIf { selection is Selection.Cob } ?: emptyList(),
                    task.rows,
                    session.realm,
                    session.keyVersion,
                    task.configVersion,
                    null,
                )
            val total = session.assemble(context, task.targetIndex, task.partitionId, selection, stagings, task.staging, record)
            db.tx {
                update("UPDATE partition_tasks SET state = 'DONE', rows_total = ?, lease_owner = NULL WHERE id = ?", total, task.id)
                val dates: List<LocalDate?> = if (selection is Selection.Cob) selection.dates else listOf(null)
                for (date in dates) {
                    update(
                        "INSERT INTO copy_registry (target, table_name, partition_id, cob_date, job_id, requester, " +
                            "rows_copied, realm, key_version, config_version) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        task.target,
                        task.table,
                        task.partitionId,
                        date,
                        task.jobId,
                        task.requester,
                        task.rows,
                        session.realm,
                        session.keyVersion,
                        task.configVersion,
                    )
                }
            }
        } catch (e: Exception) {
            db.tx {
                update(
                    "UPDATE partition_tasks SET state = 'FAILED', error = ?, lease_owner = NULL WHERE id = ?",
                    describe(e),
                    task.id,
                )
            }
            endJob(task.jobId, JobState.FAILED, "partition ${task.partitionId} of ${task.table}: ${describe(e)}")
        }
        return true
    }

    private class Assembly(
        val id: Long,
        val jobId: String,
        val table: String,
        val targetIndex: Int,
        val partitionId: String,
        val staging: String,
        val rows: Long,
        val target: String,
        val cobDates: List<LocalDate>,
        val partitions: List<String>?,
        val configVersion: String,
        val requester: String,
    )

    private fun stagingsExist(
        session: CopySession,
        context: TableContext,
        targetIndex: Int,
        stagings: List<String>,
    ): Boolean {
        if (stagings.isEmpty()) return true
        val client = session.targetClients[targetIndex]
        val existing =
            client
                .queryJson(
                    "SELECT name FROM system.tables WHERE database = {db:String} AND name IN {names:Array(String)}",
                    mapOf("param_db" to context.database, "param_names" to stagings.joinToString(",", "[", "]") { Sql.string(it) }),
                ).map { it["name"].textValue() }
                .toSet()
        return existing.containsAll(stagings)
    }

    // ------------------------------------------------------------------------------------- completion

    private fun finishOne(): Boolean {
        val job =
            db.tx {
                one(
                    "UPDATE jobs SET state = 'FINALISING', lease_owner = ?, lease_until = now() + make_interval(secs => " +
                        "?), updated_at = now() " +
                        "WHERE id = (SELECT j.id FROM jobs j WHERE j.state = 'RUNNING' AND EXISTS (SELECT 1 FROM " +
                        "partition_tasks t WHERE t.job_id = j.id) " +
                        "AND NOT EXISTS (SELECT 1 FROM partition_tasks t WHERE t.job_id = j.id AND t.state <> 'DONE') ORDER " +
                        "BY j.created_at " +
                        "FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING id, target, tables, config_version",
                    id,
                    leaseSeconds,
                ) { Triple(it.getString("id"), it.getString("target"), it.strings("tables")) to it.getString("config_version") }
            } ?: return finishEmpty()
        val (head, version) = job
        val (jobId, target, tables) = head
        try {
            val session = runtime.session(version, target) { _, _ -> }
            tables.forEach { session.finishTable(session.context(it)) }
            endJob(jobId, JobState.SUCCEEDED, null)
        } catch (e: Exception) {
            endJob(jobId, JobState.FAILED, describe(e))
        }
        return true
    }

    /** A job whose plan produced no partitions at all (nothing to copy, nothing to clear) is done. */
    private fun finishEmpty(): Boolean {
        val jobId =
            db.tx {
                one(
                    "SELECT id FROM jobs j WHERE state = 'RUNNING' AND NOT EXISTS (SELECT 1 FROM partition_tasks t WHERE " +
                        "t.job_id = j.id) " +
                        "FOR UPDATE SKIP LOCKED LIMIT 1",
                ) { it.getString(1) }
            } ?: return false
        endJob(jobId, JobState.SUCCEEDED, "nothing to copy")
        return true
    }

    // ------------------------------------------------------------------------------------- cancellation and recovery

    /** CANCELLING jobs with nothing running become CANCELLED; their staging tables are dropped. */
    private fun settleCancellations(): Boolean {
        val job =
            db.tx {
                one(
                    "SELECT id, target, tables, config_version FROM jobs j WHERE state = 'CANCELLING' " +
                        "AND NOT EXISTS (SELECT 1 FROM chunks c WHERE c.job_id = j.id AND c.state = 'RUNNING') " +
                        "AND NOT EXISTS (SELECT 1 FROM partition_tasks t WHERE t.job_id = j.id AND t.state = 'ASSEMBLING') " +
                        "FOR UPDATE SKIP LOCKED LIMIT 1",
                ) { listOf(it.getString("id"), it.getString("target"), it.getString("config_version")) to it.strings("tables") }
            } ?: return false
        val (fields, tables) = job
        val (jobId, target, version) = fields
        runCatching {
            val session = runtime.session(version, target) { _, _ -> }
            val stagings =
                db.tx {
                    query(
                        "SELECT staging FROM chunks WHERE job_id = ? UNION SELECT staging FROM partition_tasks WHERE job_id = ?",
                        jobId,
                        jobId,
                    ) {
                        it.getString(1)
                    }
                }
            val databases = tables.map { it.substringBefore('.') }.distinct()
            for (client in session.targetClients) {
                for (db in databases) {
                    for (s in stagings) {
                        runCatching {
                            client.execute(
                                "DROP TABLE IF EXISTS ${Sql.ident(db)}.${Sql.ident(s)}",
                            )
                        }
                    }
                }
            }
        }
        endJob(jobId, JobState.CANCELLED, "cancelled")
        return true
    }

    /** Puts work held under expired leases back (SPEC §12: a worker crashing mid-chunk). */
    fun reap() {
        db.tx {
            update(
                "UPDATE chunks SET state = 'QUEUED', failures = failures + 1, lease_owner = NULL, wait_reason = " +
                    "NULL, updated_at = now(), " +
                    "error = 'lease expired (worker lost)' WHERE state = 'RUNNING' AND lease_until < now()",
            )
            update(
                "UPDATE jobs SET state = 'PENDING', lease_owner = NULL, lease_until = NULL WHERE state = 'PLANNING' " +
                    "AND lease_until < now()",
            )
            update(
                "UPDATE jobs SET state = 'RUNNING', lease_owner = NULL, lease_until = NULL WHERE state = " +
                    "'FINALISING' AND lease_until < now()",
            )
            update("UPDATE partition_tasks SET state = 'READY', lease_owner = NULL WHERE state = 'ASSEMBLING' AND lease_until < now()")
            update("DELETE FROM read_tokens WHERE lease_until < now()")
        }
    }

    private fun endJob(
        jobId: String,
        state: JobState,
        reason: String?,
    ) {
        val kind =
            db.tx {
                one(
                    "SELECT kind FROM jobs WHERE id = ? AND state NOT IN ('SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELLED')",
                    jobId,
                ) { it.getString(1) }
            }
        kind?.let { ControlMetrics.jobFinished(it, state) }
        db.tx {
            update(
                "UPDATE jobs SET state = ?, reason = ?, lease_owner = NULL, lease_until = NULL, updated_at = now(), finished_at = now() " +
                    "WHERE id = ? AND state NOT IN ('SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELLED')",
                state.name,
                reason,
                jobId,
            )
            if (state !=
                JobState.SUCCEEDED
            ) {
                update("UPDATE chunks SET state = 'CANCELLED' WHERE job_id = ? AND state IN ('QUEUED', 'PREEMPTED')", jobId)
            }
            audit(id, "job.${state.name.lowercase()}", jobId, mapOf("reason" to reason))
        }
    }

    private fun jobState(jobId: String): JobState? =
        db.tx {
            one("SELECT state FROM jobs WHERE id = ?", jobId) { JobState.valueOf(it.getString(1)) }
        }

    private fun weightContinuous() = configs.current.scheduling.queueWeights.continuous

    private fun weightAdhoc() = configs.current.scheduling.queueWeights.adhoc

    override fun close() {
        running.set(false)
        heartbeats.shutdownNow()
        pool.shutdownNow()
        pool.awaitTermination(10, TimeUnit.SECONDS)
    }

    companion object {
        /** Error text for jobs and chunks: the exception message only (masking and client errors never carry data values). */
        private val QUOTED = Regex("""'(?:[^'\\]|\\.)*'""")

        /**
         * A failure as shown to users (job reason, chunk error). ClickHouse errors can quote values
         * (e.g. a value that failed to parse); quoted literals are redacted so that no source data
         * reaches the API, the portal or the audit log.
         */
        fun describe(e: Throwable): String = "${e::class.simpleName}: ${e.message?.replace(QUOTED, "'…'")?.take(500) ?: ""}"

        fun chunkJson(chunk: Chunk): Map<String, Any?> =
            mapOf(
                "shard" to chunk.shard,
                "partitionId" to chunk.partitionId,
                "estimatedBytes" to chunk.estimatedBytes,
                "selection" to (chunk.selection as? Selection.Cob)?.dates?.map { it.toString() },
                "dateBounds" to
                    chunk.dateBounds?.let { mapOf("column" to it.column, "from" to it.from.toString(), "to" to it.to.toString()) },
                "range" to
                    chunk.range?.let {
                        mapOf(
                            "column" to it.column,
                            "type" to it.type.toString(),
                            "from" to it.from,
                            "to" to it.to,
                        )
                    },
            )

        @Suppress("UNCHECKED_CAST")
        fun parseChunk(
            key: String,
            spec: Map<String, Any?>,
        ): Chunk {
            val dates = (spec["selection"] as List<String>?)?.map { LocalDate.parse(it) }
            val range =
                (spec["range"] as Map<String, Any?>?)?.let {
                    KeyRange(it["column"] as String, TypeParser.parse(it["type"] as String), it["from"] as String?, it["to"] as String?)
                }
            return Chunk(
                key,
                spec["shard"] as String,
                spec["partitionId"] as String,
                dates?.let { Selection.Cob(it) } ?: Selection.All,
                range,
                (spec["estimatedBytes"] as Number).toLong(),
                (spec["dateBounds"] as Map<String, String>?)?.let {
                    DateBounds(it.getValue("column"), LocalDate.parse(it["from"]), LocalDate.parse(it["to"]))
                },
            )
        }
    }
}
