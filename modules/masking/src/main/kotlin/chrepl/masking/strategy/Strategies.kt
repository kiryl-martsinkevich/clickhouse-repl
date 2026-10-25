package chrepl.masking.strategy

import chrepl.config.model.Domain
import chrepl.config.model.EmailDomainMode
import chrepl.config.model.EmailLocalPart
import chrepl.config.model.HashEncoding
import chrepl.config.model.Strategy
import chrepl.masking.Canonical
import chrepl.masking.MaskingException
import chrepl.masking.Prf
import chrepl.masking.box
import chrepl.masking.clamp
import chrepl.masking.contains
import chrepl.masking.parseLiteral
import chrepl.masking.toBigIntegerValue
import chrepl.types.ChType
import java.math.BigInteger
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * Masks one non-null scalar value (`Nullable` / `LowCardinality` already stripped) of a column
 * type. Implementations are deterministic in (key, input, entity) unless noted, and not thread-safe.
 */
interface ScalarStrategy {
    /** Null when [type] is supported, otherwise the reason it is not. */
    fun unsupported(type: ChType): String?

    /** [entity] is the canonical value of the row's entity column, for entity-seeded strategies. */
    fun mask(
        value: Any,
        type: ChType,
        entity: String?,
    ): Any?

    /** Worth caching (CPU-heavy and deterministic). */
    val cacheable: Boolean get() = true

    /** Also replaces NULL inputs (only `constant`). */
    val appliesToNull: Boolean get() = false
}

/** Per-domain derived keys (SPEC §6.2). */
class DomainKeys(
    val hmac: ByteArray,
    val ff1: ByteArray,
) {
    fun wipe() {
        hmac.fill(0)
        ff1.fill(0)
    }
}

object Strategies {
    fun create(
        domain: Domain,
        keys: DomainKeys,
        dictionaries: Map<String, List<String>>,
    ): ScalarStrategy =
        when (domain.strategy) {
            Strategy.HASH -> HashStrategy(Prf(keys.hmac), domain.encoding ?: HashEncoding.HEX, domain.length)
            Strategy.TOKENIZE -> TokenizeStrategy(Tokenizer(Prf(keys.hmac)))
            Strategy.FPE_FF1 -> Ff1Strategy(keys.ff1, domain.alphabet ?: "digits", domain.width)
            Strategy.EMAIL ->
                EmailStrategy(Prf(keys.hmac), domain.localPart ?: EmailLocalPart.TOKENIZE, domain.domain ?: EmailDomainMode.KEEP)
            Strategy.DICTIONARY -> {
                val name = domain.dictionary ?: throw MaskingException("dictionary strategy without a dictionary")
                DictionaryStrategy(Prf(keys.hmac), dictionaries[name] ?: throw MaskingException("dictionary '$name' is not loaded"))
            }
            Strategy.DATE_SHIFT ->
                DateShiftStrategy(
                    Prf(keys.hmac),
                    domain.maxDays ?: throw MaskingException("date_shift without max_days"),
                )
            Strategy.NUMERIC_NOISE ->
                NumericNoiseStrategy(Prf(keys.hmac), domain.relative ?: throw MaskingException("numeric_noise without relative"))
            Strategy.PARTIAL -> PartialStrategy(domain.keepFirst ?: 0, domain.keepLast ?: 0, domain.maskChar?.single() ?: '*')
            Strategy.NULL -> NullStrategy
            Strategy.CONSTANT -> ConstantStrategy(domain.value ?: throw MaskingException("constant without value"))
        }
}

// ------------------------------------------------------------------------------------------- tokenize

/**
 * Format-preserving, keyed, non-bijective substitution (SPEC §6.3): digits stay digits (the first
 * digit of a run stays non-zero if it was), ASCII letters keep their case, other letters become
 * ASCII letters, and everything else (separators, spaces, punctuation) is kept. The keystream
 * depends on the whole input, so equal inputs give equal outputs.
 */
class Tokenizer(
    private val prf: Prf,
) {
    fun tokenize(
        text: String,
        seed: String = Canonical.of(text),
    ): String {
        val codePoints = text.codePoints().toArray()
        val stream = prf.stream(seed, codePoints.size * 2)
        val out = StringBuilder(codePoints.size)
        var inDigitRun = false
        for ((i, cp) in codePoints.withIndex()) {
            val k = ((stream[2 * i].toInt() and 0xFF) shl 8) or (stream[2 * i + 1].toInt() and 0xFF)
            when {
                Character.isDigit(cp) -> {
                    val d = Character.digit(cp, 10)
                    val digit =
                        when {
                            inDigitRun -> (d + k) % 10
                            d == 0 -> 0
                            else -> 1 + (d - 1 + k) % 9
                        }
                    out.append('0' + digit)
                    inDigitRun = true
                }

                cp in 'a'.code..'z'.code -> {
                    out.append('a' + (cp - 'a'.code + k) % 26)
                    inDigitRun = false
                }

                cp in 'A'.code..'Z'.code -> {
                    out.append('A' + (cp - 'A'.code + k) % 26)
                    inDigitRun = false
                }

                Character.isLetter(cp) -> {
                    out.append((if (Character.isUpperCase(cp)) 'A' else 'a') + k % 26)
                    inDigitRun = false
                }

                else -> {
                    out.appendCodePoint(cp)
                    inDigitRun = false
                }
            }
        }
        return out.toString()
    }
}

class TokenizeStrategy(
    private val tokenizer: Tokenizer,
) : ScalarStrategy {
    override fun unsupported(type: ChType): String? =
        when (type) {
            ChType.StringType, is ChType.FixedStringType, is ChType.IntType -> null
            else -> "tokenize supports String, FixedString and integer columns, not $type"
        }

    override fun mask(
        value: Any,
        type: ChType,
        entity: String?,
    ): Any =
        when (type) {
            is ChType.IntType -> maskInteger(value.toBigIntegerValue(), type)
            else -> tokenizer.tokenize(value as String)
        }

    private fun maskInteger(
        value: BigInteger,
        type: ChType.IntType,
    ): Any {
        val canonical = value.toString()
        val digits = canonical.removePrefix("-")
        val sign = if (value.signum() < 0) "-" else ""
        for (attempt in 0..MAX_RETRIES) {
            val seed = if (attempt == 0) canonical else "$canonical#$attempt"
            val candidate = BigInteger(sign + tokenizer.tokenize(digits, seed))
            if (type.contains(candidate)) return type.box(candidate)
        }
        // Documented fallback (SPEC §6.4): reduce into the type's range.
        val span = type.max - type.min + BigInteger.ONE
        val reduced = (BigInteger(sign + tokenizer.tokenize(digits, canonical)) - type.min).mod(span) + type.min
        return type.box(reduced)
    }

    private companion object {
        const val MAX_RETRIES = 8
    }
}

// ------------------------------------------------------------------------------------------- hash

class HashStrategy(
    private val prf: Prf,
    private val encoding: HashEncoding,
    private val length: Int?,
) : ScalarStrategy {
    override fun unsupported(type: ChType): String? =
        when (type) {
            ChType.StringType, is ChType.FixedStringType, is ChType.IntType, ChType.UuidType -> null
            else -> "hash supports String, FixedString, integer and UUID columns, not $type"
        }

    override fun mask(
        value: Any,
        type: ChType,
        entity: String?,
    ): Any {
        val digest = prf.digest(Canonical.of(value))
        return when (type) {
            is ChType.IntType -> {
                val span = type.max - type.min + BigInteger.ONE
                type.box(BigInteger(1, digest).mod(span) + type.min)
            }

            ChType.UuidType -> {
                var msb = 0L
                var lsb = 0L
                for (i in 0 until 8) msb = (msb shl 8) or (digest[i].toLong() and 0xFF)
                for (i in 8 until 16) lsb = (lsb shl 8) or (digest[i].toLong() and 0xFF)
                UUID(msb, lsb)
            }

            else -> {
                val text =
                    when (encoding) {
                        HashEncoding.HEX -> HexFormat.of().formatHex(digest)
                        HashEncoding.BASE64 -> Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
                    }
                val limit = minOf(length ?: text.length, (type as? ChType.FixedStringType)?.length ?: text.length)
                text.take(limit)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------- email

class EmailStrategy(
    private val prf: Prf,
    private val localPart: EmailLocalPart,
    private val domainMode: EmailDomainMode,
) : ScalarStrategy {
    private val tokenizer = Tokenizer(prf)

    override fun unsupported(type: ChType): String? =
        if (type == ChType.StringType || type is ChType.FixedStringType) null else "email supports String columns, not $type"

    override fun mask(
        value: Any,
        type: ChType,
        entity: String?,
    ): Any {
        val email = value as String
        val at = email.lastIndexOf('@')
        if (at <= 0 || at == email.length - 1) return tokenizer.tokenize(email)
        val canonical = Canonical.of(email)
        val local = email.substring(0, at)
        val domain = email.substring(at + 1)
        val maskedLocal =
            when (localPart) {
                EmailLocalPart.TOKENIZE -> tokenizer.tokenize(local, "local:$canonical")
                EmailLocalPart.HASH -> HexFormat.of().formatHex(prf.digest("local:$canonical")).take(12)
            }
        val maskedDomain =
            when (domainMode) {
                EmailDomainMode.KEEP -> domain
                EmailDomainMode.MAP_TO_EXAMPLE -> "example.com"
                EmailDomainMode.TOKENIZE -> {
                    val dot = domain.lastIndexOf('.')
                    if (dot <= 0) {
                        tokenizer.tokenize(domain, "domain:${Canonical.of(domain)}")
                    } else {
                        tokenizer.tokenize(domain.substring(0, dot), "domain:${Canonical.of(domain)}") + domain.substring(dot)
                    }
                }
            }
        return "$maskedLocal@$maskedDomain"
    }
}

// ------------------------------------------------------------------------------------------- dictionary

class DictionaryStrategy(
    private val prf: Prf,
    private val entries: List<String>,
) : ScalarStrategy {
    init {
        require(entries.isNotEmpty()) { "dictionary is empty" }
    }

    override fun unsupported(type: ChType): String? =
        if (type == ChType.StringType || type is ChType.FixedStringType) null else "dictionary supports String columns, not $type"

    override fun mask(
        value: Any,
        type: ChType,
        entity: String?,
    ): Any {
        val entry = entries[prf.below(Canonical.of(value), entries.size.toLong()).toInt()]
        return if (type is ChType.FixedStringType) truncateUtf8(entry, type.length) else entry
    }
}

internal fun truncateUtf8(
    text: String,
    maxBytes: Int,
): String {
    if (text.toByteArray().size <= maxBytes) return text
    val sb = StringBuilder()
    var bytes = 0
    for (cp in text.codePoints()) {
        val size = String(Character.toChars(cp)).toByteArray().size
        if (bytes + size > maxBytes) break
        sb.appendCodePoint(cp)
        bytes += size
    }
    return sb.toString()
}

// ------------------------------------------------------------------------------------------- partial / null / constant

class PartialStrategy(
    private val keepFirst: Int,
    private val keepLast: Int,
    private val maskChar: Char,
) : ScalarStrategy {
    override val cacheable = false

    override fun unsupported(type: ChType): String? =
        if (type == ChType.StringType || type is ChType.FixedStringType) null else "partial supports String columns, not $type"

    override fun mask(
        value: Any,
        type: ChType,
        entity: String?,
    ): Any {
        val cps = (value as String).codePoints().toArray()
        // Too short to keep anything without revealing most of it: mask everything.
        if (cps.size <= keepFirst + keepLast) return maskChar.toString().repeat(cps.size)
        val sb = StringBuilder()
        for ((i, cp) in cps.withIndex()) {
            if (i < keepFirst || i >= cps.size - keepLast) sb.appendCodePoint(cp) else sb.append(maskChar)
        }
        return sb.toString()
    }
}

object NullStrategy : ScalarStrategy {
    override val cacheable = false

    override fun unsupported(type: ChType): String? = null

    override fun mask(
        value: Any,
        type: ChType,
        entity: String?,
    ): Any? = null
}

class ConstantStrategy(
    private val literal: String,
) : ScalarStrategy {
    private val parsed = mutableMapOf<ChType, Any>()

    override val cacheable = false
    override val appliesToNull = true

    override fun unsupported(type: ChType): String? =
        runCatching { parsed.getOrPut(type) { parseLiteral(literal, type) } }.exceptionOrNull()?.let {
            "constant value does not fit $type: ${it.message}"
        }

    override fun mask(
        value: Any,
        type: ChType,
        entity: String?,
    ): Any = parsed.getOrPut(type) { parseLiteral(literal, type) }
}

/** Clamps helper re-exported for numeric strategies. */
internal fun ChType.IntType.fit(value: BigInteger): Any = box(clamp(value))
