package chrepl.guard

import chrepl.transfer.PreemptedException
import chrepl.transfer.ReadGate
import java.time.Duration

/** Routes every source read through the [SourceGuard] (SPEC §4.4–§4.5). */
class GuardGate(
    private val guard: SourceGuard,
    private val maxWait: Duration = Duration.ofDays(1),
    private val onWait: (queryId: String, reasons: List<Reason>) -> Unit = { _, _ -> },
) : ReadGate {
    override fun <T> withPermit(
        host: String,
        queryId: String,
        read: (chrepl.clickhouse.Cancellation) -> T,
    ): T {
        val permit = guard.acquire(host, queryId, maxWait) { onWait(queryId, it) }
        try {
            return read(permit.cancellation)
        } catch (e: Exception) {
            if (permit.preempted) throw PreemptedException("read $queryId pre-empted: ${permit.preemptReasons.joinToString("; ")}")
            throw e
        } finally {
            guard.release(permit)
        }
    }
}
