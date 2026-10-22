package chrepl.types

import java.math.BigInteger

/**
 * A ClickHouse column type, as written in `system.columns.type`.
 *
 * The masking engine works on plain JVM values; the transfer layer (PLAN M2) converts rows to
 * this representation:
 *
 * | ClickHouse type | JVM value |
 * |---|---|
 * | Int8–Int64, UInt8–UInt32 | [Long] |
 * | UInt64, Int128/UInt128, Int256/UInt256 | [BigInteger] |
 * | Float32/Float64 | [Double] |
 * | Decimal(P, S) | [java.math.BigDecimal] |
 * | String, FixedString(N), Enum8/16, JSON | [String] |
 * | Date, Date32 | [java.time.LocalDate] |
 * | DateTime, DateTime64 | [java.time.Instant] |
 * | UUID | [java.util.UUID] |
 * | IPv4, IPv6 | [java.net.InetAddress] |
 * | Bool | [Boolean] |
 * | Array(T), Tuple(...) | [List] |
 * | Map(K, V) | [Map] (insertion-ordered) |
 * | Nullable(T) | value of T, or null |
 * | LowCardinality(T) | value of T |
 */
sealed interface ChType {
    data class IntType(
        val bits: Int,
        val signed: Boolean,
    ) : ChType {
        val min: BigInteger = if (signed) BigInteger.ONE.shiftLeft(bits - 1).negate() else BigInteger.ZERO
        val max: BigInteger =
            if (signed) {
                BigInteger.ONE.shiftLeft(bits - 1) - BigInteger.ONE
            } else {
                BigInteger.ONE.shiftLeft(bits) -
                    BigInteger.ONE
            }

        /** True when values are carried as [Long]; otherwise as [BigInteger]. */
        val usesLong: Boolean = bits < 64 || (bits == 64 && signed)

        override fun toString() = (if (signed) "Int" else "UInt") + bits
    }

    data class FloatType(
        val bits: Int,
    ) : ChType {
        override fun toString() = "Float$bits"
    }

    data class DecimalType(
        val precision: Int,
        val scale: Int,
    ) : ChType {
        override fun toString() = "Decimal($precision, $scale)"
    }

    data object StringType : ChType {
        override fun toString() = "String"
    }

    data class FixedStringType(
        val length: Int,
    ) : ChType {
        override fun toString() = "FixedString($length)"
    }

    data object DateType : ChType {
        override fun toString() = "Date"
    }

    data object Date32Type : ChType {
        override fun toString() = "Date32"
    }

    data class DateTimeType(
        val timezone: String?,
    ) : ChType {
        override fun toString() = if (timezone == null) "DateTime" else "DateTime('$timezone')"
    }

    data class DateTime64Type(
        val precision: Int,
        val timezone: String?,
    ) : ChType {
        override fun toString() = if (timezone == null) "DateTime64($precision)" else "DateTime64($precision, '$timezone')"
    }

    data object UuidType : ChType {
        override fun toString() = "UUID"
    }

    data object Ipv4Type : ChType {
        override fun toString() = "IPv4"
    }

    data object Ipv6Type : ChType {
        override fun toString() = "IPv6"
    }

    data object BoolType : ChType {
        override fun toString() = "Bool"
    }

    data class EnumType(
        val bits: Int,
        val values: Map<String, Int>,
    ) : ChType {
        override fun toString() = "Enum$bits(${values.entries.joinToString { "'${it.key}' = ${it.value}" }})"
    }

    data class NullableType(
        val inner: ChType,
    ) : ChType {
        override fun toString() = "Nullable($inner)"
    }

    data class LowCardinalityType(
        val inner: ChType,
    ) : ChType {
        override fun toString() = "LowCardinality($inner)"
    }

    data class ArrayType(
        val element: ChType,
    ) : ChType {
        override fun toString() = "Array($element)"
    }

    data class MapType(
        val key: ChType,
        val value: ChType,
    ) : ChType {
        override fun toString() = "Map($key, $value)"
    }

    data class TupleType(
        val elements: List<ChType>,
        val names: List<String?> = elements.map { null },
    ) : ChType {
        override fun toString() =
            "Tuple(${elements.indices.joinToString { i -> names[i]?.let { "$it ${elements[i]}" } ?: elements[i].toString() }})"
    }

    data object JsonType : ChType {
        override fun toString() = "JSON"
    }

    /** A type the masking engine does not know; only `pass` is allowed for it. */
    data class Unsupported(
        val text: String,
    ) : ChType {
        override fun toString() = text
    }
}

/** Strips `LowCardinality`, which does not change values. */
fun ChType.withoutLowCardinality(): ChType = if (this is ChType.LowCardinalityType) inner.withoutLowCardinality() else this

/** Strips `Nullable` and `LowCardinality` wrappers. */
fun ChType.scalar(): ChType =
    when (this) {
        is ChType.NullableType -> inner.scalar()
        is ChType.LowCardinalityType -> inner.scalar()
        else -> this
    }

val ChType.isNullable: Boolean
    get() =
        when (this) {
            is ChType.NullableType -> true
            is ChType.LowCardinalityType -> inner.isNullable
            else -> false
        }

val ChType.isStringLike: Boolean get() = scalar().let { it is ChType.StringType || it is ChType.FixedStringType }
