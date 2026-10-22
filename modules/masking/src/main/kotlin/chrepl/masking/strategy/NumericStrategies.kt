package chrepl.masking.strategy

import chrepl.masking.Canonical
import chrepl.masking.DATE32_MAX
import chrepl.masking.DATE32_MIN
import chrepl.masking.DATETIME64_MAX
import chrepl.masking.DATETIME64_MIN
import chrepl.masking.DATETIME_MAX
import chrepl.masking.DATE_MAX
import chrepl.masking.DATE_MIN
import chrepl.masking.Prf
import chrepl.masking.toBigIntegerValue
import chrepl.types.ChType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Shifts dates by a keyed offset in [-maxDays, +maxDays] (SPEC §6.3). With an entity column every
 * date of the same entity moves by the same offset, preserving intervals; without one (or when the
 * entity is NULL) the offset is derived from the value itself. Results are clamped to the type range.
 */
class DateShiftStrategy(
    private val prf: Prf,
    private val maxDays: Int,
) : ScalarStrategy {
    override fun unsupported(type: ChType): String? =
        when (type) {
            ChType.DateType, ChType.Date32Type, is ChType.DateTimeType, is ChType.DateTime64Type -> null
            else -> "date_shift supports Date, Date32, DateTime and DateTime64 columns, not $type"
        }

    fun offsetDays(seed: String): Long = prf.below("shift:$seed", 2L * maxDays + 1) - maxDays

    override fun mask(
        value: Any,
        type: ChType,
        entity: String?,
    ): Any {
        val days = offsetDays(entity ?: Canonical.of(value))
        return when (type) {
            ChType.DateType -> clamp((value as LocalDate).plusDays(days), DATE_MIN, DATE_MAX)
            ChType.Date32Type -> clamp((value as LocalDate).plusDays(days), DATE32_MIN, DATE32_MAX)
            is ChType.DateTimeType -> clamp((value as Instant).plus(Duration.ofDays(days)), Instant.EPOCH, DATETIME_MAX)
            else -> clamp((value as Instant).plus(Duration.ofDays(days)), DATETIME64_MIN, DATETIME64_MAX)
        }
    }

    private fun <T : Comparable<T>> clamp(
        value: T,
        min: T,
        max: T,
    ): T =
        if (value < min) {
            min
        } else if (value > max) {
            max
        } else {
            value
        }
}

/**
 * Multiplies numbers by a keyed factor in [1 − relative, 1 + relative] (SPEC §6.3), seeded by the
 * entity column when given (all amounts of one entity move together) or by the value. Integers and
 * decimals are rounded half-even to their scale and clamped to the type range; signs never flip.
 */
class NumericNoiseStrategy(
    private val prf: Prf,
    private val relative: Double,
) : ScalarStrategy {
    override fun unsupported(type: ChType): String? =
        when (type) {
            is ChType.IntType, is ChType.DecimalType, is ChType.FloatType -> null
            else -> "numeric_noise supports integer, Decimal and Float columns, not $type"
        }

    fun factor(seed: String): Double = 1.0 + relative * (2.0 * prf.unit("noise:$seed") - 1.0)

    override fun mask(
        value: Any,
        type: ChType,
        entity: String?,
    ): Any {
        val f = factor(entity ?: Canonical.of(value))
        return when (type) {
            is ChType.FloatType -> {
                (value as Double) * f
            }

            is ChType.DecimalType -> {
                val scaled = (value as BigDecimal).multiply(BigDecimal(f)).setScale(type.scale, RoundingMode.HALF_EVEN)
                val bound = BigDecimal.TEN.pow(type.precision - type.scale) - BigDecimal.ONE.movePointLeft(type.scale)
                scaled.min(bound).max(bound.negate())
            }

            else -> {
                val int = type as ChType.IntType
                val scaled = BigDecimal(value.toBigIntegerValue()).multiply(BigDecimal(f)).setScale(0, RoundingMode.HALF_EVEN)
                int.fit(scaled.toBigInteger())
            }
        }
    }
}
