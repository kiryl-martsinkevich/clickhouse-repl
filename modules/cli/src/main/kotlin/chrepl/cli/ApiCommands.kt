package chrepl.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Exit codes of the API commands: 0 ok, 1 job did not succeed, 3 request rejected, 4 API unreachable. */
object ApiExitCodes {
    const val JOB_FAILED = 1
    const val REJECTED = 3
    const val UNREACHABLE = 4
}

class ApiException(
    val status: Int,
    message: String,
) : Exception(message)

/** Minimal REST client for the control plane API (SPEC §7.5). */
class ApiClient(
    base: String,
    private val token: String,
) {
    private val base = base.trimEnd('/')
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun get(path: String): JsonNode = send(HttpRequest.newBuilder(uri(path)).GET())

    fun post(
        path: String,
        body: Any?,
        headers: Map<String, String> = emptyMap(),
    ): JsonNode {
        val builder =
            HttpRequest
                .newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(if (body == null) "{}" else mapper.writeValueAsString(body)))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return send(builder)
    }

    private fun uri(path: String) = URI.create("$base/api/v1$path")

    private fun send(builder: HttpRequest.Builder): JsonNode {
        val request =
            builder
                .header(
                    "Authorization",
                    "Bearer $token",
                ).header("Accept", "application/json")
                .timeout(Duration.ofMinutes(2))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        val body = response.body().ifBlank { "{}" }
        val json = runCatching { mapper.readTree(body) }.getOrElse { mapper.createObjectNode().put("message", body.take(500)) }
        if (response.statusCode() >= 400) {
            val message =
                json.path("message").asText("").ifEmpty {
                    if (response.statusCode() ==
                        401
                    ) {
                        "not authenticated (check the token)"
                    } else {
                        "HTTP ${response.statusCode()}"
                    }
                }
            throw ApiException(response.statusCode(), message)
        }
        return json
    }

    companion object {
        val mapper: ObjectMapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    }
}

/** `--api` and the bearer token, from options or the environment. */
class ApiOptions : OptionGroup("API options") {
    val api by option("--api", envvar = "CHREPL_API", help = "control plane base URL, e.g. https://chrepl.internal").required()
    val token by option("--token", envvar = "CHREPL_TOKEN", help = "OIDC access token (prefer CHREPL_TOKEN or --token-file)")
    val tokenFile by option("--token-file", help = "file containing the access token").path(mustExist = true)
    val json by option("--json", help = "print the raw JSON response").flag()

    fun client(): ApiClient {
        val value =
            token ?: tokenFile?.let { Files.readString(it).trim() } ?: throw ApiException(401, "give --token, --token-file or CHREPL_TOKEN")
        return ApiClient(api, value)
    }
}

/** Base class: maps API errors to messages and exit codes. */
abstract class ApiCommand(
    name: String,
) : CliktCommand(name = name) {
    protected val apiOptions by ApiOptions()

    protected abstract fun call(client: ApiClient)

    override fun run() {
        try {
            call(apiOptions.client())
        } catch (e: ApiException) {
            echo("error: ${e.message}", err = true)
            throw ProgramResult(ApiExitCodes.REJECTED)
        } catch (e: java.io.IOException) {
            echo("error: cannot reach ${apiOptions.api}: ${e.message ?: e::class.simpleName}", err = true)
            throw ProgramResult(ApiExitCodes.UNREACHABLE)
        }
    }

    protected fun print(
        json: JsonNode,
        text: () -> String,
    ) = echo(if (apiOptions.json) ApiClient.mapper.writeValueAsString(json) else text())
}

/** The selection shared by `submit` and `dry-run`. */
abstract class SelectionCommand(
    name: String,
) : ApiCommand(name) {
    private val target by option("--target", help = "target environment, e.g. qa1").required()
    private val dataset by option("--dataset", help = "dataset name")
    private val tables by option("--table", help = "database.table (repeatable)").multiple()
    private val cob by option("--cob", help = "COB dates, YYYY-MM-DD, comma-separated").split(",").multiple()
    private val cobFrom by option("--cob-from", help = "first COB date of an inclusive range")
    private val cobTo by option("--cob-to", help = "last COB date of an inclusive range")
    private val mode by option(
        "--mode",
        help = "replace the COB slice, or skip partitions already present",
    ).choice("replace", "skip_if_present").default("replace")
    private val confirm by option("--confirm-overwrite-recent", help = "replace slices another user copied recently").flag()

    protected fun body(): Map<String, Any?> {
        val dates =
            cob
                .flatten()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { date(it) }
        val cobSpec =
            when {
                cobFrom != null || cobTo != null ->
                    mapOf(
                        "from" to cobFrom?.let(::date)?.toString(),
                        "to" to cobTo?.let(::date)?.toString(),
                    )
                dates.isNotEmpty() -> mapOf("dates" to dates.map { it.toString() })
                else -> null
            }
        return mapOf(
            "dataset" to dataset,
            "tables" to tables,
            "cob" to cobSpec,
            "target" to target,
            "mode" to mode,
            "confirm_overwrite_recent" to confirm,
        )
    }

    private fun date(text: String): LocalDate =
        try {
            LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            throw ApiException(400, "invalid COB date '$text' (expected YYYY-MM-DD)")
        }
}

class SubmitCommand : SelectionCommand("submit") {
    override fun help(context: Context) = "Submit an ad-hoc copy job to the control plane (SPEC section 7)."

    private val idempotencyKey by option("--idempotency-key", help = "resubmitting with the same key returns the same job")
    private val wait by option("--wait", help = "wait until the job finishes; exit 1 unless it SUCCEEDED").flag()

    override fun call(client: ApiClient) {
        val job = client.post("/jobs", body(), idempotencyKey?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap())
        print(job) { "${job.path("id").asText()} ${job.path("state").asText()}" }
        if (wait) awaitJob(client, job.path("id").asText())
    }

    private fun awaitJob(
        client: ApiClient,
        id: String,
    ) {
        var last = ""
        while (true) {
            val job = client.get("/jobs/$id")
            val line = statusLine(job)
            if (line != last && !apiOptions.json) echo(line)
            last = line
            when (job.path("state").asText()) {
                "SUCCEEDED" -> return
                "FAILED", "BLOCKED", "CANCELLED" -> {
                    echo("job $id ended ${job.path("state").asText()}: ${job.path("reason").asText("")}", err = true)
                    throw ProgramResult(ApiExitCodes.JOB_FAILED)
                }
            }
            Thread.sleep(2_000)
        }
    }
}

class DryRunCommand : SelectionCommand("dry-run") {
    override fun help(context: Context) =
        "Estimate a copy without submitting it: partitions, rows and bytes from DR metadata, and recent copies on the target."

    override fun call(client: ApiClient) {
        val estimate = client.post("/estimates", body())
        print(estimate) {
            buildString {
                for (t in estimate.path("tables")) {
                    appendLine(
                        "%-32s %6d partitions %14d rows %16d bytes".format(
                            t.path("table").asText(),
                            t.path("partitions").asInt(),
                            t.path("rows").asLong(),
                            t.path("bytes").asLong(),
                        ),
                    )
                }
                appendLine(
                    "total ${estimate.path("bytes").asLong()} bytes (whole partitions: an upper bound for COB slices); " +
                        "${estimate.path("queued_jobs").asLong()} ad-hoc jobs queued",
                )
                if (!estimate
                        .path(
                            "source_available",
                        ).asBoolean(true)
                ) {
                    appendLine("source not available now: ${estimate.path("source_reason").asText()}")
                }
                for (r in estimate.path("recent_copies")) {
                    appendLine(
                        "recently copied: ${r.path(
                            "table",
                        ).asText()} ${r.path("cob_date").asText("")} by ${r.path("requester").asText()} at ${r.path("copied_at").asText()}",
                    )
                }
            }.trimEnd()
        }
    }
}

class StatusCommand : ApiCommand("status") {
    override fun help(context: Context) = "Show a job's state, wait reason and progress."

    private val id by argument(help = "job id")
    private val chunks by option("--chunks", help = "also list the job's chunks").flag()

    override fun call(client: ApiClient) {
        val job = client.get("/jobs/$id")
        print(job) { statusLine(job) }
        if (chunks) {
            val list = client.get("/jobs/$id/chunks")
            print(list) {
                list.joinToString("\n") { c ->
                    "  ${c.path(
                        "id",
                    ).asLong()} ${c.path("table").asText()} ${c.path("shard").asText()} ${c.path("partition_id").asText()} " +
                        "${c.path("state").asText()} attempts=${c.path("attempts").asInt()} preemptions=${c.path("preemptions").asInt()}" +
                        (
                            c
                                .path("wait_reason")
                                .asText("")
                                .takeIf { it.isNotEmpty() }
                                ?.let { " waiting: $it" } ?: ""
                        )
                }
            }
        }
    }
}

class JobsCommand : ApiCommand("jobs") {
    override fun help(context: Context) = "List jobs, newest first."

    private val mine by option("--mine", help = "only jobs I requested").flag()
    private val state by option("--state", help = "filter by state, e.g. RUNNING")

    override fun call(client: ApiClient) {
        val query =
            buildList {
                if (mine) add("mine=true")
                state?.let { add("state=${it.uppercase()}") }
            }.joinToString("&")
        val jobs = client.get("/jobs" + if (query.isEmpty()) "" else "?$query")
        print(jobs) { jobs.joinToString("\n") { statusLine(it) } }
    }
}

class CancelCommand : ApiCommand("cancel") {
    override fun help(context: Context) = "Cancel a job: running reads are stopped and staging tables dropped."

    private val id by argument(help = "job id")

    override fun call(client: ApiClient) {
        val job = client.post("/jobs/$id/cancel", null)
        print(job) { statusLine(job) }
    }
}

class RekeyCommand : ApiCommand("rekey") {
    override fun help(context: Context) =
        "After a masking key version change: re-copy everything on a target that was masked with an older key (admin)."

    private val target by option("--target", help = "target environment, e.g. qa1").required()

    override fun call(client: ApiClient) {
        val result = client.post("/admin/rekey", mapOf("target" to target))
        print(result) {
            val jobs = result.path("jobs").map { it.asText() }
            if (jobs.isEmpty()) "nothing on $target needs a rekey" else "rekey jobs: ${jobs.joinToString(" ")}"
        }
    }
}

internal fun statusLine(job: JsonNode): String {
    val p = job.path("progress")
    val wait =
        job
            .path("wait_reason")
            .asText("")
            .takeIf { it.isNotEmpty() }
            ?.let { "; waiting for source: $it" } ?: ""
    val reason =
        job
            .path("reason")
            .asText("")
            .takeIf { it.isNotEmpty() }
            ?.let { "; $it" } ?: ""
    val queue = job.path("queue_position").takeIf { it.isNumber }?.let { "; queue position ${it.asLong()}" } ?: ""
    return "${job.path("id").asText()} ${job.path("state").asText()} ${job.path("target").asText()} " +
        "chunks ${p.path("chunks_done").asInt()}/${p.path("chunks_total").asInt()} " +
        "partitions ${p.path(
            "partitions_done",
        ).asInt()}/${p.path("partitions_total").asInt()} rows ${p.path("rows_copied").asLong()}$queue$wait$reason"
}
