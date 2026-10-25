package chrepl.it

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.writeText

/**
 * PLAN §1.1: Keeper + an active "prod" node + a passive "DR" node replicating the same
 * `ReplicatedMergeTree` tables, on one Docker network. The tool is only ever given DR (and target)
 * addresses; prod is in `active_dc`. Repl users are also created on prod as a honeypot, so any
 * accidental contact would succeed and appear in prod's `system.query_log`.
 */
class ReplicatedTopology : AutoCloseable {
    val network = "chrepl-it-${UUID.randomUUID().toString().take(6)}"
    private val configDir: Path = Files.createTempDirectory("chrepl-it-config")

    val keeper: ClickHouseNode
    val prod: ClickHouseNode
    val dr: ClickHouseNode

    init {
        Docker.run("network", "create", network)
        configDir.toFile().setReadable(true, false)
        keeper = node("keeper", "127.0.0.43", keeperXml())
        prod = node("prod", "127.0.0.42", replicaXml("prod"))
        dr = node("dr", "127.0.0.41", replicaXml("dr"))
        listOf(keeper, prod, dr).forEach { it.start() }
        listOf(keeper, prod, dr).parallelStream().forEach { it.awaitReady() }
        provision()
    }

    private fun node(
        name: String,
        address: String,
        xml: String,
    ): ClickHouseNode {
        val file = configDir.resolve("$name.xml")
        file.writeText(xml)
        file.toFile().setReadable(true, false)
        return ClickHouseNode(name, address, network, listOf("-v", "$file:/etc/clickhouse-server/config.d/it.xml:ro"))
    }

    private fun keeperXml() =
        """
        <clickhouse>
          <keeper_server>
            <tcp_port>9181</tcp_port>
            <enable_ipv6>false</enable_ipv6>
            <server_id>1</server_id>
            <log_storage_path>/var/lib/clickhouse/coordination/log</log_storage_path>
            <snapshot_storage_path>/var/lib/clickhouse/coordination/snapshots</snapshot_storage_path>
            <raft_configuration><server><id>1</id><hostname>keeper</hostname><port>9234</port></server></raft_configuration>
          </keeper_server>
        </clickhouse>
        """.trimIndent()

    private fun replicaXml(name: String) =
        """
        <clickhouse>
          <zookeeper><node><host>keeper</host><port>9181</port></node></zookeeper>
          <macros><shard>01</shard><replica>$name</replica></macros>
          <interserver_http_host>$name</interserver_http_host>
          <merge_workload>background</merge_workload>
          <mutation_workload>background</mutation_workload>
        </clickhouse>
        """.trimIndent()

    /** The DBA steps: users from deploy/dr-ddl.sql on DR (and on prod, as a honeypot), plus a test app user. */
    private fun provision() {
        val ddl =
            Files
                .readString(Path.of(System.getProperty("chrepl.drDdl")))
                .replace("<reader-secret>", READER_PASSWORD)
                .replace("<guard-secret>", GUARD_PASSWORD)
                .replace("10.20.0.0/16", "0.0.0.0/0")
                // Slow reads down so that a copy is still running when a test makes DR busy.
                .replace(
                    "max_local_read_bandwidth = 50000000 MAX 50000000",
                    "max_local_read_bandwidth = $TEST_BANDWIDTH MAX $TEST_BANDWIDTH",
                )
        for (node in listOf(dr, prod)) {
            node.sql("CREATE DATABASE IF NOT EXISTS trading")
            node.sql("CREATE DATABASE IF NOT EXISTS crm")
            node.sql("CREATE DATABASE IF NOT EXISTS refdata")
            statements(ddl).forEach { node.sql(it) }
        }
        dr.sql("CREATE USER IF NOT EXISTS trading_app IDENTIFIED WITH no_password")
        dr.sql("GRANT SELECT ON *.* TO trading_app")
        for (node in listOf(prod, dr)) {
            node.sql(
                ItFixtures.TRADES_DDL.replace(
                    "ENGINE = MergeTree",
                    "ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/trading/trades', '{replica}')",
                ),
            )
        }
    }

    /**
     * The example config pointed at these containers: `dc: dr` source on DR, prod on the active-DC
     * deny-list, qa1 as one shard, fast guard timings.
     */
    fun config(): ObjectNode.() -> Unit =
        {
            ((get("active_dc") as ObjectNode).get("hosts") as ArrayNode).add("127.0.0.42") // prod
            val source = child("sources").child("dr")
            source.put("dc", "dr")
            source
                .putArray("shards")
                .addObject()
                .put("name", "s1")
                .put("host", this@ReplicatedTopology.dr.hostPort)
            source
                .child("dc_role_check")
                .putArray("ignored_users")
                .add("monitoring")
                .add("default") // default = the test's DBA
            val qa1 = child("targets").child("qa1")
            qa1.putArray("hosts").add(ItCluster.qa[0].hostPort)
            qa1.put("shards", 1).put("shard_mapping", "single")
            val guard = child("guard")
            guard.put("poll_interval", "1s").put("stale_after", "3s").put("stable_window", "2s")
            guard
                .child("thresholds")
                .child("replication")
                .put("max_queue_size", 3)
                .put("max_queue_oldest_age", "20s")
            // Host-wide CPU/IO counters include the build machine; these tests are about replication and DR activity.
            guard
                .child("thresholds")
                .child("host")
                .put("max_cpu_utilisation", 1.0)
                .put("max_io_wait", 1.0)
            guard.putArray("blackout_windows")
            guard.child("concurrency").put("global", 1)
        }

    /** Waits until DR has every part prod has. */
    fun awaitReplicated() {
        dr.sql("SYSTEM SYNC REPLICA trading.trades")
    }

    override fun close() {
        listOf(dr, prod, keeper).forEach { it.close() }
        runCatching { Docker.run("network", "rm", network) }
        configDir.toFile().deleteRecursively()
    }

    companion object {
        const val READER_PASSWORD = "reader-it"
        const val GUARD_PASSWORD = "guard-it"
        const val TEST_BANDWIDTH = 300_000

        /** Splits a SQL script into statements, dropping `--` comments. */
        fun statements(script: String): List<String> =
            script
                .lineSequence()
                .map { it.substringBefore("--").trimEnd() }
                .joinToString("\n")
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
    }
}
