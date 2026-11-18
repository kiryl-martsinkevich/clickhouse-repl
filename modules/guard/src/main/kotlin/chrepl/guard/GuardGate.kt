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
        return run(permit) { read(it.cancellation) }
    }

    override fun preferred(hosts: List<String>): List<String> = guard.preferred(hosts)

    override fun <T> withPermitOnAny(
        hosts: List<String>,
        queryId: String,
        read: (host: String, cancellation: chrepl.clickhouse.Cancellation) -> T,
    ): T {
        val permit = guard.acquireAny(hosts, queryId, maxWait) { onWait(queryId, it) }
        return run(permit) { read(it.host, it.cancellation) }
    }

    /** Whether a read on [host] would be admitted now. */
    fun admissible(host: String): Boolean = guard.admissible(host)

    /** Why none of [hosts] can be read from now (empty when one can). */
    fun waitReasons(hosts: List<String>): List<Reason> = guard.waitReasons(hosts)

    private fun <T> run(
        permit: Permit,
        read: (Permit) -> T,
    ): T {
        try {
            return read(permit)
        } catch (e: Exception) {
            if (permit.preempted) throw PreemptedException("read ${permit.queryId} pre-empted: ${permit.preemptReasons.joinToString("; ")}")
            throw e
        } finally {
            guard.release(permit)
        }
    }
}
