package chrepl.it

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.writeText

/**
 * The typical physical layout (PLAN M9): one ClickHouse cluster of 1 shard × 6 replicas, three in
 * "prod" and three in "DR", with ClickHouse Keeper on the same machines as one 6-node ensemble
 * across both DCs (DR Keepers with `can_become_leader=false`); and a separate nonprod cluster of
 * 1 shard × 3 replicas with its own 3-node Keeper ensemble. The tool is only given DR and nonprod
 * addresses; prod is in `active_dc`, and repl users exist on prod as a honeypot.
 */
class ClusterTopology : AutoCloseable {
    val network = "chrepl-cluster-${UUID.randomUUID().toString().take(6)}"
    private val configDir: Path = Files.createTempDirectory("chrepl-cluster-config")

    val prod: List<ClickHouseNode>
    val dr: List<ClickHouseNode>
    val nonprod: List<ClickHouseNode>

    /** `host:port` of each DR Keeper's client port, published on the DR node's address. */
    val drKeepers: List<String>

    init {
        Docker.run("network", "create", network)
        configDir.toFile().setReadable(true, false)
        val prodNames = (1..3).map { "prod$it" }
        val drNames = (1..3).map { "dr$it" }
        val npNames = (1..3).map { "np$it" }
        prod =
            prodNames.mapIndexed {
                i,
                n,
                ->
                node(n, "127.0.0.7${1 + i}", xml(n, i + 1, prodNames + drNames, learners = drNames, zk = prodNames))
            }
        dr =
            drNames.mapIndexed {
                i,
                n,
                ->
                node(n, "127.0.0.7${4 + i}", xml(n, i + 4, prodNames + drNames, learners = drNames, zk = drNames), keeper = true)
            }
        nonprod = npNames.mapIndexed { i, n -> node(n, "127.0.0.7${7 + i}", xml(n, i + 1, npNames, learners = emptyList(), zk = npNames)) }
        all.forEach { it.start() }
        all.parallelStream().forEach { it.awaitReady() }
        all.parallelStream().forEach { awaitKeeper(it) }
        drKeepers =
            dr.map { node ->
                "${node.bindAddress}:" +
                    Docker
                        .run("port", node.containerId, "9181/tcp")
                        .lineSequence()
                        .first()
                        .substringAfterLast(':')
            }
        provision()
    }

    val all: List<ClickHouseNode> get() = prod + dr + nonprod

    private fun node(
        name: String,
        address: String,
        xml: String,
        keeper: Boolean = false,
    ): ClickHouseNode {
        val file = configDir.resolve("$name.xml")
        file.writeText(xml)
        file.toFile().setReadable(true, false)
        val publish = if (keeper) listOf("-p", "$address::9181") else emptyList()
        return ClickHouseNode(
            name,
            address,
            network,
            listOf("-v", "$file:/etc/clickhouse-server/config.d/it.xml:ro") + publish,
            fixedPort = true,
        )
    }

    /** Embedded Keeper in a [members] ensemble ([learners] cannot lead), and ClickHouse using the Keepers [zk] of its own DC. */
    private fun xml(
        name: String,
        id: Int,
        members: List<String>,
        learners: List<String>,
        zk: List<String>,
    ): String {
        val raft =
            members
                .mapIndexed { i, m ->
                    val learner = if (m in learners) "<can_become_leader>false</can_become_leader>" else ""
                    "<server><id>${i + 1}</id><hostname>$m</hostname><port>9234</port>$learner</server>"
                }.joinToString("")
        val nodes = zk.joinToString("") { "<node><host>$it</host><port>9181</port></node>" }
        return """
            <clickhouse>
              <keeper_server>
                <tcp_port>9181</tcp_port>
                <enable_ipv6>false</enable_ipv6>
                <server_id>$id</server_id>
                <log_storage_path>/var/lib/clickhouse/coordination/log</log_storage_path>
                <snapshot_storage_path>/var/lib/clickhouse/coordination/snapshots</snapshot_storage_path>
                <raft_configuration>$raft</raft_configuration>
              </keeper_server>
              <zookeeper>$nodes</zookeeper>
              <macros><shard>01</shard><replica>$name</replica></macros>
              <interserver_http_host>$name</interserver_http_host>
              <merge_workload>background</merge_workload>
              <mutation_workload>background</mutation_workload>
            </clickhouse>
            """.trimIndent()
    }

    private fun awaitKeeper(node: ClickHouseNode) {
        val deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos()
        while (runCatching { node.scalar("SELECT count() FROM system.zookeeper WHERE path = '/'") }.isFailure) {
            if (System.nanoTime() > deadline) throw IllegalStateException("Keeper not usable from ${node.name}")
            Thread.sleep(500)
        }
    }

    private fun provision() {
        val drDdl =
            Files
                .readString(Path.of(System.getProperty("chrepl.drDdl")))
                .replace("<reader-secret>", READER_PASSWORD)
                .replace("<guard-secret>", GUARD_PASSWORD)
                .replace("10.20.0.0/16", "0.0.0.0/0")
        for (node in prod + dr) {
            listOf("trading", "crm", "refdata").forEach { node.sql("CREATE DATABASE IF NOT EXISTS $it") }
            ReplicatedTopology.statements(drDdl).forEach { node.sql(it) }
            node.sql(
                ItFixtures.TRADES_DDL.replace(
                    "ENGINE = MergeTree",
                    "ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/trading/trades', '{replica}')",
                ),
            )
        }
        val targetDdl =
            Files
                .readString(Path.of(System.getProperty("chrepl.drDdl")).resolveSibling("target-ddl.sql"))
                .replace("<target>", "qa1")
                .replace("<secret>", WRITER_PASSWORD)
                .replace("10.20.0.0/16", "0.0.0.0/0")
        for (node in nonprod) ReplicatedTopology.statements(targetDdl).forEach { node.sql(it) }
    }

    /** Inserts trades on prod1 and waits until every DR replica has them. */
    fun insertOnProd(
        from: String,
        days: Int,
        rows: Int,
        idOffset: Long = 0,
    ) {
        ItFixtures.insertTrades(prod[0], 0, from, days, rows, idOffset)
    }

    fun syncDr() = dr.forEach { it.sql("SYSTEM SYNC REPLICA trading.trades") }

    /** The example config pointed at DR and nonprod: DR replicas and Keepers, qa1 as 1 shard × 3 replicas. */
    fun config(): ObjectNode.() -> Unit =
        {
            val activeDc = child("active_dc")
            activeDc.putArray("hosts").also { a -> prod.forEach { a.add(it.bindAddress) } }
            val source = child("sources").child("dr")
            source.put("dc", "dr").put("tls", false).put("fail_closed_scope", "replica")
            source
                .putArray("shards")
                .addObject()
                .put("name", "s1")
                .putArray("replicas")
                .also { a -> dr.forEach { a.add(it.hostPort) } }
            source.child("keeper").putArray("hosts").also { a -> drKeepers.forEach { a.add(it) } }
            source.child("dc_role_check").putArray("ignored_users").add("default") // the test's DBA
            val qa1 = child("targets").child("qa1")
            qa1
                .put("tls", false)
                .put("shards", 1)
                .put("replicas", 3)
                .put("shard_mapping", "single")
            qa1.putArray("hosts").also { a -> nonprod.forEach { a.add(it.hostPort) } }
            child("targets")
                .child("uat")
                .put("tls", false)
                .putArray("hosts")
                .add("127.0.0.99:8123") // unused
            val guard = child("guard")
            guard.put("poll_interval", "1s").put("stale_after", "4s").put("stable_window", "2s")
            guard
                .child("thresholds")
                .child("replication")
                .put("max_queue_size", 3)
                .put("max_queue_oldest_age", "20s")
                .put("max_replica_delay", "30s")
            guard
                .child("thresholds")
                .child("host")
                .put("max_cpu_utilisation", 1.0)
                .put("max_io_wait", 1.0)
            // Keepers in containers on a shared build machine: generous, the tests are about topology.
            guard
                .child("thresholds")
                .child("keeper")
                .put("max_avg_latency", "2s")
                .put("max_outstanding_requests", 1000)
            guard.putArray("blackout_windows")
            child("masking").child("key").put("ref", "\${env:CHREPL_IT_MASK_KEY}")
            putArray("continuous")
        }

    override fun close() {
        all.forEach { it.close() }
        runCatching { Docker.run("network", "rm", network) }
        configDir.toFile().deleteRecursively()
    }

    companion object {
        const val READER_PASSWORD = "reader-cluster"
        const val GUARD_PASSWORD = "guard-cluster"
        const val WRITER_PASSWORD = "writer-cluster"
    }
}
