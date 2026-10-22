package chrepl.config.cron

import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * A five-field cron expression (`minute hour day-of-month month day-of-week`): `*`, lists, ranges,
 * steps, month/day names and `L` (last day of the month) in day-of-month. When both day fields are
 * restricted, a day matches if either does (classic cron semantics).
 */
class CronExpression private constructor(
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val lastDayOfMonth: Boolean,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>,
    private val domRestricted: Boolean,
    private val dowRestricted: Boolean,
) {
    fun matches(time: ZonedDateTime): Boolean {
        if (time.minute !in minutes || time.hour !in hours || time.monthValue !in months) return false
        val dom = time.dayOfMonth in daysOfMonth || (lastDayOfMonth && time.dayOfMonth == time.toLocalDate().lengthOfMonth())
        val dow = (time.dayOfWeek.value % 7) in daysOfWeek
        return when {
            domRestricted && dowRestricted -> dom || dow
            domRestricted -> dom
            dowRestricted -> dow
            else -> true
        }
    }

    /** True when a firing within the last [duration] (inclusive of now) is still running at [now]. */
    fun withinWindow(
        now: ZonedDateTime,
        duration: Duration,
    ): Boolean {
        var t = now.truncatedTo(ChronoUnit.MINUTES)
        val earliest = now.minus(duration)
        while (t > earliest) {
            if (matches(t)) return true
            t = t.minusMinutes(1)
        }
        return false
    }

    companion object {
        fun parse(expression: String): CronExpression {
            CronValidator.validate(expression)?.let { throw IllegalArgumentException(it) }
            val p = expression.trim().split(Regex("\\s+"))
            val f = CronValidator.FIELDS
            val dowValues = CronValidator.values(p[4], f[4]).map { it % 7 }.toSet()
            return CronExpression(
                minutes = CronValidator.values(p[0], f[0]),
                hours = CronValidator.values(p[1], f[1]),
                daysOfMonth =
                    p[2]
                        .split(',')
                        .filter { it != "L" }
                        .flatMap { CronValidator.values(it, f[2]) }
                        .toSet(),
                lastDayOfMonth = p[2].split(',').contains("L"),
                months = CronValidator.values(p[3], f[3]),
                daysOfWeek = dowValues,
                domRestricted = p[2] != "*",
                dowRestricted = p[4] != "*",
            )
        }
    }
}

/** Validation and field expansion for [CronExpression]. */
object CronValidator {
    internal class Field(
        val name: String,
        val range: IntRange,
        val names: List<String> = emptyList(),
        val allowLast: Boolean = false,
    )

    internal val FIELDS =
        listOf(
            Field("minute", 0..59),
            Field("hour", 0..23),
            Field("day-of-month", 1..31, allowLast = true),
            Field("month", 1..12, names = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")),
            Field("day-of-week", 0..7, names = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")),
        )

    /** Returns null when valid, otherwise a description of the first problem. */
    fun validate(expression: String): String? {
        val parts = expression.trim().split(Regex("\\s+"))
        if (parts.size != FIELDS.size) return "expected 5 fields (minute hour day-of-month month day-of-week), got ${parts.size}"
        for ((part, field) in parts.zip(FIELDS)) {
            for (item in part.split(',')) {
                checkItem(item, field)?.let { return "${field.name} '$part': $it" }
            }
        }
        return null
    }

    /** All values a (validated) field expression selects. */
    internal fun values(
        part: String,
        field: Field,
    ): Set<Int> {
        val out = mutableSetOf<Int>()
        for (item in part.split(',')) {
            val base = item.substringBefore('/')
            val step = if ('/' in item) item.substringAfter('/').toInt() else 1
            val (lo, hi) =
                when {
                    base == "*" -> field.range.first to field.range.last
                    '-' in base -> value(base.substringBefore('-'), field)!! to value(base.substringAfter('-'), field)!!
                    '/' in item -> value(base, field)!! to field.range.last
                    else -> value(base, field)!!.let { it to it }
                }
            for (v in lo..hi step step) out += v
        }
        return out
    }

    private fun checkItem(
        item: String,
        field: Field,
    ): String? {
        if (item.isEmpty()) return "empty list element"
        if (field.allowLast && item == "L") return null
        val base = item.substringBefore('/')
        if ('/' in item) {
            val step = item.substringAfter('/').toIntOrNull()
            if (step == null || step < 1 || step > field.range.last) return "invalid step in '$item'"
        }
        if (base == "*") return null
        val bounds = base.split('-')
        if (bounds.size > 2) return "invalid range '$base'"
        val values = bounds.map { value(it, field) ?: return "invalid value '$it' (allowed ${field.range.first}-${field.range.last})" }
        if (values.size == 2 && values[0] > values[1]) return "range '$base' is reversed"
        return null
    }

    private fun value(
        token: String,
        field: Field,
    ): Int? {
        token.toIntOrNull()?.let { return it.takeIf { v -> v in field.range } }
        val index = field.names.indexOf(token.uppercase())
        if (index < 0) return null
        return index + field.range.first
    }
}
