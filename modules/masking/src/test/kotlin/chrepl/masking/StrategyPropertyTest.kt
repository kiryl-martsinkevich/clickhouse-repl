package chrepl.masking

import chrepl.config.model.Domain
import chrepl.config.model.EmailDomainMode
import chrepl.config.model.EmailLocalPart
import chrepl.config.model.Normaliser
import chrepl.config.model.Strategy
import chrepl.masking.MaskingFixtures.engine
import chrepl.masking.MaskingFixtures.single
import chrepl.masking.strategy.Ff1Strategy
import chrepl.masking.types.ChType
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bigDecimal
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.localDate
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Property tests for the strategies (PLAN M1 exit criteria). */
class StrategyPropertyTest {
    private val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf(' ', '-', '.', '@', '_', '/', 'é', 'Ж', 'ß', '٣')
    private val texts = Arb.list(Arb.element(chars), 0..40).map { it.joinToString("") }
    private val tokenize = Domain(Strategy.TOKENIZE)

    private fun property(block: suspend () -> Unit) = runBlocking { block() }

    @Test
    fun `masking is deterministic across engine instances`() =
        property {
            val first = single(tokenize, "String", engine(mapOf("d" to tokenize)))
            val second = single(tokenize, "String", engine(mapOf("d" to tokenize)))
            checkAll(texts) { assertEquals(first(it), second(it)) }
        }

    @Test
    fun `realms, key versions and keys produce unrelated outputs`() =
        property {
            val base = single(tokenize, "String", engine(mapOf("d" to tokenize)))
            val otherRealm = single(tokenize, "String", engine(mapOf("d" to tokenize), realm = "vendor"))
            val otherVersion = single(tokenize, "String", engine(mapOf("d" to tokenize), version = 2))
            val otherKey = single(tokenize, "String", engine(mapOf("d" to tokenize), key = MaskingFixtures.KEY_B))
            val inputs = (1..200).map { "customer-$it-ABCDEFGH" }
            for (other in listOf(otherRealm, otherVersion, otherKey)) {
                val same = inputs.count { base(it) == other(it) }
                assertTrue(same == 0, "$same of ${inputs.size} outputs coincided across keys")
            }
        }

    @Test
    fun `tokenize preserves length and character classes`() =
        property {
            val mask = single(tokenize, "String")
            checkAll(texts) { input ->
                val output = mask(input) as String
                val inCps = input.codePoints().toArray()
                val outCps = output.codePoints().toArray()
                assertEquals(inCps.size, outCps.size)
                for (i in inCps.indices) {
                    val a = inCps[i]
                    val b = outCps[i]
                    when {
                        Character.isDigit(a) -> assertTrue(b in '0'.code..'9'.code)
                        a in 'a'.code..'z'.code -> assertTrue(b in 'a'.code..'z'.code)
                        a in 'A'.code..'Z'.code -> assertTrue(b in 'A'.code..'Z'.code)
                        Character.isLetter(a) -> assertTrue(Character.isLetter(b) && b < 128)
                        else -> assertEquals(a, b)
                    }
                }
            }
        }

    @Test
    fun `tokenize keeps integers in range with their sign and a non-zero leading digit`() =
        property {
            val int64 = single(tokenize, "Int64")
            checkAll(Arb.long()) { value ->
                val out = int64(value) as Long
                assertEquals(value.sign(), out.sign())
                if (value != 0L) assertTrue(out.toString().removePrefix("-")[0] != '0')
            }
            val uint8 = single(tokenize, "UInt8")
            checkAll(Arb.long(0L..255L)) { value -> assertTrue((uint8(value) as Long) in 0..255) }
            val uint64 = single(tokenize, "UInt64")
            val max = BigInteger.TWO.pow(64) - BigInteger.ONE
            checkAll(Arb.long(0L..Long.MAX_VALUE)) { value ->
                val out = uint64(BigInteger.valueOf(value).multiply(BigInteger.TWO)) as BigInteger
                assertTrue(out.signum() >= 0 && out <= max)
            }
        }

    @Test
    fun `integers and their decimal strings mask identically`() =
        property {
            val domains = mapOf("d" to tokenize)
            val asInt = single(tokenize, "UInt64", engine(domains))
            val asString = single(tokenize, "String", engine(domains))
            checkAll(Arb.long(0L..Long.MAX_VALUE)) { value ->
                assertEquals((asInt(BigInteger.valueOf(value)) as BigInteger).toString(), asString(value.toString()))
            }
        }

    @Test
    fun `fpe_ff1 over digits is a bijection on its width`() =
        property {
            val domain = Domain(Strategy.FPE_FF1, alphabet = "digits", width = 12)
            val ff1 = Ff1Strategy(MaskingFixtures.KEY_A, "digits", 12)
            val type = ChType.IntType(64, true)
            checkAll(Arb.long(0L until 1_000_000_000_000L)) { value ->
                val masked = ff1.mask(value, type, null) as Long
                assertTrue(masked in 0 until 1_000_000_000_000L)
                assertEquals(value, ff1.unmask(masked, type))
            }
            // No collisions over a dense range of a 6-digit domain.
            val six = Ff1Strategy(MaskingFixtures.KEY_A, "digits", 6)
            val outputs = (0L until 200_000L).map { six.mask(it, type, null) }.toSet()
            assertEquals(200_000, outputs.size)
            // Integer and string columns agree.
            val asInt = single(domain, "UInt64")
            val asString = single(domain, "String")
            checkAll(Arb.long(0L until 1_000_000_000_000L)) { value ->
                assertEquals((asInt(BigInteger.valueOf(value)) as BigInteger).toString(), asString(value.toString()))
            }
        }

    @Test
    fun `fpe_ff1 over an alphabet keeps length and alphabet and round-trips`() =
        property {
            val ff1 = Ff1Strategy(MaskingFixtures.KEY_A, "alphanumeric", null)
            val alnum = ('0'..'9') + ('A'..'Z') + ('a'..'z')
            checkAll(Arb.list(Arb.element(alnum), 4..32).map { it.joinToString("") }) { input ->
                val out = ff1.mask(input, ChType.StringType, null) as String
                assertEquals(input.length, out.length)
                assertTrue(out.all { it in alnum })
                assertEquals(input, ff1.unmask(out, ChType.StringType))
            }
        }

    @Test
    fun `hash output fits the column`() =
        property {
            val hex = single(Domain(Strategy.HASH, length = 16), "String")
            val fixed = single(Domain(Strategy.HASH), "FixedString(8)")
            val int8 = single(Domain(Strategy.HASH), "Int8")
            checkAll(texts) { input ->
                assertTrue((hex(input) as String).matches(Regex("[0-9a-f]{16}")))
                assertEquals(8, (fixed(input) as String).length)
            }
            checkAll(Arb.long(-128L..127L)) { assertTrue((int8(it) as Long) in -128..127) }
        }

    @Test
    fun `email keeps its shape and normalises before masking`() =
        property {
            val keep =
                Domain(
                    Strategy.EMAIL,
                    localPart = EmailLocalPart.TOKENIZE,
                    domain = EmailDomainMode.KEEP,
                    normalise = listOf(Normaliser.TRIM, Normaliser.LOWER),
                )
            val mask = single(keep, "String")
            val example = single(Domain(Strategy.EMAIL, localPart = EmailLocalPart.HASH, domain = EmailDomainMode.MAP_TO_EXAMPLE), "String")
            val tokenized = single(Domain(Strategy.EMAIL, localPart = EmailLocalPart.TOKENIZE, domain = EmailDomainMode.TOKENIZE), "String")
            val locals = Arb.list(Arb.element(('a'..'z') + ('0'..'9') + listOf('.', '_')), 1..20).map { it.joinToString("") }
            checkAll(locals, Arb.element("bank.co.uk", "example.org", "mail.com")) { local, domain ->
                val email = "$local@$domain"
                val out = mask(email) as String
                assertTrue(out.endsWith("@$domain"))
                assertEquals(email.length, out.length)
                assertEquals(out, mask("  ${email.uppercase()} "))
                assertTrue((example(email) as String).matches(Regex("[0-9a-f]{12}@example\\.com")))
                val tok = tokenized(email) as String
                assertTrue(tok.endsWith("." + domain.substringAfterLast('.')))
                assertEquals(email.length, tok.length)
            }
        }

    @Test
    fun `dictionary picks entries from the dictionary`() =
        property {
            val names = listOf("Alex", "Bailey", "Casey", "Dana")
            val domain = Domain(Strategy.DICTIONARY, dictionary = "names")
            val mask = single(domain, "String", engine(mapOf("d" to domain), mapOf("names" to names)))
            checkAll(texts) { assertTrue(mask(it) in names) }
        }

    @Test
    fun `date_shift moves all dates of one entity by the same bounded offset`() =
        property {
            val domain = Domain(Strategy.DATE_SHIFT, maxDays = 30)
            val eng = engine(mapOf("d" to domain))
            val masker =
                MaskingFixtures.masker(
                    eng,
                    Triple("customer_id", "UInt64", chrepl.config.model.ColumnPolicy.Pass),
                    Triple(
                        "dob",
                        "Date32",
                        chrepl.config.model.ColumnPolicy
                            .Mask("d", "customer_id"),
                    ),
                    Triple(
                        "onboarded",
                        "Date",
                        chrepl.config.model.ColumnPolicy
                            .Mask("d", "customer_id"),
                    ),
                )
            val dates = Arb.localDate(LocalDate.of(1950, 1, 1), LocalDate.of(2100, 1, 1))
            checkAll(
                Arb.long(1L..1_000_000L),
                dates,
                Arb.localDate(LocalDate.of(1971, 1, 1), LocalDate.of(2140, 1, 1)),
            ) { id, dob, onboarded ->
                val out = masker.mask(arrayOf(BigInteger.valueOf(id), dob, onboarded))
                val shift = ChronoUnit.DAYS.between(dob, out[1] as LocalDate)
                assertTrue(abs(shift) <= 30)
                assertEquals(shift, ChronoUnit.DAYS.between(onboarded, out[2] as LocalDate))
            }
            // Clamped to the Date range.
            val date = single(domain, "Date")
            assertTrue((date(LocalDate.of(1970, 1, 1)) as LocalDate) >= LocalDate.of(1970, 1, 1))
        }

    @Test
    fun `numeric_noise stays within bounds, keeps the sign and the scale`() =
        property {
            val domain = Domain(Strategy.NUMERIC_NOISE, relative = 0.05)
            val decimal = single(domain, "Decimal(18, 2)")
            checkAll(Arb.bigDecimal(BigDecimal("-1000000"), BigDecimal("1000000"))) { raw ->
                val value = raw.setScale(2, java.math.RoundingMode.HALF_EVEN)
                val out = decimal(value) as BigDecimal
                assertEquals(2, out.scale())
                assertTrue(out.signum() == 0 || out.signum() == value.signum())
                val bound = value.abs().multiply(BigDecimal("0.05")).add(BigDecimal("0.01"))
                assertTrue((out - value).abs() <= bound, "$value -> $out")
            }
            val uint = single(domain, "UInt32")
            checkAll(Arb.long(0L..4_294_967_295L)) { assertTrue((uint(it) as Long) in 0..4_294_967_295L) }
        }

    @Test
    fun `partial keeps only the configured ends`() {
        val mask = single(Domain(Strategy.PARTIAL, keepLast = 4), "String")
        assertEquals("************1234", mask("4111111111111234"))
        assertEquals("***", mask("abc"))
    }

    @Test
    fun `different inputs rarely collide under tokenize`() =
        property {
            val mask = single(tokenize, "String")
            val outputs = (0 until 20_000).map { mask("ACC-%08d".format(it)) }.toSet()
            assertTrue(outputs.size > 19_990, "${20_000 - outputs.size} collisions")
            checkAll(Arb.int(0..1000)) { assertNotEquals("ACC-%08d".format(it), mask("ACC-%08d".format(it))) }
        }

    private fun Long.sign() = java.lang.Long.signum(this)
}
