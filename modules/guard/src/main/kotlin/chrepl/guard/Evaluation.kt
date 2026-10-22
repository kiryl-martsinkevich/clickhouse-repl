package chrepl.guard

import chrepl.config.model.Thresholds

enum class GuardLevel {
    /** All signals well below thresholds for the stable window: reads may start. */
    GREEN,

    /** Below thresholds but not yet stable, or close to a threshold: metadata only. */
    AMBER,

    /** A threshold is exceeded, or an operational control is active: nothing may read; running reads are pre-empted. */
    RED,

    /** DR looks active (SPEC §4.6); like RED, and only an operator can clear it. */
    DR_ACTIVE,

    /** The host cannot be assessed (unreachable, stale, clock skew): treated as RED. */
    UNKNOWN,
}

/** Why the guard is not GREEN; [message] is shown to users and never contains query text. */
data class Reason(
    val code: String,
    val host: String?,
    val message: String,
) {
    override fun toString() = if (host == null) message else "$host: $message"
}

/**
 * Compares a sample with the thresholds (SPEC §4.4). With [scale] < 1 it reports signals above that
 * fraction of their threshold, which is how hysteresis keeps hosts out of GREEN until signals drop
 * well below the limits.
 */
internal class Evaluator(
    private val thresholds: Thresholds,
) {
    fun breaches(
        sample: HostSample,
        scale: Double = 1.0,
    ): List<Reason> {
        val r = thresholds.replication
        val h = thresholds.host
        val b = thresholds.background
        val out = mutableListOf<Reason>()
        val host = sample.host

        fun check(
            code: String,
            value: Double,
            limit: Double,
            describe: (String) -> String,
        ) {
            if (value > limit * scale) out += Reason(code, host, describe(fmt(limit)))
        }

        check(
            "REPLICA_DELAY",
            sample.replicaDelaySeconds.toDouble(),
            r.maxReplicaDelay.value.seconds
                .toDouble(),
        ) {
            "replication delay ${sample.replicaDelaySeconds} s (threshold $it s)"
        }
        check("QUEUE_SIZE", sample.queueSize.toDouble(), r.maxQueueSize.toDouble()) {
            "replication queue ${sample.queueSize} entries (threshold $it)"
        }
        check(
            "QUEUE_AGE",
            sample.queueOldestAgeSeconds.toDouble(),
            r.maxQueueOldestAge.value.seconds
                .toDouble(),
        ) {
            "oldest replication entry ${sample.queueOldestAgeSeconds} s old (threshold $it s)"
        }
        check("STUCK_ENTRIES", sample.stuckEntries.toDouble(), r.maxStuckEntries.toDouble()) {
            "${sample.stuckEntries} failing replication entries (threshold $it)"
        }
        check("FETCHES", sample.inflightFetches.toDouble(), r.maxInflightFetches.toDouble()) {
            "${sample.inflightFetches} part fetches in flight (threshold $it)"
        }
        if (r.blockOnReadonly && (sample.readonlyReplicas > 0 || sample.expiredSessions > 0)) {
            out += Reason("KEEPER_SESSION", host, "replica readonly or Keeper session expired")
        }
        check(
            "CPU",
            sample.cpuUtilisation,
            h.maxCpuUtilisation,
        ) { "CPU ${pct(sample.cpuUtilisation)} (threshold ${pct(h.maxCpuUtilisation)})" }
        check("IO_WAIT", sample.ioWait, h.maxIoWait) { "IO wait ${pct(sample.ioWait)} (threshold ${pct(h.maxIoWait)})" }
        check("MEMORY", sample.memoryRatio, h.maxMemoryRatio) { "memory ${pct(sample.memoryRatio)} (threshold ${pct(h.maxMemoryRatio)})" }
        check(
            "MERGES",
            sample.activeMerges.toDouble(),
            b.maxActiveMerges.toDouble(),
        ) { "${sample.activeMerges} merges running (threshold $it)" }
        check("MUTATIONS", sample.mutationBytes.toDouble(), b.maxMutationBytes.bytes.toDouble()) {
            "mutations pending on ${sample.mutationBytes / 1_000_000} MB (threshold ${b.maxMutationBytes})"
        }
        return out
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(v)

    private fun pct(v: Double) = "${(v * 100).toInt()}%"
}
