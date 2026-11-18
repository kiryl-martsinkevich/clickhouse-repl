package chrepl.guard

import chrepl.config.model.Guard
import chrepl.config.model.Source
import chrepl.transfer.ClientFactory

/** Builds a [SourceGuard] for a configured DR source, polling as the guard user (SPEC §4.2). */
object GuardFactory {
    fun create(
        guard: Guard,
        source: Source,
        clients: ClientFactory,
        flags: OpsFlags = InMemoryOpsFlags(),
    ): SourceGuard {
        val guardClients =
            source.shards.flatMap { it.hosts }.associate { host ->
                hostKey(host, source.tls) to clients.create(host, source.tls, source.guard.user, source.guard.password)
            }
        return SourceGuard(
            config = guard,
            source = source,
            signals = ClickHouseSignalSource(guardClients),
            flags = flags,
            killer = QueryKiller(guardClients, source.reader.user),
            keeperSignals = source.keeper?.let { MntrKeeperSource(it.tls) },
        )
    }
}
