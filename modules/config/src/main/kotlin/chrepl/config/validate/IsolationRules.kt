package chrepl.config.validate

import chrepl.config.model.HostPort
import chrepl.config.net.Cidr
import chrepl.config.net.IpLiterals
import java.net.InetAddress

/**
 * The active DC must be unreachable for the tool (SPEC §1.3, §9.3): no source or target host may
 * be, or resolve into, the deny-list, and no target may point at a source host.
 */
internal fun RuleContext.checkIsolation() {
    val activeDc = config.activeDc
    if (activeDc.hosts.isEmpty() && activeDc.cidrs.isEmpty()) {
        error(Code.ACTIVE_DC_EMPTY, "active_dc", "list the active DC's hosts and/or CIDRs so the tool can refuse to contact them")
    }
    val cidrs =
        activeDc.cidrs.mapIndexedNotNull { i, text ->
            runCatching { Cidr.parse(text) }
                .onFailure { error(Code.INVALID_CIDR, "active_dc.cidrs[$i]", it.message ?: "invalid CIDR") }
                .getOrNull()
        }
    val deniedHosts = activeDc.hosts.map { it.lowercase().removeSuffix(".").substringBefore(':') }.toSet()

    val sourceEndpoints =
        config.sources.flatMap { (name, source) ->
            source.shards.mapIndexed { i, shard -> Endpoint("sources.$name.shards[$i].host", shard.host) }
        }
    val targetEndpoints =
        config.targets.flatMap { (name, target) ->
            target.hosts.mapIndexed { i, host -> Endpoint("targets.$name.hosts[$i]", host) }
        }
    val all = sourceEndpoints + targetEndpoints

    for (endpoint in all) {
        val host = endpoint.host.normalizedHost
        if (host in deniedHosts) {
            error(Code.ACTIVE_DC_HOST, endpoint.path, "host '$host' is in the active-DC deny-list (${activeDc.name})")
        }
        val literal = IpLiterals.parse(endpoint.host.host)
        val addresses =
            when {
                literal != null -> {
                    listOf(literal)
                }

                options.resolveDns -> {
                    options.hostResolver.resolve(endpoint.host.host).also {
                        if (it.isEmpty()) error(Code.DNS_UNRESOLVED, endpoint.path, "host '$host' does not resolve")
                    }
                }

                else -> {
                    emptyList()
                }
            }
        endpoint.addresses = addresses
        for (address in addresses) {
            val cidr = cidrs.firstOrNull { address in it } ?: continue
            error(Code.ACTIVE_DC_CIDR, endpoint.path, "host '$host' (${address.hostAddress}) is inside active-DC network $cidr")
        }
    }

    for (group in listOf(sourceEndpoints, targetEndpoints)) {
        val seen = mutableMapOf<HostPort, String>()
        for (endpoint in group) {
            val previous = seen.putIfAbsent(endpoint.host, endpoint.path) ?: continue
            error(Code.DUPLICATE_HOST, endpoint.path, "host '${endpoint.host}' is also used at $previous")
        }
    }

    val sourceHosts = sourceEndpoints.associateBy { it.host.normalizedHost }
    val sourceAddresses = sourceEndpoints.flatMap { e -> e.addresses.map { it to e } }.toMap()
    for (target in targetEndpoints) {
        val clash =
            sourceHosts[target.host.normalizedHost]
                ?: target.addresses.firstNotNullOfOrNull { sourceAddresses[it] }
                ?: continue
        error(
            Code.TARGET_IS_SOURCE,
            target.path,
            "target host '${target.host}' is the source host at ${clash.path}; targets must never be source nodes",
        )
    }
}

private class Endpoint(
    val path: String,
    val host: HostPort,
) {
    var addresses: List<InetAddress> = emptyList()
}
