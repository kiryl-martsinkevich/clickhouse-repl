package chrepl.masking.keys

import chrepl.config.model.FortanixKey
import chrepl.config.model.KeyEncoding
import chrepl.config.model.Masking
import chrepl.config.validate.SecretReader
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * The wrapped master key (envelope mode): AES-GCM ciphertext of the master key under a DSM KEK, as
 * returned by DSM's encrypt API. `clickhouse-repl dsm-wrap` produces it from the existing static
 * key, so moving from `static` to `fortanix` keeps masked values identical (SPEC §6.2).
 */
data class WrappedKey(
    val kek: String,
    val alg: String = "AES",
    val mode: String = "GCM",
    val cipher: String,
    val iv: String,
    val tag: String? = null,
) {
    fun toJson(): String =
        ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
            mapOf("kek" to kek, "alg" to alg, "mode" to mode, "cipher" to cipher, "iv" to iv, "tag" to tag),
        )

    companion object {
        fun parse(json: String): WrappedKey {
            val node = ObjectMapper().readTree(json)

            fun text(name: String) = node[name]?.takeIf { !it.isNull }?.asText()
            return WrappedKey(
                kek = text("kek") ?: throw KeyLoadException("the wrapped key has no 'kek'"),
                alg = text("alg") ?: "AES",
                mode = text("mode") ?: "GCM",
                cipher = text("cipher") ?: throw KeyLoadException("the wrapped key has no 'cipher'"),
                iv = text("iv") ?: throw KeyLoadException("the wrapped key has no 'iv'"),
                tag = text("tag"),
            )
        }
    }
}

/** Minimal Fortanix DSM REST client: session auth, encrypt/decrypt with a named key, export. */
class DsmClient(
    endpoint: String,
    private val apiKey: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    private val base = endpoint.trimEnd('/')
    private val mapper = ObjectMapper()
    private var token: String? = null

    /** `POST /sys/v1/session/auth` with the app API key; returns a bearer token. */
    private fun authenticate(): String =
        token ?: post("/sys/v1/session/auth", null, "Basic $apiKey")["access_token"]
            ?.asText()
            ?.also { token = it }
            ?: throw KeyLoadException("Fortanix DSM authentication returned no access token")

    /** `POST /crypto/v1/decrypt` with a named key: unwraps the master key; the plaintext never leaves this process. */
    fun decrypt(wrapped: WrappedKey): ByteArray {
        val body =
            buildMap {
                put("key", mapOf("name" to wrapped.kek))
                put("alg", wrapped.alg)
                put("mode", wrapped.mode)
                put("cipher", wrapped.cipher)
                put("iv", wrapped.iv)
                wrapped.tag?.let { put("tag", it) }
            }
        val plain =
            post("/crypto/v1/decrypt", body)["plain"]?.asText() ?: throw KeyLoadException("Fortanix DSM decrypt returned no plaintext")
        return Base64.getDecoder().decode(plain)
    }

    /** `POST /crypto/v1/encrypt` with a named key (AES-GCM): wraps key material for envelope mode. */
    fun encrypt(
        kek: String,
        plain: ByteArray,
    ): WrappedKey {
        val response =
            post(
                "/crypto/v1/encrypt",
                mapOf(
                    "key" to mapOf("name" to kek),
                    "alg" to "AES",
                    "mode" to "GCM",
                    "plain" to Base64.getEncoder().encodeToString(plain),
                ),
            )
        return WrappedKey(
            kek = kek,
            cipher = response["cipher"]?.asText() ?: throw KeyLoadException("Fortanix DSM encrypt returned no ciphertext"),
            iv = response["iv"]?.asText() ?: throw KeyLoadException("Fortanix DSM encrypt returned no IV"),
            tag = response["tag"]?.asText(),
        )
    }

    /** `POST /crypto/v1/keys/export`: the value of an exportable security object (export mode). */
    fun export(name: String): ByteArray {
        val value =
            post("/crypto/v1/keys/export", mapOf("name" to name))["value"]?.asText()
                ?: throw KeyLoadException("Fortanix DSM export of '$name' returned no value")
        return Base64.getDecoder().decode(value)
    }

    private fun post(
        path: String,
        body: Any?,
        authorization: String = "Bearer ${authenticate()}",
    ): JsonNode {
        val request =
            HttpRequest
                .newBuilder(URI.create("$base$path"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(
                    if (body ==
                        null
                    ) {
                        HttpRequest.BodyPublishers.noBody()
                    } else {
                        HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))
                    },
                ).build()
        val response =
            try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: java.io.IOException) {
                throw KeyLoadException("cannot reach Fortanix DSM at $base: ${e.message ?: e::class.simpleName}")
            }
        if (response.statusCode() !in 200..299) {
            // DSM error bodies describe the request, never key material; still, keep them short.
            throw KeyLoadException("Fortanix DSM $path failed: HTTP ${response.statusCode()} ${response.body().take(200)}")
        }
        return mapper.readTree(response.body())
    }
}

/**
 * The `fortanix` provider (SPEC §6.2, PLAN M8): fetches the HMAC master key from Fortanix DSM once,
 * at start-up. In envelope mode, a non-exportable KEK decrypts the wrapped key. In export mode,
 * an exportable HMAC security object is read. Derivation and per-value HMAC stay local, so DSM sees
 * one call per process, never per value. Errors name the endpoint and objects, never the material.
 */
class FortanixKeyProvider(
    private val masking: Masking,
    private val secrets: SecretReader = SecretReader.SYSTEM,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : MasterKeyProvider {
    override fun load(): MasterKey {
        val key = masking.key
        val dsm: FortanixKey = key.fortanix ?: throw KeyLoadException("masking.key.fortanix is required for the fortanix provider")
        val apiKey = secrets.read(dsm.apiKey) ?: throw KeyLoadException("cannot read the DSM API key ${dsm.apiKey}")
        val client = DsmClient(dsm.endpoint, apiKey.trim(), http)
        val material =
            if (dsm.keyName != null) {
                client.export(dsm.keyName!!)
            } else {
                val ref = dsm.wrappedKey ?: throw KeyLoadException("envelope mode needs masking.key.fortanix.wrapped_key")
                val text = secrets.read(ref) ?: throw KeyLoadException("cannot read the wrapped key $ref")
                val wrapped = WrappedKey.parse(text)
                if (wrapped.kek !=
                    dsm.kek
                ) {
                    throw KeyLoadException("the wrapped key $ref was made with KEK '${wrapped.kek}', not '${dsm.kek}'")
                }
                client.decrypt(wrapped)
            }
        try {
            if (material.size < KeyEncoding.MIN_BYTES) {
                throw KeyLoadException(
                    "the DSM master key has ${material.size * 8} bits; at least ${KeyEncoding.MIN_BYTES * 8} are required",
                )
            }
            return MasterKey(masking.realm, key.version, material)
        } finally {
            material.fill(0)
        }
    }
}

/** The configured provider: `static` or `fortanix`. */
object KeyProviders {
    fun create(
        masking: Masking,
        secrets: SecretReader = SecretReader.SYSTEM,
    ): MasterKeyProvider =
        when (masking.key.provider) {
            "static" -> StaticKeyProvider(masking, secrets)
            "fortanix" -> FortanixKeyProvider(masking, secrets)
            else -> throw KeyLoadException("unknown key provider '${masking.key.provider}'")
        }
}
