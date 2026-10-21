package chrepl.config.expr

/**
 * Extracts column identifiers from a ClickHouse key expression such as
 * `(toYYYYMM(trade_date), cityHash64(customer_id) % 16)`. Function names, string literals and
 * numbers are skipped; backtick- and double-quoted identifiers are unquoted. The result is used
 * only to intersect with configured column names, so stray keywords are harmless.
 */
object Identifiers {
    fun of(expression: String): Set<String> {
        val result = linkedSetOf<String>()
        var i = 0
        while (i < expression.length) {
            val c = expression[i]
            when {
                c == '\'' -> {
                    i = skipQuoted(expression, i, '\'')
                }

                c == '`' || c == '"' -> {
                    val end = skipQuoted(expression, i, c)
                    result += expression.substring(i + 1, (end - 1).coerceAtLeast(i + 1))
                    i = end
                }

                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < expression.length && (expression[i].isLetterOrDigit() || expression[i] == '_')) i++
                    val name = expression.substring(start, i)
                    var j = i
                    while (j < expression.length && expression[j].isWhitespace()) j++
                    if (j >= expression.length || expression[j] != '(') result += name
                }

                else -> {
                    i++
                }
            }
        }
        return result
    }

    /** Returns the index just past the closing quote (or the end of the string). */
    private fun skipQuoted(
        s: String,
        start: Int,
        quote: Char,
    ): Int {
        var i = start + 1
        while (i < s.length) {
            if (s[i] == '\\') {
                i += 2
                continue
            }
            if (s[i] == quote) return i + 1
            i++
        }
        return s.length
    }
}
