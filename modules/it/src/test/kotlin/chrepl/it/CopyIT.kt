package chrepl.it

import chrepl.clickhouse.Cancellation
import chrepl.clickhouse.ClickHouseException
import chrepl.masking.MaskingConfigException
import chrepl.transfer.CopyJob
import chrepl.transfer.Janitor
import chrepl.transfer.ReadGate
import chrepl.transfer.TargetSafetyException
import chrepl.transfer.UnguardedGate
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** PLAN M2 exit criteria, against real ClickHouse servers. */
class CopyIT {
    private val oct1 = LocalDate.of(2026, 10, 1)
    private val oct2 = LocalDate.of(2026, 10, 2)

    @BeforeEach
    fun reset() {
        ItFixtures.resetSources()
        ItFixtures.resetTargets()
        ItCluster.sources.forEachIndexed { i, node ->
            ItFixtures.insertTrades(node, shard = i, from = "2026-09-29", days = 6, rows = 1_000) // Sep 29 – Oct 4
        }
    }

    private fun job(
        target: String = "qa1",
        config: chrepl.config.model.ReplicatorConfig = ItFixtures.config(),
        gate: ReadGate = UnguardedGate,
    ) = CopyJob(config, target, ItFixtures.engine(config), gate, ItFixtures.clients)

    private fun count(
        node: ClickHouseNode,
        where: String = "1",
    ) = node.scalar("SELECT count() FROM trading.trades WHERE $where").toLong()

    private fun checksum(node: ClickHouseNode) = node.scalar("SELECT groupBitXor(cityHash64(*)) FROM trading.trades")

    @Test
    fun `a COB slice replaces only that date inside a monthly partition`() {
        job().copy("trading.trades", listOf(oct1, oct2))
        val before = ItCluster.qa.map { count(it, "trade_date = '$oct1'") }
        assertEquals(listOf(1_000L, 1_000L, 1_000L), before)
        // The source gains rows for Oct 2; copying only Oct 2 must keep Oct 1 untouched.
        ItCluster.sources.forEachIndexed { i, node -> ItFixtures.insertTrades(node, i, "2026-10-02", 1, 250, idOffset = 5_000_000) }
        val result = job().copy("trading.trades", listOf(oct2))
        assertEquals(3 * 1_250L, result.rowsCopied)
        assertEquals(before, ItCluster.qa.map { count(it, "trade_date = '$oct1'") })
        assertEquals(listOf(1_250L, 1_250L, 1_250L), ItCluster.qa.map { count(it, "trade_date = '$oct2'") })
        assertEquals(listOf(0L, 0L, 0L), ItCluster.qa.map { count(it, "trade_date NOT IN ('$oct1', '$oct2')") })
    }

    @Test
    fun `masked columns are masked and pass columns are copied`() {
        job().copy("trading.trades", listOf(oct1))
        val source = ItCluster.sources[0]
        val target = ItCluster.qa[0]
        val q =
            "SELECT trade_id, customer_id, account_number, quantity, trader_comment FROM trading.trades " +
                "WHERE trade_date = '$oct1' ORDER BY trade_id LIMIT 5"
        val sourceRows = source.client().queryJson(q)
        val targetRows = target.client().queryJson(q)
        assertEquals(sourceRows.map { it["trade_id"] }, targetRows.map { it["trade_id"] })
        assertEquals(sourceRows.map { it["quantity"] }, targetRows.map { it["quantity"] })
        for ((s, t) in sourceRows.zip(targetRows)) {
            assertNotEquals(s["customer_id"], t["customer_id"])
            assertNotEquals(s["account_number"].textValue(), t["account_number"].textValue())
            assertEquals(s["account_number"].textValue().length, t["account_number"].textValue().length)
            assertTrue(t["trader_comment"].isNull) // free_text -> null
        }
        // The same customer masks to the same value on every shard.
        val masked =
            ItCluster.qa.map {
                it.scalar(
                    "SELECT customer_id FROM trading.trades WHERE trade_id % 10000000 = 7 AND trade_date = '$oct1'",
                )
            }
        assertEquals(1, masked.toSet().size)
        // Bookkeeping on the target.
        assertEquals("1", target.scalar("SELECT key_version FROM repl_meta.tables FINAL WHERE table = 'trades'"))
        assertTrue(target.scalar("SELECT count() FROM repl_meta.copy_history").toInt() >= 1)
    }

    @Test
    fun `3 source shards map to 3 target shards or to one node`() {
        job("qa1").copy("trading.trades", listOf(oct1, oct2))
        ItCluster.sources.indices.forEach { i ->
            assertEquals(count(ItCluster.sources[i], "trade_date IN ('$oct1', '$oct2')"), count(ItCluster.qa[i]))
        }
        job("uat").copy("trading.trades", listOf(oct1, oct2))
        assertEquals(6_000L, count(ItCluster.uat))
    }

    @Test
    fun `re-running a copy gives an identical target`() {
        job().copy("trading.trades", listOf(oct1, oct2))
        val first = ItCluster.qa.map { checksum(it) to count(it) }
        job().copy("trading.trades", listOf(oct1, oct2))
        assertEquals(first, ItCluster.qa.map { checksum(it) to count(it) })
    }

    @Test
    fun `large partitions are split into key ranges`() {
        ItCluster.sources.forEachIndexed { i, node -> ItFixtures.insertTrades(node, i, "2026-12-01", 1, 60_000, idOffset = 7_000_000) }
        val dec1 = LocalDate.of(2026, 12, 1)
        val config = ItFixtures.config { (get("tables").get(0) as ObjectNode).child("chunk").put("max_bytes", "64KB") }
        val result = job(config = config).copy("trading.trades", listOf(dec1))
        assertTrue(result.chunks > 6, "expected split chunks, got ${result.chunks}")
        assertEquals(listOf(60_000L, 60_000L, 60_000L), ItCluster.qa.map { count(it, "trade_date = '$dec1'") })
        assertEquals(60_000L, ItCluster.qa[0].scalar("SELECT uniqExact(trade_id) FROM trading.trades WHERE trade_date = '$dec1'").toLong())
    }

    @Test
    fun `a copy killed mid-chunk leaves the target unchanged and a re-run completes`() {
        ItCluster.sources.forEachIndexed { i, node -> ItFixtures.insertTrades(node, i, "2026-11-01", 1, 400_000, idOffset = 9_000_000) }
        val nov1 = LocalDate.of(2026, 11, 1)
        val killed = AtomicBoolean(false)
        val killer =
            object : ReadGate {
                override fun <T> withPermit(
                    host: String,
                    queryId: String,
                    read: (Cancellation) -> T,
                ): T {
                    val cancellation = Cancellation()
                    if (killed.compareAndSet(false, true)) {
                        Thread {
                            Thread.sleep(150)
                            cancellation.cancel()
                        }.start()
                    }
                    return read(cancellation)
                }
            }
        assertFailsWith<Exception> { job(gate = killer).copy("trading.trades", listOf(nov1)) }
        assertEquals(0L, count(ItCluster.qa[0], "trade_date = '$nov1'"))
        job().copy("trading.trades", listOf(nov1))
        assertEquals(400_000L, count(ItCluster.qa[0], "trade_date = '$nov1'"))
        assertEquals(400_000L, ItCluster.qa[0].scalar("SELECT uniqExact(trade_id) FROM trading.trades WHERE trade_date = '$nov1'").toLong())
        // Staging tables left by the killed run are orphans the janitor removes.
        val dropped = ItCluster.qa.flatMap { Janitor.dropOrphanStaging(it.client(), Duration.ZERO) }
        assertTrue(ItCluster.qa.all { it.scalar("SELECT count() FROM system.tables WHERE name LIKE '%__stg_%'") == "0" }, "left: $dropped")
    }

    @Test
    fun `a new column at the source blocks the copy as unclassified`() {
        ItCluster.sources.forEach { it.sql("ALTER TABLE trading.trades ADD COLUMN client_ssn String DEFAULT ''") }
        val e = assertFailsWith<MaskingConfigException> { job().copy("trading.trades", listOf(oct1)) }
        assertContains(e.message!!, "'client_ssn' of trading.trades has no masking policy")
    }

    @Test
    fun `a wrong environment marker refuses every write`() {
        ItCluster.qa[1].sql("TRUNCATE TABLE repl_meta.environment")
        ItCluster.qa[1].sql("INSERT INTO repl_meta.environment VALUES ('prod')")
        val e = assertFailsWith<TargetSafetyException> { job().copy("trading.trades", listOf(oct1)) }
        assertContains(e.message!!, "expected 'qa1'")
        ItCluster.qa[2].sql("DROP TABLE repl_meta.environment")
        assertFailsWith<TargetSafetyException> { job().copy("trading.trades", listOf(oct1)) }
        assertEquals(0L, ItCluster.qa[0].scalar("SELECT count() FROM system.tables WHERE database = 'trading'").toLong())
    }

    @Test
    fun `a different masking key version is refused`() {
        job().copy("trading.trades", listOf(oct1))
        val v2 = ItFixtures.config { child("masking").child("key").put("version", 2) }
        assertFailsWith<TargetSafetyException> { job(config = v2).copy("trading.trades", listOf(oct1)) }
    }

    @Test
    fun `an unguarded copy from a DR source is refused`() {
        val config = ItFixtures.config { child("sources").child("dr").put("dc", "dr") }
        assertFailsWith<IllegalArgumentException> { job(config = config) }
    }

    @Test
    fun `a query failing mid-stream is an error, not a short result`() {
        val client = ItCluster.sources[0].client()
        assertFailsWith<ClickHouseException> {
            client.queryRows(
                "SELECT throwIf(number = 50000) AS x FROM numbers(1000000)",
                mapOf("max_block_size" to "1000"),
            ) { header, reader ->
                var n = 0
                while (reader.hasMore()) {
                    reader.readRow(header.map { it.second })
                    n++
                }
                n
            }
        }
    }
}
