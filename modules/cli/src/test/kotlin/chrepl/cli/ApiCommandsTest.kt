package chrepl.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The API commands against a stub HTTP server: request shape, auth header, output and exit codes. */
class ApiCommandsTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<Triple<String, String, String>>()
    private var authHeaders = mutableListOf<String?>()
    private var responses = mutableMapOf<String, Pair<Int, String>>()
    private val mapper = ObjectMapper()

    private val job =
        """{"id":"j-1","state":"PENDING","target":"qa1","progress":{"chunks_total":0,"chunks_done":0,"partitions_total":0,"partitions_done":0,"rows_copied":0},"queue_position":2}"""

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val key = "${ex.requestMethod} ${ex.requestURI}"
            requests += Triple(ex.requestMethod, ex.requestURI.toString(), ex.requestBody.readAllBytes().decodeToString())
            authHeaders += ex.requestHeaders.getFirst("Authorization")
            val (status, body) = responses[key] ?: (404 to """{"error":"not_found","message":"no route $key"}""")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun url() = "http://127.0.0.1:${server.address.port}"

    private fun body(i: Int): JsonNode = mapper.readTree(requests[i].third)

    @Test
    fun `submit sends the selection and prints the job`() {
        responses["POST /api/v1/jobs"] = 202 to job
        val result =
            cli().test(
                listOf(
                    "submit",
                    "--api",
                    url(),
                    "--token",
                    "t0k",
                    "--dataset",
                    "trading-eod",
                    "--cob",
                    "2026-10-01,2026-10-02",
                    "--target",
                    "qa1",
                    "--idempotency-key",
                    "k1",
                ),
            )
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.output, "j-1 PENDING")
        assertEquals("Bearer t0k", authHeaders.single())
        val sent = body(0)
        assertEquals("trading-eod", sent["dataset"].asText())
        assertEquals(listOf("2026-10-01", "2026-10-02"), sent["cob"]["dates"].map { it.asText() })
        assertEquals("replace", sent["mode"].asText())
        assertEquals(false, sent["confirm_overwrite_recent"].asBoolean())
    }

    @Test
    fun `submit with a COB range sends from and to`() {
        responses["POST /api/v1/jobs"] = 202 to job
        val result =
            cli().test(
                listOf(
                    "submit",
                    "--api",
                    url(),
                    "--token",
                    "t",
                    "--table",
                    "trading.trades",
                    "--cob-from",
                    "2026-10-01",
                    "--cob-to",
                    "2026-10-05",
                    "--target",
                    "qa1",
                ),
            )
        assertEquals(0, result.statusCode, result.output)
        assertEquals("2026-10-01", body(0)["cob"]["from"].asText())
        assertEquals("2026-10-05", body(0)["cob"]["to"].asText())
        assertEquals(listOf("trading.trades"), body(0)["tables"].map { it.asText() })
    }

    @Test
    fun `a rejected request prints the server message and exits 3`() {
        responses["POST /api/v1/jobs"] = 403 to """{"error":"forbidden","message":"eve may not request copies"}"""
        val result =
            cli().test(
                listOf("submit", "--api", url(), "--token", "t", "--dataset", "trading-eod", "--cob", "2026-10-01", "--target", "qa1"),
            )
        assertEquals(ApiExitCodes.REJECTED, result.statusCode, result.output)
        assertContains(result.stderr, "eve may not request copies")
    }

    @Test
    fun `an invalid COB date is rejected before calling the API`() {
        val result =
            cli().test(
                listOf("submit", "--api", url(), "--token", "t", "--dataset", "d", "--cob", "2026-13-01", "--target", "qa1"),
            )
        assertEquals(ApiExitCodes.REJECTED, result.statusCode, result.output)
        assertContains(result.stderr, "invalid COB date")
        assertEquals(0, requests.size)
    }

    @Test
    fun `submit --wait exits 1 when the job fails`() {
        responses["POST /api/v1/jobs"] = 202 to job
        responses["GET /api/v1/jobs/j-1"] =
            200 to job.replace("PENDING", "BLOCKED").replace("\"queue_position\":2", "\"reason\":\"unclassified column x\"")
        val result =
            cli().test(
                listOf("submit", "--api", url(), "--token", "t", "--dataset", "d", "--cob", "2026-10-01", "--target", "qa1", "--wait"),
            )
        assertEquals(ApiExitCodes.JOB_FAILED, result.statusCode, result.output)
        assertContains(result.stderr, "unclassified column x")
    }

    @Test
    fun `status, cancel and dry-run`() {
        responses["GET /api/v1/jobs/j-1"] = 200 to job
        responses["POST /api/v1/jobs/j-1/cancel"] = 200 to job.replace("PENDING", "CANCELLING")
        responses["POST /api/v1/estimates"] = 200 to
            """{"tables":[{"table":"trading.trades","partitions":2,"rows":600,"bytes":12345}],"bytes":12345,"source_available":true,"queued_jobs":1,
               "recent_copies":[{"table":"trading.trades","cob_date":"2026-10-01","requester":"bob","job_id":"j-0","copied_at":"2026-10-21T10:00:00Z"}]}"""
        val status = cli().test(listOf("status", "--api", url(), "--token", "t", "j-1"))
        assertContains(status.output, "j-1 PENDING qa1 chunks 0/0 partitions 0/0 rows 0; queue position 2")
        val cancel = cli().test(listOf("cancel", "--api", url(), "--token", "t", "j-1"))
        assertContains(cancel.output, "CANCELLING")
        val dry =
            cli().test(
                listOf("dry-run", "--api", url(), "--token", "t", "--table", "trading.trades", "--cob", "2026-10-01", "--target", "qa1"),
            )
        assertEquals(0, dry.statusCode, dry.output)
        assertContains(dry.output, "trading.trades")
        assertContains(dry.output, "recently copied: trading.trades 2026-10-01 by bob")
    }

    @Test
    fun `an unreachable API exits 4`() {
        val port = server.address.port
        server.stop(0)
        val result = cli().test(listOf("status", "--api", "http://127.0.0.1:$port", "--token", "t", "j-1"))
        assertEquals(ApiExitCodes.UNREACHABLE, result.statusCode, result.output)
    }
}
