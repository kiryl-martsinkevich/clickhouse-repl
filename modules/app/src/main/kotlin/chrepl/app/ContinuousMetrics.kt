package chrepl.app

import chrepl.control.ContinuousService
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Prometheus gauges for continuous mode (SPEC §11): end-to-end lag and SLO state per stream. */
@Component
@EnableScheduling
class ContinuousMetrics(
    registry: MeterRegistry,
    private val continuous: ContinuousService,
) {
    private val lag =
        MultiGauge
            .builder(
                "chrepl_continuous_lag_seconds",
            ).description("End-to-end lag of a continuous stream")
            .register(registry)
    private val alerting =
        MultiGauge
            .builder(
                "chrepl_continuous_alerting",
            ).description("1 while lag exceeds the SLO beyond lag_alert_after")
            .register(registry)

    @Scheduled(fixedDelayString = "\${chrepl.metrics.refresh:15s}")
    fun refresh() {
        val views = runCatching { continuous.views() }.getOrNull() ?: return
        lag.register(
            views.filter { it.lagSeconds != null }.map { v ->
                MultiGauge.Row.of(tags(v.name, v.table, v.target), v.lagSeconds!!.toDouble())
            },
            true,
        )
        alerting.register(views.map { v -> MultiGauge.Row.of(tags(v.name, v.table, v.target), if (v.alerting) 1.0 else 0.0) }, true)
    }

    private fun tags(
        name: String,
        table: String,
        target: String,
    ) = Tags.of("name", name, "table", table, "target", target)
}
