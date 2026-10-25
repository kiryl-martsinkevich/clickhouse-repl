package chrepl.control

import chrepl.config.model.Role
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** `GET /api/v1/continuous` (SPEC §7.5, §8.4): one row per (continuous job, table, target). */
data class ContinuousView(
    val name: String,
    val table: String,
    val target: String,
    val mode: String,
    /** IDLE, COPYING, WAITING (for the source or a flag), PAUSED or ERROR. */
    val status: String,
    val paused: Boolean,
    val lastPollAt: Instant?,
    val lastSuccessAt: Instant?,
    /** The target reflects the active DC as of this time (DR replication delay included). */
    val syncedAsOf: Instant?,
    /** End-to-end lag: now − [syncedAsOf]; null until the first sync. */
    val lagSeconds: Long?,
    val lagSloSeconds: Long?,
    val sloBreached: Boolean,
    /** Lag above the SLO for longer than `lag_alert_after` (SPEC §8.4). */
    val alerting: Boolean,
    val jobId: String?,
    val waitReason: String?,
    val lastError: String?,
    val polls: Long,
    val copies: Long,
)

class ContinuousService(
    private val db: Db,
    private val configs: ConfigRegistry,
    private val clock: Clock = Clock.systemUTC(),
) {
    private fun require(
        principal: Principal,
        role: Role,
    ) {
        if (!AccessPolicy(configs.current).grants(principal).has(role)) {
            throw ForbiddenException("${principal.user} lacks the ${role.name.lowercase()} role")
        }
    }

    fun list(principal: Principal): List<ContinuousView> {
        require(principal, Role.VIEWER)
        return views()
    }

    /** Every configured stream, also those that have not polled yet; no RBAC (metrics). */
    fun views(): List<ContinuousView> {
        val now = clock.instant()
        val config = configs.current
        val paused = db.tx { query("SELECT name FROM continuous_paused") { it.getString(1) } }.toSet()
        val rows =
            db
                .tx {
                    query("SELECT * FROM continuous_state") {
                        Scheduler.StreamKey(it.getString("name"), it.getString("table_name"), it.getString("target")) to
                            Row(
                                it.instant("last_poll_at"),
                                it.instant("last_success_at"),
                                it.instant("synced_as_of"),
                                it.getString("job_id"),
                                it.getString("wait_reason"),
                                it.getString("last_error"),
                                it.getLong("polls"),
                                it.getLong("copies"),
                            )
                    }
                }.toMap()
        return config.continuous.flatMap { entry ->
            entry.allTables.flatMap { table ->
                entry.targets.map { target ->
                    val row = rows[Scheduler.StreamKey(entry.name, table, target)]
                    val lag = row?.syncedAsOf?.let { Duration.between(it, now).seconds.coerceAtLeast(0) }
                    val slo = entry.lagSlo?.value?.seconds
                    val alertAfter = entry.lagAlertAfter?.value?.seconds ?: 0
                    val isPaused = entry.name in paused
                    ContinuousView(
                        name = entry.name,
                        table = table,
                        target = target,
                        mode = entry.mode.name.lowercase(),
                        status =
                            when {
                                isPaused -> "PAUSED"
                                row?.jobId != null -> "COPYING"
                                row?.waitReason != null -> "WAITING"
                                row?.lastError != null -> "ERROR"
                                else -> "IDLE"
                            },
                        paused = isPaused,
                        lastPollAt = row?.lastPollAt,
                        lastSuccessAt = row?.lastSuccessAt,
                        syncedAsOf = row?.syncedAsOf,
                        lagSeconds = lag,
                        lagSloSeconds = slo,
                        sloBreached = lag != null && slo != null && lag > slo,
                        alerting = lag != null && slo != null && lag > slo + alertAfter,
                        jobId = row?.jobId,
                        waitReason = row?.waitReason,
                        lastError = row?.lastError,
                        polls = row?.polls ?: 0,
                        copies = row?.copies ?: 0,
                    )
                }
            }
        }
    }

    private class Row(
        val lastPollAt: Instant?,
        val lastSuccessAt: Instant?,
        val syncedAsOf: Instant?,
        val jobId: String?,
        val waitReason: String?,
        val lastError: String?,
        val polls: Long,
        val copies: Long,
    )

    /** Stops new polls of a continuous job; a copy already running finishes. */
    fun pause(
        principal: Principal,
        name: String,
    ) {
        require(principal, Role.OPERATOR)
        known(name)
        db.tx {
            update("INSERT INTO continuous_paused (name, paused_by) VALUES (?, ?) ON CONFLICT DO NOTHING", name, principal.user)
            audit(principal.user, "continuous.pause", null, mapOf("name" to name))
        }
    }

    fun resume(
        principal: Principal,
        name: String,
    ) {
        require(principal, Role.OPERATOR)
        known(name)
        db.tx {
            update("DELETE FROM continuous_paused WHERE name = ?", name)
            audit(principal.user, "continuous.resume", null, mapOf("name" to name))
        }
    }

    private fun known(name: String) {
        if (configs.current.continuous.none { it.name == name }) throw NotFoundException("no continuous job '$name'")
    }
}
