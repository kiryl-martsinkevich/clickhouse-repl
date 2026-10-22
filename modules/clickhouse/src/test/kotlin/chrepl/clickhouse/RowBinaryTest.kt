package chrepl.clickhouse

import chrepl.types.TypeParser
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.net.InetAddress
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RowBinaryTest {
    private val cases: List<Pair<String, Any?>> =
        listOf(
            "Int8" to -5L,
            "Int16" to -30000L,
            "Int32" to Int.MIN_VALUE.toLong(),
            "Int64" to Long.MIN_VALUE,
            "UInt8" to 255L,
            "UInt16" to 65535L,
            "UInt32" to 4_294_967_295L,
            "UInt64" to BigInteger("18446744073709551615"),
            "Int128" to BigInteger("-170141183460469231731687303715884105728"),
            "UInt256" to BigInteger.TWO.pow(256) - BigInteger.ONE,
            "Float32" to 1.5,
            "Float64" to -2.25e300,
            "Decimal(9, 2)" to BigDecimal("-1234567.89"),
            "Decimal(18, 4)" to BigDecimal("12345678901234.5678"),
            "Decimal(38, 10)" to BigDecimal("-1234567890123456789012345678.0123456789"),
            "Decimal(76, 0)" to BigDecimal("9".repeat(76)),
            "Bool" to true,
            "String" to "héllo wörld",
            "FixedString(6)" to "abc\u0000\u0000\u0000",
            "Date" to LocalDate.of(2149, 6, 6),
            "Date32" to LocalDate.of(1900, 1, 2),
            "DateTime" to Instant.parse("2106-02-07T06:28:15Z"),
            "DateTime64(3)" to Instant.parse("2020-01-01T00:00:00.123Z"),
            "DateTime64(6, 'UTC')" to Instant.parse("1969-12-31T23:59:59.999999Z"),
            "UUID" to UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
            "IPv4" to InetAddress.getByName("1.2.3.4"),
            "IPv6" to InetAddress.getByName("2001:db8::1"),
            "Enum8('buy' = 1, 'sell' = -2)" to "sell",
            "Nullable(String)" to null,
            "LowCardinality(Nullable(String))" to "x",
            "Array(Nullable(UInt64))" to listOf(BigInteger.ONE, null),
            "Map(String, Array(Int32))" to linkedMapOf("a" to listOf(1L, 2L), "b" to emptyList<Long>()),
            "Tuple(a String, b Date)" to listOf("t", LocalDate.of(2026, 10, 19)),
            "JSON" to """{"a":1}""",
        )

    @Test
    fun `every supported type round-trips`() {
        val types = cases.map { TypeParser.parse(it.first) }
        val row = cases.map { it.second }.toTypedArray()
        val bytes = ByteArrayOutputStream()
        RowBinaryWriter(bytes).use { it.writeRow(row, types) }
        val reader = RowBinaryReader(ByteArrayInputStream(bytes.toByteArray()))
        val back = reader.readRow(types)
        for (i in cases.indices) assertEquals(row[i], back[i], cases[i].first)
        assertFalse(reader.hasMore())
    }

    @Test
    fun `known byte layouts match ClickHouse`() {
        // Verified against ClickHouse 25.8: UUID halves little-endian (high first), IPv6 network order, IPv4 little-endian.
        val bytes = ByteArrayOutputStream()
        RowBinaryWriter(bytes).use {
            it.writeRow(
                arrayOf(
                    UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
                    InetAddress.getByName("1.2.3.4"),
                    LocalDate.of(1900, 1, 2),
                ),
                listOf("UUID", "IPv4", "Date32").map(TypeParser::parse),
            )
        }
        val expected = "7766554433221100" + "ffeeddccbbaa9988" + "04030201" + "229cffff"
        assertEquals(
            expected,
            java.util.HexFormat
                .of()
                .formatHex(bytes.toByteArray()),
        )
    }

    @Test
    fun `invalid UTF-8 strings are kept as bytes`() {
        val raw = byteArrayOf(0x66, 0xC3.toByte(), 0x28, 0xFF.toByte())
        val bytes = ByteArrayOutputStream()
        RowBinaryWriter(bytes).use { it.writeRow(arrayOf(raw), listOf(TypeParser.parse("String"))) }
        val back = RowBinaryReader(ByteArrayInputStream(bytes.toByteArray())).readRow(listOf(TypeParser.parse("String")))[0]
        assertContentEquals(raw, back as ByteArray)
    }

    @Test
    fun `header of RowBinaryWithNamesAndTypes`() {
        // As sent by ClickHouse for: SELECT 1 AS x FORMAT RowBinaryWithNamesAndTypes
        val data = byteArrayOf(1, 1, 'x'.code.toByte(), 5) + "UInt8".toByteArray() + byteArrayOf(1)
        val reader = RowBinaryReader(ByteArrayInputStream(data))
        val header = reader.readHeader()
        assertEquals(listOf("x" to TypeParser.parse("UInt8")), header)
        assertEquals(1L, reader.readRow(header.map { it.second })[0])
    }

    @Test
    fun `values that do not fit are rejected`() {
        fun write(
            type: String,
            value: Any?,
        ) = RowBinaryWriter(ByteArrayOutputStream()).writeValue(value, TypeParser.parse(type))
        assertFailsWith<RowBinaryException> { write("UInt8", 256L) }
        assertFailsWith<RowBinaryException> { write("String", null) }
        assertFailsWith<RowBinaryException> { write("FixedString(2)", "abc") }
        assertFailsWith<RowBinaryException> { write("Decimal(9, 2)", BigDecimal("1.234")) }
        assertFailsWith<RowBinaryException> { write("Date", LocalDate.of(1969, 12, 31)) }
        assertFailsWith<RowBinaryException> { write("Enum8('a' = 1)", "b") }
    }

    @Test
    fun `truncated data fails`() {
        assertFailsWith<java.io.EOFException> {
            RowBinaryReader(ByteArrayInputStream(byteArrayOf(1, 2))).readRow(listOf(TypeParser.parse("UInt64")))
        }
    }
}
