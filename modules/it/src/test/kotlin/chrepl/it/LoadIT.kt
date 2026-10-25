package chrepl.it

import chrepl.config.model.ReplicatorConfig
import chrepl.config.model.SecretRef
import chrepl.config.validate.SecretReader
import chrepl.guard.GuardFactory
import chrepl.guard.GuardGate
import chrepl.guard.GuardLevel
import chrepl.guard.InMemoryOpsFlags
import chrepl.masking.MaskingEngine
import chrepl.masking.keys.StaticKeyProvider
import chrepl.transfer.ClientFactory
import chrepl.transfer.CopyJob
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The load-test harness of PLAN M7, scaled down to containers: steady ingest into "prod", DR
 * replicating it, and the impact of replication reads on DR (replica delay, replication queue)
 * measured without and then with the tool copying continuously through the guard. The report goes
 * to modules/it/build/loadtest-report.md. Container numbers share one machine and are not a
 * substitute for the staging run in docs/loadtest.md; this checks the method and the budget logic.
 * Run with `./gradlew :it:test -Pit -Pload --tests '*LoadIT*'`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "chrepl.load", matches = "true")
class LoadIT {
    private lateinit var topology: ReplicatedTopology
    private lateinit var config: ReplicatorConfig
    private val phase = Duration.ofSeconds((System.getProperty("chrepl.load.seconds") ?: "60").toLong())
    private val budgetSeconds = (System.getProperty("chrepl.load.budget") ?: "5").toDouble()
    private val secrets =
        SecretReader { ref ->
            when {
                ref.kind == SecretRef.Kind.ENV && "MASK" in ref.target -> ItFixtures.maskingKey
                "reader" in ref.target -> ReplicatedTopology.READER_PASSWORD
                "guard" in ref.target -> ReplicatedTopology.GUARD_PASSWORD
                else -> ""
            }
        }

    @BeforeAll
    fun start() {
        topology = ReplicatedTopology()
        ItFixtures.resetTargets()
        config = ItFixtures.config(topology.config())
        // Something worth copying: 200k rows for one COB.
        ItFixtures.insertTrades(topology.prod, 0, "2026-10-01", 1, 200_000)
        topology.awaitReplicated()
    }

    @AfterAll
    fun stop() = topology.close()

    private class Sample(
        val delay: Long,
        val queue: Long,
    )

    /** Steady ingest into prod: [rows] rows every 200 ms, as the active DC would receive. */
    private fun ingest(
        running: AtomicBoolean,
        rows: Int,
        offset: AtomicLong,
    ) = thread(name = "ingest") {
        while (running.get()) {
            ItFixtures.insertTrades(
                topology.prod,
                0,
                "2026-11-01",
                1,
                rows,
                idOffset = 2_000_000_000L + offset.getAndAdd(rows.toLong()),
                insertedAt = "now64(3)",
            )
            Thread.sleep(200)
        }
    }

    private fun measure(duration: Duration): List<Sample> {
        val samples = mutableListOf<Sample>()
        val end = System.nanoTime() + duration.toNanos()
        while (System.nanoTime() < end) {
            val row =
                topology.dr
                    .client()
                    .queryJson(
                        "SELECT max(absolute_delay) AS d, sum(queue_size) AS q FROM system.replicas WHERE database = 'trading'",
                    ).single()
            samples += Sample(row["d"].asLong(), row["q"].asLong())
            Thread.sleep(500)
        }
        return samples
    }

    private fun percentile(
        values: List<Long>,
        p: Double,
    ): Long = values.sorted().let { if (it.isEmpty()) 0 else it[((it.size - 1) * p).toInt()] }

    @Test
    fun `replication reads stay within the DR impact budget`() {
        val running = AtomicBoolean(true)
        val offset = AtomicLong()
        val ingest = ingest(running, 500, offset)
        val baseline = measure(phase)

        val flags = InMemoryOpsFlags()
        val clients = ClientFactory(secrets)
        val copies = AtomicLong()
        val preempted = CopyOnWriteArrayList<Long>()
        val copying = AtomicBoolean(true)
        val guard = GuardFactory.create(config.guard, config.sources.getValue("dr"), clients, flags).start()
        val copier =
            thread(name = "copier") {
                val keys = StaticKeyProvider(config.masking, secrets)
                MaskingEngine.create(config.masking, keys, ItFixtures.examplePath.parent).use { engine ->
                    while (copying.get()) {
                        runCatching {
                            CopyJob(
                                config,
                                "qa1",
                                engine,
                                GuardGate(guard, Duration.ofMinutes(5)),
                                clients,
                                retryBackoff = { Duration.ofMillis(500) },
                            ).copy("trading.trades", listOf(LocalDate.of(2026, 10, 1)))
                            copies.incrementAndGet()
                        }
                    }
                }
            }
        val withTool = measure(phase)
        copying.set(false)
        running.set(false)
        ingest.join()
        copier.join(Duration.ofMinutes(5).toMillis())
        val preemptions = guard.preemptionCount
        val finalLevel = guard.status().level
        guard.close()
        preempted += preemptions.toLong()

        topology.prod.sql("SYSTEM FLUSH LOGS")
        val prodContact = topology.prod.scalar("SELECT count() FROM system.query_log WHERE user LIKE 'repl\\_%'")
        topology.dr.sql("SYSTEM FLUSH LOGS")
        val readsOnDr = topology.dr.scalar("SELECT count() FROM system.query_log WHERE user = 'repl_reader' AND type = 'QueryFinish'")

        val p99Before = percentile(baseline.map { it.delay }, 0.99)
        val p99With = percentile(withTool.map { it.delay }, 0.99)
        val report =
            """
            # Load test report (containers)

            Phase length: ${phase.seconds} s each. Ingest: 500 rows every 200 ms into "prod". Budget: DR delay p99 increase ≤ $budgetSeconds s.

            | | DR delay p50 | DR delay p99 | DR delay max | queue p99 |
            |---|---|---|---|---|
            | baseline | ${percentile(
                baseline.map { it.delay },
                0.5,
            )} s | $p99Before s | ${baseline.maxOf { it.delay }} s | ${percentile(baseline.map { it.queue }, 0.99)} |
            | with replication reads | ${percentile(
                withTool.map { it.delay },
                0.5,
            )} s | $p99With s | ${withTool.maxOf { it.delay }} s | ${percentile(withTool.map { it.queue }, 0.99)} |

            - p99 delay increase: ${p99With - p99Before} s (budget $budgetSeconds s)
            - completed copies: ${copies.get()}; replication reads finished on DR: $readsOnDr; pre-emptions: $preemptions; guard level at the end: $finalLevel
            - connections from the tool to "prod": $prodContact
            """.trimIndent()
        val out = Path.of("build", "loadtest-report.md")
        Files.writeString(out, report + "\n")
        println(report)

        assertEquals("0", prodContact)
        assertTrue(copies.get() > 0 || preemptions > 0, "the tool should have been reading")
        assertTrue(p99With - p99Before <= budgetSeconds, "DR delay p99 grew by ${p99With - p99Before} s, above the $budgetSeconds s budget")
        assertTrue(finalLevel != GuardLevel.DR_ACTIVE)
    }
}
