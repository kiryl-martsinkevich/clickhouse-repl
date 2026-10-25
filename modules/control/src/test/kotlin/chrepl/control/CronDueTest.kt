package chrepl.control

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CronDueTest {
    private val london = ZoneId.of("Europe/London")

    private fun at(text: String) = Instant.parse(text)

    @Test
    fun `fires once the scheduled minute has passed, in the job's time zone`() {
        // 03:00 London is 02:00 UTC in summer time.
        assertFalse(cronDue("0 3 * * *", london, at("2026-07-01T01:00:00Z"), at("2026-07-01T01:59:59Z")))
        assertTrue(cronDue("0 3 * * *", london, at("2026-07-01T01:00:00Z"), at("2026-07-01T02:00:00Z")))
        // ... and 03:00 UTC in winter.
        assertFalse(cronDue("0 3 * * *", london, at("2026-12-01T01:00:00Z"), at("2026-12-01T02:30:00Z")))
        assertTrue(cronDue("0 3 * * *", london, at("2026-12-01T01:00:00Z"), at("2026-12-01T03:00:30Z")))
    }

    @Test
    fun `the minute of the last run does not fire again`() {
        assertFalse(cronDue("0 3 * * *", ZoneId.of("UTC"), at("2026-12-01T03:00:10Z"), at("2026-12-01T03:00:50Z")))
        assertTrue(cronDue("*/5 * * * *", ZoneId.of("UTC"), at("2026-12-01T03:00:10Z"), at("2026-12-01T03:05:00Z")))
    }

    @Test
    fun `a long outage catches up with one run`() {
        assertTrue(cronDue("0 3 1 1 *", ZoneId.of("UTC"), at("2026-03-01T00:00:00Z"), at("2026-03-05T00:00:00Z")))
    }
}
