package chrepl.config.validate

import chrepl.config.model.Domain
import chrepl.config.model.SecretRef
import chrepl.config.model.Strategy
import java.util.Base64
import kotlin.io.path.exists

private const val MIN_KEY_BYTES = 32
private val ALPHABETS = setOf("digits", "lower", "upper", "alpha", "alphanumeric", "hex")

/** Options each strategy requires, and the ones it additionally accepts (SPEC §6.3). */
private class StrategyOptions(
    val required: Set<String>,
    val optional: Set<String> = emptySet(),
)

private val COMMON_OPTIONS = setOf("normalise", "approved_by")

private val STRATEGY_OPTIONS =
    mapOf(
        Strategy.HASH to StrategyOptions(emptySet(), setOf("encoding", "length", "collision_check")),
        Strategy.TOKENIZE to StrategyOptions(emptySet(), setOf("collision_check")),
        Strategy.FPE_FF1 to StrategyOptions(setOf("alphabet", "approved_by")),
        Strategy.EMAIL to StrategyOptions(setOf("local_part", "domain"), setOf("collision_check")),
        Strategy.DICTIONARY to StrategyOptions(setOf("dictionary")),
        Strategy.DATE_SHIFT to StrategyOptions(setOf("max_days")),
        Strategy.NUMERIC_NOISE to StrategyOptions(setOf("relative")),
        Strategy.PARTIAL to StrategyOptions(emptySet(), setOf("keep_first", "keep_last", "mask_char")),
        Strategy.NULL to StrategyOptions(emptySet()),
        Strategy.CONSTANT to StrategyOptions(setOf("value")),
    )

/** Strategies whose derivation is seeded by another column of the row. */
internal val ENTITY_STRATEGIES = setOf(Strategy.DATE_SHIFT, Strategy.NUMERIC_NOISE)

internal fun RuleContext.checkMasking() {
    val masking = config.masking
    if (masking.realm.isBlank()) error(Code.REALM, "masking.realm", "realm must not be blank")
    checkKey()
    for ((name, dictionary) in masking.dictionaries) {
        val path = "masking.dictionaries.$name.file"
        if (dictionary.file.isBlank()) {
            error(Code.DICTIONARY_FILE, path, "dictionary file must not be blank")
        } else if (options.checkFiles) {
            val file =
                baseDir?.resolve(dictionary.file) ?: java.nio.file.Path
                    .of(dictionary.file)
            if (!file.exists()) error(Code.DICTIONARY_FILE, path, "dictionary file not found: $file")
        }
    }
    for ((name, domain) in masking.domains) checkDomain("masking.domains.$name", domain)
}

private fun RuleContext.checkKey() {
    val key = config.masking.key
    when (key.provider) {
        "static" -> {}

        "fortanix" -> {
            error(Code.KEY_PROVIDER, "masking.key.provider", "the Fortanix DSM provider is planned for M8; use 'static' for now")
        }

        else -> {
            error(Code.KEY_PROVIDER, "masking.key.provider", "unknown key provider '${key.provider}' (supported: static)")
        }
    }
    if (key.version < 1) error(Code.KEY_VERSION, "masking.key.version", "key version must be at least 1")
    val ref = key.ref
    if (ref == null) {
        error(Code.KEY_REF, "masking.key.ref", "the static provider needs ref: \${file:/path} or \${env:NAME}")
        return
    }
    val value = checkSecret(ref, "masking.key.ref") ?: return
    val bytes = decodeKey(value)
    if (bytes == null) {
        error(Code.KEY_REF, "masking.key.ref", "key ${keyName(ref)} is neither hex nor base64")
    } else if (bytes.size < MIN_KEY_BYTES) {
        error(
            Code.KEY_TOO_SHORT,
            "masking.key.ref",
            "key ${keyName(ref)} has ${bytes.size * 8} bits; at least ${MIN_KEY_BYTES * 8} are required",
        )
    }
}

private fun keyName(ref: SecretRef) = ref.toString()

private fun decodeKey(value: String): ByteArray? {
    val text = value.trim()
    if (text.length % 2 == 0 && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        return text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    return runCatching { Base64.getDecoder().decode(text) }.getOrNull()
}

private fun RuleContext.checkDomain(
    path: String,
    domain: Domain,
) {
    val spec = STRATEGY_OPTIONS.getValue(domain.strategy)
    val strategyName = domain.strategy.name.lowercase()
    val present = presentOptions(domain)
    for (missing in spec.required - present) {
        error(Code.STRATEGY_OPTION_MISSING, "$path.$missing", "strategy $strategyName requires '$missing'")
    }
    for (extra in present - spec.required - spec.optional - COMMON_OPTIONS) {
        error(Code.STRATEGY_OPTION_UNSUPPORTED, "$path.$extra", "'$extra' does not apply to strategy $strategyName")
    }

    fun invalid(
        option: String,
        message: String,
    ) = error(Code.STRATEGY_OPTION_INVALID, "$path.$option", message)

    domain.approvedBy?.let { if (it.isBlank()) invalid("approved_by", "approved_by must name an approver") }
    domain.length?.let { if (it < 8) invalid("length", "hash length must be at least 8 characters") }
    domain.alphabet?.let { if (it !in ALPHABETS) invalid("alphabet", "unknown alphabet '$it' (allowed: ${ALPHABETS.joinToString()})") }
    domain.dictionary?.let {
        if (it !in config.masking.dictionaries) error(Code.UNKNOWN_DICTIONARY, "$path.dictionary", "unknown dictionary '$it'")
    }
    domain.maxDays?.let { if (it !in 1..3650) invalid("max_days", "max_days must be between 1 and 3650") }
    domain.relative?.let { if (it <= 0.0 || it >= 1.0) invalid("relative", "relative must be between 0 and 1 (exclusive)") }
    domain.keepFirst?.let { if (it < 0) invalid("keep_first", "keep_first must not be negative") }
    domain.keepLast?.let { if (it < 0) invalid("keep_last", "keep_last must not be negative") }
    domain.maskChar?.let { if (it.length != 1) invalid("mask_char", "mask_char must be a single character") }
    if (domain.strategy == Strategy.PARTIAL && domain.keepFirst == null && domain.keepLast == null) {
        error(Code.STRATEGY_OPTION_MISSING, "$path.keep_last", "strategy partial requires keep_first and/or keep_last")
    }
}

private fun presentOptions(domain: Domain): Set<String> =
    buildSet {
        if (domain.normalise.isNotEmpty()) add("normalise")
        if (domain.collisionCheck) add("collision_check")
        if (domain.approvedBy != null) add("approved_by")
        if (domain.encoding != null) add("encoding")
        if (domain.length != null) add("length")
        if (domain.alphabet != null) add("alphabet")
        if (domain.localPart != null) add("local_part")
        if (domain.domain != null) add("domain")
        if (domain.dictionary != null) add("dictionary")
        if (domain.maxDays != null) add("max_days")
        if (domain.relative != null) add("relative")
        if (domain.keepFirst != null) add("keep_first")
        if (domain.keepLast != null) add("keep_last")
        if (domain.maskChar != null) add("mask_char")
        if (domain.value != null) add("value")
    }
