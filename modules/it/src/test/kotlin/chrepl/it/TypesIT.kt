package chrepl.it

import chrepl.config.load.ConfigLoader
import chrepl.transfer.CopyJob
import chrepl.transfer.UnguardedGate
import com.fasterxml.jackson.databind.node.ArrayNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Every supported column type survives decode → mask → encode through real servers. */
class TypesIT {
    private val columns =
        listOf(
            "id UInt64",
            "i8 Int8",
            "u16 UInt16",
            "i128 Int128",
            "u256 UInt256",
            "f32 Float32",
            "f64 Float64",
            "dec9 Decimal(9, 3)",
            "dec38 Decimal(38, 10)",
            "b Bool",
            "s String",
            "fs FixedString(8)",
            "d Date",
            "d32 Date32",
            "dt DateTime('UTC')",
            "dt64 DateTime64(6, 'UTC')",
            "u UUID",
            "ip4 IPv4",
            "ip6 IPv6",
            "e Enum8('buy' = 1, 'sell' = -2)",
            "ns Nullable(String)",
            "lc LowCardinality(Nullable(String))",
            "arr Array(Nullable(UInt32))",
            "m Map(String, Array(Int32))",
            "t Tuple(a String, b Date)",
            "j JSON",
            "bin String",
            "secret String",
            "doc String",
        )
    private val passColumns = columns.map { it.substringBefore(' ') } - setOf("secret", "doc", "j")

    private val spec =
        (
            listOf("source: trading.alltypes", "full_copy: true", "max_full_copy_bytes: 1GiB", "columns:") +
                passColumns.map { "  $it: pass" } +
                listOf(
                    "  j:",
                    "    json_paths:",
                    "      $.contact.email: { mask: email }",
                    "    default: pass",
                    "  secret: { mask: account_number }",
                    "  doc:",
                    "    json_paths:",
                    "      $.card: { mask: account_number }",
                    "    default: pass",
                )
        ).joinToString("\n")

    @Test
    fun `all types round-trip and masked columns change`() {
        ItFixtures.resetSources()
        ItFixtures.resetTargets()
        for (node in ItCluster.sources) {
            node.sql("CREATE TABLE trading.alltypes (${columns.joinToString()}) ENGINE = MergeTree ORDER BY id")
            node.sql(
                """
                INSERT INTO trading.alltypes SELECT
                    number, toInt8(number % 256 - 128), number % 65536, toInt128(number) * toInt128('-1000000000000000000000'),
                    toUInt256(number) * toUInt256('1000000000000000000000000000000'), number / 3, number / 7, toDecimal32(number / 8, 3),
                    toDecimal128(number / 9, 10), number % 2 = 0, concat('héllo ', toString(number)), leftPad(toString(number), 8, 'x'),
                    toDate('2026-01-01') + number, toDate32('1901-01-01') + number, toDateTime('2026-01-01 00:00:00', 'UTC') + number,
                    toDateTime64('1969-12-31 23:59:59.5', 6, 'UTC') + number, generateUUIDv4(number), toIPv4(number),
                    toIPv6(concat('2001:db8::', hex(number))), if(number % 2 = 0, 'buy', 'sell'), if(number % 3 = 0, NULL, 'n'),
                    if(number % 5 = 0, NULL, 'lc'), [number, NULL], map('k', [1, -2]), tuple('a', toDate('2026-10-19')),
                    concat('{"contact":{"email":"u', toString(number), '@bank.com"},"n":', toString(number), '}'),
                    unhex('C328FF'), concat('GB29NWBK', leftPad(toString(number), 8, '0')),
                    concat('{"card":"CARD', leftPad(toString(number), 8, '0'), '","x":1}')
                FROM numbers(1000)
                """.trimIndent(),
            )
        }
        val config =
            ItFixtures.config {
                (get("tables") as ArrayNode).add(ConfigLoader.mapper.readTree(spec))
            }
        CopyJob(config, "qa1", ItFixtures.engine(config), UnguardedGate, ItFixtures.clients).copy("trading.alltypes")
        val hash = "SELECT groupBitXor(cityHash64(${passColumns.joinToString()})) FROM trading.alltypes"
        for (i in ItCluster.sources.indices) {
            val source = ItCluster.sources[i]
            val target = ItCluster.qa[i]
            assertEquals(source.scalar("SELECT count() FROM trading.alltypes"), target.scalar("SELECT count() FROM trading.alltypes"))
            assertEquals(source.scalar(hash), target.scalar(hash), "pass-through columns differ on shard $i")
            assertEquals("C328FF", target.scalar("SELECT hex(any(bin)) FROM trading.alltypes"))
            val q = "SELECT secret, toString(j.contact.email), doc, toString(j.n) FROM trading.alltypes WHERE id = 7 FORMAT TSV"
            val (srcSecret, srcEmail, srcDoc, srcN) = source.scalarRow(q)
            val (dstSecret, dstEmail, dstDoc, dstN) = target.scalarRow(q)
            assertNotEquals(srcSecret, dstSecret)
            assertEquals(srcSecret.length, dstSecret.length)
            assertNotEquals(srcEmail, dstEmail)
            assertEquals(srcN, dstN)
            assertNotEquals(srcDoc, dstDoc)
        }
    }

    private fun ClickHouseNode.scalarRow(query: String): List<String> =
        client()
            .let { c -> c.queryJson(query.removeSuffix(" FORMAT TSV")) }
            .single()
            .let { row -> row.properties().map { it.value.asText() } }
}
