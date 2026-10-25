package chrepl.it

import chrepl.app.Application
import chrepl.config.load.ConfigLoader
import chrepl.devidp.DevIdp
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PLAN M6 exit criteria: the portal in a real browser (Playwright, modules/portal/e2e) against the
 * whole stack: prod → DR ClickHouse with the sample dataset, qa1, Postgres, the dev IdP and the
 * app. A small control server lets the browser test create DR replication backlog on cue.
 * Run with `./gradlew :it:test -Pit -Pe2e --tests '*PortalE2EIT*'` (needs Node.js and Chromium).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "chrepl.e2e", matches = "true")
class PortalE2EIT {
    private lateinit var topology: ReplicatedTopology
    private lateinit var postgres: PostgresNode
    private lateinit var idp: DevIdp
    private lateinit var app: ConfigurableApplicationContext
    private lateinit var control: HttpServer
    private lateinit var appUrl: String
    private var lagRound = 0
    private val demo = Path.of(System.getProperty("chrepl.demoDir"))
    private val qa get() = ItCluster.qa[0]

    @BeforeAll
    fun start() {
        topology = ReplicatedTopology()
        val schema = ReplicatedTopology.statements(Files.readString(demo.resolve("sample-schema.sql")))
        for (node in listOf(topology.prod, topology.dr)) schema.forEach { node.sql(it) }
        ReplicatedTopology.statements(Files.readString(demo.resolve("sample-data.sql"))).forEach { topology.prod.sql(it) }
        for (table in listOf(
            "trading.trades",
            "trading.positions",
            "crm.customers",
            "refdata.instruments",
        )) {
            topology.dr.sql("SYSTEM SYNC REPLICA $table")
        }

        ItFixtures.resetTargets()
        for (db in listOf("crm", "refdata")) {
            qa.sql("DROP DATABASE IF EXISTS $db SYNC")
            qa.sql("GRANT CREATE DATABASE, ALL ON $db.* TO repl_writer")
        }

        postgres = PostgresNode("127.0.0.55").start()
        // The issuer URL is in every token, so the IdP needs its port before it starts.
        val idpPort = java.net.ServerSocket(0).use { it.localPort }
        val issuer = "http://127.0.0.1:$idpPort"
        idp =
            DevIdp(
                issuer,
                DevIdp.parseUsers(DevIdp.DEFAULT_USERS),
                listOf("http://127.0.0.1:"),
                bind = InetSocketAddress("127.0.0.1", idpPort),
            ).start()

        val dir = Files.createTempDirectory("chrepl-e2e")
        ItFixtures.examplePath.parent
            .resolve("dictionaries")
            .toFile()
            .copyRecursively(dir.resolve("dictionaries").toFile())
        val secrets = dir.resolve("secrets").createDirectories()
        val tree =
            ItFixtures.configTree {
                topology.config()(this)
                putArray("continuous")
            }
        ItFixtures.secretsToFiles(tree, secrets) { ref ->
            when {
                "MASK" in ref.uppercase() -> ItFixtures.maskingKey
                "reader" in ref -> ReplicatedTopology.READER_PASSWORD
                "guard" in ref -> ReplicatedTopology.GUARD_PASSWORD
                else -> "it-password"
            }
        }
        val config = dir.resolve("replicator.yaml")
        config.writeText(ConfigLoader.mapper.writeValueAsString(tree))

        app =
            SpringApplicationBuilder(Application::class.java).run(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=postgres",
                "--spring.datasource.password=chrepl",
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=$issuer",
                "--chrepl.portal.issuer=$issuer",
                "--chrepl.config=$config",
                "--chrepl.roles=api,worker",
                "--chrepl.worker.threads=2",
                "--chrepl.worker.idle-sleep=100ms",
            )
        appUrl = "http://127.0.0.1:${app.environment.getProperty("local.server.port")}"
        control = controlServer().also { it.start() }
    }

    @AfterAll
    fun stop() {
        if (::control.isInitialized) control.stop(0)
        if (::app.isInitialized) app.close()
        if (::idp.isInitialized) idp.close()
        if (::postgres.isInitialized) postgres.close()
        if (::topology.isInitialized) topology.close()
    }

    /** POST /lag/start: DR stops fetching while prod keeps inserting, so the guard goes RED. /lag/stop undoes it. */
    private fun controlServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/lag/start") { ex ->
            topology.dr.sql("SYSTEM STOP FETCHES trading.trades")
            val round = ++lagRound
            repeat(6) { i ->
                ItFixtures.insertTrades(topology.prod, 0, "2027-01-01", 1, 10, idOffset = 900_000_000L + round * 10_000L + i * 100L)
            }
            ex.sendResponseHeaders(204, -1)
            ex.close()
        }
        server.createContext("/lag/stop") { ex ->
            topology.dr.sql("SYSTEM START FETCHES trading.trades")
            topology.awaitReplicated()
            ex.sendResponseHeaders(204, -1)
            ex.close()
        }
        return server
    }

    @Test
    fun `the portal flows pass in a browser, and the copy on qa1 is masked`() {
        val portalDir = File(System.getProperty("chrepl.portalDir"))
        val log = portalDir.resolve("build/e2e.log").also { it.parentFile.mkdirs() }
        val process =
            ProcessBuilder("npx", "playwright", "test")
                .directory(portalDir)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .apply {
                    environment()["BASE_URL"] = appUrl
                    environment()["CONTROL_URL"] = "http://127.0.0.1:${control.address.port}"
                    if (File("/opt/pw-browsers/chromium").exists()) environment()["CHROMIUM_PATH"] = "/opt/pw-browsers/chromium"
                    System.getProperty("chrepl.screenshots")?.let { environment()["SCREENSHOTS"] = it }
                }.start()
        val finished = process.waitFor(Duration.ofMinutes(20).toMinutes(), TimeUnit.MINUTES)
        val output = log.readText()
        println(output)
        assertTrue(finished && process.exitValue() == 0, "Playwright failed:\n${output.takeLast(6000)}")

        // The browser saw SUCCEEDED; check what landed on qa1.
        val cob = "trade_date = '2026-10-01'"
        assertEquals(
            topology.dr.scalar("SELECT count() FROM trading.trades WHERE $cob"),
            qa.scalar("SELECT count() FROM trading.trades WHERE $cob"),
        )
        assertEquals("0", qa.scalar("SELECT count() FROM trading.trades WHERE trade_date != '2026-10-01'"))
        // Masked: no source customer id or e-mail address survives; pass columns are untouched.
        assertEquals("0", qa.scalar("SELECT count() FROM trading.trades WHERE customer_id BETWEEN 100000 AND 101999"))
        assertEquals(
            topology.dr.scalar("SELECT sum(quantity) FROM trading.trades WHERE $cob"),
            qa.scalar("SELECT sum(quantity) FROM trading.trades WHERE $cob"),
        )
        assertEquals("2000", qa.scalar("SELECT count() FROM crm.customers"))
        assertEquals("0", qa.scalar("SELECT count() FROM crm.customers WHERE email LIKE '%@bank.com'"))
        assertEquals("500", qa.scalar("SELECT count() FROM refdata.instruments"))
        topology.prod.sql("SYSTEM FLUSH LOGS")
        assertEquals("0", topology.prod.scalar("SELECT count() FROM system.query_log WHERE user LIKE 'repl\\_%'"))
    }
}
