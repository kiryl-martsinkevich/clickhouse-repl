package chrepl.config

import chrepl.config.cron.CronValidator
import chrepl.config.expr.Identifiers
import chrepl.config.model.ConfigDuration
import chrepl.config.model.DataSize
import chrepl.config.model.HostPort
import chrepl.config.model.SecretRef
import chrepl.config.net.Cidr
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValuesTest {
    @Test
    fun durations() {
        assertEquals(Duration.ofMillis(500), ConfigDuration.parse("500ms").value)
        assertEquals(Duration.ofSeconds(5), ConfigDuration.parse("5s").value)
        assertEquals(Duration.ofMinutes(10), ConfigDuration.parse("10m").value)
        assertEquals(Duration.ofHours(36), ConfigDuration.parse("36h").value)
        assertEquals(Duration.ofDays(2), ConfigDuration.parse("2d").value)
        for (bad in listOf("5", "5 s", "-5s", "1.5h", "5sec", "")) {
            assertFailsWith<IllegalArgumentException>(bad) { ConfigDuration.parse(bad) }
        }
    }

    @Test
    fun sizes() {
        assertEquals(2L shl 30, DataSize.parse("2GiB").bytes)
        assertEquals(50_000_000_000L, DataSize.parse("50GB").bytes)
        assertEquals(1L shl 40, DataSize.parse("1TiB").bytes)
        assertEquals(512L, DataSize.parse("512B").bytes)
        assertFailsWith<IllegalArgumentException> { DataSize.parse("2gb") }
        assertFailsWith<IllegalArgumentException> { DataSize.parse("99999999999TiB") }
    }

    @Test
    fun `secret references never fail to parse and never print literals`() {
        assertEquals(SecretRef.Kind.FILE, SecretRef.parse("\${file:/var/run/secrets/x}").kind)
        assertEquals("/var/run/secrets/x", SecretRef.parse("\${file:/var/run/secrets/x}").target)
        assertEquals(SecretRef.Kind.ENV, SecretRef.parse("\${env:MASK_KEY}").kind)
        assertEquals(SecretRef.Kind.UNSUPPORTED, SecretRef.parse("\${vault:secret/x}").kind)
        val literal = SecretRef.parse("hunter2")
        assertEquals(SecretRef.Kind.LITERAL, literal.kind)
        assertFalse("hunter2" in literal.toString())
    }

    @Test
    fun `host and port`() {
        val plain = HostPort.parse("CH-DR-S1.dc-dr.internal.:8443")
        assertEquals("ch-dr-s1.dc-dr.internal", plain.normalizedHost)
        assertEquals(8443, plain.port)
        assertNull(HostPort.parse("ch-uat-01").port)
        assertEquals("fd00::1", HostPort.parse("[fd00::1]:9440").host)
        assertFailsWith<IllegalArgumentException> { HostPort.parse("host:99999") }
        assertFailsWith<IllegalArgumentException> { HostPort.parse("http://host") }
    }
}

class CidrTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    @Test
    fun ipv4() {
        val cidr = Cidr.parse("10.10.0.0/16")
        assertTrue(ip("10.10.0.1") in cidr)
        assertTrue(ip("10.10.255.255") in cidr)
        assertFalse(ip("10.11.0.1") in cidr)
        assertTrue(ip("192.168.1.130") in Cidr.parse("192.168.1.128/25"))
        assertFalse(ip("192.168.1.127") in Cidr.parse("192.168.1.128/25"))
        assertTrue(ip("1.2.3.4") in Cidr.parse("0.0.0.0/0"))
        assertTrue(ip("10.0.0.7") in Cidr.parse("10.0.0.7"))
    }

    @Test
    fun ipv6() {
        val cidr = Cidr.parse("fd00:10::/32")
        assertTrue(ip("fd00:10::1") in cidr)
        assertFalse(ip("fd00:11::1") in cidr)
        assertFalse(ip("10.10.0.1") in cidr)
    }

    @ParameterizedTest
    @ValueSource(strings = ["10.10.0.0/33", "10.10.0/16", "host.internal/24", "fd00::/129", "300.1.1.1/8"])
    fun `invalid networks`(text: String) {
        assertFailsWith<IllegalArgumentException> { Cidr.parse(text) }
    }
}

class CronValidatorTest {
    @ParameterizedTest
    @ValueSource(
        strings = ["0 3 * * *", "*/5 * * * *", "0 17 * * 1-5", "0 0 L * *", "0 8 1 */3 *", "15,45 9-17 * JAN-MAR mon-fri", "0 0 * * 7"],
    )
    fun valid(expression: String) {
        assertNull(CronValidator.validate(expression))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "0 25 * * *", "* * * *", "0 0 * * * *", "60 * * * *", "0 0 32 * *",
            "0 0 * 13 *", "0 0 * * 8", "5-1 * * * *", "*/0 * * * *", "0 0 L * L",
        ],
    )
    fun invalid(expression: String) {
        assertNotNull(CronValidator.validate(expression))
    }
}

class IdentifiersTest {
    @Test
    fun `function names and literals are skipped`() {
        assertEquals(setOf("trade_date"), Identifiers.of("toYYYYMM(trade_date)"))
        assertEquals(setOf("trade_date", "customer_id"), Identifiers.of("(toYYYYMM(trade_date), cityHash64(customer_id) % 16)"))
        assertEquals(setOf("region"), Identifiers.of("if(region = 'EU', 1, 2)"))
        assertEquals(setOf("order id"), Identifiers.of("cityHash64(`order id`)"))
        assertEquals(emptySet(), Identifiers.of("tuple()"))
    }
}

class CronExpressionTest {
    private fun at(text: String) = java.time.ZonedDateTime.parse(text)

    @Test
    fun matching() {
        val weekdays =
            chrepl.config.cron.CronExpression
                .parse("0 17 * * 1-5")
        assertTrue(weekdays.matches(at("2026-10-22T17:00:00Z"))) // Thursday
        assertFalse(weekdays.matches(at("2026-10-24T17:00:00Z"))) // Saturday
        assertFalse(weekdays.matches(at("2026-10-22T17:01:00Z")))
        val monthEnd =
            chrepl.config.cron.CronExpression
                .parse("0 0 L * *")
        assertTrue(monthEnd.matches(at("2026-02-28T00:00:00Z")))
        assertFalse(monthEnd.matches(at("2026-02-27T00:00:00Z")))
        assertTrue(
            chrepl.config.cron.CronExpression
                .parse("*/15 * * * SUN")
                .matches(at("2026-10-25T09:45:00Z")),
        )
        assertTrue(
            chrepl.config.cron.CronExpression
                .parse("0 0 * * 7")
                .matches(at("2026-10-25T00:00:00Z")),
        )
    }

    @Test
    fun windows() {
        val eod =
            chrepl.config.cron.CronExpression
                .parse("0 17 * * 1-5")
        val fiveHours = java.time.Duration.ofHours(5)
        assertTrue(eod.withinWindow(at("2026-10-22T21:59:00Z"), fiveHours))
        assertFalse(eod.withinWindow(at("2026-10-22T22:00:00Z"), fiveHours))
        assertFalse(eod.withinWindow(at("2026-10-22T16:59:00Z"), fiveHours))
    }
}
