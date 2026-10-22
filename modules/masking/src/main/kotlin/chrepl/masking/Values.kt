package chrepl.masking

import chrepl.config.model.Normaliser
import chrepl.masking.types.ChType
import chrepl.masking.types.scalar
import java.math.BigDecimal
import java.math.BigInteger
import java.net.InetAddress
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/** A configuration/schema problem found while compiling a table masker. Never contains data values. */
class MaskingConfigException(
    val problems: List<String>,
) : Exception(problems.joinToString("; "))

/** A value could not be masked. The message names the column and domain only, never the value. */
open class MaskingException(
    message: String,
) : Exception(message) {
    /** The same failure, prefixed with where it happened (e.g. `db.table.column`). */
    open fun withContext(context: String): MaskingException = MaskingException("$context: $message")
}

class MaskingCollisionException(
    val domain: String,
    context: String? = null,
) : MaskingException(
        (context?.let { "$it: " } ?: "") +
            "collision in masking domain '$domain': two different inputs produced the same output in this chunk",
    ) {
    override fun withContext(context: String) = MaskingCollisionException(domain, context)
}

/**
 * Type-aware canonical text of a value (SPEC §6.2). Integers and their decimal strings share a
 * canonical form, so `UInt64` and `String` columns in the same domain mask consistently.
 */
object Canonical {
    fun of(value: Any): String =
        when (value) {
            is String -> Normalizer.normalize(value, Normalizer.Form.NFC)
            is Long, is Int, is Short, is Byte, is BigInteger -> value.toString()
            is BigDecimal -> if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString()
            is Double -> if (value.isFinite()) Canonical.of(BigDecimal(value)) else value.toString()
            is Float -> of(value.toDouble())
            is LocalDate -> value.toString()
            is Instant -> value.toString()
            is UUID -> value.toString()
            is InetAddress -> value.hostAddress
            is Boolean -> value.toString()
            else -> throw MaskingException("unsupported value class ${value::class.simpleName}")
        }

    fun normalise(
        value: String,
        normalisers: List<Normaliser>,
    ): String =
        normalisers.fold(value) { acc, n ->
            when (n) {
                Normaliser.TRIM -> acc.trim()
                Normaliser.LOWER -> acc.lowercase(Locale.ROOT)
                Normaliser.NFC -> Normalizer.normalize(acc, Normalizer.Form.NFC)
            }
        }
}

/** Integer values as [BigInteger], whatever their JVM class. */
internal fun Any.toBigIntegerValue(): BigInteger =
    when (this) {
        is BigInteger -> this
        is Long -> BigInteger.valueOf(this)
        is Int -> BigInteger.valueOf(toLong())
        is Short -> BigInteger.valueOf(toLong())
        is Byte -> BigInteger.valueOf(toLong())
        else -> throw MaskingException("expected an integer value, got ${this::class.simpleName}")
    }

/** Converts [value] to the JVM class used for [type] (Long or BigInteger). */
internal fun ChType.IntType.box(value: BigInteger): Any = if (usesLong) value.toLong() else value

internal fun ChType.IntType.contains(value: BigInteger) = value >= min && value <= max

internal fun ChType.IntType.clamp(value: BigInteger): BigInteger = value.max(min).min(max)

internal val DATE_MIN: LocalDate = LocalDate.of(1970, 1, 1)
internal val DATE_MAX: LocalDate = LocalDate.of(2149, 6, 6)
internal val DATE32_MIN: LocalDate = LocalDate.of(1900, 1, 1)
internal val DATE32_MAX: LocalDate = LocalDate.of(2299, 12, 31)
internal val DATETIME_MAX: Instant = Instant.ofEpochSecond(4_294_967_295L)
internal val DATETIME64_MIN: Instant = Instant.parse("1900-01-01T00:00:00Z")
internal val DATETIME64_MAX: Instant = Instant.parse("2299-12-31T23:59:59.999999999Z")

/** The value ClickHouse stores for a non-nullable column when no value is given. */
fun defaultValue(type: ChType): Any =
    when (val t = if (type is ChType.LowCardinalityType) type.inner else type) {
        is ChType.IntType -> t.box(BigInteger.ZERO)
        is ChType.FloatType -> 0.0
        is ChType.DecimalType -> BigDecimal.ZERO.setScale(t.scale)
        ChType.StringType, is ChType.FixedStringType -> ""
        ChType.DateType, ChType.Date32Type -> DATE_MIN
        is ChType.DateTimeType, is ChType.DateTime64Type -> Instant.EPOCH
        ChType.UuidType -> UUID(0, 0)
        ChType.Ipv4Type -> InetAddress.getByAddress(ByteArray(4))
        ChType.Ipv6Type -> InetAddress.getByAddress(ByteArray(16))
        ChType.BoolType -> false
        is ChType.EnumType -> t.values.keys.first()
        is ChType.ArrayType -> emptyList<Any?>()
        is ChType.MapType -> emptyMap<Any?, Any?>()
        is ChType.TupleType -> t.elements.map { defaultValue(it) }
        ChType.JsonType -> "{}"
        is ChType.NullableType -> defaultValue(t.inner)
        is ChType.LowCardinalityType -> defaultValue(t.inner)
        is ChType.Unsupported -> throw MaskingException("no default value for unsupported type $t")
    }

/** Parses a `constant` strategy literal for [type]; throws [IllegalArgumentException] when it does not fit. */
fun parseLiteral(
    text: String,
    type: ChType,
): Any =
    when (val t = type.scalar()) {
        is ChType.IntType -> {
            val v = text.trim().toBigIntegerOrNull() ?: throw IllegalArgumentException("'$text' is not an integer")
            require(t.contains(v)) { "'$text' is outside the range of $t" }
            t.box(v)
        }
        is ChType.FloatType -> text.trim().toDoubleOrNull() ?: throw IllegalArgumentException("'$text' is not a number")
        is ChType.DecimalType -> {
            val v = text.trim().toBigDecimalOrNull() ?: throw IllegalArgumentException("'$text' is not a decimal")
            v.setScale(t.scale, java.math.RoundingMode.UNNECESSARY)
        }
        ChType.StringType -> text
        is ChType.FixedStringType -> {
            require(text.toByteArray().size <= t.length) { "'$text' is longer than ${t.length} bytes" }
            text
        }
        ChType.DateType, ChType.Date32Type -> LocalDate.parse(text.trim())
        is ChType.DateTimeType, is ChType.DateTime64Type -> Instant.parse(text.trim())
        ChType.UuidType -> UUID.fromString(text.trim())
        ChType.BoolType -> text.trim().toBooleanStrictOrNull() ?: throw IllegalArgumentException("'$text' is not true/false")
        is ChType.EnumType -> text.also { require(it in t.values) { "'$text' is not a value of $t" } }
        ChType.JsonType -> text
        else -> throw IllegalArgumentException("constant values are not supported for $t")
    }
