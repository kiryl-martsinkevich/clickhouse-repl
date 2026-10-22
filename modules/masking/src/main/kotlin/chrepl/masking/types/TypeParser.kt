package chrepl.masking.types

/** Parses ClickHouse type strings such as `Map(String, Array(Nullable(Decimal(18, 4))))`. */
object TypeParser {
    fun parse(text: String): ChType {
        val parser = Parser(text)
        val type = parser.type()
        parser.skipSpaces()
        if (!parser.atEnd()) throw IllegalArgumentException("unexpected '${parser.rest()}' in type '$text'")
        return type
    }

    private class Parser(
        val text: String,
    ) {
        var pos = 0

        fun atEnd() = pos >= text.length

        fun rest() = text.substring(pos)

        fun skipSpaces() {
            while (!atEnd() && text[pos].isWhitespace()) pos++
        }

        fun peek(): Char? {
            skipSpaces()
            return text.getOrNull(pos)
        }

        fun expect(c: Char) {
            if (peek() != c) throw IllegalArgumentException("expected '$c' at position $pos in type '$text'")
            pos++
        }

        fun identifier(): String {
            skipSpaces()
            val start = pos
            while (!atEnd() && (text[pos].isLetterOrDigit() || text[pos] == '_')) pos++
            if (start == pos) throw IllegalArgumentException("expected a name at position $pos in type '$text'")
            return text.substring(start, pos)
        }

        fun number(): Int {
            skipSpaces()
            val start = pos
            if (!atEnd() && text[pos] == '-') pos++
            while (!atEnd() && text[pos].isDigit()) pos++
            return text.substring(start, pos).toIntOrNull()
                ?: throw IllegalArgumentException("expected a number at position $start in type '$text'")
        }

        fun quoted(): String {
            expect('\'')
            val sb = StringBuilder()
            while (!atEnd() && text[pos] != '\'') {
                if (text[pos] == '\\' && pos + 1 < text.length) pos++
                sb.append(text[pos++])
            }
            expect('\'')
            return sb.toString()
        }

        /** Parses `(a, b, ...)` with [item] for each element; returns empty when there are no parentheses. */
        fun <T> args(item: () -> T): List<T> {
            if (peek() != '(') return emptyList()
            pos++
            val result = mutableListOf<T>()
            if (peek() == ')') {
                pos++
                return result
            }
            while (true) {
                result += item()
                when (peek()) {
                    ',' -> pos++
                    ')' -> {
                        pos++
                        return result
                    }
                    else -> throw IllegalArgumentException("expected ',' or ')' at position $pos in type '$text'")
                }
            }
        }

        /** Skips a balanced argument list without interpreting it. */
        fun skipArgs() {
            if (peek() != '(') return
            var depth = 0
            while (!atEnd()) {
                when (text[pos]) {
                    '(' -> depth++
                    ')' ->
                        if (--depth == 0) {
                            pos++
                            return
                        }
                    '\'' -> {
                        quoted()
                        continue
                    }
                }
                pos++
            }
            throw IllegalArgumentException("unbalanced parentheses in type '$text'")
        }

        fun type(): ChType {
            val start = pos
            val name = identifier()
            INT_TYPES[name]?.let { return it }
            return when (name) {
                "Float32" -> ChType.FloatType(32)
                "Float64" -> ChType.FloatType(64)
                "String" -> ChType.StringType
                "FixedString" -> ChType.FixedStringType(args { number() }.single())
                "Bool", "Boolean" -> ChType.BoolType
                "UUID" -> ChType.UuidType
                "IPv4" -> ChType.Ipv4Type
                "IPv6" -> ChType.Ipv6Type
                "Date" -> ChType.DateType
                "Date32" -> ChType.Date32Type
                "DateTime" -> ChType.DateTimeType(args { quoted() }.singleOrNull())
                "DateTime64" -> dateTime64()
                "Decimal" -> args { number() }.let { ChType.DecimalType(it[0], it.getOrElse(1) { 0 }) }
                "Decimal32" -> ChType.DecimalType(9, args { number() }.single())
                "Decimal64" -> ChType.DecimalType(18, args { number() }.single())
                "Decimal128" -> ChType.DecimalType(38, args { number() }.single())
                "Decimal256" -> ChType.DecimalType(76, args { number() }.single())
                "Enum8", "Enum16", "Enum" -> enum(if (name == "Enum16") 16 else 8)
                "Nullable" -> ChType.NullableType(args { type() }.single())
                "LowCardinality" -> ChType.LowCardinalityType(args { type() }.single())
                "Array" -> ChType.ArrayType(args { type() }.single())
                "Map" -> args { type() }.let { ChType.MapType(it[0], it[1]) }
                "Tuple" -> tuple()
                "JSON", "Object" -> {
                    skipArgs()
                    ChType.JsonType
                }
                "SimpleAggregateFunction" -> {
                    // SimpleAggregateFunction(func, T) stores plain T values.
                    expect('(')
                    identifier()
                    expect(',')
                    val inner = type()
                    expect(')')
                    inner
                }
                else -> {
                    skipArgs()
                    ChType.Unsupported(text.substring(start, pos).trim())
                }
            }
        }

        private fun dateTime64(): ChType {
            expect('(')
            val precision = number()
            val tz =
                if (peek() == ',') {
                    pos++
                    quoted()
                } else {
                    null
                }
            expect(')')
            return ChType.DateTime64Type(precision, tz)
        }

        private fun enum(bits: Int): ChType {
            val entries =
                args {
                    val label = quoted()
                    expect('=')
                    label to number()
                }
            return ChType.EnumType(bits, entries.toMap(LinkedHashMap()))
        }

        private fun tuple(): ChType {
            val elements =
                args {
                    // Named element: `name Type`; unnamed: `Type`.
                    val save = pos
                    val first = identifier()
                    skipSpaces()
                    val next = text.getOrNull(pos)
                    if (next != null && (next.isLetter() || next == '_') && first !in INT_TYPES && first !in KNOWN_TYPE_NAMES) {
                        first to type()
                    } else {
                        pos = save
                        null to type()
                    }
                }
            return ChType.TupleType(elements.map { it.second }, elements.map { it.first })
        }
    }

    private val INT_TYPES: Map<String, ChType.IntType> =
        listOf(8, 16, 32, 64, 128, 256)
            .flatMap { bits -> listOf("Int$bits" to ChType.IntType(bits, true), "UInt$bits" to ChType.IntType(bits, false)) }
            .toMap()

    private val KNOWN_TYPE_NAMES =
        setOf(
            "Float32",
            "Float64",
            "String",
            "FixedString",
            "Bool",
            "Boolean",
            "UUID",
            "IPv4",
            "IPv6",
            "Date",
            "Date32",
            "DateTime",
            "DateTime64",
            "Decimal",
            "Decimal32",
            "Decimal64",
            "Decimal128",
            "Decimal256",
            "Enum8",
            "Enum16",
            "Nullable",
            "LowCardinality",
            "Array",
            "Map",
            "Tuple",
            "JSON",
            "Object",
            "SimpleAggregateFunction",
        )
}
