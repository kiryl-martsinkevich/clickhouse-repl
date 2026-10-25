package chrepl.masking.keys

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A stand-in for the Fortanix DSM REST endpoints the provider uses: session auth with an app API
 * key, AES-GCM encrypt/decrypt with named, non-exportable keys, and export of exportable
 * security objects. For tests only.
 */
class MockDsm(
    private val apiKey: String = Base64.getEncoder().encodeToString("app-1:secret-1".toByteArray()),
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val mapper = ObjectMapper()
    private val keks = ConcurrentHashMap<String, SecretKey>()
    private val exportable = ConcurrentHashMap<String, ByteArray>()
    private val tokens = ConcurrentHashMap.newKeySet<String>()
    private val random = SecureRandom()

    /** When false, every request fails with 503, as during a DSM outage. */
    @Volatile var available: Boolean = true

    /** Requests per path, to check that DSM is called once per process, not per value. */
    val calls = ConcurrentHashMap<String, AtomicInteger>()

    val apiKeyValue: String get() = apiKey
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun createKek(name: String) {
        keks[name] = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    fun importExportable(
        name: String,
        value: ByteArray,
    ) {
        exportable[name] = value.copyOf()
    }

    fun start(): MockDsm {
        server.createContext("/") { ex -> runCatching { route(ex) }.onFailure { send(ex, 500, mapOf("message" to it.message)) } }
        server.start()
        return this
    }

    override fun close() = server.stop(0)

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path
        calls.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
        if (!available) return send(ex, 503, mapOf("message" to "service unavailable"))
        val auth = ex.requestHeaders.getFirst("Authorization") ?: ""
        if (path == "/sys/v1/session/auth") {
            if (auth != "Basic $apiKey") return send(ex, 401, mapOf("message" to "invalid API key"))
            val token = UUID.randomUUID().toString()
            tokens += token
            return send(ex, 200, mapOf("token_type" to "Bearer", "expires_in" to 600, "access_token" to token))
        }
        if (auth.removePrefix("Bearer ") !in tokens) return send(ex, 401, mapOf("message" to "not authenticated"))
        val body = mapper.readTree(ex.requestBody.readAllBytes())
        when (path) {
            "/crypto/v1/encrypt" -> {
                val key = keks[body["key"]["name"].asText()] ?: return send(ex, 404, mapOf("message" to "no such key"))
                val iv = ByteArray(12).also { random.nextBytes(it) }
                val out =
                    Cipher
                        .getInstance("AES/GCM/NoPadding")
                        .apply {
                            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
                        }.doFinal(Base64.getDecoder().decode(body["plain"].asText()))
                val cipher = out.copyOfRange(0, out.size - 16)
                val tag = out.copyOfRange(out.size - 16, out.size)
                send(ex, 200, mapOf("kid" to "kid-1", "cipher" to b64(cipher), "iv" to b64(iv), "tag" to b64(tag)))
            }
            "/crypto/v1/decrypt" -> {
                val key = keks[body["key"]["name"].asText()] ?: return send(ex, 404, mapOf("message" to "no such key"))
                val iv = Base64.getDecoder().decode(body["iv"].asText())
                val input = Base64.getDecoder().decode(body["cipher"].asText()) + Base64.getDecoder().decode(body["tag"].asText())
                val plain =
                    Cipher
                        .getInstance(
                            "AES/GCM/NoPadding",
                        ).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
                        .doFinal(input)
                send(ex, 200, mapOf("kid" to "kid-1", "plain" to b64(plain)))
            }
            "/crypto/v1/keys/export" -> {
                val name = body["name"].asText()
                if (keks.containsKey(name)) return send(ex, 400, mapOf("message" to "security object is not exportable"))
                val value = exportable[name] ?: return send(ex, 404, mapOf("message" to "no such key"))
                send(ex, 200, mapOf("name" to name, "obj_type" to "HMAC", "value" to b64(value)))
            }
            else -> send(ex, 404, mapOf("message" to "not found"))
        }
    }

    private fun send(
        ex: HttpExchange,
        status: Int,
        body: Any,
    ) {
        val bytes = mapper.writeValueAsBytes(body)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
