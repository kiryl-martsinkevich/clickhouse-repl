package chrepl.app

import chrepl.control.ActiveDcWatch
import chrepl.control.Db
import chrepl.control.PgOpsFlags
import chrepl.control.Runtime
import chrepl.guard.GuardLevel
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Guard and queue gauges (SPEC §11), refreshed from the in-process guard and Postgres. */
@Component
class GuardMetrics(
    registry: MeterRegistry,
    private val runtime: Runtime,
    private val db: Db,
) {
    private val hostLevel =
        MultiGauge
            .builder(
                "chrepl_guard_state",
            ).description("Guard level per DR host: 0 GREEN, 1 AMBER, 2 RED, 3 UNKNOWN, 4 DR_ACTIVE")
            .register(registry)
    private val clusterLevel =
        MultiGauge
            .builder(
                "chrepl_guard_cluster_state",
            ).description("Cluster guard level (same scale)")
            .register(registry)
    private val delay = MultiGauge.builder("chrepl_dr_replica_delay_seconds").description("DR replica delay per host").register(registry)
    private val queue =
        MultiGauge
            .builder(
                "chrepl_dr_replication_queue",
            ).description("DR replication queue size per host")
            .register(registry)
    private val reasons = MultiGauge.builder("chrepl_guard_block_reasons").description("Active blocking reasons by code").register(registry)
    private val depth = MultiGauge.builder("chrepl_queue_depth").description("Active jobs per queue").register(registry)

    init {
        // Time from pre-emption to the read's end: the budgeted reaction time (SPEC §1.3).
        val latency =
            io.micrometer.core.instrument.Timer
                .builder("chrepl.preemption.latency")
                .publishPercentileHistogram()
                .register(registry)
        runtime.guard?.onPreemptionEnded = { latency.record(it) }
    }

    @Scheduled(fixedDelayString = "\${chrepl.metrics.refresh:15s}")
    fun refresh() {
        runtime.guard?.status()?.let { status ->
            hostLevel.register(status.hosts.map { (host, level) -> MultiGauge.Row.of(Tags.of("host", host), level.ordinal()) }, true)
            clusterLevel.register(listOf(MultiGauge.Row.of(Tags.empty(), status.level.ordinal())), true)
            delay.register(
                status.hostSignals.map { (host, s) ->
                    MultiGauge.Row.of(Tags.of("host", host), s.replicaDelaySeconds.toDouble())
                },
                true,
            )
            queue.register(status.hostSignals.map { (host, s) -> MultiGauge.Row.of(Tags.of("host", host), s.queueSize.toDouble()) }, true)
            reasons.register(
                status.reasons.groupBy { it.code }.map { (code, list) ->
                    MultiGauge.Row.of(Tags.of("reason", code), list.size.toDouble())
                },
                true,
            )
        }
        runCatching {
            val counts =
                db
                    .tx {
                        query(
                            "SELECT queue, count(*) FROM jobs WHERE state IN ('PENDING', 'PLANNING', 'RUNNING', 'FINALISING') GROUP BY queue",
                        ) {
                            it.getString(1) to it.getLong(2)
                        }
                    }.toMap()
            depth.register(
                listOf("adhoc", "continuous").map { q ->
                    MultiGauge.Row.of(Tags.of("queue", q), (counts[q] ?: 0L).toDouble())
                },
                true,
            )
        }
    }

    private fun GuardLevel.ordinal(): Double =
        when (this) {
            GuardLevel.GREEN -> 0.0
            GuardLevel.AMBER -> 1.0
            GuardLevel.RED -> 2.0
            GuardLevel.UNKNOWN -> 3.0
            GuardLevel.DR_ACTIVE -> 4.0
        }
}

/** Re-checks every configured host against the active-DC deny-list every minute (SPEC §4.1). */
@Component
class ActiveDcCheck(
    configs: chrepl.control.ConfigRegistry,
    flags: PgOpsFlags,
) {
    private val watch = ActiveDcWatch({ configs.current }, flags)

    @Scheduled(fixedDelayString = "\${chrepl.active-dc-check:60s}", initialDelayString = "\${chrepl.active-dc-check:60s}")
    fun check() {
        runCatching { watch.check() }.onFailure { log.warn("active-DC re-check failed", it) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ActiveDcCheck::class.java)
    }
}
