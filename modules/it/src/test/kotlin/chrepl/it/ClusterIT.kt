package chrepl.it

import chrepl.config.load.ConfigLoader
import chrepl.config.model.SecretRef
import chrepl.config.validate.SecretReader
import chrepl.config.validate.ValidationOptions
import chrepl.control.ConfigRegistry
import chrepl.control.Db
import chrepl.control.JobRequest
import chrepl.control.JobService
import chrepl.control.JobState
import chrepl.control.PgOpsFlags
import chrepl.control.Principal
import chrepl.control.Runtime
import chrepl.control.Worker
import chrepl.control.WorkerSettings
import chrepl.guard.GuardFactory
import chrepl.guard.GuardLevel
import chrepl.guard.MntrKeeperSource
import chrepl.guard.SourceGuard
import chrepl.transfer.ClientFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDate
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PLAN M9: the physical-cluster layout end to end — 3 prod + 3 DR replicas of one shard sharing a
 * Keeper ensemble (DR Keepers cannot lead), and a nonprod target of 1 shard × 3 replicas with its
 * own Keepers. Copies run through the control plane (Postgres tokens, Source Guard, workers).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ClusterIT {
    private lateinit var topology: ClusterTopology
    private lateinit var postgres: PostgresNode
    private lateinit var guard: SourceGuard
    private lateinit var runtime: Runtime
    private lateinit var worker: Worker
    private lateinit var jobs: JobService
    private val alice = Principal("alice", setOf("qa-trading"))

    private val secrets =
        SecretReader { ref ->
            when {
                ref.kind == SecretRef.Kind.ENV && "MASK" in ref.target -> ItFixtures.maskingKey
                "reader" in ref.target -> ClusterTopology.READER_PASSWORD
                "guard" in ref.target -> ClusterTopology.GUARD_PASSWORD
                "writer" in ref.target -> ClusterTopology.WRITER_PASSWORD
                else -> ""
            }
        }

    @BeforeAll
    fun start() {
        topology = ClusterTopology()
        topology.insertOnProd("2026-10-01", days = 3, rows = 2_000)
        topology.syncDr()
        postgres = PostgresNode("127.0.0.80").start()
        val db = Db(postgres.dataSource()).also { it.migrate() }
        val tree = ConfigLoader.readTree(ItFixtures.examplePath)
        topology.config()(tree)
        ItFixtures.bindValid(tree) // fails fast with the validation errors
        val file = Files.createTempDirectory("chrepl-cluster").resolve("replicator.yaml")
        file.writeText(ConfigLoader.mapper.writeValueAsString(tree))
        ItFixtures.examplePath.parent
            .resolve("dictionaries")
            .toFile()
            .copyRecursively(file.parent.resolve("dictionaries").toFile())
        val configs = ConfigRegistry(db, file, ValidationOptions()).also { it.reload("test") }
        guard =
            GuardFactory
                .create(
                    configs.current.guard,
                    configs.current.sources.getValue("dr"),
                    ClientFactory(secrets),
                    PgOpsFlags(db),
                ).start()
        runtime = Runtime(configs, db, secrets, guard, "cluster-it")
        worker = Worker(db, configs, runtime, "w-cluster", WorkerSettings(threads = 3, idleSleep = Duration.ofMillis(100))).start()
        jobs = JobService(db, configs, runtime)
    }

    @AfterAll
    fun stop() {
        worker.close()
        runtime.close()
        postgres.close()
        topology.close()
    }

    @Test
    @Order(1)
    fun `the guard watches every DR replica and every DR Keeper, and DR Keepers never lead`() {
        await("guard GREEN") { guard.status().level == GuardLevel.GREEN }
        val status = guard.status()
        assertEquals(topology.dr.map { it.hostPort }.toSet(), status.hosts.keys)
        assertEquals(topology.drKeepers.toSet(), status.keepers.keys)
        for ((host, keeper) in status.keepers) {
            println("DR Keeper $host: ${keeper.state}, ${keeper.avgLatencyMs} ms, ${keeper.outstandingRequests} outstanding")
            assertTrue(keeper.state != null && keeper.state != "leader", "DR Keeper $host is ${keeper.state}")
        }
        // For the docs: how the prod leader sees the ensemble (learners are not counted as voters).
        val leader =
            topology.prod.firstNotNullOfOrNull { node ->
                val values = MntrKeeperSource.parse(mntrInside(node))
                values.takeIf { it["zk_server_state"] == "leader" }
            }
        println("prod Keeper leader: $leader")
    }

    @Test
    @Order(2)
    fun `an ad-hoc copy lands on every nonprod replica, replicated`() {
        val cob = LocalDate.of(2026, 10, 1)
        runJob(cob)
        val dr = topology.dr[1].scalar("SELECT count() FROM trading.trades WHERE trade_date = '$cob'").toLong()
        assertTrue(dr > 0)
        for (node in topology.nonprod) {
            node.sql("SYSTEM SYNC REPLICA trading.trades")
            assertEquals(dr, node.scalar("SELECT count() FROM trading.trades WHERE trade_date = '$cob'").toLong(), node.name)
            assertEquals(
                "ReplicatedMergeTree",
                node.scalar("SELECT engine FROM system.tables WHERE database = 'trading' AND name = 'trades'"),
            )
            assertEquals(
                "ReplicatedReplacingMergeTree",
                node.scalar("SELECT engine FROM system.tables WHERE database = 'repl_meta' AND name = 'tables'"),
            )
            node.sql("SYSTEM SYNC REPLICA repl_meta.tables")
            assertEquals("1", node.scalar("SELECT count() FROM repl_meta.tables FINAL WHERE table = 'trades'"), node.name)
        }
        val fingerprint = "SELECT sum(cityHash64(trade_id, customer_id, account_number)) FROM trading.trades WHERE trade_date = '$cob'"
        assertEquals(
            1,
            topology.nonprod
                .map { it.scalar(fingerprint) }
                .distinct()
                .size,
            "replicas hold identical masked rows",
        )
        assertTrue(
            topology.nonprod[0].scalar("SELECT count() FROM trading.trades WHERE account_number LIKE 'GB29NWBK6016%'") == "0",
            "account numbers are masked",
        )
    }

    @Test
    @Order(3)
    fun `a lagging DR replica is skipped while the others serve reads`() {
        val lagging = topology.dr[0]
        lagging.sql("SYSTEM STOP FETCHES trading.trades")
        try {
            repeat(5) { i -> topology.insertOnProd("2026-11-0${1 + i}", days = 1, rows = 10, idOffset = 50_000_000L + i * 100) }
            await("dr1 RED") { guard.status().hosts[lagging.hostPort] == GuardLevel.RED }
            assertEquals(GuardLevel.GREEN, guard.status().level, "the shard still has GREEN replicas")
            val job = runJob(LocalDate.of(2026, 10, 2))
            topology.dr.forEach { it.sql("SYSTEM FLUSH LOGS") }
            val reads =
                topology.dr.map { node ->
                    node
                        .scalar(
                            "SELECT count() FROM system.query_log WHERE user = 'repl_reader' AND query_id LIKE 'chrepl-$job-%' AND type = 'QueryStart'",
                        ).toLong()
                }
            assertEquals(0L, reads[0], "no chunk read from the lagging replica: $reads")
            assertTrue(reads.sum() > 0, "chunks were read from the other replicas: $reads")
        } finally {
            lagging.sql("SYSTEM START FETCHES trading.trades")
        }
        topology.syncDr()
        await("guard GREEN again") {
            guard
                .status()
                .hosts.values
                .all { it == GuardLevel.GREEN }
        }
    }

    @Test
    @Order(4)
    fun `with the first nonprod replica down the copy is written through the next one`() {
        val down = topology.nonprod[0]
        Docker.run("stop", down.containerId)
        val cob = LocalDate.of(2026, 10, 3)
        try {
            runJob(cob)
            val expected = topology.dr[1].scalar("SELECT count() FROM trading.trades WHERE trade_date = '$cob'")
            topology.nonprod[1].sql("SYSTEM SYNC REPLICA trading.trades")
            assertEquals(expected, topology.nonprod[1].scalar("SELECT count() FROM trading.trades WHERE trade_date = '$cob'"))
        } finally {
            Docker.run("start", down.containerId)
        }
        down.awaitReady()
        await("np1 caught up") {
            runCatching {
                down.sql("SYSTEM SYNC REPLICA trading.trades")
                down.scalar("SELECT count() FROM trading.trades WHERE trade_date = '$cob'") ==
                    topology.nonprod[1].scalar("SELECT count() FROM trading.trades WHERE trade_date = '$cob'")
            }.getOrDefault(false)
        }
    }

    @Test
    @Order(5)
    fun `the tool never contacted prod`() {
        for (node in topology.prod) {
            node.sql("SYSTEM FLUSH LOGS")
            assertEquals("0", node.scalar("SELECT count() FROM system.query_log WHERE user LIKE 'repl\\_%'"), node.name)
        }
    }

    // ------------------------------------------------------------------------------------- helpers

    /** `mntr` from inside a container (the test may look at prod; the tool never does). */
    private fun mntrInside(node: ClickHouseNode): String =
        runCatching {
            Docker.run("exec", node.containerId, "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/9181; printf mntr >&3; timeout 2 cat <&3")
        }.getOrDefault("")

    private fun runJob(cob: LocalDate): String {
        val job = jobs.submit(alice, JobRequest(dataset = "trades-only", cobDates = listOf(cob), target = "qa1"))
        await("job ${job.id} SUCCEEDED", Duration.ofSeconds(180)) {
            val view = jobs.get(alice, job.id)
            if (view.state.terminal && view.state != JobState.SUCCEEDED) throw AssertionError("job failed: $view")
            view.state == JobState.SUCCEEDED
        }
        return job.id
    }

    private fun await(
        what: String,
        within: Duration = Duration.ofSeconds(120),
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + within.toNanos()
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what; guard: ${guard.status()}")
            Thread.sleep(500)
        }
    }
}
