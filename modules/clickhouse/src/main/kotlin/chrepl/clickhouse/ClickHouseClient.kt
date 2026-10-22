package chrepl.clickhouse

import chrepl.types.ChType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** A ClickHouse server reached over its HTTP interface. */
data class Endpoint(
    val host: String,
    val port: Int,
    val tls: Boolean,
) {
    val baseUri: URI get() = URI.create("${if (tls) "https" else "http"}://${if (':' in host) "[$host]" else host}:$port/")

    override fun toString() = "$host:$port"
}

/** An error reported by ClickHouse (HTTP status other than 200, or an aborted response). */
class ClickHouseException(
    val code: Int?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Lets another thread abort a running query by closing its connection (SPEC §4.5). */
class Cancellation {
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var stream: InputStream? = null

    @Volatile
    private var future: CompletableFuture<*>? = null

    val isCancelled: Boolean get() = cancelled.get()

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        runCatching { stream?.close() }
        future?.cancel(true)
    }

    internal fun attach(s: InputStream) {
        stream = s
        if (cancelled.get()) runCatching { s.close() }
    }

    internal fun attach(f: CompletableFuture<*>) {
        future = f
        if (cancelled.get()) f.cancel(true)
    }
}

/**
 * Minimal ClickHouse HTTP client: metadata queries as JSON, DDL/DML, streaming `RowBinary` reads
 * and inserts. Never uses an HTTP proxy. Not tied to any particular user: settings that bound
 * a query (SPEC §4.2) are passed per call and enforced server-side by the user's profile.
 */
class ClickHouseClient(
    val endpoint: Endpoint,
    private val user: String,
    private val password: String,
    private val connectTimeout: Duration = Duration.ofSeconds(10),
    private val http: HttpClient = sharedClient(connectTimeout),
) {
    /** Runs a statement and discards its output. */
    fun execute(
        sql: String,
        settings: Map<String, String> = emptyMap(),
        queryId: String? = null,
    ) {
        send(sql, settings, queryId).use { it.readAllBytes() }
    }

    /** Runs a query and returns its rows as JSON objects (`FORMAT JSONEachRow`); for small metadata results. */
    fun queryJson(
        sql: String,
        settings: Map<String, String> = emptyMap(),
        queryId: String? = null,
    ): List<JsonNode> {
        val text =
            send("$sql\nFORMAT JSONEachRow", settings + ("output_format_json_quote_64bit_integers" to "0"), queryId).use {
                String(it.readAllBytes())
            }
        return text
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { MAPPER.readTree(it) }
            .toList()
    }

    /** A single scalar value as text (`FORMAT TSV`, first row and column). */
    fun queryScalar(
        sql: String,
        settings: Map<String, String> = emptyMap(),
    ): String? =
        send("$sql\nFORMAT TSV", settings, null)
            .use { String(it.readAllBytes()) }
            .lineSequence()
            .firstOrNull()
            ?.substringBefore('\t')

    /**
     * Streams the result of [sql] in `RowBinaryWithNamesAndTypes`. [handler] receives the column
     * header and a reader positioned at the first row; the connection is closed afterwards.
     */
    fun <T> queryRows(
        sql: String,
        settings: Map<String, String> = emptyMap(),
        queryId: String? = null,
        cancellation: Cancellation? = null,
        handler: (header: List<Pair<String, ChType>>, reader: RowBinaryReader) -> T,
    ): T {
        val all = settings + ("output_format_binary_write_json_as_string" to "1")
        send("$sql\nFORMAT RowBinaryWithNamesAndTypes", all, queryId, cancellation).use { stream ->
            try {
                val reader = RowBinaryReader(stream)
                return handler(reader.readHeader(), reader)
            } catch (e: IOException) {
                if (cancellation?.isCancelled == true) throw CancelledException("query ${queryId ?: ""} was cancelled")
                // ClickHouse aborts the chunked response when a query fails mid-stream.
                throw ClickHouseException(null, "result stream from $endpoint ended abnormally: ${e.message}", e)
            }
        }
    }

    /**
     * Streams rows into [table] (`INSERT … FORMAT RowBinary`). [writer] runs on the calling
     * thread while the request body is sent; backpressure comes from the pipe between them.
     */
    fun insert(
        table: String,
        columns: List<String>,
        settings: Map<String, String> = emptyMap(),
        queryId: String? = null,
        writer: (RowBinaryWriter) -> Unit,
    ): Long {
        val sql = "INSERT INTO $table (${columns.joinToString { Sql.ident(it) }}) FORMAT RowBinary"
        val pipeIn = PipedInputStream(1 shl 20)
        val pipeOut = PipedOutputStream(pipeIn)
        val request =
            request(mapOf("query" to sql) + settings + ("input_format_binary_read_json_as_string" to "1"), queryId)
                .POST(HttpRequest.BodyPublishers.ofInputStream { pipeIn })
                .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        var rows = 0L
        try {
            RowBinaryWriter(pipeOut).use { w ->
                writer(w)
                w.flush()
                rows = w.rows
            }
        } catch (e: IOException) {
            // The server stopped reading (usually it rejected the insert): report its error below.
            response.cancel(true)
            awaitAndCheck(response)
            throw ClickHouseException(null, "insert into $table on $endpoint failed: ${e.message}", e)
        } catch (e: Exception) {
            runCatching { pipeOut.close() }
            response.cancel(true)
            throw e
        }
        awaitAndCheck(response).use { it.readAllBytes() }
        return rows
    }

    private fun awaitAndCheck(future: CompletableFuture<HttpResponse<InputStream>>): InputStream {
        val response =
            try {
                future.get()
            } catch (e: ExecutionException) {
                throw ClickHouseException(null, "request to $endpoint failed: ${e.cause?.message}", e.cause)
            } catch (e: java.util.concurrent.CancellationException) {
                throw ClickHouseException(null, "request to $endpoint was cancelled", e)
            }
        return checked(response)
    }

    private fun send(
        sql: String,
        settings: Map<String, String>,
        queryId: String?,
        cancellation: Cancellation? = null,
    ): InputStream {
        val request = request(settings, queryId).POST(HttpRequest.BodyPublishers.ofString(sql)).build()
        val future = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
        cancellation?.attach(future)
        val response =
            try {
                future.get()
            } catch (e: ExecutionException) {
                throw ClickHouseException(null, "request to $endpoint failed: ${e.cause?.message}", e.cause)
            } catch (e: java.util.concurrent.CancellationException) {
                throw CancelledException("query ${queryId ?: ""} was cancelled")
            }
        val stream = checked(response)
        cancellation?.attach(stream)
        return stream
    }

    private fun checked(response: HttpResponse<InputStream>): InputStream {
        if (response.statusCode() == 200) return response.body()
        val body = response.body().use { String(it.readNBytes(4096)) }.trim()
        val code =
            response
                .headers()
                .firstValue("X-ClickHouse-Exception-Code")
                .map { it.toIntOrNull() }
                .orElse(null)
        throw ClickHouseException(code, "ClickHouse $endpoint: $body")
    }

    private fun request(
        params: Map<String, String>,
        queryId: String?,
    ): HttpRequest.Builder {
        val query =
            (params + listOfNotNull(queryId?.let { "query_id" to it }))
                .entries
                .joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return HttpRequest
            .newBuilder(endpoint.baseUri.resolve(if (query.isEmpty()) "" else "?$query"))
            .header("X-ClickHouse-User", user)
            .header("X-ClickHouse-Key", password)
    }

    override fun toString() = "ClickHouseClient($endpoint, user=$user)"

    companion object {
        private val MAPPER = ObjectMapper()
        private val shared = mutableMapOf<Duration, HttpClient>()

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

        @Synchronized
        fun sharedClient(connectTimeout: Duration): HttpClient =
            shared.getOrPut(connectTimeout) {
                HttpClient
                    .newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .proxy(HttpClient.Builder.NO_PROXY)
                    .connectTimeout(connectTimeout)
                    .build()
            }
    }
}

class CancelledException(
    message: String,
) : Exception(message)

/** SQL quoting helpers. */
object Sql {
    fun ident(name: String): String = "`" + name.replace("\\", "\\\\").replace("`", "\\`") + "`"

    /** `db.table` → `` `db`.`table` ``. */
    fun table(qualified: String): String = qualified.split('.', limit = 2).joinToString(".") { ident(it) }

    fun string(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
