package chrepl.masking.keys

import chrepl.config.model.KeyEncoding
import chrepl.config.model.Masking
import chrepl.config.model.SecretRef
import chrepl.config.validate.SecretReader
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Master key material for one realm and version (SPEC §6.2). Held only in memory; [toString]
 * never prints it, and [close] overwrites the bytes. (JCA `SecretKeySpec` instances keep their
 * own copies, so process memory remains the trust boundary.)
 */
class MasterKey(
    val realm: String,
    val version: Int,
    material: ByteArray,
) : AutoCloseable {
    private val bytes = material.copyOf()
    private var closed = false

    init {
        require(bytes.size >= KeyEncoding.MIN_BYTES) { "master key must be at least ${KeyEncoding.MIN_BYTES * 8} bits" }
    }

    /** Derives a [length]-byte key for [purpose] and [domain] with HKDF-SHA256 (RFC 5869). */
    fun derive(
        purpose: String,
        domain: String,
        length: Int = 32,
    ): ByteArray {
        check(!closed) { "master key is closed" }
        val salt = "chrepl/v1/realm=$realm/version=$version".toByteArray()
        val info = "$purpose/$domain".toByteArray()
        return Hkdf.derive(bytes, salt, info, length)
    }

    override fun close() {
        bytes.fill(0)
        closed = true
    }

    override fun toString() = "MasterKey(realm=$realm, version=$version, material=<redacted>)"
}

fun interface MasterKeyProvider {
    fun load(): MasterKey
}

class KeyLoadException(
    message: String,
) : Exception(message)

/**
 * The v1 `static` provider: reads hex or base64 key material from a secret reference
 * (`${file:…}` or `${env:…}`). Error messages name the reference, never the material.
 */
class StaticKeyProvider(
    private val masking: Masking,
    private val secrets: SecretReader = SecretReader.SYSTEM,
) : MasterKeyProvider {
    override fun load(): MasterKey {
        val key = masking.key
        if (key.provider != "static") throw KeyLoadException("key provider '${key.provider}' is not supported by StaticKeyProvider")
        val ref = key.ref ?: throw KeyLoadException("masking.key.ref is required for the static provider")
        if (ref.kind != SecretRef.Kind.FILE && ref.kind != SecretRef.Kind.ENV) {
            throw KeyLoadException("masking.key.ref must be a \${file:…} or \${env:…} reference")
        }
        val text = secrets.read(ref) ?: throw KeyLoadException("cannot read masking key $ref")
        val material = KeyEncoding.decode(text) ?: throw KeyLoadException("masking key $ref is neither hex nor base64")
        try {
            if (material.size < KeyEncoding.MIN_BYTES) {
                throw KeyLoadException("masking key $ref has ${material.size * 8} bits; at least ${KeyEncoding.MIN_BYTES * 8} are required")
            }
            return MasterKey(masking.realm, key.version, material)
        } finally {
            material.fill(0)
        }
    }
}

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    private const val ALGORITHM = "HmacSHA256"
    private const val HASH_LENGTH = 32

    fun derive(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length in 1..255 * HASH_LENGTH) { "invalid HKDF output length $length" }
        val prk = hmac(if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt, ikm)
        try {
            val out = ByteArray(length)
            var previous = ByteArray(0)
            var offset = 0
            var counter = 1
            while (offset < length) {
                val block = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
                previous.fill(0)
                block.copyInto(out, offset, 0, minOf(HASH_LENGTH, length - offset))
                offset += HASH_LENGTH
                previous = block
                counter++
            }
            previous.fill(0)
            return out
        } finally {
            prk.fill(0)
        }
    }

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }.doFinal(data)
}
