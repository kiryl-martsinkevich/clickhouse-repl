package chrepl.control

import chrepl.config.model.Role
import chrepl.guard.GuardLevel
import chrepl.transfer.ChunkPlanner
import chrepl.transfer.CopySession
import chrepl.transfer.SchemaInspector
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Ad-hoc jobs (SPEC §7): submission with RBAC, quotas, coalescing and idempotency; metadata-only
 * estimates; job views; cancellation; the "last copied" registry. Every change is audited in the
 * same transaction.
 */
class JobService(
    private val db: Db,
    private val configs: ConfigRegistry,
    private val runtime: Runtime,
    private val clock: Clock = Clock.systemUTC(),
) {
    private fun policy() = AccessPolicy(configs.current)

    fun grants(principal: Principal) = policy().grants(principal)

    fun catalog(principal: Principal): Catalog {
        requireRole(principal, Role.VIEWER)
        return policy().catalog(principal)
    }

    // ------------------------------------------------------------------------------------- estimates

    /** Tables a request expands to (dataset tables first, then explicit tables). */
    private fun tablesOf(request: JobRequest): List<String> {
        val config = configs.current
        val fromDataset =
            request.dataset?.let { name ->
                config.datasets.firstOrNull { it.name == name }?.tables ?: throw RequestException("unknown dataset '$name'")
            } ?: emptyList()
        val tables = (fromDataset + request.tables).distinct()
        if (tables.isEmpty()) throw RequestException("give a dataset or at least one table")
        for (t in tables) if (config.tables.none { it.source == t }) throw RequestException("unknown table '$t'")
        if (request.target !in config.targets) throw RequestException("unknown target '${request.target}'")
        return tables
    }

    fun estimate(
        principal: Principal,
        request: JobRequest,
    ): Estimate {
        val tables = tablesOf(request)
        policy().authoriseSubmit(principal, request.dataset, tables, request.target)
        val status = runtime.guard?.status()
        // Metadata queries are allowed while the guard is GREEN or AMBER (SPEC §4.4), never when DR may be active.
        val blocked = status != null && status.level !in setOf(GuardLevel.GREEN, GuardLevel.AMBER)
        val estimates =
            if (blocked) {
                emptyList()
            } else {
                val session = runtime.session(configs.currentVersion, request.target) { _, _ -> }
                tables.map { estimateTable(session, it, request.cobDates) }
            }
        return Estimate(
            tables = estimates,
            bytes = estimates.sumOf { it.bytes },
            sourceAvailable = !blocked,
            sourceReason = if (blocked) status.reasons.joinToString("; ") else null,
            queuedJobs = db.tx { long("SELECT count(*) FROM jobs WHERE state IN ('PENDING', 'PLANNING', 'RUNNING') AND queue = 'adhoc'") },
            recentCopies = recentCopies(request.target, tables, request.cobDates, Duration.ofDays(7)),
        )
    }

    private fun estimateTable(
        session: CopySession,
        table: String,
        cobDates: List<LocalDate>,
    ): TableEstimate {
        val spec = session.spec(table)
        val selection = session.selection(spec, cobDates)
        val (database, name) = table.split('.', limit = 2)
        val client = session.sourceClients.values.first()
        val info = SchemaInspector.read(client, database, name) ?: throw RequestException("$table does not exist on the source")
        val plan = ChunkPlanner(Long.MAX_VALUE).plan(spec, info, selection, session.sourceClients)
        return TableEstimate(table, plan.partitionIds.size, plan.rows, plan.bytes)
    }

    // ------------------------------------------------------------------------------------- submission

    fun submit(
        principal: Principal,
        request: JobRequest,
    ): JobView {
        val version = configs.currentVersion
        val config = configs.get(version)
        val tables = tablesOf(request)
        val rule = policy().authoriseSubmit(principal, request.dataset, tables, request.target)
        val quota = rule.quota ?: throw QuotaException("no quota configured for group ${rule.group}")
        val cobTables = tables.filter { t -> config.tables.first { it.source == t }.cobColumn != null }
        if (cobTables.isNotEmpty() &&
            request.cobDates.isEmpty()
        ) {
            throw RequestException("COB-sliced tables need at least one COB date: ${cobTables.joinToString()}")
        }
        if (request.cobDates.size > quota.maxCobDatesPerJob) throw QuotaException("at most ${quota.maxCobDatesPerJob} COB dates per job")

        // Recently copied slices on a shared target need an explicit confirmation (SPEC §7.1 step 4).
        val window =
            config.targets
                .getValue(request.target)
                .recentCopyWarning
                ?.value
        if (window != null && !request.confirmOverwriteRecent) {
            // Continuous syncs overwrite their slices anyway, so only other people's copies need a confirmation.
            val recent =
                recentCopies(request.target, tables, request.cobDates, window).filter {
                    it.requester != principal.user &&
                        it.requester != SCHEDULER
                }
            if (recent.isNotEmpty()) {
                throw ConflictException(
                    "recently copied by others: " +
                        recent.joinToString { "${it.table} ${it.cobDate ?: ""} by ${it.requester} at ${it.copiedAt}" } +
                        "; resubmit with confirmOverwriteRecent",
                )
            }
        }

        val bytes = runCatching { estimate(principal, request).bytes }.getOrDefault(0L)
        if (bytes >
            quota.maxBytesPerJob.bytes
        ) {
            throw QuotaException("estimated $bytes bytes exceeds max_bytes_per_job ${quota.maxBytesPerJob}")
        }
        val coalesceKey =
            listOf(
                request.target,
                request.mode.name,
                tables.sorted().joinToString(","),
                request.cobDates.sorted().joinToString(","),
            ).joinToString("|")

        return db.tx {
            exec("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "submit:${principal.user}")
            request.idempotencyKey?.let { key ->
                one(
                    "SELECT id FROM jobs WHERE requester = ? AND idempotency_key = ?",
                    principal.user,
                    key,
                ) { it.getString(1) }?.let { return@tx view(it) }
            }
            // An identical active job: attach this requester instead of copying twice (SPEC §7.3).
            one(
                "SELECT id FROM jobs WHERE coalesce_key = ? AND state IN ('PENDING', 'PLANNING', 'RUNNING', " +
                    "'FINALISING') ORDER BY created_at LIMIT 1",
                coalesceKey,
            ) { it.getString(1) }?.let { existing ->
                update("INSERT INTO job_requesters (job_id, requester) VALUES (?, ?) ON CONFLICT DO NOTHING", existing, principal.user)
                audit(principal.user, "job.coalesce", existing, mapOf("request" to request))
                return@tx view(existing)
            }
            val today = LocalDate.now(clock).atStartOfDay().toInstant(ZoneOffset.UTC)
            val used =
                long("SELECT coalesce(sum(estimated_bytes), 0) FROM jobs WHERE requester = ? AND created_at >= ?", principal.user, today)
            if (used + bytes >
                quota.maxBytesPerDay.bytes
            ) {
                throw QuotaException("daily quota: $used + $bytes bytes exceeds max_bytes_per_day ${quota.maxBytesPerDay}")
            }
            val active =
                long(
                    "SELECT count(*) FROM jobs WHERE requester = ? AND state NOT IN ('SUCCEEDED', 'FAILED', 'BLOCKED', 'CANCELLED')",
                    principal.user,
                )
            if (active >=
                quota.maxConcurrentJobs
            ) {
                throw QuotaException("$active active jobs; max_concurrent_jobs is ${quota.maxConcurrentJobs}")
            }

            val id = chrepl.transfer.CopyJob.newJobId()
            update(
                "INSERT INTO jobs (id, kind, queue, requester, target, dataset, tables, cob_dates, mode, state, " +
                    "config_version, coalesce_key, idempotency_key, estimated_bytes) " +
                    "VALUES (?, 'adhoc', 'adhoc', ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)",
                id,
                principal.user,
                request.target,
                request.dataset,
                StringArray(tables),
                DateArray(request.cobDates.distinct().sorted()),
                request.mode.name,
                version,
                coalesceKey,
                request.idempotencyKey,
                bytes,
            )
            update("INSERT INTO job_requesters (job_id, requester) VALUES (?, ?)", id, principal.user)
            audit(principal.user, "job.submit", id, mapOf("request" to request, "estimatedBytes" to bytes))
            view(id)
        }
    }

    /**
     * Creates a job on behalf of the scheduler (continuous polls and scheduled refreshes, SPEC §8).
     * Returns null when a job with the same [coalesceKey] is still active. [partitions] selects
     * whole partitions; null means [cobDates] (or a full copy).
     */
    fun submitSystem(
        kind: String,
        target: String,
        tables: List<String>,
        cobDates: List<LocalDate>,
        partitions: List<String>?,
        coalesceKey: String,
        configVersion: String = configs.currentVersion,
    ): String? =
        db.tx {
            exec("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "submit:$coalesceKey")
            val active =
                long(
                    "SELECT count(*) FROM jobs WHERE coalesce_key = ? AND state IN ('PENDING', 'PLANNING', 'RUNNING', 'FINALISING', 'CANCELLING')",
                    coalesceKey,
                )
            if (active > 0) return@tx null
            val id = chrepl.transfer.CopyJob.newJobId()
            update(
                "INSERT INTO jobs (id, kind, queue, requester, target, tables, cob_dates, partitions, state, config_version, " +
                    "coalesce_key) VALUES (?, ?, 'continuous', ?, ?, ?, ?, ?, 'PENDING', ?, ?)",
                id,
                kind,
                SCHEDULER,
                target,
                StringArray(tables),
                DateArray(cobDates),
                partitions?.let { StringArray(it) },
                configVersion,
                coalesceKey,
            )
            update("INSERT INTO job_requesters (job_id, requester) VALUES (?, ?)", id, SCHEDULER)
            audit(SCHEDULER, "job.submit", id, mapOf("kind" to kind, "tables" to tables, "partitions" to partitions))
            id
        }

    // ------------------------------------------------------------------------------------- reading

    fun get(
        principal: Principal,
        id: String,
    ): JobView {
        requireRole(principal, Role.VIEWER)
        return withSourceWait(db.tx { view(id) })
    }

    /** A job that cannot be planned yet because the guard blocks DR metadata shows the guard's reason. */
    private fun withSourceWait(view: JobView): JobView =
        if (view.state == JobState.PENDING && view.waitReason == null) {
            runtime.metadataBlockedReason()?.let { view.copy(waitReason = it) } ?: view
        } else {
            view
        }

    fun list(
        principal: Principal,
        mine: Boolean,
        state: JobState?,
        limit: Int = 100,
    ): List<JobView> {
        requireRole(principal, Role.VIEWER)
        val ids =
            db.tx {
                query(
                    "SELECT id FROM jobs j WHERE (? = false OR EXISTS (SELECT 1 FROM job_requesters r WHERE r.job_id = " +
                        "j.id AND r.requester = ?)) " +
                        "AND (?::text IS NULL OR state = ?) ORDER BY created_at DESC LIMIT ?",
                    mine,
                    principal.user,
                    state?.name,
                    state?.name,
                    limit,
                ) { it.getString(1) }
            }
        return ids.map { withSourceWait(db.tx { view(it) }) }
    }

    fun chunks(
        principal: Principal,
        id: String,
    ): List<ChunkView> {
        requireRole(principal, Role.VIEWER)
        return db.tx {
            query(
                "SELECT c.*, t.partition_id FROM chunks c JOIN partition_tasks t ON t.id = c.partition_task_id WHERE " +
                    "c.job_id = ? ORDER BY c.id",
                id,
            ) {
                ChunkView(
                    it.getLong("id"),
                    it.getString("table_name"),
                    it.getString("shard"),
                    it.getString("partition_id"),
                    ChunkState.valueOf(it.getString("state")),
                    it.getInt("attempts"),
                    it.getInt("preemptions"),
                    it.getObject("rows_copied") as Long?,
                    it.getString("wait_reason"),
                    it.getString("error"),
                )
            }
        }
    }

    fun history(
        principal: Principal,
        target: String,
        table: String?,
        cob: LocalDate?,
    ): List<RecentCopy> {
        requireRole(principal, Role.VIEWER)
        return db.tx {
            query(
                "SELECT table_name, cob_date, requester, job_id, copied_at FROM copy_registry WHERE target = ? " +
                    "AND (?::text IS NULL OR table_name = ?) AND (?::date IS NULL OR cob_date = ?) ORDER BY copied_at DESC LIMIT 200",
                target,
                table,
                table,
                cob,
                cob,
            ) { recent(it) }
        }
    }

    private fun recentCopies(
        target: String,
        tables: List<String>,
        cobDates: List<LocalDate>,
        within: Duration,
    ): List<RecentCopy> =
        db.tx {
            query(
                "SELECT DISTINCT ON (table_name, cob_date) table_name, cob_date, requester, job_id, copied_at FROM copy_registry " +
                    "WHERE target = ? AND table_name = ANY(?) AND (cob_date IS NULL OR cob_date = ANY(?)) AND copied_at > ? " +
                    "ORDER BY table_name, cob_date, copied_at DESC",
                target,
                StringArray(tables),
                DateArray(cobDates),
                clock.instant().minus(within),
            ) { recent(it) }
        }

    private fun recent(rs: ResultSet) =
        RecentCopy(
            rs.getString("table_name"),
            rs.getObject("cob_date", LocalDate::class.java),
            rs.getString("requester"),
            rs.getString("job_id"),
            rs.instant("copied_at")!!,
        )

    // ------------------------------------------------------------------------------------- cancellation

    fun cancel(
        principal: Principal,
        id: String,
    ): JobView =
        db.tx {
            val owner =
                one("SELECT requester, state FROM jobs WHERE id = ? FOR UPDATE", id) {
                    it.getString(1) to JobState.valueOf(it.getString(2))
                }
                    ?: throw NotFoundException("no job $id")
            val grants = policy().grants(principal)
            if (owner.first != principal.user &&
                !grants.has(Role.OPERATOR)
            ) {
                throw ForbiddenException("only the requester or an operator can cancel $id")
            }
            if (!owner.second.terminal) {
                update("UPDATE jobs SET state = 'CANCELLING', updated_at = now() WHERE id = ?", id)
                update(
                    "UPDATE chunks SET state = 'CANCELLED', updated_at = now() WHERE job_id = ? AND state IN ('QUEUED', 'PREEMPTED')",
                    id,
                )
                audit(principal.user, "job.cancel", id, emptyMap<String, Any>())
            }
            view(id)
        }

    private fun requireRole(
        principal: Principal,
        role: Role,
    ) {
        if (!policy().grants(principal).has(role)) throw ForbiddenException("${principal.user} lacks the ${role.name.lowercase()} role")
    }
}

internal fun Tx.audit(
    actor: String,
    action: String,
    jobId: String?,
    details: Any,
) = update(
    "INSERT INTO audit_log (actor, action, job_id, details) VALUES (?, ?, ?, ?::jsonb)",
    actor,
    action,
    jobId,
    Db.json.writeValueAsString(details),
)

private class JobRow(
    val kind: String,
    val queue: String,
    val requester: String,
    val target: String,
    val dataset: String?,
    val tables: List<String>,
    val cobDates: List<LocalDate>,
    val state: JobState,
    val reason: String?,
    val estimatedBytes: Long,
    val configVersion: String,
    val createdAt: java.time.Instant,
    val startedAt: java.time.Instant?,
    val finishedAt: java.time.Instant?,
)

/** Assembles a [JobView] inside a transaction. */
internal fun Tx.view(id: String): JobView {
    val job =
        one("SELECT * FROM jobs WHERE id = ?", id) { rs ->
            JobRow(
                rs.getString("kind"),
                rs.getString("queue"),
                rs.getString("requester"),
                rs.getString("target"),
                rs.getString("dataset"),
                rs.strings("tables"),
                rs.dates("cob_dates"),
                JobState.valueOf(rs.getString("state")),
                rs.getString("reason"),
                rs.getLong("estimated_bytes"),
                rs.getString("config_version"),
                rs.instant("created_at")!!,
                rs.instant("started_at"),
                rs.instant("finished_at"),
            )
        } ?: throw NotFoundException("no job $id")
    val queuePosition =
        if (job.state == JobState.PENDING) {
            long("SELECT count(*) FROM jobs WHERE state = 'PENDING' AND queue = ? AND created_at < ?", job.queue, job.createdAt)
        } else {
            null
        }
    val (chunksTotal, chunksDone, rows) =
        one("SELECT count(*), count(*) FILTER (WHERE state = 'DONE'), coalesce(sum(rows_copied), 0) FROM chunks WHERE job_id = ?", id) {
            Triple(it.getInt(1), it.getInt(2), it.getLong(3))
        }!!
    val (tasksTotal, tasksDone) =
        one("SELECT count(*), count(*) FILTER (WHERE state = 'DONE') FROM partition_tasks WHERE job_id = ?", id) {
            it.getInt(1) to
                it.getInt(2)
        }!!
    val waitReason =
        if (job.state == JobState.RUNNING) {
            one(
                "SELECT wait_reason FROM chunks WHERE job_id = ? AND state = 'RUNNING' AND wait_reason IS NOT NULL " +
                    "ORDER BY updated_at DESC LIMIT 1",
                id,
            ) { it.getString(1) }
        } else {
            null
        }
    return JobView(
        id = id,
        kind = job.kind,
        requester = job.requester,
        requesters = query("SELECT requester FROM job_requesters WHERE job_id = ? ORDER BY added_at", id) { it.getString(1) },
        target = job.target,
        dataset = job.dataset,
        tables = job.tables,
        cobDates = job.cobDates,
        state = job.state,
        waitReason = waitReason,
        reason = job.reason,
        queuePosition = queuePosition,
        progress = Progress(chunksTotal, chunksDone, tasksTotal, tasksDone, rows),
        estimatedBytes = job.estimatedBytes,
        configVersion = job.configVersion,
        createdAt = job.createdAt,
        startedAt = job.startedAt,
        finishedAt = job.finishedAt,
    )
}
