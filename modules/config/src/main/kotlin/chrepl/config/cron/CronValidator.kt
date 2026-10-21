package chrepl.config.cron

/**
 * Validates five-field cron expressions (`minute hour day-of-month month day-of-week`).
 * Supports `*`, lists, ranges, steps, month/day names and `L` (last day) in day-of-month.
 * Next-fire computation is added with the scheduler (PLAN M5).
 */
object CronValidator {
    private class Field(
        val name: String,
        val range: IntRange,
        val names: List<String> = emptyList(),
        val allowLast: Boolean = false,
    )

    private val FIELDS =
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
