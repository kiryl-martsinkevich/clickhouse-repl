package chrepl.it

import chrepl.config.load.ConfigLoader
import chrepl.config.model.SecretRef
import chrepl.config.validate.SecretReader
import chrepl.config.validate.ValidationOptions
import chrepl.control.ConfigRegistry
import chrepl.control.ContinuousService
import chrepl.control.ContinuousView
import chrepl.control.Db
import chrepl.control.JobService
import chrepl.control.PgOpsFlags
import chrepl.control.Principal
import chrepl.control.Runtime
import chrepl.control.Scheduler
import chrepl.control.SchedulerSettings
import chrepl.control.Worker
import chrepl.control.WorkerSettings
import chrepl.guard.GuardFactory
import chrepl.guard.GuardLevel
import chrepl.guard.SourceGuard
import chrepl.transfer.ClientFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PLAN M5 exit criteria: continuous mode on a prod → DR replicated pair. Rows only ever enter
 * through "prod"; the tool reads DR and writes qa1 (append_watermark) and uat (partition_fingerprint).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ContinuousIT {
    private lateinit var topology: ReplicatedTopology
    private lateinit var postgres: PostgresNode
    private lateinit var guard: SourceGuard
    private lateinit var runtime: Runtime
    private lateinit var worker: Worker
    private lateinit var scheduler: Scheduler
    private lateinit var continuous: ContinuousService
    private val ops = Principal("olga", setOf("data-platform-ops"))
    private val secrets =
        SecretReader { ref ->
            when {
                ref.kind == SecretRef.Kind.ENV && "MASK" in ref.target -> ItFixtures.maskingKey
                "reader" in ref.target -> ReplicatedTopology.READER_PASSWORD
                "guard" in ref.target -> ReplicatedTopology.GUARD_PASSWORD
                else -> ""
            }
        }

    private fun streams(): ObjectNode.() -> Unit =
        {
            topology.config()(this)
            val list = putArray("continuous")
            list
                .addObject()
                .put("name", "trades-append")
                .put("table", "trading.trades")
                .put("mode", "append_watermark")
                .put("watermark_column", "inserted_at")
                .put("interval", "2s")
                .put("overlap", "10m")
                .put("dedupe", "replace_partition")
                .put("only_recent_partitions", 3)
                .put("lag_slo", "30s")
                .putArray("targets")
                .add("qa1")
            list
                .addObject()
                .put("name", "trades-fingerprint")
                .put("table", "trading.trades")
                .put("mode", "partition_fingerprint")
                .put("interval", "2s")
                .put("only_recent_partitions", 3)
                .put("sync_drops", true)
                .put("lag_slo", "30s")
                .putArray("targets")
                .add("uat")
        }

    @BeforeAll
    fun start() {
        topology = ReplicatedTopology()
        ItFixtures.resetTargets()
        postgres = PostgresNode("127.0.0.54").start()
        db = Db(postgres.dataSource()).also { it.migrate() }
        val file = Files.createTempDirectory("chrepl-continuous").resolve("replicator.yaml")
        file.writeText(ConfigLoader.mapper.writeValueAsString(ItFixtures.configTree(streams())))
        ItFixtures.examplePath.parent
            .resolve("dictionaries")
            .toFile()
            .copyRecursively(file.parent.resolve("dictionaries").toFile())
        val configs = ConfigRegistry(db, file, ValidationOptions()).also { it.reload("test") }
        val config = configs.current
        guard = GuardFactory.create(config.guard, config.sources.getValue("dr"), ClientFactory(secrets), PgOpsFlags(db)).start()
        runtime = Runtime(configs, db, secrets, guard, "continuous-it")
        worker = Worker(db, configs, runtime, "w-continuous", WorkerSettings(threads = 2, idleSleep = Duration.ofMillis(100))).start()
        scheduler =
            Scheduler(db, configs, runtime, JobService(db, configs, runtime), SchedulerSettings(tick = Duration.ofMillis(500))).start()
        continuous = ContinuousService(db, configs)
        awaitGreen()
    }

    @AfterAll
    fun stop() {
        scheduler.close()
        worker.close()
        runtime.close()
        postgres.close()
        topology.close()
    }

    // ------------------------------------------------------------------------------------- helpers

    private fun awaitGreen() {
        await("guard GREEN", Duration.ofSeconds(60)) { guard.status().level == GuardLevel.GREEN }
    }

    private fun await(
        what: String,
        within: Duration = Duration.ofSeconds(90),
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + within.toNanos()
        while (!condition()) {
            if (System.nanoTime() >
                deadline
            ) {
                throw AssertionError("timed out waiting for $what; streams: ${continuous.views()}\n${diagnostics()}")
            }
            Thread.sleep(250)
        }
    }

    private fun drCount(where: String = "1") = topology.dr.scalar("SELECT count() FROM trading.trades WHERE $where").toLong()

    private fun count(
        node: ClickHouseNode,
        where: String = "1",
    ): Long {
        val exists = node.scalar("SELECT count() FROM system.tables WHERE database = 'trading' AND name = 'trades'") == "1"
        return if (exists) node.scalar("SELECT count() FROM trading.trades WHERE $where").toLong() else -1
    }

    private val qa get() = ItCluster.qa[0]

    private val october = "toYYYYMM(trade_date) = 202610"

    private lateinit var db: Db

    private fun diagnostics(): String {
        val q = "SELECT _partition_id, count(), toString(max(inserted_at)) FROM trading.trades GROUP BY 1 ORDER BY 1 FORMAT TSV"
        val state =
            db.tx {
                query("SELECT name, watermarks::text, coalesce(pending::text, '') FROM continuous_state") {
                    "${it.getString(1)} ${it.getString(2)} ${it.getString(3)}"
                }
            }
        val fps =
            db.tx {
                query("SELECT name, partition_id, fingerprint FROM partition_fingerprints ORDER BY 1, 2") {
                    "${it.getString(1)} ${it.getString(2)} ${it.getString(3)}"
                }
            }
        val parts =
            topology.dr
                .client()
                .queryJson(
                    "SELECT partition_id, name, rows FROM system.parts WHERE table = 'trades' AND active",
                ).joinToString("; ")
        return "DR:\n${topology.dr.client().queryJson(
            q.removeSuffix(" FORMAT TSV"),
        )}\nqa1:\n${runCatching { qa.client().queryJson(q.removeSuffix(" FORMAT TSV")) }.getOrNull()}\n" +
            "state: $state\nfingerprints: $fps\nDR parts: $parts"
    }

    private fun view(name: String): ContinuousView = continuous.list(ops).single { it.name == name }

    /** Inserts on prod only; DR receives the rows through ReplicatedMergeTree, the tool never sees prod. */
    private fun insertOnProd(
        date: String,
        rows: Int,
        idOffset: Long,
        insertedAt: String = "now64(3) - toIntervalSecond(3600) + toIntervalMillisecond(number)",
    ) {
        ItFixtures.insertTrades(topology.prod, 0, date, 1, rows, idOffset = idOffset, insertedAt = insertedAt)
        topology.awaitReplicated()
    }

    // ------------------------------------------------------------------------------------- tests

    @Test
    @Order(1)
    fun `rows inserted on prod reach the target through DR while the guard is GREEN`() {
        insertOnProd("2026-10-01", 2_000, 0)
        await("first sync of qa1") { count(qa) == 2_000L }
        val started = System.nanoTime()
        insertOnProd("2026-10-02", 500, 1_000_000)
        await("new rows on qa1") { count(qa) == 2_500L }
        val seconds = (System.nanoTime() - started) / 1e9
        // interval (2 s) + copy time; generous because the DR reader is throttled to 300 kB/s in tests.
        assertTrue(seconds < 60, "took $seconds s")
        val v = view("trades-append")
        assertTrue(v.lagSeconds != null && v.lagSeconds!! < 30, "$v")
        assertEquals(false, v.sloBreached)
    }

    @Test
    @Order(2)
    fun `late rows within the overlap are picked up without duplicates`() {
        val watermark = topology.dr.scalar("SELECT toString(max(inserted_at)) FROM trading.trades")
        // Arrives now, but stamped 5 minutes before the last copied watermark (overlap is 10 minutes).
        insertOnProd("2026-10-03", 100, 2_000_000, "toDateTime64('$watermark', 3) - toIntervalSecond(300) + toIntervalMillisecond(number)")
        await("late rows on qa1") { count(qa) == drCount() }
        assertEquals(drCount(), qa.scalar("SELECT uniqExact(trade_id) FROM trading.trades").toLong())
        assertEquals(2_600L, count(qa))
    }

    @Test
    @Order(3)
    fun `a mutation on prod is replicated to DR and the partition is re-copied (fingerprint mode)`() {
        val uat = ItCluster.uat
        await("first sync of uat") { count(uat) == drCount() }
        topology.prod.sql("ALTER TABLE trading.trades UPDATE quantity = 4242 WHERE trade_id % 10 = 0 SETTINGS mutations_sync = 2")
        topology.awaitReplicated()
        val expected = drCount("quantity = 4242")
        assertTrue(expected > 0)
        await("mutated rows on uat") { count(uat, "quantity = 4242") == expected }
        assertEquals(drCount(), count(uat))
    }

    @Test
    @Order(4)
    fun `a partition dropped on prod is dropped on the target with sync_drops`() {
        val uat = ItCluster.uat
        insertOnProd("2026-09-15", 300, 3_000_000)
        await("September on uat") { count(uat, "toYYYYMM(trade_date) = 202609") == 300L }
        topology.prod.sql("ALTER TABLE trading.trades DROP PARTITION ID '202609'")
        topology.awaitReplicated()
        assertEquals(0L, drCount("toYYYYMM(trade_date) = 202609"))
        await("September gone from uat") { count(uat, "toYYYYMM(trade_date) = 202609") == 0L }
        assertEquals(drCount(), count(uat))
    }

    @Test
    @Order(5)
    fun `while the guard is RED nothing is read, lag grows, and the streams catch up after GREEN`() {
        awaitGreen()
        await("streams idle") { continuous.views().all { it.jobId == null } }
        val before = count(qa, october)
        val lagBefore = view("trades-append").lagSeconds!!
        topology.dr.sql("SYSTEM STOP FETCHES trading.trades")
        val redSince: Instant
        try {
            // Unique blocks so ReplicatedMergeTree deduplication does not drop them; they queue up on DR.
            repeat(6) { i ->
                ItFixtures.insertTrades(
                    topology.prod,
                    0,
                    "2026-10-04",
                    1,
                    10,
                    idOffset = 4_000_000L + i * 100L,
                    insertedAt = "now64(3) + toIntervalMillisecond(number)",
                )
            }
            await("guard RED", Duration.ofSeconds(60)) { guard.status().level == GuardLevel.RED }
            redSince = Instant.now()
            Thread.sleep(10_000)
            assertEquals(before, count(qa, october), "nothing new may be copied while DR is behind")
            val lagDuring = view("trades-append").lagSeconds!!
            assertTrue(lagDuring >= lagBefore + 8, "lag should grow during RED: $lagBefore -> $lagDuring")
            // No reads by the replication user once pre-emption had its grace period.
            topology.dr.sql("SYSTEM FLUSH LOGS")
            val reads =
                topology.dr.scalar(
                    "SELECT count() FROM system.query_log WHERE user = 'repl_reader' AND type = 'QueryStart' " +
                        "AND event_time_microseconds > fromUnixTimestamp64Milli(${redSince.plusSeconds(2).toEpochMilli()})",
                )
            assertEquals("0", reads)
        } finally {
            topology.dr.sql("SYSTEM START FETCHES trading.trades")
            topology.awaitReplicated()
        }
        // append_watermark never drops (September went in test 4), so compare the live partition.
        await("qa1 caught up", Duration.ofSeconds(120)) { count(qa, october) == drCount(october) }
        await("uat caught up", Duration.ofSeconds(120)) { count(ItCluster.uat) == drCount() }
        await("lag recovered", Duration.ofSeconds(60)) { (view("trades-append").lagSeconds ?: Long.MAX_VALUE) < 30 }
    }

    @Test
    @Order(6)
    fun `operators can pause and resume a continuous job`() {
        continuous.pause(ops, "trades-append")
        await("paused") { view("trades-append").status == "PAUSED" }
        insertOnProd("2026-10-05", 50, 5_000_000)
        Thread.sleep(5_000)
        assertTrue(count(qa, october) < drCount(october), "a paused stream must not copy")
        continuous.resume(ops, "trades-append")
        await("resumed copy") { count(qa, october) == drCount(october) }
    }

    @Test
    @Order(100)
    fun `the tool never contacted the active DC`() {
        topology.prod.sql("SYSTEM FLUSH LOGS")
        assertEquals("0", topology.prod.scalar("SELECT count() FROM system.query_log WHERE user LIKE 'repl\\_%'"))
    }
}
