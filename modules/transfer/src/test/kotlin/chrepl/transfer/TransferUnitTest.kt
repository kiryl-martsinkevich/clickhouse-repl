package chrepl.transfer

import chrepl.config.model.ColumnPolicy
import chrepl.config.model.Credentials
import chrepl.config.model.DataSize
import chrepl.config.model.HostPort
import chrepl.config.model.SecretRef
import chrepl.config.model.ShardMapping
import chrepl.config.model.TableOverrides
import chrepl.config.model.TableSpec
import chrepl.config.model.Target
import chrepl.types.ChType
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DdlRewriterTest {
    private val replicated =
        """
        CREATE TABLE trading.trades
        (
            `trade_date` Date,
            `id` UInt64,
            `note` String DEFAULT 'a, b'
        )
        ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/trading/trades', '{replica}', id)
        PARTITION BY toYYYYMM(trade_date)
        ORDER BY (id, trade_date)
        SETTINGS index_granularity = 8192, storage_policy = 'tiered'
        """.trimIndent()

    private fun info(create: String) =
        TableInfo("trading", "trades", "ReplicatedReplacingMergeTree", "toYYYYMM(trade_date)", listOf("id"), emptyList(), create)

    private fun target(
        template: String? = null,
        policy: String? = null,
    ) = Target(
        hosts = listOf(HostPort.parse("qa:8123")),
        clusterName = "qa",
        shardMapping = ShardMapping.SINGLE,
        writer = Credentials("w", SecretRef.parse("\${env:X}")),
        environmentMarker = "qa",
        zookeeperPathTemplate = template,
        tableOverrides = policy?.let { TableOverrides(storagePolicy = it) },
    )

    @Test
    fun `replicated engine becomes non-replicated without a template, keeping engine arguments`() {
        val ddl = DdlRewriter.forTarget(info(replicated), target())
        assertContains(ddl, "CREATE TABLE IF NOT EXISTS `trading`.`trades`")
        assertContains(ddl, "ENGINE = ReplacingMergeTree(id)")
        assertContains(ddl, "DEFAULT 'a, b'")
        assertContains(ddl, "PARTITION BY toYYYYMM(trade_date)")
    }

    @Test
    fun `replicated engine uses the target path template`() {
        val ddl = DdlRewriter.forTarget(info(replicated), target("/clickhouse/qa/tables/{shard}/{database}/{table}", policy = "default"))
        assertContains(ddl, "ENGINE = ReplicatedReplacingMergeTree('/clickhouse/qa/tables/{shard}/trading/trades', '{replica}', id)")
        assertContains(ddl, "storage_policy = 'default'")
        assertFalse("tiered" in ddl)
    }

    @Test
    fun `staging tables are never replicated`() {
        val ddl = DdlRewriter.forStaging(replicated, "trading", "trades__stg_abcd1234_0")
        assertContains(ddl, "`trading`.`trades__stg_abcd1234_0`")
        assertContains(ddl, "ENGINE = ReplacingMergeTree(id)")
        val plain =
            DdlRewriter.forStaging(
                replicated.replace(
                    "ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/trading/trades', '{replica}', id)",
                    "MergeTree",
                ),
                "trading",
                "s",
            )
        assertContains(plain, "ENGINE = MergeTree()")
    }
}

class SourceQueriesTest {
    private val spec =
        TableSpec(
            source = "trading.trades",
            cobColumn = "trade_date",
            maxFullCopyBytes = null,
            columns =
                mapOf(
                    "trade_date" to ColumnPolicy.Pass,
                ),
        )

    @Test
    fun `reads are pinned to a partition with typed parameters`() {
        val chunk =
            Chunk(
                "s1-202610-0",
                "s1",
                "202610",
                Selection.Cob(listOf(LocalDate.of(2026, 10, 19))),
                KeyRange("id", ChType.IntType(64, false), "100", null),
                0,
            )
        val read = SourceQueries.read(spec, listOf("trade_date", "id"), chunk)
        assertEquals(
            "SELECT `trade_date`, `id` FROM `trading`.`trades` WHERE _partition_id = {pid:String} " +
                "AND `trade_date` IN {cobs:Array(Date)} AND `id` >= {lo:UInt64}",
            read.sql,
        )
        assertEquals(mapOf("param_pid" to "202610", "param_cobs" to "['2026-10-19']", "param_lo" to "100"), read.params)
        assertEquals("1", SourceQueries.readSettings(fullCopy = false)["force_index_by_date"])
        assertEquals("chrepl-j1-s1-202610-0-2", SourceQueries.queryId("j1", chunk.id, 2))
    }

    @Test
    fun `full copies still read partition by partition`() {
        val read =
            SourceQueries.read(
                spec.copy(cobColumn = null, fullCopy = true, maxFullCopyBytes = DataSize.parse("1GiB")),
                listOf("a"),
                Chunk("c", "s", "all", Selection.All, null, 0),
            )
        assertEquals("SELECT `a` FROM `trading`.`trades` WHERE _partition_id = {pid:String}", read.sql)
    }

    @Test
    fun `whole-partition reads of a COB table carry the partition's date range for force_index_by_date`() {
        val bounds = DateBounds("trade_date", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
        val chunk = Chunk("c", "s1", "202610", Selection.Partitions(setOf("202610")), null, 0, bounds)
        val read = SourceQueries.read(spec, listOf("trade_date"), chunk)
        assertEquals(
            "SELECT `trade_date` FROM `trading`.`trades` WHERE _partition_id = {pid:String} AND `trade_date` BETWEEN {dlo:Date} AND {dhi:Date}",
            read.sql,
        )
        assertEquals(mapOf("param_pid" to "202610", "param_dlo" to "2026-10-01", "param_dhi" to "2026-10-31"), read.params)
    }
}
