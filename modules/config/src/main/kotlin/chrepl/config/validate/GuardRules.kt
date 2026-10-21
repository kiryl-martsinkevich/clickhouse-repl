package chrepl.config.validate

import chrepl.config.cron.CronValidator
import java.time.Duration

/** Guard settings (SPEC §4.4–§4.7). Anything that weakens the guard is an error, not a warning. */
internal fun RuleContext.checkGuard() {
    val guard = config.guard
    val path = "guard"

    if (!guard.preemption.enabled) {
        error(Code.PREEMPTION_DISABLED, "$path.preemption.enabled", "pre-emption is a core protection and cannot be disabled (SPEC §4.5)")
    }
    if (!guard.thresholds.replication.blockOnReadonly) {
        error(
            Code.UNSAFE_GUARD_SETTING,
            "$path.thresholds.replication.block_on_readonly",
            "reads must stop while a DR replica is readonly or has lost its Keeper session",
        )
    }

    val poll = guard.pollInterval.value
    if (poll.isZero) error(Code.GUARD_TIMING, "$path.poll_interval", "poll_interval must be positive")
    if (guard.staleAfter.value < poll.multipliedBy(2)) {
        error(Code.GUARD_TIMING, "$path.stale_after", "stale_after must be at least twice poll_interval")
    }
    if (guard.stableWindow.value < poll) error(Code.GUARD_TIMING, "$path.stable_window", "stable_window must be at least poll_interval")
    if (guard.releaseRatio <= 0.0 ||
        guard.releaseRatio > 1.0
    ) {
        error(Code.GUARD_TIMING, "$path.release_ratio", "release_ratio must be in (0, 1]")
    }
    val grace = guard.preemption.killGrace.value
    if (grace.isZero || grace > Duration.ofSeconds(60)) {
        error(Code.GUARD_TIMING, "$path.preemption.kill_grace", "kill_grace must be between 1ms and 60s")
    }

    val host = guard.thresholds.host
    for ((name, value) in listOf(
        "max_cpu_utilisation" to host.maxCpuUtilisation,
        "max_io_wait" to host.maxIoWait,
        "max_memory_ratio" to host.maxMemoryRatio,
    )) {
        if (value <= 0.0 || value > 1.0) error(Code.GUARD_THRESHOLD, "$path.thresholds.host.$name", "ratio must be in (0, 1]")
    }
    val replication = guard.thresholds.replication
    for ((name, value) in listOf(
        "max_queue_size" to replication.maxQueueSize,
        "max_stuck_entries" to replication.maxStuckEntries,
        "max_inflight_fetches" to replication.maxInflightFetches,
        "max_active_merges" to guard.thresholds.background.maxActiveMerges,
    )) {
        if (value < 0) error(Code.GUARD_THRESHOLD, "$path.thresholds.$name", "must not be negative")
    }
    if (replication.maxReplicaDelay.value.isZero) {
        error(Code.GUARD_THRESHOLD, "$path.thresholds.replication.max_replica_delay", "must be positive")
    }

    val concurrency = guard.concurrency
    if (concurrency.perHost < 1 || concurrency.global < concurrency.perHost) {
        error(
            Code.GUARD_CONCURRENCY,
            "$path.concurrency",
            "need 1 <= per_host <= global, got per_host=${concurrency.perHost}, global=${concurrency.global}",
        )
    }

    val breaker = guard.circuitBreaker
    if (breaker.maxPreemptions < 1 || breaker.within.value.isZero || breaker.openFor.value.isZero) {
        error(Code.GUARD_THRESHOLD, "$path.circuit_breaker", "max_preemptions, within and open_for must be positive")
    }

    for (dup in guard.blackoutWindows.duplicates { it.name }) {
        error(Code.DUPLICATE_NAME, "$path.blackout_windows", "blackout window '$dup' is defined more than once")
    }
    guard.blackoutWindows.forEachIndexed { i, window ->
        val windowPath = "$path.blackout_windows[$i]"
        CronValidator.validate(window.cron)?.let { error(Code.INVALID_CRON, "$windowPath.cron", it) }
        if (window.duration.value.isZero) error(Code.GUARD_TIMING, "$windowPath.duration", "duration must be positive")
        checkTimezone("$windowPath.timezone", window.timezone)
    }
}
