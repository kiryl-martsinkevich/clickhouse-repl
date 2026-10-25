package chrepl.it

import chrepl.config.load.ConfigLoader
import chrepl.config.validate.ValidationOptions
import chrepl.control.ConfigRegistry
import chrepl.control.Db
import chrepl.control.JobRequest
import chrepl.control.JobService
import chrepl.control.JobState
import chrepl.control.JobView
import chrepl.control.Principal
import chrepl.control.RetentionJanitor
import chrepl.control.Runtime
import chrepl.control.Worker
import chrepl.control.WorkerSettings
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** PLAN M7: materialized views (mv_mode base_only), target retention and rekey. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaintenanceIT {
    private lateinit var postgres: PostgresNode
    private lateinit var db: Db
    private lateinit var configs: ConfigRegistry
    private lateinit var configFile: Path
    private lateinit var runtime: Runtime
    private lateinit var worker: Worker
    private lateinit var jobs: JobService
    private val alice = Principal("alice", setOf("qa-trading"))
    private val root = Principal("root", setOf("data-platform"))

    private fun writeConfig(keyVersion: Int) {
        val tree =
            ItFixtures.configTree {
                child("targets").child("qa1").put("retention", "22d")
                child("masking").child("key").put("version", keyVersion)
                putArray("continuous")
            }
        configFile.writeText(ConfigLoader.mapper.writeValueAsString(tree))
    }

    @BeforeAll
    fun start() {
        postgres = PostgresNode("127.0.0.56").start()
        db = Db(postgres.dataSource()).also { it.migrate() }
        val dir = Files.createTempDirectory("chrepl-maint")
        ItFixtures.examplePath.parent
            .resolve("dictionaries")
            .toFile()
            .copyRecursively(dir.resolve("dictionaries").toFile())
        configFile = dir.resolve("replicator.yaml")
        writeConfig(1)
        configs = ConfigRegistry(db, configFile, ValidationOptions(), refresh = Duration.ZERO).also { it.reload("test") }
    }

    @AfterAll
    fun stop() = postgres.close()

    @BeforeEach
    fun reset() {
        writeConfig(1)
        configs.reload("test")
        ItFixtures.resetSources()
        ItFixtures.resetTargets()
        ItCluster.sources.forEachIndexed { i, node -> ItFixtures.insertTrades(node, i, "2026-09-29", 6, 1_000) }
        db.tx { update("TRUNCATE jobs, job_requesters, partition_tasks, chunks, copy_registry, audit_log, read_tokens, ops_flags CASCADE") }
        runtime = Runtime(configs, db, ItFixtures.secrets, guard = null, workerId = "maint")
        jobs = JobService(db, configs, runtime)
        worker = Worker(db, configs, runtime, "w-maint", WorkerSettings(threads = 2, idleSleep = Duration.ofMillis(100))).start()
    }

    @AfterEach
    fun stopWorker() {
        worker.close()
        runtime.close()
    }

    private fun await(
        id: String,
        vararg states: JobState,
    ): JobView {
        val deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos()
        while (true) {
            val view = jobs.get(root, id)
            if (view.state in states) return view
            if (view.state.terminal || System.nanoTime() > deadline) throw AssertionError("job $id: $view")
            Thread.sleep(200)
        }
    }

    private fun copy(vararg dates: String): JobView {
        val job =
            jobs.submit(
                alice,
                JobRequest(
                    dataset = "trades-only",
                    cobDates =
                        dates.map {
                            LocalDate.parse(it)
                        },
                    target = "qa1",
                    confirmOverwriteRecent = true,
                ),
            )
        return await(job.id, JobState.SUCCEEDED)
    }

    private fun qaSum(sql: String) = ItCluster.qa.sumOf { it.scalar(sql).toLong() }

    @Test
    fun `base_only re-creates views on the target and recomputes them from the masked base table`() {
        for (node in ItCluster.sources) {
            node.sql(
                "CREATE TABLE trading.daily (trade_date Date, customer_id UInt64, n UInt64, qty Int64) " +
                    "ENGINE = SummingMergeTree ORDER BY (trade_date, customer_id)",
            )
            node.sql(
                "CREATE MATERIALIZED VIEW trading.daily_mv TO trading.daily AS " +
                    "SELECT trade_date, customer_id, count() AS n, sum(quantity) AS qty FROM trading.trades GROUP BY trade_date, customer_id",
            )
        }
        copy("2026-10-01")
        val trades = qaSum("SELECT count() FROM trading.trades")
        assertEquals(trades, qaSum("SELECT sum(n) FROM trading.daily"))
        assertEquals(qaSum("SELECT sum(quantity) FROM trading.trades"), qaSum("SELECT sum(qty) FROM trading.daily"))
        // Derived from masked data: no real customer id leaks through the view.
        assertEquals(0, qaSum("SELECT count() FROM trading.daily WHERE customer_id BETWEEN 100000 AND 101999"))
        assertEquals(3, qaSum("SELECT count() FROM system.tables WHERE database = 'trading' AND name = 'daily_mv'"))

        // A second COB: the view target is rebuilt, not double-counted.
        copy("2026-10-02")
        assertEquals(qaSum("SELECT count() FROM trading.trades"), qaSum("SELECT sum(n) FROM trading.daily"))
    }

    @Test
    fun `retention drops COB partitions whose newest date is past the target's retention`() {
        copy("2026-09-30", "2026-10-04")
        assertTrue(qaSum("SELECT count() FROM trading.trades WHERE toYYYYMM(trade_date) = 202609") > 0)
        val clock = Clock.fixed(Instant.parse("2026-10-25T06:00:00Z"), ZoneOffset.UTC) // cutoff: 2026-10-03
        val dropped = RetentionJanitor(db, configs, runtime, clock).run()
        assertEquals(3, dropped.size, "202609 on each of the three qa1 shards: $dropped")
        assertTrue(dropped.all { it.endsWith("/trading.trades/202609") })
        assertEquals(0, qaSum("SELECT count() FROM trading.trades WHERE toYYYYMM(trade_date) = 202609"))
        assertTrue(qaSum("SELECT count() FROM trading.trades WHERE trade_date = '2026-10-04'") > 0)
        assertContains(db.tx { query("SELECT action FROM audit_log") { it.getString(1) } }, "retention.drop")
    }

    @Test
    fun `after a key version change, writes are refused until a rekey re-copies the target`() {
        copy("2026-10-01", "2026-10-02")
        val sample = "SELECT toString(groupArray(customer_id)) FROM (SELECT customer_id FROM trading.trades ORDER BY trade_id LIMIT 50)"
        val before = ItCluster.qa[0].scalar(sample)
        val rows = qaSum("SELECT count() FROM trading.trades")

        writeConfig(2)
        configs.reload("test")
        val refused =
            jobs.submit(
                alice,
                JobRequest(dataset = "trades-only", cobDates = listOf(LocalDate.parse("2026-10-03")), target = "qa1"),
            )
        val failed = await(refused.id, JobState.FAILED, JobState.BLOCKED)
        assertContains(failed.reason ?: "", "rekey")

        val rekeys = jobs.rekey(root, "qa1")
        assertEquals(1, rekeys.size)
        await(rekeys.single(), JobState.SUCCEEDED)
        assertEquals(rows, qaSum("SELECT count() FROM trading.trades"))
        assertEquals(
            "2026-10-01,2026-10-02",
            ItCluster.qa[0].scalar(
                "SELECT arrayStringConcat(groupArray(toString(d)), ',') FROM (SELECT DISTINCT trade_date AS d FROM trading.trades ORDER BY d)",
            ),
        )
        assertNotEquals(before, ItCluster.qa[0].scalar(sample), "customer ids must be re-masked with key v2")
        assertEquals(3, qaSum("SELECT count() FROM repl_meta.tables FINAL WHERE table = 'trades' AND key_version = 2"))
        // Nothing is stale any more.
        assertEquals(emptyList(), jobs.rekey(root, "qa1"))
    }
}
