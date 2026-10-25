package chrepl.it

import chrepl.clickhouse.ClickHouseClient
import chrepl.clickhouse.Endpoint
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Runs `docker` and returns stdout; fails with stderr on a non-zero exit. */
internal object Docker {
    fun run(vararg args: String): String {
        val process = ProcessBuilder(listOf("docker") + args).redirectErrorStream(false).start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        if (!process.waitFor(5, TimeUnit.MINUTES) || process.exitValue() != 0) {
            throw IllegalStateException("docker ${args.joinToString(" ")} failed: $err")
        }
        return out.trim()
    }

    val image: String = System.getProperty("chrepl.it.image") ?: "clickhouse/clickhouse-server:25.8"
}

/**
 * A ClickHouse server in Docker, published on its own loopback address so that hosts differ in
 * configs (the validator rejects a target that is also a source host).
 */
class ClickHouseNode(
    val name: String,
    val bindAddress: String,
    private val network: String? = null,
    private val extraArgs: List<String> = emptyList(),
) : AutoCloseable {
    private val containerName = "chrepl-it-$name-${UUID.randomUUID().toString().take(6)}"
    lateinit var containerId: String
        private set
    var port: Int = 0
        private set

    fun start(): ClickHouseNode {
        val args =
            mutableListOf(
                "run",
                "-d",
                "--name",
                containerName,
                "-p",
                "$bindAddress::8123",
                "-e",
                "CLICKHOUSE_SKIP_USER_SETUP=1",
                "-e",
                "CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1",
            )
        network?.let { args += listOf("--network", it, "--network-alias", name, "--hostname", name) }
        args += extraArgs
        args += Docker.image
        containerId = Docker.run(*args.toTypedArray())
        port =
            Docker
                .run("port", containerId, "8123/tcp")
                .lineSequence()
                .first()
                .substringAfterLast(':')
                .toInt()
        return this
    }

    fun awaitReady(timeout: Duration = Duration.ofSeconds(90)): ClickHouseNode {
        val http =
            HttpClient
                .newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(Duration.ofSeconds(2))
                .build()
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            val ok =
                runCatching {
                    http
                        .send(
                            HttpRequest.newBuilder(URI.create("http://$bindAddress:$port/ping")).build(),
                            HttpResponse.BodyHandlers.ofString(),
                        ).body()
                        .startsWith("Ok")
                }.getOrDefault(false)
            if (ok && runCatching { client().queryScalar("SELECT 1") == "1" }.getOrDefault(false)) return this
            Thread.sleep(250)
        }
        throw IllegalStateException(
            "ClickHouse $name did not become ready: ${runCatching { Docker.run("logs", "--tail", "30", containerId) }.getOrNull()}",
        )
    }

    val hostPort: String get() = "$bindAddress:$port"

    fun client(user: String = "default"): ClickHouseClient = ClickHouseClient(Endpoint(bindAddress, port, false), user, "")

    fun sql(statement: String) = client().execute(statement)

    fun scalar(query: String): String = client().queryScalar(query) ?: ""

    override fun close() {
        runCatching { Docker.run("rm", "-f", containerId) }
    }
}

/** Shared containers for the whole test JVM (started once, removed on exit). */
object ItCluster {
    /** Source "DR" shards (standalone servers, `dc: test`). */
    val sources: List<ClickHouseNode> by lazy { startAll((1..3).map { ClickHouseNode("dr-s$it", "127.0.0.1$it") }) }

    /** A 3-shard target (`same_shards`). */
    val qa: List<ClickHouseNode> by lazy { startAll((1..3).map { ClickHouseNode("qa-s$it", "127.0.0.2$it") }) }

    /** A single-node target (`single`). */
    val uat: ClickHouseNode by lazy { startAll(listOf(ClickHouseNode("uat", "127.0.0.31"))).single() }

    private val started = mutableListOf<ClickHouseNode>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { synchronized(started) { started.forEach { it.close() } } })
    }

    private fun startAll(nodes: List<ClickHouseNode>): List<ClickHouseNode> {
        nodes.forEach {
            it.start()
            synchronized(started) { started += it }
        }
        nodes.parallelStream().forEach { it.awaitReady() }
        return nodes
    }
}
