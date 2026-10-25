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
import chrepl.control.Principal
import chrepl.control.Runtime
import chrepl.control.Worker
import chrepl.control.WorkerSettings
import chrepl.masking.MaskingEngine
import chrepl.masking.keys.DsmClient
import chrepl.masking.keys.MockDsm
import chrepl.masking.keys.StaticKeyProvider
import chrepl.transfer.CopyJob
import chrepl.transfer.UnguardedGate
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDate
import java.util.HexFormat
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PLAN M8: masking keys from Fortanix DSM (a mock of its REST API). While DSM is unreachable, workers
 * take no work; once it is back, the copy completes with masked values identical to the static
 * provider's for the same key material (BYOK migration without rekey).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FortanixIT {
    private lateinit var postgres: PostgresNode
    private lateinit var dsm: MockDsm
    private val alice = Principal("alice", setOf("qa-trading"))
    private val cob = LocalDate.of(2026, 10, 1)

    @BeforeAll
    fun start() {
        postgres = PostgresNode("127.0.0.57").start()
        dsm = MockDsm().start().also { it.createKek("chrepl-nonprod-kek") }
    }

    @AfterAll
    fun stop() {
        dsm.close()
        postgres.close()
    }

    @Test
    fun `workers wait while DSM is down, then copy with the same masked values as the static key`() {
        ItFixtures.resetSources()
        ItFixtures.resetTargets()
        ItCluster.sources.forEachIndexed { i, node -> ItFixtures.insertTrades(node, i, "2026-09-29", 6, 1_000) }

        // Reference: the same COB copied to uat with the static provider and the same key material.
        val staticConfig = ItFixtures.config()
        MaskingEngine
            .create(
                staticConfig.masking,
                StaticKeyProvider(staticConfig.masking, ItFixtures.secrets),
                ItFixtures.examplePath.parent,
            ).use { engine ->
                CopyJob(staticConfig, "uat", engine, UnguardedGate, ItFixtures.clients).copy("trading.trades", listOf(cob))
            }

        // BYOK: the static key wrapped under the DSM KEK (as `clickhouse-repl dsm-wrap` does).
        val dir = Files.createTempDirectory("chrepl-dsm")
        ItFixtures.examplePath.parent
            .resolve("dictionaries")
            .toFile()
            .copyRecursively(dir.resolve("dictionaries").toFile())
        val wrapped = dir.resolve("masking-key-v1.wrapped")
        wrapped.writeText(
            DsmClient(dsm.url, dsm.apiKeyValue).encrypt("chrepl-nonprod-kek", HexFormat.of().parseHex(ItFixtures.maskingKey)).toJson(),
        )
        val apiKey = dir.resolve("dsm-api-key").also { it.writeText(dsm.apiKeyValue) }
        val file = dir.resolve("replicator.yaml")
        val tree =
            ItFixtures.configTree {
                val key = child("masking").child("key")
                key.removeAll()
                key.put("provider", "fortanix").put("version", 1)
                key
                    .putObject("fortanix")
                    .put("endpoint", dsm.url)
                    .put("api_key", "\${file:$apiKey}")
                    .put("kek", "chrepl-nonprod-kek")
                    .put("wrapped_key", "\${file:$wrapped}")
                putArray("continuous")
            }
        file.writeText(ConfigLoader.mapper.writeValueAsString(tree))
        // The DSM API key and wrapped key are files; the ClickHouse test users have no passwords.
        val secrets =
            SecretReader { ref ->
                val path =
                    java.nio.file.Path
                        .of(ref.target)
                if (ref.kind == SecretRef.Kind.FILE && Files.exists(path)) Files.readString(path) else ""
            }

        val db = Db(postgres.dataSource()).also { it.migrate() }
        val configs = ConfigRegistry(db, file, ValidationOptions()).also { it.reload("test") }
        dsm.available = false
        val runtime = Runtime(configs, db, secrets, guard = null, workerId = "dsm", keyRetry = Duration.ofSeconds(1))
        val jobs = JobService(db, configs, runtime)
        val job = jobs.submit(alice, JobRequest(dataset = "trades-only", cobDates = listOf(cob), target = "qa1"))
        Worker(db, configs, runtime, "w-dsm", WorkerSettings(threads = 2, idleSleep = Duration.ofMillis(100))).start().use {
            Thread.sleep(5_000)
            assertEquals(JobState.PENDING, jobs.get(alice, job.id).state, "no work without the masking key")
            assertContains(runtime.keyError ?: "", "HTTP 503")

            dsm.available = true
            val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
            while (jobs.get(alice, job.id).state != JobState.SUCCEEDED) {
                val view = jobs.get(alice, job.id)
                if (view.state.terminal || System.nanoTime() > deadline) throw AssertionError("job did not succeed: $view")
                Thread.sleep(200)
            }
        }
        runtime.close()

        val fingerprint =
            "SELECT sum(cityHash64(trade_id, customer_id, account_number, notional)) FROM trading.trades WHERE trade_date = '$cob'"
        val qa =
            ItCluster.qa.sumOf { it.scalar(fingerprint).toBigInteger() }.mod(
                java.math.BigInteger.TWO
                    .pow(64),
            )
        assertEquals(ItCluster.uat.scalar(fingerprint).toBigInteger(), qa, "Fortanix and static masking must agree")
        assertTrue(ItCluster.qa.sumOf { it.scalar("SELECT count() FROM trading.trades").toLong() } > 0)
        // One unwrap per process, not per value (the failed attempts during the outage never got past auth).
        assertEquals(1, dsm.calls["/crypto/v1/decrypt"]?.get())
    }
}
