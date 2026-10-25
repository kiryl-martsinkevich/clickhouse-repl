package chrepl.it

import chrepl.app.Application
import chrepl.cli.ApiExitCodes
import chrepl.cli.cli
import chrepl.config.load.ConfigLoader
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * PLAN M4 exit criteria through the REST API and the CLI: the Spring Boot app (roles `all`) runs
 * against Postgres and the ClickHouse containers, with JWTs signed by a test key and validated
 * like an OIDC issuer's.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiIT {
    private lateinit var postgres: PostgresNode
    private lateinit var app: ConfigurableApplicationContext
    private lateinit var url: String
    private lateinit var signingKey: RSAPrivateKey
    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()

    private val alice by lazy { token("alice", "qa-trading") }
    private val eve by lazy { token("eve", "marketing") }
    private val olga by lazy { token("olga", "data-platform-ops") }
    private val root by lazy { token("root", "data-platform") }

    @BeforeAll
    fun start() {
        postgres = PostgresNode("127.0.0.52").start()
        val dir = Files.createTempDirectory("chrepl-api")
        ItFixtures.examplePath.parent
            .resolve("dictionaries")
            .toFile()
            .copyRecursively(dir.resolve("dictionaries").toFile())
        // Secrets as files, the way the app reads them in production.
        val secrets = dir.resolve("secrets").createDirectories()
        val tree = ItFixtures.configTree()
        ItFixtures.secretsToFiles(tree, secrets) { ref -> if ("MASK" in ref.uppercase()) ItFixtures.maskingKey else "it-password" }
        val config = dir.resolve("replicator.yaml")
        config.writeText(ConfigLoader.mapper.writeValueAsString(tree))

        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        signingKey = keys.private as RSAPrivateKey
        val pem = dir.resolve("issuer.pem")
        pem.writeText(
            "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.public.encoded) +
                "\n-----END PUBLIC KEY-----\n",
        )

        app =
            SpringApplicationBuilder(Application::class.java).run(
                "--server.port=0",
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=postgres",
                "--spring.datasource.password=chrepl",
                "--spring.security.oauth2.resourceserver.jwt.public-key-location=file:$pem",
                "--chrepl.config=$config",
                // No scheduler: the example's continuous jobs would copy in the background and skew the assertions.
                "--chrepl.roles=api,worker",
                "--chrepl.worker.threads=2",
                "--chrepl.worker.idle-sleep=100ms",
            )
        url = "http://127.0.0.1:${app.environment.getProperty("local.server.port")}"
    }

    @AfterAll
    fun stop() {
        if (::app.isInitialized) app.close()
        postgres.close()
    }

    @BeforeEach
    fun reset() {
        ItFixtures.resetSources()
        ItFixtures.resetTargets()
        ItCluster.sources.forEachIndexed { i, node -> ItFixtures.insertTrades(node, i, "2026-09-29", 6, 1_000) }
        postgres.dataSource().connection.use {
            it.createStatement().execute(
                "TRUNCATE jobs, job_requesters, partition_tasks, chunks, copy_registry, audit_log, read_tokens, ops_flags, continuous_paused CASCADE",
            )
        }
    }

    // ------------------------------------------------------------------------------------- helpers

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** A JWT as an OIDC provider would issue it: RS256, user name and groups claims. */
    private fun token(
        user: String,
        vararg groups: String,
    ): String {
        val now = Instant.now().epochSecond
        val header = b64("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims =
            mapOf("sub" to "id-$user", "preferred_username" to user, "groups" to groups.toList(), "iat" to now, "exp" to now + 3600)
        val payload = b64(mapper.writeValueAsBytes(claims))
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(signingKey) }
        signature.update("$header.$payload".toByteArray())
        return "$header.$payload.${b64(signature.sign())}"
    }

    private data class Reply(
        val status: Int,
        val json: JsonNode,
    )

    private fun call(
        method: String,
        path: String,
        token: String?,
        body: Any? = null,
        headers: Map<String, String> = emptyMap(),
    ): Reply {
        val builder =
            HttpRequest
                .newBuilder(URI.create("$url$path"))
                .method(
                    method,
                    if (body ==
                        null
                    ) {
                        HttpRequest.BodyPublishers.noBody()
                    } else {
                        HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))
                    },
                ).header("Content-Type", "application/json")
        token?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val json =
            response.body().takeIf { it.isNotBlank() }?.let { runCatching { mapper.readTree(it) }.getOrNull() } ?: mapper.createObjectNode()
        return Reply(response.statusCode(), json)
    }

    private fun submitBody(
        cob: Any,
        dataset: String = "trades-only",
        target: String = "qa1",
    ) = mapOf("dataset" to dataset, "cob" to cob, "target" to target)

    private fun awaitState(
        id: String,
        vararg states: String,
        within: Duration = Duration.ofSeconds(120),
    ): JsonNode {
        val deadline = System.nanoTime() + within.toNanos()
        while (true) {
            val job = call("GET", "/api/v1/jobs/$id", olga).json
            if (job.path("state").asText() in states) return job
            if (System.nanoTime() > deadline) throw AssertionError("job $id did not reach ${states.toList()}: $job")
            Thread.sleep(250)
        }
    }

    private fun qaCount(cob: String) =
        ItCluster.qa.sumOf { node ->
            val exists = node.scalar("SELECT count() FROM system.tables WHERE database = 'trading' AND name = 'trades'") == "1"
            if (exists) node.scalar("SELECT count() FROM trading.trades WHERE trade_date = '$cob'").toLong() else 0L
        }

    private fun sourceCount(cob: String) =
        ItCluster.sources.sumOf {
            it.scalar("SELECT count() FROM trading.trades WHERE trade_date = '$cob'").toLong()
        }

    // ------------------------------------------------------------------------------------- tests

    @Test
    fun `the API needs a valid token, while health and OpenAPI are open`() {
        assertEquals(401, call("GET", "/api/v1/catalog", null).status)
        assertEquals(401, call("GET", "/api/v1/catalog", "not-a-jwt").status)
        val forged = alice.substringBeforeLast('.') + "." + b64(ByteArray(256))
        assertEquals(401, call("GET", "/api/v1/catalog", forged).status)
        assertEquals("UP", call("GET", "/actuator/health", null).json.path("status").asText())

        val openapi = call("GET", "/v3/api-docs", null)
        assertEquals(200, openapi.status)
        assertTrue(
            openapi.json.path("paths").has("/api/v1/jobs"),
            openapi.json
                .path("paths")
                .fieldNames()
                .asSequence()
                .toList()
                .toString(),
        )
        // Published with the integration build (CI uploads it as an artefact).
        val out = Path.of("build", "openapi").createDirectories().resolve("openapi.json")
        Files.writeString(out, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(openapi.json))
    }

    @Test
    fun `catalog and identity follow the access rules`() {
        val me = call("GET", "/api/v1/me", alice).json
        assertEquals("alice", me.path("user").asText())
        assertEquals(listOf("viewer", "requester"), me.path("roles").map { it.asText() })
        val catalog = call("GET", "/api/v1/catalog", alice).json
        assertEquals(setOf("trading-eod", "trades-only"), catalog.path("datasets").map { it.path("name").asText() }.toSet())
        assertEquals(listOf("qa1"), catalog.path("targets").map { it.asText() })
        assertEquals(403, call("GET", "/api/v1/catalog", eve).status)
    }

    @Test
    fun `a job submitted with the CLI runs to SUCCEEDED and lands in the registry`() {
        val dry =
            cli().test(
                listOf("dry-run", "--api", url, "--token", alice, "--dataset", "trades-only", "--cob", "2026-10-01", "--target", "qa1"),
            )
        assertEquals(0, dry.statusCode, dry.stderr + dry.output)
        assertContains(dry.output, "trading.trades")

        val submit =
            cli().test(
                listOf(
                    "submit",
                    "--api",
                    url,
                    "--token",
                    alice,
                    "--dataset",
                    "trades-only",
                    "--cob",
                    "2026-10-01",
                    "--target",
                    "qa1",
                    "--wait",
                ),
            )
        assertEquals(0, submit.statusCode, submit.stderr + submit.output)
        assertContains(submit.output, "SUCCEEDED")
        assertEquals(sourceCount("2026-10-01"), qaCount("2026-10-01"))
        assertEquals(0, qaCount("2026-10-02"))

        val id =
            submit.output
                .lineSequence()
                .first()
                .substringBefore(' ')
        val status = cli().test(listOf("status", "--api", url, "--token", alice, id, "--chunks"))
        assertContains(status.output, "$id SUCCEEDED qa1")
        assertContains(status.output, "trading.trades")

        val history = call("GET", "/api/v1/targets/qa1/history?table=trading.trades&cob=2026-10-01", alice).json
        // One entry per target shard the partition was replaced on.
        assertEquals(ItCluster.qa.size, history.size(), history.toString())
        assertTrue(history.all { it.path("job_id").asText() == id && it.path("requester").asText() == "alice" }, history.toString())

        // A second requester replacing alice's fresh copy must confirm (SPEC §7.1 step 4).
        val bob = token("bob", "qa-trading")
        val conflict = call("POST", "/api/v1/jobs", bob, submitBody(mapOf("dates" to listOf("2026-10-01"))))
        assertEquals(409, conflict.status, conflict.json.toString())
        assertContains(conflict.json.path("message").asText(), "alice")
        val confirmed =
            call(
                "POST",
                "/api/v1/jobs",
                bob,
                submitBody(mapOf("dates" to listOf("2026-10-01"))) + ("confirm_overwrite_recent" to true),
            )
        assertEquals(202, confirmed.status, confirmed.json.toString())
        awaitState(confirmed.json.path("id").asText(), "SUCCEEDED")
    }

    @Test
    fun `RBAC and quotas are enforced over HTTP`() {
        val forbidden = call("POST", "/api/v1/jobs", eve, submitBody(mapOf("dates" to listOf("2026-10-01"))))
        assertEquals(403, forbidden.status)
        assertEquals("forbidden", forbidden.json.path("error").asText())
        // qa-trading may not write to uat.
        assertEquals(403, call("POST", "/api/v1/jobs", alice, submitBody(mapOf("dates" to listOf("2026-10-01")), target = "uat")).status)
        // A 31-day range exceeds max_cob_dates_per_job (10).
        val overQuota = call("POST", "/api/v1/jobs", alice, submitBody(mapOf("from" to "2026-09-01", "to" to "2026-10-01")))
        assertEquals(422, overQuota.status, overQuota.json.toString())
        assertContains(overQuota.json.path("message").asText(), "COB dates")
        val bad = call("POST", "/api/v1/jobs", alice, submitBody(mapOf("dates" to listOf("2026-10-01"), "from" to "2026-10-01")))
        assertEquals(400, bad.status)

        val cli =
            cli().test(
                listOf("submit", "--api", url, "--token", eve, "--dataset", "trades-only", "--cob", "2026-10-01", "--target", "qa1"),
            )
        assertEquals(ApiExitCodes.REJECTED, cli.statusCode)
        assertContains(cli.stderr, "eve may not request copies")
    }

    @Test
    fun `idempotency keys and cancellation`() {
        // Hold the copy with the global pause so the job is still active when it is cancelled.
        assertEquals(204, call("POST", "/api/v1/admin/pause", root, mapOf("note" to "test")).status)
        val body = submitBody(mapOf("dates" to listOf("2026-10-02")))
        val first = call("POST", "/api/v1/jobs", alice, body, mapOf("Idempotency-Key" to "k-42"))
        val again = call("POST", "/api/v1/jobs", alice, body, mapOf("Idempotency-Key" to "k-42"))
        assertEquals(202, first.status, first.json.toString())
        assertEquals(first.json.path("id").asText(), again.json.path("id").asText())
        val id = first.json.path("id").asText()

        // Only the requester or an operator may cancel.
        assertEquals(403, call("POST", "/api/v1/jobs/$id/cancel", token("bob", "qa-trading")).status)
        val cancel = cli().test(listOf("cancel", "--api", url, "--token", alice, id))
        assertEquals(0, cancel.statusCode, cancel.stderr)
        awaitState(id, "CANCELLED")
        assertEquals(204, call("POST", "/api/v1/admin/resume", root).status)
        assertEquals(0, qaCount("2026-10-02"))
    }

    @Test
    fun `operator and admin endpoints are role-checked and audited`() {
        assertEquals(403, call("POST", "/api/v1/admin/pause", alice).status)
        assertEquals(403, call("POST", "/api/v1/admin/pause", olga).status)
        assertEquals(204, call("POST", "/api/v1/admin/pause", root, mapOf("note" to "maintenance")).status)
        assertTrue(call("GET", "/api/v1/guard", alice).json.path("paused").asBoolean())
        assertEquals(204, call("POST", "/api/v1/admin/resume", root).status)
        assertTrue(!call("GET", "/api/v1/guard", alice).json.path("paused").asBoolean())

        assertEquals(403, call("POST", "/api/v1/admin/failover", alice).status)
        assertEquals(204, call("POST", "/api/v1/admin/failover", olga, mapOf("note" to "DC switch rehearsal")).status)
        val guard = call("GET", "/api/v1/guard", alice).json
        assertEquals("DR_ACTIVE", guard.path("failover").path("code").asText())
        assertContains(guard.path("failover").path("message").asText(), "olga")
        assertEquals(204, call("POST", "/api/v1/admin/failover/clear", olga).status)
        assertTrue(call("GET", "/api/v1/guard", alice).json.path("failover").isMissingNode)

        assertEquals(403, call("POST", "/api/v1/admin/config/reload", olga).status)
        val reload = call("POST", "/api/v1/admin/config/reload", root)
        assertEquals(200, reload.status, reload.json.toString())
        assertNotEquals("", reload.json.path("version").asText())

        val actions =
            postgres.dataSource().connection.use { c ->
                c.createStatement().executeQuery("SELECT actor || ' ' || action FROM audit_log ORDER BY id").use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                }
            }
        assertEquals(
            listOf(
                "root flag.set.paused",
                "root flag.clear.paused",
                "olga flag.set.failover",
                "olga flag.clear.failover",
                "root config.reload",
            ),
            actions,
        )
    }

    @Test
    fun `continuous jobs can be listed, and paused and resumed by operators`() {
        val streams = call("GET", "/api/v1/continuous", alice).json
        assertEquals(
            setOf("trades-to-qa1", "positions-to-qa1", "refdata-nightly"),
            streams.map { it.path("name").asText() }.toSet(),
        )
        // refdata-nightly covers two tables on two targets.
        assertEquals(6, streams.size())
        assertEquals("append_watermark", streams.first { it.path("name").asText() == "trades-to-qa1" }.path("mode").asText())

        assertEquals(403, call("POST", "/api/v1/continuous/trades-to-qa1/pause", alice).status)
        assertEquals(404, call("POST", "/api/v1/continuous/no-such-job/pause", olga).status)
        assertEquals(204, call("POST", "/api/v1/continuous/trades-to-qa1/pause", olga).status)
        val paused = call("GET", "/api/v1/continuous", alice).json.first { it.path("name").asText() == "trades-to-qa1" }
        assertEquals("PAUSED", paused.path("status").asText())
        assertEquals(204, call("POST", "/api/v1/continuous/trades-to-qa1/resume", olga).status)
        assertTrue(
            !call("GET", "/api/v1/continuous", alice)
                .json
                .first { it.path("name").asText() == "trades-to-qa1" }
                .path("paused")
                .asBoolean(),
        )
    }
}
