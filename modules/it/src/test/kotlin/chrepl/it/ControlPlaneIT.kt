package chrepl.it

import chrepl.config.load.ConfigLoader
import chrepl.config.validate.ValidationOptions
import chrepl.control.ConfigRegistry
import chrepl.control.ConflictException
import chrepl.control.Db
import chrepl.control.ForbiddenException
import chrepl.control.JobRequest
import chrepl.control.JobService
import chrepl.control.JobState
import chrepl.control.JobView
import chrepl.control.Principal
import chrepl.control.QuotaException
import chrepl.control.Runtime
import chrepl.control.Worker
import chrepl.control.WorkerSettings
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** PLAN M4 exit criteria: jobs through Postgres, workers, leases, coalescing, RBAC, quotas, cancellation. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ControlPlaneIT {
    private lateinit var postgres: PostgresNode
    private lateinit var db: Db
    private lateinit var configs: ConfigRegistry
    private val workers = mutableListOf<Worker>()
    private val runtimes = mutableListOf<Runtime>()

    private val alice = Principal("alice", setOf("qa-trading"))
    private val bob = Principal("bob", setOf("qa-trading"))
    private val ops = Principal("olga", setOf("data-platform-ops"))
    private val nobody = Principal("eve", setOf("marketing"))
    private val oct1 = LocalDate.of(2026, 10, 1)
    private val oct2 = LocalDate.of(2026, 10, 2)

    @BeforeAll
    fun start() {
        postgres = PostgresNode("127.0.0.51").start()
        db = Db(postgres.dataSource())
        db.migrate()
        // The config lives in a temp dir next to a copy of the dictionaries it references.
        val dir = Files.createTempDirectory("chrepl-cp")
        ItFixtures.examplePath.parent
            .resolve("dictionaries")
            .toFile()
            .copyRecursively(dir.resolve("dictionaries").toFile())
        val file = dir.resolve("replicator.yaml")
        file.writeText(ConfigLoader.mapper.writeValueAsString(ItFixtures.configTree()))
        configs = ConfigRegistry(db, file, ValidationOptions())
        configs.reload("test")
    }

    @AfterAll
    fun stop() = postgres.close()

    @BeforeEach
    fun reset() {
        ItFixtures.resetSources()
        ItFixtures.resetTargets()
        ItCluster.sources.forEachIndexed { i, node -> ItFixtures.insertTrades(node, i, "2026-09-29", 6, 1_000) }
        db.tx { update("TRUNCATE jobs, job_requesters, partition_tasks, chunks, copy_registry, audit_log, read_tokens, ops_flags CASCADE") }
    }

    @AfterEach
    fun stopWorkers() {
        workers.forEach { it.close() }
        workers.clear()
        runtimes.forEach { it.close() }
        runtimes.clear()
    }

    private fun runtime(beforeRead: (String) -> Unit = {}) =
        Runtime(
            configs,
            db,
            ItFixtures.secrets,
            guard = null,
            workerId = "rt-${UUID.randomUUID().toString().take(4)}",
            beforeRead = beforeRead,
        ).also {
            runtimes +=
                it
        }

    private fun service(runtime: Runtime = runtime()) = JobService(db, configs, runtime)

    private fun startWorker(
        runtime: Runtime = runtime(),
        threads: Int = 2,
        lease: Duration = Duration.ofSeconds(30),
    ) = Worker(
        db,
        configs,
        runtime,
        "w${workers.size}",
        WorkerSettings(threads = threads, lease = lease, idleSleep = Duration.ofMillis(100)),
    ).start().also {
        workers +=
            it
    }

    private fun awaitState(
        service: JobService,
        id: String,
        vararg states: JobState,
        within: Duration = Duration.ofSeconds(90),
    ): JobView {
        val deadline = System.nanoTime() + within.toNanos()
        while (true) {
            val view = service.get(ops, id)
            if (view.state in states) return view
            if (System.nanoTime() > deadline) throw AssertionError("job $id did not reach ${states.toList()}: $view")
            Thread.sleep(200)
        }
    }

    private fun count(
        node: ClickHouseNode,
        where: String,
    ) = node.scalar("SELECT count() FROM trading.trades WHERE $where").toLong()

    @Test
    fun `a submitted job runs to SUCCEEDED with audit and registry rows`() {
        val service = service()
        val job = service.submit(alice, JobRequest(dataset = "trades-only", cobDates = listOf(oct1, oct2), target = "qa1"))
        assertEquals(JobState.PENDING, job.state)
        startWorker()
        val done = awaitState(service, job.id, JobState.SUCCEEDED, JobState.FAILED, JobState.BLOCKED)
        assertEquals(JobState.SUCCEEDED, done.state, done.reason)
        assertEquals(6_000L, done.progress.rowsCopied)
        assertEquals(done.progress.chunksTotal, done.progress.chunksDone)
        assertEquals(listOf(2_000L, 2_000L, 2_000L), ItCluster.qa.map { count(it, "trade_date IN ('$oct1', '$oct2')") })
        val actions = db.tx { query("SELECT action FROM audit_log WHERE job_id = ? ORDER BY id", job.id) { it.getString(1) } }
        assertEquals(listOf("job.submit", "job.planned", "job.succeeded"), actions)
        val history = service.history(alice, "qa1", "trading.trades", oct1)
        assertTrue(history.isNotEmpty() && history.all { it.requester == "alice" && it.jobId == job.id })
        assertTrue(ItCluster.qa[0].scalar("SELECT count() FROM repl_meta.copy_history WHERE job_id = '${job.id}'").toInt() > 0)
    }

    @Test
    fun `a worker that dies mid-chunk loses its lease and another worker finishes`() {
        val service = service()
        val job = service.submit(alice, JobRequest(tables = listOf("trading.trades"), cobDates = listOf(oct1), target = "qa1"))
        val planner = Worker(db, configs, runtime(), "planner")
        while (service.get(ops, job.id).state != JobState.RUNNING) planner.step()
        // Simulate a crash: a chunk is RUNNING under a dead worker whose lease already expired.
        db.tx {
            update(
                "UPDATE chunks SET state = 'RUNNING', lease_owner = 'dead', lease_until = now() - interval '1 second', attempts = 1 WHERE id = (SELECT min(id) FROM chunks WHERE job_id = ?)",
                job.id,
            )
        }
        startWorker()
        val done = awaitState(service, job.id, JobState.SUCCEEDED, JobState.FAILED)
        assertEquals(JobState.SUCCEEDED, done.state, done.reason)
        assertEquals(listOf(1_000L, 1_000L, 1_000L), ItCluster.qa.map { count(it, "trade_date = '$oct1'") })
        assertEquals(
            "lease expired (worker lost)",
            db.tx {
                one("SELECT error FROM chunks WHERE job_id = ? AND failures > 0", job.id) { it.getString(1) }
            },
        )
    }

    @Test
    fun `identical requests coalesce and idempotency keys return the same job`() {
        val service = service()
        val request = JobRequest(dataset = "trades-only", cobDates = listOf(oct1), target = "qa1")
        val first = service.submit(alice, request.copy(idempotencyKey = "k1"))
        assertEquals(first.id, service.submit(alice, request.copy(idempotencyKey = "k1")).id)
        val second = service.submit(bob, request)
        assertEquals(first.id, second.id)
        assertEquals(listOf("alice", "bob"), second.requesters)
        startWorker()
        assertEquals(JobState.SUCCEEDED, awaitState(service, first.id, JobState.SUCCEEDED, JobState.FAILED).state)
        assertEquals(1L, db.tx { long("SELECT count(*) FROM jobs") })
    }

    @Test
    fun `overlapping requests are serialised and leave a consistent target`() {
        val service = service()
        val a = service.submit(alice, JobRequest(tables = listOf("trading.trades"), cobDates = listOf(oct1), target = "qa1"))
        val b = service.submit(bob, JobRequest(tables = listOf("trading.trades"), cobDates = listOf(oct1, oct2), target = "qa1"))
        startWorker(threads = 4)
        startWorker(threads = 4)
        assertEquals(JobState.SUCCEEDED, awaitState(service, a.id, JobState.SUCCEEDED, JobState.FAILED).state)
        assertEquals(JobState.SUCCEEDED, awaitState(service, b.id, JobState.SUCCEEDED, JobState.FAILED).state)
        for (node in ItCluster.qa) {
            assertEquals(1_000L, count(node, "trade_date = '$oct1'"))
            assertEquals(1_000L, count(node, "trade_date = '$oct2'"))
            assertEquals(count(node, "1"), node.scalar("SELECT uniqExact(trade_id) FROM trading.trades").toLong(), "no duplicates")
        }
    }

    @Test
    fun `RBAC and quotas are enforced at submission`() {
        val service = service()
        assertFailsWith<ForbiddenException> {
            service.submit(
                nobody,
                JobRequest(dataset = "trades-only", cobDates = listOf(oct1), target = "qa1"),
            )
        }
        assertFailsWith<ForbiddenException> {
            service.submit(
                alice,
                JobRequest(dataset = "trades-only", cobDates = listOf(oct1), target = "uat"),
            )
        }
        assertFailsWith<ForbiddenException> { service.get(nobody, "nope") }
        val tooManyDates = (1..11).map { LocalDate.of(2026, 9, it) }
        assertContains(
            assertFailsWith<QuotaException> {
                service.submit(alice, JobRequest(dataset = "trades-only", cobDates = tooManyDates, target = "qa1"))
            }.message!!,
            "at most 10",
        )
        repeat(
            4,
        ) { i ->
            service.submit(
                alice,
                JobRequest(dataset = "trades-only", cobDates = listOf(LocalDate.of(2026, 9, 1 + i)), target = "qa1"),
            )
        }
        assertContains(
            assertFailsWith<QuotaException> {
                service.submit(alice, JobRequest(dataset = "trades-only", cobDates = listOf(oct2), target = "qa1"))
            }.message!!,
            "max_concurrent_jobs",
        )
        // Only the requester or an operator may cancel.
        val job = service.list(alice, mine = true, state = null).first()
        assertFailsWith<ForbiddenException> { service.cancel(bob, job.id) }
        assertEquals(JobState.CANCELLING, service.cancel(ops, job.id).state)
    }

    @Test
    fun `replacing a slice someone else copied recently needs confirmation`() {
        val service = service()
        startWorker()
        val first = service.submit(alice, JobRequest(dataset = "trades-only", cobDates = listOf(oct1), target = "qa1"))
        awaitState(service, first.id, JobState.SUCCEEDED)
        val e =
            assertFailsWith<ConflictException> {
                service.submit(
                    bob,
                    JobRequest(dataset = "trades-only", cobDates = listOf(oct1), target = "qa1"),
                )
            }
        assertContains(e.message!!, "by alice")
        val confirmed =
            service.submit(
                bob,
                JobRequest(dataset = "trades-only", cobDates = listOf(oct1), target = "qa1", confirmOverwriteRecent = true),
            )
        assertEquals(JobState.SUCCEEDED, awaitState(service, confirmed.id, JobState.SUCCEEDED, JobState.FAILED).state)
    }

    @Test
    fun `cancelling a running job stops its read, drops staging and leaves the target unchanged`() {
        val runtime = runtime(beforeRead = { Thread.sleep(4_000) }) // holds the read open long enough to cancel it
        val service = service(runtime)
        val job = service.submit(alice, JobRequest(dataset = "trades-only", cobDates = listOf(oct1), target = "qa1"))
        startWorker(runtime, threads = 1, lease = Duration.ofSeconds(3))
        while (db.tx { long("SELECT count(*) FROM chunks WHERE job_id = ? AND state = 'RUNNING'", job.id) } == 0L) Thread.sleep(100)
        service.cancel(alice, job.id)
        val done = awaitState(service, job.id, JobState.CANCELLED, JobState.SUCCEEDED, JobState.FAILED, within = Duration.ofSeconds(60))
        assertEquals(JobState.CANCELLED, done.state, done.reason)
        assertEquals(
            listOf(0L, 0L, 0L),
            ItCluster.qa.map { node ->
                node.scalar("SELECT count() FROM system.tables WHERE database = 'trading' AND name = 'trades'").toLong().let {
                    if (it ==
                        0L
                    ) {
                        0L
                    } else {
                        count(node, "1")
                    }
                }
            },
        )
        assertTrue(ItCluster.qa.all { it.scalar("SELECT count() FROM system.tables WHERE name LIKE '%__stg_%'") == "0" })
    }

    @Test
    fun `a job for a table with an unclassified column is BLOCKED with the reason`() {
        ItCluster.sources.forEach { it.sql("ALTER TABLE trading.trades ADD COLUMN client_ssn String DEFAULT ''") }
        val service = service()
        val job = service.submit(alice, JobRequest(dataset = "trades-only", cobDates = listOf(oct1), target = "qa1"))
        startWorker()
        val done = awaitState(service, job.id, JobState.BLOCKED, JobState.FAILED, JobState.SUCCEEDED)
        assertEquals(JobState.BLOCKED, done.state)
        assertContains(done.reason!!, "client_ssn")
    }
}

/** Postgres in Docker for the control plane tests. */
class PostgresNode(
    private val bindAddress: String,
) : AutoCloseable {
    private lateinit var containerId: String
    private var port = 0

    fun start(): PostgresNode {
        val image = System.getProperty("chrepl.it.postgresImage") ?: "postgres:16"
        containerId =
            Docker.run("run", "-d", "-p", "$bindAddress::5432", "-e", "POSTGRES_PASSWORD=chrepl", "-e", "POSTGRES_DB=chrepl", image)
        port =
            Docker
                .run("port", containerId, "5432/tcp")
                .lineSequence()
                .first()
                .substringAfterLast(':')
                .toInt()
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        while (true) {
            if (runCatching { dataSource().connection.use { it.isValid(2) } }.getOrDefault(false)) return this
            if (System.nanoTime() > deadline) throw IllegalStateException("Postgres did not start")
            Thread.sleep(500)
        }
    }

    val jdbcUrl get() = "jdbc:postgresql://$bindAddress:$port/chrepl"

    fun dataSource() =
        PGSimpleDataSource().apply {
            setURL(jdbcUrl)
            user = "postgres"
            password = "chrepl"
        }

    override fun close() {
        runCatching { Docker.run("rm", "-f", containerId) }
    }
}
