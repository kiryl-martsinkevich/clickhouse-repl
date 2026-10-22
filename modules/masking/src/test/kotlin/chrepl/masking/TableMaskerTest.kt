package chrepl.masking

import chrepl.config.load.ConfigLoader
import chrepl.config.model.ColumnPolicy
import chrepl.config.model.ColumnPolicy.Pass
import chrepl.config.model.Domain
import chrepl.config.model.Strategy
import chrepl.config.validate.SecretReader
import chrepl.masking.MaskingFixtures.engine
import chrepl.masking.MaskingFixtures.mask
import chrepl.masking.MaskingFixtures.masker
import chrepl.masking.keys.StaticKeyProvider
import chrepl.masking.table.ColumnSchema
import chrepl.types.TypeParser
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableMaskerTest {
    private val domains =
        mapOf(
            "tok" to Domain(Strategy.TOKENIZE),
            "id" to Domain(Strategy.FPE_FF1, alphabet = "digits", width = 12, approvedBy = "test"),
            "null" to Domain(Strategy.NULL),
            "const" to Domain(Strategy.CONSTANT, value = "redacted"),
            "email" to
                Domain(
                    Strategy.EMAIL,
                    localPart = chrepl.config.model.EmailLocalPart.TOKENIZE,
                    domain = chrepl.config.model.EmailDomainMode.KEEP,
                ),
            "tiny" to Domain(Strategy.HASH, length = 8, collisionCheck = true),
            "shift" to Domain(Strategy.DATE_SHIFT, maxDays = 10),
        )
    private val engine = engine(domains)

    @Test
    fun `pass columns are copied and masked columns change`() {
        val m = masker(engine, Triple("id", "UInt64", Pass), Triple("name", "String", mask("tok")))
        val out = m.mask(arrayOf(BigInteger.valueOf(42), "Alice Smith"))
        assertEquals(BigInteger.valueOf(42), out[0])
        assertNotEquals("Alice Smith", out[1])
        assertEquals(listOf("name"), m.maskedColumns)
    }

    @Test
    fun `nullable and low-cardinality wrappers`() {
        val m =
            masker(
                engine,
                Triple("a", "Nullable(String)", mask("tok")),
                Triple("b", "LowCardinality(Nullable(String))", mask("tok")),
                Triple("c", "String", mask("null")),
                Triple("d", "Nullable(String)", mask("null")),
                Triple("e", "Nullable(String)", mask("const")),
                Triple("f", "UInt32", mask("null")),
            )
        val out = m.mask(arrayOf(null, "x1", "secret", "secret", null, 123L))
        assertNull(out[0])
        assertEquals(2, (out[1] as String).length)
        assertEquals("", out[2]) // non-nullable NULL strategy -> type default
        assertNull(out[3])
        assertEquals("redacted", out[4]) // constant replaces NULL too
        assertEquals(0L, out[5])
    }

    @Test
    fun `arrays, tuples and maps`() {
        val m =
            masker(
                engine,
                Triple("tags", "Array(Nullable(String))", mask("tok")),
                Triple("pair", "Tuple(String, String)", mask("tok")),
                Triple(
                    "attributes",
                    "Map(String, String)",
                    ColumnPolicy.MapPolicy(null, mapOf("client_email" to mask("email")), Pass),
                ),
                Triple("by_id", "Map(UInt64, String)", ColumnPolicy.MapPolicy(mask("id"), emptyMap(), mask("tok"))),
            )
        val out =
            m.mask(
                arrayOf(
                    listOf("alpha", null, "beta"),
                    listOf("left", "right"),
                    linkedMapOf("client_email" to "jane@bank.com", "desk" to "rates"),
                    linkedMapOf(BigInteger.valueOf(7) to "seven"),
                ),
            )
        val tags = out[0] as List<*>
        assertEquals(3, tags.size)
        assertNull(tags[1])
        assertEquals(2, (out[1] as List<*>).size)
        val attributes = out[2] as Map<*, *>
        assertTrue((attributes["client_email"] as String).endsWith("@bank.com"))
        assertNotEquals("jane@bank.com", attributes["client_email"])
        assertEquals("rates", attributes["desk"])
        val byId = out[3] as Map<*, *>
        assertNotEquals(BigInteger.valueOf(7), byId.keys.single())
        assertNotEquals("seven", byId.values.single())
    }

    @Test
    fun `json paths mask selected fields and keep the rest`() {
        val policy =
            ColumnPolicy.JsonPaths(
                mapOf("$.contact.email" to mask("email"), "$.accounts[*].number" to mask("tok"), "$.notes" to mask("null")),
                Pass,
            )
        val m = masker(engine, Triple("profile", "String", policy), Triple("doc", "JSON", mask("tok")))
        val json =
            """{"contact":{"email":"jane@bank.com","city":"Leeds"},""" +
                """"accounts":[{"number":"AC-1234","open":true}],"notes":"vip","n":5}"""
        val out = m.mask(arrayOf(json, """{"a":"secret","b":12345,"c":true}"""))
        val tree = ObjectMapper().readTree(out[0] as String)
        assertTrue(tree["contact"]["email"].textValue().endsWith("@bank.com"))
        assertNotEquals("jane@bank.com", tree["contact"]["email"].textValue())
        assertEquals("Leeds", tree["contact"]["city"].textValue())
        assertNotEquals("AC-1234", tree["accounts"][0]["number"].textValue())
        assertTrue(tree["accounts"][0]["open"].booleanValue())
        assertTrue(tree["notes"].isNull)
        assertEquals(5, tree["n"].intValue())
        val doc = ObjectMapper().readTree(out[1] as String)
        assertNotEquals("secret", doc["a"].textValue())
        assertNotEquals(12345, doc["b"].intValue())
        assertTrue(doc["c"].booleanValue())
    }

    @Test
    fun `invalid JSON fails without echoing the value`() {
        val m = masker(engine, Triple("profile", "String", ColumnPolicy.JsonPaths(mapOf("$.a" to mask("tok")), Pass)))
        val e = assertFailsWith<MaskingException> { m.mask(arrayOf("{not json: jane@bank.com")) }
        assertContains(e.message!!, "db.t.profile")
        assertFalse("jane" in e.message!!)
    }

    @Test
    fun `entity columns are read before masking, across tables`() {
        val spec = { table: String ->
            masker(
                engine,
                Triple("customer_id", "UInt64", mask("id")),
                Triple("event_date", "Date", ColumnPolicy.Mask("shift", "customer_id")),
                table = table,
            )
        }
        val a = spec("db.a").mask(arrayOf(BigInteger.valueOf(1001), LocalDate.of(2020, 5, 1)))
        val b = spec("db.b").mask(arrayOf(BigInteger.valueOf(1001), LocalDate.of(2021, 5, 1)))
        assertEquals(a[0], b[0])
        val shiftA =
            java.time.temporal.ChronoUnit.DAYS
                .between(LocalDate.of(2020, 5, 1), a[1] as LocalDate)
        val shiftB =
            java.time.temporal.ChronoUnit.DAYS
                .between(LocalDate.of(2021, 5, 1), b[1] as LocalDate)
        assertEquals(shiftA, shiftB)
    }

    @Test
    fun `customer_id masks the same as UInt64 in one table and String in another`() {
        val numeric = masker(engine, Triple("customer_id", "UInt64", mask("id")), table = "trading.trades")
        val text = masker(engine, Triple("customer_id", "String", mask("id")), table = "crm.legacy")
        for (id in listOf(1L, 42L, 987_654_321L, 999_999_999_999L)) {
            val a = numeric.mask(arrayOf(BigInteger.valueOf(id)))[0] as BigInteger
            val b = text.mask(arrayOf(id.toString()))[0] as String
            assertEquals(a.toString(), b)
        }
    }

    @Test
    fun `compile reports every schema and policy problem`() {
        val e =
            assertFailsWith<MaskingConfigException> {
                engine.compile(
                    MaskingFixtures.spec(
                        "db.t",
                        mapOf(
                            "gone" to Pass,
                            "amount" to mask("tok"),
                            "small_id" to mask("id"),
                            "m" to mask("tok"),
                            "d" to ColumnPolicy.Mask("shift", "nope"),
                            "x" to mask("unknown"),
                        ),
                    ),
                    listOf(
                        ColumnSchema("amount", TypeParser.parse("Decimal(18, 2)")),
                        ColumnSchema("small_id", TypeParser.parse("UInt32")),
                        ColumnSchema("m", TypeParser.parse("Map(String, String)")),
                        ColumnSchema("d", TypeParser.parse("Date")),
                        ColumnSchema("x", TypeParser.parse("String")),
                        ColumnSchema("new_col", TypeParser.parse("String")),
                    ),
                )
            }
        val text = e.problems.joinToString("\n")
        assertContains(text, "'gone' is configured but does not exist")
        assertContains(text, "tokenize supports")
        assertContains(text, "width 12 does not fit UInt32")
        assertContains(text, "use map_keys / map_values")
        assertContains(text, "entity column 'nope' does not exist")
        assertContains(text, "unknown masking domain 'unknown'")
        assertContains(text, "'new_col' of db.t has no masking policy")
    }

    @Test
    fun `collision check fails the chunk and resets per chunk`() {
        val tiny = Domain(Strategy.HASH, length = 8, collisionCheck = true)
        val m = masker(engine(mapOf("tiny" to tiny), settings = MaskingSettings(cacheSize = 0)), Triple("c", "Int8", mask("tiny")))
        // Int8 has 256 values: hashing 256 distinct inputs into 256 outputs collides almost surely.
        assertFailsWith<MaskingCollisionException> { (-128L..127L).forEach { m.mask(arrayOf(it)) } }
        m.startChunk()
        m.mask(arrayOf(5L))
    }

    @Test
    fun `errors never contain values`() {
        val m = masker(engine, Triple("id", "UInt64", mask("id")))
        val secret = BigInteger.TEN.pow(15) + BigInteger.valueOf(424242)
        val e = assertFailsWith<MaskingException> { m.mask(arrayOf(secret)) }
        assertFalse("424242" in e.message!!)
        assertFalse(HexFormat.of().formatHex(MaskingFixtures.KEY_A) in engine.toString())
    }

    @Test
    fun `wrong row width is rejected`() {
        val m = masker(engine, Triple("a", "String", Pass))
        assertFailsWith<MaskingException> { m.mask(arrayOf("a", "b")) }
    }

    @Test
    fun `engine rejects a key for another realm or version`() {
        assertFailsWith<IllegalArgumentException> {
            engine(domains, realm = "nonprod").let {
                MaskingEngine(
                    MaskingFixtures.masking(domains),
                    chrepl.masking.keys.MasterKey("other", 1, MaskingFixtures.KEY_A),
                    emptyMap(),
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            MaskingEngine(MaskingFixtures.masking(domains), chrepl.masking.keys.MasterKey("nonprod", 9, MaskingFixtures.KEY_A), emptyMap())
        }
    }
}

/** Compiles every table of the example config against a plausible schema and masks a row. */
class ExampleConfigMaskingTest {
    private val examplePath = Path.of(System.getProperty("chrepl.exampleConfig"))
    private val config = ConfigLoader.load(examplePath).config!!
    private val key = HexFormat.of().formatHex(MaskingFixtures.KEY_A)

    private val schemas =
        mapOf(
            "trading.trades" to
                listOf(
                    "trade_id" to "UInt64",
                    "trade_date" to "Date",
                    "inserted_at" to "DateTime64(3)",
                    "instrument_id" to "UInt32",
                    "customer_id" to "UInt64",
                    "account_number" to "String",
                    "counterparty_name" to "LowCardinality(String)",
                    "quantity" to "Int64",
                    "price" to "Decimal(18, 6)",
                    "notional" to "Decimal(18, 2)",
                    "trader_comment" to "Nullable(String)",
                    "tags" to "Array(LowCardinality(String))",
                    "attributes" to "Map(String, String)",
                ),
            "trading.positions" to
                listOf(
                    "cob_date" to "Date",
                    "customer_id" to "UInt64",
                    "account_number" to "String",
                    "instrument_id" to "UInt32",
                    "quantity" to "Int64",
                    "market_value" to "Decimal(18, 2)",
                ),
            "crm.customers" to
                listOf(
                    "customer_id" to "UInt64",
                    "first_name" to "String",
                    "last_name" to "String",
                    "email" to "String",
                    "phone" to "Nullable(String)",
                    "date_of_birth" to "Date32",
                    "onboarded_at" to "DateTime",
                    "profile_json" to "String",
                    "segment" to "LowCardinality(String)",
                ),
            "refdata.instruments" to listOf("instrument_id" to "UInt32", "isin" to "FixedString(12)", "name" to "String"),
        )

    @Test
    fun `every example table compiles and masks consistently`() {
        val engine = MaskingEngine.create(config.masking, StaticKeyProvider(config.masking, SecretReader { key }), examplePath.parent)
        val maskers =
            config.tables.associate { spec ->
                spec.source to
                    engine.compile(spec, schemas.getValue(spec.source).map { ColumnSchema(it.first, TypeParser.parse(it.second)) })
            }
        val trade =
            maskers.getValue("trading.trades").mask(
                arrayOf(
                    BigInteger.ONE,
                    LocalDate.of(2026, 10, 19),
                    Instant.parse("2026-10-19T10:00:00Z"),
                    7L,
                    BigInteger.valueOf(123456),
                    "GB29NWBK60161331926819",
                    "Northwind Capital",
                    100L,
                    BigDecimal("101.250000"),
                    BigDecimal("10125.00"),
                    "call me",
                    listOf("fx"),
                    linkedMapOf("client_email" to "jane@bank.com", "desk" to "rates"),
                ),
            )
        val customer =
            maskers.getValue("crm.customers").mask(
                arrayOf(
                    BigInteger.valueOf(123456),
                    "Jane",
                    "Doe",
                    "Jane.Doe@Bank.com",
                    "+44 20 7946 0000",
                    LocalDate.of(1980, 1, 2),
                    Instant.parse("2015-03-04T05:06:07Z"),
                    """{"contact":{"email":"jane@bank.com"},"notes":"x"}""",
                    "retail",
                ),
            )
        assertEquals(trade[4], customer[0]) // same masked customer id across tables
        assertNotEquals(BigInteger.valueOf(123456), trade[4])
        assertNull(trade[10])
        assertEquals(LocalDate.of(2026, 10, 19), trade[1])
        assertTrue((customer[3] as String).endsWith("@example.com"))
        assertEquals(1, engine.keyVersion)
    }
}
