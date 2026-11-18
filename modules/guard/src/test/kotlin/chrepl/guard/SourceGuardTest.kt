package chrepl.guard

import chrepl.config.load.ConfigLoader
import chrepl.config.model.BlackoutWindow
import chrepl.config.model.ConfigDuration
import chrepl.config.model.FailClosedScope
import chrepl.config.model.Guard
import chrepl.config.model.Source
import chrepl.config.model.SourceShard
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Deterministic tests of the guard's state machine (SPEC §4.4–§4.7) with fake signals and a manual clock. */
class SourceGuardTest {
    private class ManualClock(
        var now: Instant = Instant.parse("2026-10-22T10:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now

        fun advance(seconds: Long) {
            now = now.plusSeconds(seconds)
        }
    }

    private val example = ConfigLoader.load(Path.of(System.getProperty("chrepl.exampleConfig"))).config!!
    private val clock = ManualClock()

    /** The example's DR source (1 shard × 3 replicas) with cluster scope and no Keeper watch, unless a test says otherwise. */
    private val dr = example.sources.getValue("dr").copy(failClosedScope = FailClosedScope.CLUSTER, keeper = null)
    private val hosts = dr.shards.flatMap { it.hosts }.map { hostKey(it, true) }

    /** Current sample per host; tests change fields to simulate DR conditions. */
    private val current = hosts.associateWith { HostSample(it, clock.now, serverTime = clock.now) }.toMutableMap()
    private val signals =
        SignalSource { h ->
            current.getValue(h).copy(takenAt = clock.now, serverTime = current.getValue(h).serverTime?.let { clock.now })
        }
    private val kills = mutableListOf<Pair<String, String>>()
    private val flags = InMemoryOpsFlags()

    private fun guard(
        config: Guard = example.guard.copy(blackoutWindows = emptyList()),
        source: Source = dr,
        keepers: KeeperSource? = null,
    ) = SourceGuard(config, source, signals, flags, { h, q -> kills += h to q }, clock, keepers)

    private fun set(
        host: String = hosts[0],
        change: HostSample.() -> HostSample,
    ) {
        current[host] = current.getValue(host).change()
    }

    /** Ticks every 5 s for [seconds]. */
    private fun SourceGuard.run(seconds: Long) {
        var t = 0L
        while (t <= seconds) {
            tick()
            clock.advance(5)
            t += 5
        }
    }

    private fun SourceGuard.tryAcquire(host: String = hosts[0]) = acquire(host, "chrepl-j-c-1", Duration.ofMillis(10))

    @Test
    fun `nothing is admitted before a stable window of clean polls`() {
        val g = guard()
        assertFailsWith<GuardTimeoutException> { g.tryAcquire() }.also { assertEquals("STARTING", it.reasons.first().code) }
        g.run(30)
        assertEquals(GuardLevel.AMBER, g.status().level)
        assertFailsWith<GuardTimeoutException> { g.tryAcquire() }.also { assertEquals("STABILISING", it.reasons.first().code) }
        g.run(35)
        assertEquals(GuardLevel.GREEN, g.status().level)
        g.release(g.tryAcquire())
    }

    @Test
    fun `replication lag blocks reads with a clear reason and pre-empts running ones`() {
        val g = guard()
        g.run(65)
        val permit = g.tryAcquire()
        set { copy(replicaDelaySeconds = 74) }
        g.tick()
        assertTrue(permit.cancellation.isCancelled)
        assertEquals(1, g.preemptionCount)
        g.release(permit)
        val blocked = assertFailsWith<GuardTimeoutException> { g.tryAcquire() }
        assertContains(blocked.message!!, "replication delay 74 s (threshold 30 s)")
        assertEquals(GuardLevel.RED, g.status().level)
    }

    @Test
    fun `hysteresis keeps a host out of GREEN until signals drop below the release ratio`() {
        val g = guard()
        g.run(65)
        set { copy(queueSize = 45) } // threshold 50, release at 40
        g.run(120)
        assertEquals(GuardLevel.AMBER, g.status().level)
        set { copy(queueSize = 10) }
        g.run(30)
        assertEquals(GuardLevel.AMBER, g.status().level)
        g.run(35)
        assertEquals(GuardLevel.GREEN, g.status().level)
    }

    @Test
    fun `an unreachable or stale host fails closed`() {
        val g = guard()
        g.run(65)
        val permit = g.tryAcquire(hosts[1])
        set(hosts[2]) { copy(error = "connection refused") }
        g.tick()
        assertEquals(GuardLevel.UNKNOWN, g.status().level)
        assertTrue(permit.cancellation.isCancelled, "cluster scope: a read on another host is pre-empted too")
        g.release(permit)
    }

    @Test
    fun `with shard scope only the affected shard is blocked`() {
        val threeShards =
            dr.copy(
                shards =
                    dr.shards
                        .single()
                        .hosts
                        .mapIndexed { i, h -> SourceShard("s${i + 1}", host = h) },
            )
        val g = guard(source = threeShards.copy(failClosedScope = FailClosedScope.SHARD))
        g.run(65)
        set(hosts[2]) { copy(error = "connection refused") }
        g.tick()
        g.release(g.tryAcquire(hosts[0]))
        assertFailsWith<GuardTimeoutException> { g.tryAcquire(hosts[2]) }
    }

    @Test
    fun `a guard that cannot see any replicated table fails closed`() {
        val g = guard()
        set { copy(replicatedTables = 0) }
        g.run(65)
        assertEquals(GuardLevel.UNKNOWN, g.status().level)
        assertEquals("NO_REPLICAS_VISIBLE", assertFailsWith<GuardTimeoutException> { g.tryAcquire() }.reasons.first().code)
    }

    @Test
    fun `clock skew makes a host unknown`() {
        val g = guard()
        g.run(65)
        current[hosts[0]] = current.getValue(hosts[0]).copy(serverTime = Instant.EPOCH)
        val skewed = SignalSource { h -> if (h == hosts[0]) current.getValue(h).copy(takenAt = clock.now) else signals.sample(h) }
        val g2 = SourceGuard(example.guard.copy(blackoutWindows = emptyList()), dr, skewed, flags, null, clock)
        g2.tick()
        assertEquals(GuardLevel.UNKNOWN, g2.status().hosts.getValue(hosts[0]))
        assertTrue(g.status().level != GuardLevel.UNKNOWN)
    }

    @Test
    fun `a foreign query on DR means DR_ACTIVE until an operator clears it`() {
        val g = guard()
        g.run(65)
        val permit = g.tryAcquire()
        set { copy(queries = listOf(RunningQuery("trading_app", "q1", 2.0, 0, 0))) }
        g.tick()
        assertEquals(GuardLevel.DR_ACTIVE, g.status().level)
        assertTrue(permit.cancellation.isCancelled)
        g.release(permit)
        set { copy(queries = emptyList()) }
        g.run(300)
        assertEquals(GuardLevel.DR_ACTIVE, g.status().level, "never clears itself")
        flags.clearFailover()
        g.run(65)
        assertEquals(GuardLevel.GREEN, g.status().level)
    }

    @Test
    fun `monitoring users and the tool's own users are not foreign`() {
        val g = guard()
        set {
            copy(
                queries =
                    listOf(
                        RunningQuery("monitoring", "m", 1.0, 0, 0),
                        RunningQuery("repl_guard", "g", 0.0, 0, 0),
                        RunningQuery("repl_reader", "chrepl-x", 3.0, 0, 0),
                    ),
            )
        }
        g.run(65)
        assertEquals(GuardLevel.GREEN, g.status().level)
    }

    @Test
    fun `any insert on DR means DR_ACTIVE`() {
        val g = guard()
        g.run(65)
        set { copy(insertQueries = insertQueries + 1) }
        g.tick()
        assertEquals(GuardLevel.DR_ACTIVE, g.status().level)
        assertContains(flags.failover!!.message, "insert")
    }

    @Test
    fun `pause, failover flag and blackout windows block admission`() {
        val g = guard()
        g.run(65)
        flags.pause("maintenance")
        g.tick()
        assertEquals("PAUSED", assertFailsWith<GuardTimeoutException> { g.tryAcquire() }.reasons.single().code)
        flags.resume()
        flags.setFailover(Reason("DR_ACTIVE", null, "failover started by ops"))
        g.tick()
        assertEquals(GuardLevel.DR_ACTIVE, g.status().level)
        flags.clearFailover()
        val always = BlackoutWindow("drill", "* * * * *", ConfigDuration.parse("2h"), "Europe/London")
        val withBlackout = guard(example.guard.copy(blackoutWindows = listOf(always)))
        withBlackout.run(65)
        assertEquals("BLACKOUT", assertFailsWith<GuardTimeoutException> { withBlackout.tryAcquire() }.reasons.single().code)
    }

    @Test
    fun `repeated pre-emptions open the circuit breaker`() {
        val g = guard()
        repeat(5) {
            set { copy(replicaDelaySeconds = 0) }
            g.run(65)
            val permit = g.tryAcquire()
            set { copy(replicaDelaySeconds = 100) }
            g.tick()
            g.release(permit)
        }
        set { copy(replicaDelaySeconds = 0) }
        g.run(120)
        assertEquals("CIRCUIT_OPEN", assertFailsWith<GuardTimeoutException> { g.tryAcquire() }.reasons.single().code)
        clock.advance(3600)
        g.run(65)
        g.release(g.tryAcquire())
    }

    @Test
    fun `concurrency is limited per host and globally`() {
        val g = guard()
        g.run(65)
        val a = g.tryAcquire(hosts[0])
        assertEquals("CONCURRENCY", assertFailsWith<GuardTimeoutException> { g.tryAcquire(hosts[0]) }.reasons.single().code)
        val b = g.tryAcquire(hosts[1])
        assertFailsWith<GuardTimeoutException> { g.tryAcquire(hosts[2]) } // global limit 2
        g.release(a)
        g.release(g.tryAcquire(hosts[2]))
        g.release(b)
    }

    @Test
    fun `the kill backstop fires only for our query still running after the grace period`() {
        val g = guard()
        g.run(65)
        val permit = g.acquire(hosts[0], "chrepl-j1-c1-1", Duration.ofMillis(10))
        set { copy(replicaDelaySeconds = 100, queries = listOf(RunningQuery("repl_reader", "chrepl-j1-c1-1", 9.0, 0, 0))) }
        g.tick()
        assertTrue(kills.isEmpty(), "first the connection is closed")
        clock.advance(6)
        g.tick()
        assertEquals(listOf(hosts[0] to "chrepl-j1-c1-1"), kills)
        g.release(permit)
    }

    @Test
    fun `the killer statement is pinned to the reader and the query id`() {
        val killer = QueryKiller(emptyMap(), "repl_reader")
        assertEquals("KILL QUERY WHERE user = 'repl_reader' AND query_id = 'chrepl-j1-c1-1' ASYNC", killer.statement("chrepl-j1-c1-1"))
        assertFailsWith<IllegalArgumentException> { killer.kill("h", "someone-elses-query") }
        assertFalse("'" in killer.statement("chrepl-x").substringAfter("query_id = '").removeSuffix("' ASYNC"))
    }

    @Test
    fun `with replica scope a lagging replica is skipped and reads go to the others`() {
        val g = guard(source = dr.copy(failClosedScope = FailClosedScope.REPLICA))
        g.run(65)
        set(hosts[0]) { copy(replicaDelaySeconds = 120) }
        g.tick()
        assertEquals(GuardLevel.GREEN, g.status().level, "the shard still has a GREEN replica")
        assertEquals(GuardLevel.RED, g.status().hosts.getValue(hosts[0]))
        assertFalse(g.admissible(hosts[0]))
        assertEquals(hosts[1], g.preferred(hosts).first())
        val a = g.acquireAny(hosts, "chrepl-j-c-1", Duration.ofMillis(10))
        assertEquals(hosts[1], a.host)
        val b = g.acquireAny(hosts, "chrepl-j-c-2", Duration.ofMillis(10))
        assertEquals(hosts[2], b.host, "per_host 1: the next read goes to the other GREEN replica")
        set(hosts[1]) { copy(replicaDelaySeconds = 120) }
        g.tick()
        assertTrue(a.cancellation.isCancelled, "only reads on the replica that turned RED are pre-empted")
        assertFalse(b.cancellation.isCancelled)
        g.release(a)
        g.release(b)
        set(hosts[2]) { copy(error = "connection refused") }
        g.tick()
        assertEquals(GuardLevel.RED, g.status().level, "no replica left: the shard is as good as its best replica")
        assertFailsWith<GuardTimeoutException> { g.acquireAny(hosts, "chrepl-j-c-3", Duration.ofMillis(10)) }
    }

    private val keeperHosts =
        example.sources
            .getValue("dr")
            .keeper!!
            .hosts
            .map { keeperKey(it, false) }
    private val keeperNow = keeperHosts.associateWith { KeeperSample(it, clock.now, state = "follower", avgLatencyMs = 1.0) }.toMutableMap()
    private val keepers = KeeperSource { h -> keeperNow.getValue(h).copy(takenAt = clock.now) }

    @Test
    fun `a slow DR Keeper blocks every read until it is quiet again`() {
        val g =
            guard(
                source = dr.copy(failClosedScope = FailClosedScope.REPLICA, keeper = example.sources.getValue("dr").keeper),
                keepers = keepers,
            )
        g.run(65)
        assertEquals(GuardLevel.GREEN, g.status().level)
        val permit = g.tryAcquire()
        keeperNow[keeperHosts[2]] = keeperNow.getValue(keeperHosts[2]).copy(avgLatencyMs = 80.0)
        g.tick()
        assertTrue(permit.cancellation.isCancelled, "Keeper signals apply to the whole cluster, whatever the scope")
        g.release(permit)
        assertEquals("KEEPER_LATENCY", assertFailsWith<GuardTimeoutException> { g.tryAcquire(hosts[1]) }.reasons.single().code)
        assertEquals(
            80.0,
            g
                .status()
                .keepers
                .getValue(keeperHosts[2])
                .avgLatencyMs,
        )
        keeperNow[keeperHosts[2]] = keeperNow.getValue(keeperHosts[2]).copy(avgLatencyMs = 1.0, outstandingRequests = 500)
        g.tick()
        assertEquals("KEEPER_OUTSTANDING", assertFailsWith<GuardTimeoutException> { g.tryAcquire() }.reasons.single().code)
        keeperNow[keeperHosts[2]] = keeperNow.getValue(keeperHosts[2]).copy(outstandingRequests = 0)
        g.run(30)
        assertEquals("STABILISING", assertFailsWith<GuardTimeoutException> { g.tryAcquire() }.reasons.single().code)
        g.run(35)
        g.release(g.tryAcquire())
    }

    @Test
    fun `an unreachable DR Keeper fails closed`() {
        val g = guard(source = dr.copy(keeper = example.sources.getValue("dr").keeper), keepers = keepers)
        g.run(65)
        keeperNow[keeperHosts[0]] = KeeperSample(keeperHosts[0], clock.now, error = "connection refused")
        g.tick()
        assertEquals("KEEPER_UNREACHABLE", assertFailsWith<GuardTimeoutException> { g.tryAcquire() }.reasons.single().code)
    }

    @Test
    fun `a DR Keeper leading the ensemble means DR_ACTIVE`() {
        val g = guard(source = dr.copy(keeper = example.sources.getValue("dr").keeper), keepers = keepers)
        g.run(65)
        keeperNow[keeperHosts[1]] = keeperNow.getValue(keeperHosts[1]).copy(state = "leader")
        g.tick()
        assertEquals(GuardLevel.DR_ACTIVE, g.status().level)
        assertEquals(keeperHosts[1], flags.failover!!.host)
        assertContains(flags.failover!!.message, "Raft leader")
    }

    @Test
    fun `mntr output is parsed`() {
        val text = "zk_version\tv25.8.1\nzk_avg_latency\t3\nzk_outstanding_requests\t7\nzk_server_state\tfollower\n"
        val values = MntrKeeperSource.parse(text)
        assertEquals("follower", values["zk_server_state"])
        assertEquals("3", values["zk_avg_latency"])
        assertEquals("7", values["zk_outstanding_requests"])
    }
}
