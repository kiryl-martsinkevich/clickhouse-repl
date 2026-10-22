package chrepl.it

import chrepl.clickhouse.ClickHouseClient
import chrepl.clickhouse.ClickHouseException
import chrepl.clickhouse.Endpoint
import chrepl.config.model.ReplicatorConfig
import chrepl.config.model.SecretRef
import chrepl.config.validate.SecretReader
import chrepl.guard.GuardFactory
import chrepl.guard.GuardGate
import chrepl.guard.GuardLevel
import chrepl.guard.InMemoryOpsFlags
import chrepl.guard.QueryKiller
import chrepl.guard.Reason
import chrepl.guard.SourceGuard
import chrepl.guard.hostKey
import chrepl.masking.MaskingEngine
import chrepl.masking.keys.StaticKeyProvider
import chrepl.transfer.Chunk
import chrepl.transfer.ClientFactory
import chrepl.transfer.CopyJob
import chrepl.transfer.JobListener
import chrepl.transfer.TableResult
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PLAN M3 exit criteria: the Source Guard against a real prod → DR replicated pair. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GuardIT {
    private lateinit var topology: ReplicatedTopology
    private lateinit var config: ReplicatorConfig
    private val secrets =
        SecretReader { ref ->
            when {
                ref.kind == SecretRef.Kind.ENV && "MASK" in ref.target -> ItFixtures.maskingKey
                "reader" in ref.target -> ReplicatedTopology.READER_PASSWORD
                "guard" in ref.target -> ReplicatedTopology.GUARD_PASSWORD
                else -> ""
            }
        }
    private val clients = ClientFactory(secrets)

    @BeforeAll
    fun start() {
        topology = ReplicatedTopology()
        ItFixtures.resetTargets()
        config =
            ItFixtures.config {
                ((get("active_dc") as ObjectNode).get("hosts") as ArrayNode).add("127.0.0.42") // prod
                val dr = child("sources").child("dr")
                dr.put("dc", "dr")
                dr
                    .putArray("shards")
                    .addObject()
                    .put("name", "s1")
                    .put("host", topology.dr.hostPort)
                dr
                    .child("dc_role_check")
                    .putArray("ignored_users")
                    .add("monitoring")
                    .add("default") // default = the test's DBA
                val qa1 = child("targets").child("qa1")
                qa1.putArray("hosts").add(ItCluster.qa[0].hostPort)
                qa1.put("shards", 1).put("shard_mapping", "single")
                val guard = child("guard")
                guard.put("poll_interval", "1s").put("stale_after", "3s").put("stable_window", "2s")
                guard
                    .child("thresholds")
                    .child("replication")
                    .put("max_queue_size", 3)
                    .put("max_queue_oldest_age", "20s")
                // Host-wide CPU/IO counters include the build machine; these tests are about replication and DR activity.
                guard
                    .child("thresholds")
                    .child("host")
                    .put("max_cpu_utilisation", 1.0)
                    .put("max_io_wait", 1.0)
                guard.putArray("blackout_windows")
                guard.child("concurrency").put("global", 1)
            }
    }

    @AfterAll
    fun stop() = topology.close()

    private val drKey get() =
        hostKey(
            config.sources
                .getValue("dr")
                .shards
                .single()
                .host,
            false,
        )

    private fun guard(flags: InMemoryOpsFlags = InMemoryOpsFlags()): SourceGuard =
        GuardFactory.create(config.guard, config.sources.getValue("dr"), clients, flags).start()

    private fun engine(): MaskingEngine =
        MaskingEngine.create(config.masking, StaticKeyProvider(config.masking, secrets), ItFixtures.examplePath.parent)

    private fun awaitLevel(
        guard: SourceGuard,
        level: GuardLevel,
        within: Duration = Duration.ofSeconds(30),
    ) {
        val deadline = System.nanoTime() + within.toNanos()
        while (guard.status().level != level) {
            if (System.nanoTime() > deadline) throw AssertionError("guard did not reach $level: ${guard.status()}")
            Thread.sleep(100)
        }
    }

    private class Events : JobListener {
        val preempted = CopyOnWriteArrayList<Long>()
        val done = CopyOnWriteArrayList<Long>()

        override fun chunkPreempted(
            chunk: Chunk,
            attempt: Int,
            reason: String,
        ) {
            preempted += System.nanoTime()
        }

        override fun chunkDone(
            chunk: Chunk,
            rows: Long,
        ) {
            done += rows
        }
    }

    private fun copyAsync(
        guard: SourceGuard,
        date: LocalDate,
        events: Events,
        waits: MutableList<List<Reason>> = CopyOnWriteArrayList(),
    ): CompletableFuture<TableResult> =
        CompletableFuture.supplyAsync {
            engine().use { engine ->
                CopyJob(
                    config,
                    "qa1",
                    engine,
                    GuardGate(guard, Duration.ofMinutes(3)) { _, r -> waits += r },
                    clients,
                    retryBackoff = { Duration.ofMillis(200) },
                    listener = events,
                ).copy("trading.trades", listOf(date))
            }
        }

    private fun insertOnProd(
        date: String,
        rows: Int,
        offset: Long,
    ) {
        ItFixtures.insertTrades(topology.prod, 0, date, 1, rows, idOffset = offset)
        topology.awaitReplicated()
    }

    private var lagRound = 0

    /**
     * Stops fetches on DR and inserts small blocks on prod, so DR's replication queue grows. Every
     * block is unique: identical blocks would be dropped by ReplicatedMergeTree insert deduplication.
     */
    private fun makeDrLag(blocks: Int = 6) {
        topology.dr.sql("SYSTEM STOP FETCHES trading.trades")
        val round = ++lagRound
        repeat(blocks) { i ->
            ItFixtures.insertTrades(
                topology.prod,
                0,
                "2027-01-01",
                1,
                10,
                idOffset =
                    900_000_000L + round * 10_000L + i * 100L,
            )
        }
    }

    private fun endDrLag() {
        topology.dr.sql("SYSTEM START FETCHES trading.trades")
        topology.awaitReplicated()
    }

    private fun targetCount(date: LocalDate) =
        ItCluster.qa[0].scalar("SELECT count() FROM trading.trades WHERE trade_date = '$date'").toLong()

    @Test
    @Order(1)
    fun `the DR user profile enforces the L1 limits`() {
        insertOnProd("2026-10-04", 1_000, 50_000_000) // the partition-pruning check needs parts to prune
        val reader = ClickHouseClient(Endpoint("127.0.0.41", topology.dr.port, false), "repl_reader", ReplicatedTopology.READER_PASSWORD)
        assertEquals("replication_reads", reader.queryScalar("SELECT getSetting('workload')"))
        assertEquals("2", reader.queryScalar("SELECT getSetting('max_threads')"))
        assertFailsWith<ClickHouseException> { reader.queryScalar("SELECT 1", mapOf("max_threads" to "8")) }
        assertFailsWith<ClickHouseException> { reader.queryScalar("SELECT 1", mapOf("workload" to "default")) }
        assertFailsWith<ClickHouseException> { reader.execute("INSERT INTO trading.trades (trade_id) VALUES (1)") }
        val unpruned =
            assertFailsWith<ClickHouseException> { reader.queryScalar("SELECT count() FROM trading.trades WHERE customer_id = 1") }
        assertContains(unpruned.message!!, "INDEX_NOT_USED")
        assertEquals("background", topology.dr.scalar("SELECT value FROM system.server_settings WHERE name = 'merge_workload'"))
        assertEquals("1", topology.dr.scalar("SELECT count() FROM system.workloads WHERE name = 'replication_reads'"))
    }

    @Test
    @Order(2)
    fun `replication backlog on DR holds a copy until DR catches up`() {
        val date = LocalDate.of(2026, 10, 5)
        insertOnProd("$date", 5_000, 0)
        guard().use { guard ->
            awaitLevel(guard, GuardLevel.GREEN)
            val waits = CopyOnWriteArrayList<List<Reason>>()
            val copy =
                try {
                    makeDrLag()
                    awaitLevel(guard, GuardLevel.RED)
                    copyAsync(guard, date, Events(), waits).also {
                        Thread.sleep(3_000)
                        assertFalse(it.isDone, "the copy must wait while DR is behind")
                        assertTrue(waits.flatten().any { r -> "replication queue" in r.message }, "wait reasons: $waits")
                    }
                } finally {
                    endDrLag()
                }
            copy.get(2, TimeUnit.MINUTES)
            assertEquals(5_000L, targetCount(date))
        }
    }

    @Test
    @Order(3)
    fun `backlog during a chunk pre-empts the read within a poll plus grace and the copy completes`() {
        val date = LocalDate.of(2026, 10, 6)
        insertOnProd("$date", 400_000, 10_000_000)
        guard().use { guard ->
            awaitLevel(guard, GuardLevel.GREEN)
            val events = Events()
            val copy = copyAsync(guard, date, events)
            while (guard.status().activeReads == 0) Thread.sleep(50)
            Thread.sleep(500)
            val breachAt = System.nanoTime()
            try {
                makeDrLag()
                while (events.preempted.isEmpty()) {
                    assertFalse(copy.isDone, "the copy finished before the backlog built up; slow the read down further")
                    assertTrue(System.nanoTime() - breachAt < TimeUnit.SECONDS.toNanos(30), "no pre-emption: ${guard.status()}")
                    Thread.sleep(50)
                }
            } finally {
                endDrLag()
            }
            val latency = Duration.ofNanos(events.preempted.first() - breachAt)
            assertTrue(latency <= Duration.ofSeconds(1 + 5), "pre-emption took $latency")
            assertTrue(guard.preemptionCount >= 1)
            copy.get(3, TimeUnit.MINUTES)
            assertEquals(400_000L, targetCount(date))
            assertEquals("400000", ItCluster.qa[0].scalar("SELECT uniqExact(trade_id) FROM trading.trades WHERE trade_date = '$date'"))
        }
    }

    @Test
    @Order(4)
    fun `a foreign query on DR is DR_ACTIVE - reads stop until an operator clears it`() {
        val date = LocalDate.of(2026, 10, 7)
        insertOnProd("$date", 100_000, 20_000_000)
        val flags = InMemoryOpsFlags()
        guard(flags).use { guard ->
            awaitLevel(guard, GuardLevel.GREEN)
            val events = Events()
            val copy = copyAsync(guard, date, events)
            while (guard.status().activeReads == 0) Thread.sleep(50)
            val app = ClickHouseClient(Endpoint("127.0.0.41", topology.dr.port, false), "trading_app", "")
            CompletableFuture.runAsync { runCatching { app.queryScalar("SELECT sleep(3)") } }
            awaitLevel(guard, GuardLevel.DR_ACTIVE, Duration.ofSeconds(10))
            assertContains(flags.failover!!.message, "trading_app")
            Thread.sleep(6_000)
            assertEquals(GuardLevel.DR_ACTIVE, guard.status().level, "DR_ACTIVE never clears itself")
            assertFalse(copy.isDone)
            assertTrue(events.preempted.isNotEmpty())
            flags.clearFailover()
            copy.get(3, TimeUnit.MINUTES)
            assertEquals(100_000L, targetCount(date))
        }
    }

    @Test
    @Order(5)
    fun `an insert on DR is DR_ACTIVE`() {
        val flags = InMemoryOpsFlags()
        guard(flags).use { guard ->
            awaitLevel(guard, GuardLevel.GREEN)
            topology.dr.sql("CREATE TABLE IF NOT EXISTS default.scratch (x UInt8) ENGINE = Memory")
            topology.dr.sql("INSERT INTO default.scratch VALUES (1)")
            awaitLevel(guard, GuardLevel.DR_ACTIVE, Duration.ofSeconds(10))
            assertContains(flags.failover!!.message, "insert")
        }
    }

    @Test
    @Order(6)
    fun `an unreachable DR host fails closed`() {
        guard().use { guard ->
            awaitLevel(guard, GuardLevel.GREEN)
            Docker.run("pause", topology.dr.containerId)
            try {
                awaitLevel(guard, GuardLevel.UNKNOWN, Duration.ofSeconds(15))
                assertFailsWith<chrepl.guard.GuardTimeoutException> { guard.acquire(drKey, "chrepl-x-y-1", Duration.ofSeconds(1)) }
            } finally {
                Docker.run("unpause", topology.dr.containerId)
            }
            awaitLevel(guard, GuardLevel.GREEN)
        }
    }

    @Test
    @Order(7)
    fun `the guard kills our own queries and never anyone else's`() {
        val guardClient = ClickHouseClient(Endpoint("127.0.0.41", topology.dr.port, false), "repl_guard", ReplicatedTopology.GUARD_PASSWORD)
        val killer = QueryKiller(mapOf(drKey to guardClient), "repl_reader")
        // Someone else's query whose id looks like ours survives.
        val foreign =
            CompletableFuture.supplyAsync {
                topology.dr.client().queryScalar(
                    "SELECT sleep(2)",
                    mapOf(
                        "query_id" to "chrepl-fake-1-1",
                    ),
                )
            }
        Thread.sleep(500)
        killer.kill(drKey, "chrepl-fake-1-1")
        assertEquals("0", foreign.get(10, TimeUnit.SECONDS))
        // Our own query is killed.
        val reader = ClickHouseClient(Endpoint("127.0.0.41", topology.dr.port, false), "repl_reader", ReplicatedTopology.READER_PASSWORD)
        val own =
            CompletableFuture.supplyAsync {
                runCatching {
                    reader.queryScalar(
                        "SELECT sum(sleepEachRow(0.5)) FROM numbers(20)",
                        mapOf(
                            "query_id" to "chrepl-own-1-1",
                            "max_block_size" to "1",
                        ),
                    )
                }
            }
        Thread.sleep(1_000)
        killer.kill(drKey, "chrepl-own-1-1")
        val result = own.get(15, TimeUnit.SECONDS)
        assertTrue(result.isFailure, "our query should have been killed, got ${result.getOrNull()}")
    }

    @Test
    @Order(100)
    fun `the tool never contacted the active DC`() {
        topology.prod.sql("SYSTEM FLUSH LOGS")
        assertEquals("0", topology.prod.scalar("SELECT count() FROM system.query_log WHERE user LIKE 'repl\\_%'"))
        // Sanity: the honeypot users exist on prod, so contact would have been logged rather than refused.
        assertEquals("2", topology.prod.scalar("SELECT count() FROM system.users WHERE name IN ('repl_reader', 'repl_guard')"))
        // And DR did see the tool.
        topology.dr.sql("SYSTEM FLUSH LOGS")
        assertTrue(topology.dr.scalar("SELECT count() FROM system.query_log WHERE user = 'repl_reader'").toLong() > 0)
    }
}
