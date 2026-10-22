package chrepl.masking

import chrepl.config.model.Masking
import chrepl.config.model.MaskingKey
import chrepl.config.model.SecretRef
import chrepl.config.validate.SecretReader
import chrepl.masking.keys.Hkdf
import chrepl.masking.keys.KeyLoadException
import chrepl.masking.keys.MasterKey
import chrepl.masking.keys.StaticKeyProvider
import chrepl.masking.types.ChType
import chrepl.masking.types.TypeParser
import org.junit.jupiter.api.Test
import java.util.HexFormat
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class TypeParserTest {
    @Test
    fun scalars() {
        assertEquals(ChType.IntType(64, false), TypeParser.parse("UInt64"))
        assertEquals(ChType.IntType(8, true), TypeParser.parse("Int8"))
        assertEquals(ChType.DecimalType(18, 4), TypeParser.parse("Decimal(18, 4)"))
        assertEquals(ChType.DecimalType(38, 2), TypeParser.parse("Decimal128(2)"))
        assertEquals(ChType.FixedStringType(16), TypeParser.parse("FixedString(16)"))
        assertEquals(ChType.DateTimeType("Europe/London"), TypeParser.parse("DateTime('Europe/London')"))
        assertEquals(ChType.DateTime64Type(3, "UTC"), TypeParser.parse("DateTime64(3, 'UTC')"))
        assertEquals(ChType.DateTime64Type(6, null), TypeParser.parse("DateTime64(6)"))
        assertEquals(ChType.EnumType(8, linkedMapOf("buy" to 1, "sell" to 2)), TypeParser.parse("Enum8('buy' = 1, 'sell' = 2)"))
        assertEquals(ChType.JsonType, TypeParser.parse("JSON(max_dynamic_paths = 1024)"))
        assertIs<ChType.Unsupported>(TypeParser.parse("AggregateFunction(uniq, String)"))
    }

    @Test
    fun containers() {
        assertEquals(
            ChType.MapType(ChType.StringType, ChType.ArrayType(ChType.NullableType(ChType.DecimalType(18, 4)))),
            TypeParser.parse("Map(String, Array(Nullable(Decimal(18, 4))))"),
        )
        assertEquals(
            ChType.LowCardinalityType(ChType.NullableType(ChType.StringType)),
            TypeParser.parse("LowCardinality(Nullable(String))"),
        )
        assertEquals(
            ChType.TupleType(listOf(ChType.StringType, ChType.IntType(32, false)), listOf("name", "age")),
            TypeParser.parse("Tuple(name String, age UInt32)"),
        )
        assertEquals(
            ChType.TupleType(listOf(ChType.StringType, ChType.ArrayType(ChType.UuidType))),
            TypeParser.parse("Tuple(String, Array(UUID))"),
        )
        assertEquals(ChType.IntType(32, false), TypeParser.parse("SimpleAggregateFunction(max, UInt32)"))
        assertFailsWith<IllegalArgumentException> { TypeParser.parse("Array(String") }
        assertFailsWith<IllegalArgumentException> { TypeParser.parse("String extra") }
    }
}

class KeysTest {
    private val hex = HexFormat.of()

    @Test
    fun `HKDF matches RFC 5869 test case 1`() {
        val okm =
            Hkdf.derive(
                ikm = ByteArray(22) { 0x0b },
                salt = hex.parseHex("000102030405060708090a0b0c"),
                info = hex.parseHex("f0f1f2f3f4f5f6f7f8f9"),
                length = 42,
            )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", hex.formatHex(okm))
    }

    @Test
    fun `derived keys are separated by realm, version, purpose and domain`() {
        val keys =
            listOf(
                MasterKey("nonprod", 1, MaskingFixtures.KEY_A).derive("hmac", "email"),
                MasterKey("vendor", 1, MaskingFixtures.KEY_A).derive("hmac", "email"),
                MasterKey("nonprod", 2, MaskingFixtures.KEY_A).derive("hmac", "email"),
                MasterKey("nonprod", 1, MaskingFixtures.KEY_A).derive("ff1", "email"),
                MasterKey("nonprod", 1, MaskingFixtures.KEY_A).derive("hmac", "phone"),
            )
        assertEquals(keys.size, keys.map { hex.formatHex(it) }.toSet().size)
        assertContentEquals(keys[0], MasterKey("nonprod", 1, MaskingFixtures.KEY_A).derive("hmac", "email"))
    }

    @Test
    fun `master key never prints its material and is wiped on close`() {
        val key = MasterKey("nonprod", 1, MaskingFixtures.KEY_A)
        assertFalse(hex.formatHex(MaskingFixtures.KEY_A) in key.toString())
        key.close()
        assertFailsWith<IllegalStateException> { key.derive("hmac", "x") }
    }

    private fun provider(
        secret: String?,
        ref: String = "\${env:MASK_KEY}",
    ) = StaticKeyProvider(
        Masking("nonprod", MaskingKey("static", SecretRef.parse(ref), 3), emptyMap(), emptyMap()),
        SecretReader { secret },
    )

    @Test
    fun `static provider loads hex and base64 keys`() {
        val fromHex = provider(hex.formatHex(MaskingFixtures.KEY_A)).load()
        assertEquals(3, fromHex.version)
        val fromBase64 =
            provider(
                java.util.Base64
                    .getEncoder()
                    .encodeToString(MaskingFixtures.KEY_A),
            ).load()
        assertContentEquals(fromHex.derive("hmac", "d"), fromBase64.derive("hmac", "d"))
    }

    @Test
    fun `static provider rejects bad keys without echoing them`() {
        val short = hex.formatHex(ByteArray(16) { 7 })
        val e = assertFailsWith<KeyLoadException> { provider(short).load() }
        assertFalse(short in e.message!!)
        assertFailsWith<KeyLoadException> { provider(null).load() }
        assertFailsWith<KeyLoadException> { provider("not-a-key!").load() }
        assertFailsWith<KeyLoadException> { provider(hex.formatHex(MaskingFixtures.KEY_A), ref = "literal").load() }
    }
}
