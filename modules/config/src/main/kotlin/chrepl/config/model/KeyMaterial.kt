package chrepl.config.model

import java.util.Base64

/** Decoding of masking key material, shared by validation and the key providers. */
object KeyEncoding {
    /** Minimum master key length: 256 bits. */
    const val MIN_BYTES = 32

    /** Decodes hex (even length, 0-9a-f) or standard base64; null when it is neither. */
    fun decode(text: String): ByteArray? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length % 2 == 0 && trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return ByteArray(trimmed.length / 2) { i -> trimmed.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        }
        return runCatching { Base64.getDecoder().decode(trimmed) }.getOrNull()
    }
}

/** Alphabets for format-preserving encryption (`fpe_ff1`), by config name. */
object Alphabets {
    const val DIGITS = "digits"

    val BY_NAME: Map<String, String> =
        mapOf(
            DIGITS to "0123456789",
            "lower" to "abcdefghijklmnopqrstuvwxyz",
            "upper" to "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
            "alpha" to "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
            "alphanumeric" to "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
            "hex" to "0123456789abcdef",
        )

    /** `fpe_ff1` over digits encrypts fixed-width integers: 6 digits (NIST minimum domain 10^6) to 19 (fits Int64/UInt64). */
    val DIGIT_WIDTHS = 6..19
}
