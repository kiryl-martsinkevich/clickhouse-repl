package chrepl.control

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Timer
import java.time.Duration

/**
 * Prometheus metrics of the control plane (SPEC §11), on Micrometer's global registry, which the
 * app exposes at `/actuator/prometheus`. Names become e.g. `chrepl_rows_copied_total`.
 */
object ControlMetrics {
    fun chunkDone(
        table: String,
        target: String,
        rows: Long,
        took: Duration,
    ) {
        Timer
            .builder("chrepl.chunk.duration")
            .tag("table", table)
            .tag("outcome", "done")
            .register(Metrics.globalRegistry)
            .record(took)
        Counter
            .builder(
                "chrepl.rows.copied",
            ).tag("table", table)
            .tag("target", target)
            .register(Metrics.globalRegistry)
            .increment(rows.toDouble())
    }

    fun chunkPreempted(table: String) {
        Counter
            .builder("chrepl.preemptions")
            .tag("table", table)
            .register(Metrics.globalRegistry)
            .increment()
    }

    fun chunkFailed(table: String) {
        Counter
            .builder("chrepl.chunk.failures")
            .tag("table", table)
            .register(Metrics.globalRegistry)
            .increment()
    }

    fun jobFinished(
        kind: String,
        state: JobState,
    ) {
        Counter
            .builder("chrepl.jobs.finished")
            .tag("kind", kind)
            .tag("state", state.name)
            .register(Metrics.globalRegistry)
            .increment()
    }

    /** Must stay 0: a configured host resolved into the active DC at runtime (SPEC §4.1). */
    fun activeDcViolation(code: String) {
        Counter
            .builder("chrepl.active.dc.violations")
            .tag("code", code)
            .register(Metrics.globalRegistry)
            .increment()
    }

    fun retentionDrops(count: Int) {
        Counter.builder("chrepl.retention.dropped.partitions").register(Metrics.globalRegistry).increment(count.toDouble())
    }
}
