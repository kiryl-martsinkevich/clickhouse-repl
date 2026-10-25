package chrepl.guard

import chrepl.clickhouse.Cancellation
import chrepl.clickhouse.ClickHouseClient
import chrepl.clickhouse.Sql
import chrepl.config.cron.CronExpression
import chrepl.config.model.FailClosedScope
import chrepl.config.model.Guard
import chrepl.config.model.Source
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Operator controls that outlive the process (SPEC §4.6, §4.7); Postgres-backed in M4. */
interface OpsFlags {
    val paused: Boolean
    val failover: Reason?

    fun pause(reason: String)

    fun resume()

    fun setFailover(reason: Reason)

    fun clearFailover()
}

class InMemoryOpsFlags : OpsFlags {
    @Volatile override var paused: Boolean = false
        private set

    @Volatile override var failover: Reason? = null
        private set

    override fun pause(reason: String) {
        paused = true
    }

    override fun resume() {
        paused = false
    }

    override fun setFailover(reason: Reason) {
        if (failover == null) failover = reason
    }

    override fun clearFailover() {
        failover = null
    }
}

class GuardTimeoutException(
    val reasons: List<Reason>,
) : Exception("still waiting for the source: ${reasons.joinToString("; ")}")

/** The key the guard uses for a source host: `host:port` with the protocol's default port. */
fun hostKey(
    host: chrepl.config.model.HostPort,
    tls: Boolean,
): String = "${host.host}:${host.port ?: if (tls) 8443 else 8123}"

/** Admission to read from one host; [cancellation] is triggered on pre-emption. */
class Permit internal constructor(
    val host: String,
    val queryId: String,
    val cancellation: Cancellation,
) {
    @Volatile internal var preemptedAt: Instant? = null

    @Volatile internal var preemptReasons: List<Reason> = emptyList()

    @Volatile internal var killSent = false

    val preempted: Boolean get() = preemptedAt != null
}

/** Snapshot of the guard for the API / portal (SPEC §7.5). */
data class GuardStatus(
    val level: GuardLevel,
    val hosts: Map<String, GuardLevel>,
    val reasons: List<Reason>,
    val activeReads: Int,
    /** Largest DR replica delay in the latest successful samples: how far DR trails the active DC. */
    val replicaDelaySeconds: Long? = null,
    /** Replication signals of each host's latest successful sample (metrics). */
    val hostSignals: Map<String, HostSignals> = emptyMap(),
)

data class HostSignals(
    val replicaDelaySeconds: Long,
    val queueSize: Long,
)

/**
 * The Source Guard (SPEC §4). Polls every DR host, computes a level per host and for the cluster,
 * hands out read permits only while everything is GREEN, and pre-empts running reads as soon as
 * that stops being true. Fails closed: unknown means RED. [tick] does one poll round; [start]
 * runs it every `poll_interval` on a daemon thread.
 */
class SourceGuard(
    private val config: Guard,
    private val source: Source,
    private val signals: SignalSource,
    private val flags: OpsFlags = InMemoryOpsFlags(),
    private val killer: Killer? = null,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val hosts = source.shards.map { hostKey(it.host, source.tls) }
    private val allowedUsers = setOf(source.reader.user, source.guard.user) + source.dcRoleCheck.ignoredUsers
    private val evaluator = Evaluator(config.thresholds)
    private val blackouts = config.blackoutWindows.map { Triple(CronExpression.parse(it.cron), it.duration.value, ZoneId.of(it.timezone)) }
    private val maxSkew = Duration.ofSeconds(30)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val states = hosts.associateWith { HostState(it) }
    private var clusterReasons: List<Reason> = listOf(Reason("STARTING", null, "waiting for the first poll"))
    private val permits = mutableListOf<Permit>()
    private val preemptions = ArrayDeque<Instant>()
    private var breakerOpenUntil: Instant? = null
    private val pool = Executors.newFixedThreadPool(hosts.size.coerceAtLeast(1)) { r -> Thread(r, "guard-poll").apply { isDaemon = true } }

    @Volatile private var running = false
    private var loop: Thread? = null

    private class HostState(
        host: String,
    ) {
        var level = GuardLevel.UNKNOWN
        var reasons: List<Reason> = listOf(Reason("STARTING", host, "waiting for the first poll"))
        var cleanSince: Instant? = null
        var last: HostSample? = null
        var lastOk: Instant? = null
    }

    /** Number of reads pre-empted so far (for metrics and tests). */
    @Volatile var preemptionCount: Int = 0
        private set

    // ------------------------------------------------------------------------------------- polling

    fun start(): SourceGuard {
        running = true
        loop =
            Thread({
                while (running) {
                    val started = System.nanoTime()
                    runCatching { tick() }
                    val sleep = config.pollInterval.value.toMillis() - (System.nanoTime() - started) / 1_000_000
                    if (sleep > 0) runCatching { Thread.sleep(sleep) }
                }
            }, "source-guard").apply {
                isDaemon = true
                start()
            }
        return this
    }

    /** One poll round: sample all hosts in parallel, re-evaluate, pre-empt and wake waiters. */
    fun tick() {
        val timeout =
            config.pollInterval.value
                .toMillis()
                .coerceAtLeast(1_000)
        val futures = hosts.map { h -> h to pool.submit(Callable { signals.sample(h) }) }
        val samples =
            futures.map { (h, f) ->
                runCatching { f.get(timeout, TimeUnit.MILLISECONDS) }
                    .getOrElse { HostSample(h, clock.instant(), error = "poll timed out") }
            }
        lock.withLock {
            val now = clock.instant()
            for (sample in samples) update(states.getValue(sample.host), sample, now)
            ageStale(now)
            clusterReasons = clusterControls(now)
            preemptIfNeeded(now)
            changed.signalAll()
        }
    }

    private fun update(
        state: HostState,
        sample: HostSample,
        now: Instant,
    ) {
        val previous = state.last
        if (sample.ok) {
            state.last = sample
            state.lastOk = now
        }
        val skew = sample.serverTime?.let { Duration.between(it, sample.takenAt).abs() }
        when {
            !sample.ok -> unknown(state, Reason("UNREACHABLE", sample.host, "cannot poll: ${sample.error}"))
            skew != null && skew > maxSkew -> unknown(state, Reason("CLOCK_SKEW", sample.host, "clock differs by ${skew.seconds} s"))
            sample.replicatedTables == 0L ->
                unknown(
                    state,
                    Reason("NO_REPLICAS_VISIBLE", sample.host, "no replicated tables visible; the guard user needs SHOW TABLES ON *.*"),
                )
            else -> {
                detectDrActive(sample, previous)
                val breaches = evaluator.breaches(sample)
                val near = evaluator.breaches(sample, config.releaseRatio)
                when {
                    breaches.isNotEmpty() -> {
                        state.level = GuardLevel.RED
                        state.reasons = breaches
                        state.cleanSince = null
                    }

                    near.isNotEmpty() -> {
                        state.level = GuardLevel.AMBER
                        state.reasons = near.map { it.copy(message = "close to limit: ${it.message}") }
                        state.cleanSince = null
                    }

                    else -> {
                        val since = state.cleanSince ?: now.also { state.cleanSince = it }
                        val stable = Duration.between(since, now) >= config.stableWindow.value
                        state.level = if (stable) GuardLevel.GREEN else GuardLevel.AMBER
                        state.reasons =
                            if (stable) {
                                emptyList()
                            } else {
                                listOf(
                                    Reason(
                                        "STABILISING",
                                        sample.host,
                                        "quiet for ${Duration.between(since, now).seconds} s of ${config.stableWindow}",
                                    ),
                                )
                            }
                    }
                }
            }
        }
    }

    private fun unknown(
        state: HostState,
        reason: Reason,
    ) {
        state.level = GuardLevel.UNKNOWN
        state.reasons = listOf(reason)
        state.cleanSince = null
    }

    private fun ageStale(now: Instant) {
        for ((host, state) in states) {
            val lastOk = state.lastOk
            if (state.level != GuardLevel.UNKNOWN && (lastOk == null || Duration.between(lastOk, now) > config.staleAfter.value)) {
                unknown(state, Reason("STALE", host, "no successful poll for ${config.staleAfter}"))
            }
        }
    }

    /** SPEC §4.6: any foreign query, or any insert, on a DR host means DR may have become active. */
    private fun detectDrActive(
        sample: HostSample,
        previous: HostSample?,
    ) {
        if (!source.dcRoleCheck.foreignActivity) return
        val foreign = sample.queries.firstOrNull { it.user !in allowedUsers }
        if (foreign != null) {
            flags.setFailover(
                Reason(
                    "DR_ACTIVE",
                    sample.host,
                    "query by user '${foreign.user}' running ${foreign.elapsedSeconds.toInt()} s — DR may be active",
                ),
            )
        } else if (previous != null && sample.insertQueries > previous.insertQueries) {
            flags.setFailover(
                Reason("DR_ACTIVE", sample.host, "${sample.insertQueries - previous.insertQueries} insert(s) on DR — DR may be active"),
            )
        }
    }

    /** Controls that apply to the whole cluster, in priority order. */
    private fun clusterControls(now: Instant): List<Reason> {
        flags.failover?.let { return listOf(it) }
        if (flags.paused) return listOf(Reason("PAUSED", null, "replication paused by an operator"))
        val zoned = now.atZone(ZoneId.of("UTC"))
        config.blackoutWindows
            .zip(blackouts)
            .firstOrNull { (_, b) ->
                b.first.withinWindow(zoned.withZoneSameInstant(b.third), b.second)
            }?.let {
                return listOf(Reason("BLACKOUT", null, "blackout window '${it.first.name}'"))
            }
        breakerOpenUntil?.let { until ->
            if (now < until) return listOf(Reason("CIRCUIT_OPEN", null, "too many pre-emptions; paused until $until"))
            breakerOpenUntil = null
        }
        return emptyList()
    }

    // ------------------------------------------------------------------------------------- levels

    private fun clusterLevel(): GuardLevel =
        when {
            flags.failover != null -> GuardLevel.DR_ACTIVE
            clusterReasons.isNotEmpty() -> GuardLevel.RED
            else ->
                states.values.map { it.level }.let { levels ->
                    when {
                        levels.any { it == GuardLevel.UNKNOWN } -> GuardLevel.UNKNOWN
                        levels.any { it == GuardLevel.RED } -> GuardLevel.RED
                        levels.any { it == GuardLevel.AMBER } -> GuardLevel.AMBER
                        else -> GuardLevel.GREEN
                    }
                }
        }

    /** Reasons a read on [host] may not run now; empty means admitted (tokens aside). */
    private fun blockers(host: String): List<Reason> {
        if (clusterReasons.isNotEmpty()) return clusterReasons
        val relevant = if (source.failClosedScope == FailClosedScope.CLUSTER) states.keys else setOf(host)
        return relevant.mapNotNull { states[it] }.filter { it.level != GuardLevel.GREEN }.flatMap { it.reasons }
    }

    private fun tokenBlocker(host: String): Reason? {
        if (permits.size >=
            config.concurrency.global
        ) {
            return Reason("CONCURRENCY", null, "${permits.size} reads running (global limit ${config.concurrency.global})")
        }
        if (permits.count { it.host == host } >= config.concurrency.perHost) {
            return Reason("CONCURRENCY", host, "read already running on this host (limit ${config.concurrency.perHost})")
        }
        return null
    }

    // ------------------------------------------------------------------------------------- permits

    /** Blocks until a read on [host] is admitted (SPEC §4.4) or [maxWait] passes. */
    fun acquire(
        host: String,
        queryId: String,
        maxWait: Duration = Duration.ofDays(1),
        onWait: (List<Reason>) -> Unit = {},
    ): Permit {
        require(host in states) { "unknown source host $host" }
        val deadline = System.nanoTime() + maxWait.toNanos()
        lock.withLock {
            var reported: List<Reason>? = null
            while (true) {
                val blocked = blockers(host).ifEmpty { listOfNotNull(tokenBlocker(host)) }
                if (blocked.isEmpty()) {
                    val permit = Permit(host, queryId, Cancellation())
                    permits += permit
                    return permit
                }
                if (blocked != reported) {
                    onWait(blocked)
                    reported = blocked
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw GuardTimeoutException(blocked)
                changed.awaitNanos(remaining.coerceAtMost(TimeUnit.SECONDS.toNanos(1)))
            }
        }
    }

    /** Called with the time from pre-emption to the read's end (the latency SPEC §1.3 budgets). */
    @Volatile var onPreemptionEnded: (Duration) -> Unit = {}

    fun release(permit: Permit) {
        lock.withLock {
            permits -= permit
            changed.signalAll()
        }
        permit.preemptedAt?.let { at ->
            val latency = Duration.between(at, Instant.now())
            log.info("read {} on {} ended {} ms after pre-emption", permit.queryId, permit.host, latency.toMillis())
            runCatching { onPreemptionEnded(latency) }
        }
    }

    /** Cancels running reads on hosts that are no longer admissible (SPEC §4.5). */
    private fun preemptIfNeeded(now: Instant) {
        for (permit in permits.toList()) {
            if (permit.preemptedAt == null) {
                val blocked = blockers(permit.host)
                if (blocked.isEmpty()) continue
                permit.preemptedAt = now
                permit.preemptReasons = blocked
                permit.cancellation.cancel()
                preemptionCount++
                preemptions.addLast(now)
                tripBreaker(now)
            } else if (!permit.killSent && Duration.between(permit.preemptedAt, now) >= config.preemption.killGrace.value) {
                // Backstop: the query should have ended when its connection closed.
                val stillRunning =
                    states[permit.host]?.last?.queries?.any { it.queryId == permit.queryId && it.user == source.reader.user } ?: false
                if (stillRunning) killer?.kill(permit.host, permit.queryId)
                permit.killSent = true
            }
        }
    }

    private fun tripBreaker(now: Instant) {
        val window = config.circuitBreaker.within.value
        while (preemptions.isNotEmpty() && Duration.between(preemptions.first(), now) > window) preemptions.removeFirst()
        if (preemptions.size >= config.circuitBreaker.maxPreemptions) {
            breakerOpenUntil = now.plus(config.circuitBreaker.openFor.value)
            preemptions.clear()
        }
    }

    fun status(): GuardStatus =
        lock.withLock {
            val level = clusterLevel()
            val reasons = clusterReasons.ifEmpty { states.values.flatMap { it.reasons } }
            val delay = states.values.mapNotNull { s -> s.last?.takeIf { it.ok }?.replicaDelaySeconds }.maxOrNull()
            val signals =
                states
                    .mapNotNull { (host, s) ->
                        s.last?.takeIf { it.ok }?.let { host to HostSignals(it.replicaDelaySeconds, it.queueSize) }
                    }.toMap()
            GuardStatus(level, states.mapValues { it.value.level }, reasons, permits.size, delay, signals)
        }

    override fun close() {
        running = false
        loop?.interrupt()
        pool.shutdownNow()
    }
}

private val log = org.slf4j.LoggerFactory.getLogger(SourceGuard::class.java)

/** The pre-emption backstop (SPEC §4.5). */
fun interface Killer {
    fun kill(
        host: String,
        queryId: String,
    )
}

/**
 * Kills a replication query on a DR host as the guard user. The statement always filters on the
 * reader user and the exact query id, so it can never touch another user's query.
 */
class QueryKiller(
    private val guardClients: Map<String, ClickHouseClient>,
    private val readerUser: String,
) : Killer {
    override fun kill(
        host: String,
        queryId: String,
    ) {
        require(queryId.startsWith("chrepl-")) { "refusing to kill a query that is not ours" }
        guardClients[host]?.let { client ->
            runCatching { client.execute(statement(queryId)) }
        }
    }

    fun statement(queryId: String): String = "KILL QUERY WHERE user = ${Sql.string(readerUser)} AND query_id = ${Sql.string(queryId)} ASYNC"
}
