package chrepl.it

import chrepl.config.load.ConfigLoader
import chrepl.config.model.ReplicatorConfig
import chrepl.config.model.SecretRef
import chrepl.config.validate.ConfigValidator
import chrepl.config.validate.SecretReader
import chrepl.config.validate.Severity
import chrepl.masking.MaskingEngine
import chrepl.masking.keys.StaticKeyProvider
import chrepl.transfer.ClientFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path
import java.util.HexFormat
import kotlin.test.assertTrue

object ItFixtures {
    val examplePath: Path = Path.of(System.getProperty("chrepl.exampleConfig"))
    val maskingKey: String = HexFormat.of().formatHex(ByteArray(32) { (it * 7).toByte() })

    /** Secrets for tests: the masking key from `${env:...MASK...}`, empty passwords otherwise. */
    val secrets = SecretReader { ref -> if (ref.kind == SecretRef.Kind.ENV && "MASK" in ref.target) maskingKey else "" }
    val clients = ClientFactory(secrets)

    /** The example config, pointed at the test containers; validated before use. */
    fun config(mutate: ObjectNode.() -> Unit = {}): ReplicatorConfig = bindValid(configTree(mutate))

    fun bindValid(tree: ObjectNode): ReplicatorConfig {
        val loaded = ConfigLoader.bind(tree)
        val config = loaded.config ?: error("test config does not bind: ${loaded.issues}")
        val errors = ConfigValidator().validate(config, examplePath.parent).issues.filter { it.severity == Severity.ERROR }
        assertTrue(errors.isEmpty(), "test config is invalid: $errors")
        return config
    }

    /** The example config tree pointed at the test containers. */
    fun configTree(mutate: ObjectNode.() -> Unit = {}): ObjectNode {
        val tree = ConfigLoader.readTree(examplePath)
        val dr = tree.child("sources").child("dr")
        dr.put("dc", "test")
        dr.put("tls", false)
        dr.get("shards").forEachIndexed { i, shard -> (shard as ObjectNode).put("host", ItCluster.sources[i].hostPort) }
        val qa1 = tree.child("targets").child("qa1")
        qa1.remove("zookeeper_path_template")
        qa1.put("tls", false)
        qa1.putArray("hosts").also { a -> ItCluster.qa.forEach { a.add(it.hostPort) } }
        val uat = tree.child("targets").child("uat")
        uat.remove("zookeeper_path_template")
        uat.put("tls", false)
        uat.putArray("hosts").add(ItCluster.uat.hostPort)
        tree.child("masking").child("key").put("ref", "\${env:CHREPL_IT_MASK_KEY}")
        tree.mutate()
        return tree
    }

    fun engine(config: ReplicatorConfig): MaskingEngine =
        MaskingEngine.create(config.masking, StaticKeyProvider(config.masking, secrets), examplePath.parent)

    const val TRADES_DDL =
        """
        CREATE TABLE trading.trades (
            trade_id UInt64, trade_date Date, inserted_at DateTime64(3), instrument_id UInt32, customer_id UInt64,
            account_number String, counterparty_name LowCardinality(String), quantity Int64, price Decimal(18, 6),
            notional Decimal(18, 2), trader_comment Nullable(String), tags Array(LowCardinality(String)),
            attributes Map(String, String)
        ) ENGINE = MergeTree PARTITION BY toYYYYMM(trade_date) ORDER BY trade_id
        SETTINGS index_granularity = 1024
        """

    /** Inserts [rows] trades per day for [days] days from [from], with ids offset by shard. */
    fun insertTrades(
        node: ClickHouseNode,
        shard: Int,
        from: String,
        days: Int,
        rows: Int,
        idOffset: Long = 0,
    ) {
        node.sql(
            """
            INSERT INTO trading.trades
            SELECT
                $idOffset + ${shard * 10_000_000L} + number AS trade_id,
                toDate('$from') + intDiv(number, $rows) AS trade_date,
                toDateTime64('2026-10-01 00:00:00', 3) + number AS inserted_at,
                number % 500 AS instrument_id,
                100000 + number % 2000 AS customer_id,
                concat('GB29NWBK6016', leftPad(toString(number % 2000), 8, '0')) AS account_number,
                ['Northwind Capital', 'Cobalt Capital', 'Meridian Group'][1 + number % 3] AS counterparty_name,
                toInt64(number % 1000) - 500 AS quantity,
                toDecimal64(101.25, 6) AS price,
                toDecimal64(number % 100000, 2) / 100 AS notional,
                if(number % 4 = 0, concat('call client ', toString(number)), NULL) AS trader_comment,
                ['fx', 'spot'] AS tags,
                map('client_email', concat('client', toString(number % 2000), '@bank.com'), 'desk', 'rates') AS attributes
            FROM numbers(${days * rows})
            """.trimIndent(),
        )
    }

    /** Prepares a clean source: trading.trades (plus crm/refdata tables used by datasets) on every shard. */
    fun resetSources() {
        for (node in ItCluster.sources) {
            node.sql("DROP DATABASE IF EXISTS trading SYNC")
            node.sql("CREATE DATABASE trading")
            node.sql(TRADES_DDL)
            // As deploy/dr-ddl.sql would, minus the profile limits (added with the guard in M3).
            node.sql("CREATE USER IF NOT EXISTS repl_reader IDENTIFIED WITH no_password")
            node.sql("GRANT SELECT ON trading.* TO repl_reader")
            node.sql("GRANT SELECT ON system.* TO repl_reader")
        }
    }

    /** The writer's least privileges on a target, as in deploy/target-ddl.sql. */
    private val TARGET_GRANTS =
        listOf(
            "CREATE USER IF NOT EXISTS repl_writer IDENTIFIED WITH no_password",
            "GRANT SELECT ON repl_meta.environment TO repl_writer",
            "GRANT CREATE TABLE, SELECT, INSERT ON repl_meta.tables TO repl_writer",
            "GRANT CREATE TABLE, SELECT, INSERT ON repl_meta.copy_history TO repl_writer",
            "GRANT CREATE DATABASE, ALL ON trading.* TO repl_writer",
            "GRANT SELECT ON system.* TO repl_writer",
        )

    /** Drops copied databases and recreates the DBA-owned environment marker on every target node. */
    fun resetTargets() {
        for ((name, nodes) in listOf("qa1" to ItCluster.qa, "uat" to listOf(ItCluster.uat))) {
            for (node in nodes) {
                node.sql("DROP DATABASE IF EXISTS trading SYNC")
                node.sql("DROP DATABASE IF EXISTS repl_meta SYNC")
                node.sql("CREATE DATABASE repl_meta")
                node.sql("CREATE TABLE repl_meta.environment (value String) ENGINE = MergeTree ORDER BY tuple()")
                node.sql("INSERT INTO repl_meta.environment VALUES ('$name')")
                TARGET_GRANTS.forEach { node.sql(it) }
            }
        }
    }
}

internal fun ObjectNode.child(name: String): ObjectNode = get(name) as ObjectNode
