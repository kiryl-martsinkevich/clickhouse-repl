package chrepl.masking

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Keyed pseudo-random function (HMAC-SHA256) that drives every deterministic strategy.
 * Not thread-safe: each [chrepl.masking.table.TableMasker] owns its instances.
 */
class Prf(
    key: ByteArray,
) {
    private val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }

    fun digest(input: String): ByteArray = mac.doFinal(input.toByteArray(Charsets.UTF_8))

    /** [length] pseudo-random bytes for [input]: HMAC(input ‖ 0x00 ‖ counter) blocks. */
    fun stream(
        input: String,
        length: Int,
    ): ByteArray {
        val seed = input.toByteArray(Charsets.UTF_8)
        val out = ByteArray(length)
        var offset = 0
        var counter = 0
        while (offset < length) {
            mac.update(seed)
            mac.update(0)
            mac.update(byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte()))
            val block = mac.doFinal()
            block.copyInto(out, offset, 0, minOf(block.size, length - offset))
            offset += block.size
            counter++
        }
        return out
    }

    /** A uniform non-negative long below [bound] (rejection sampling avoids modulo bias). */
    fun below(
        input: String,
        bound: Long,
    ): Long {
        require(bound > 0)
        val limit = Long.MAX_VALUE - (Long.MAX_VALUE % bound)
        var attempt = 0
        while (true) {
            val bytes = digest(if (attempt == 0) input else "$input#$attempt")
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
            value = value and Long.MAX_VALUE
            if (value < limit) return value % bound
            attempt++
        }
    }

    /** A uniform double in [0, 1). */
    fun unit(input: String): Double {
        val bytes = digest(input)
        var value = 0L
        for (i in 0 until 7) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        return (value ushr 3).toDouble() / (1L shl 53).toDouble()
    }
}
