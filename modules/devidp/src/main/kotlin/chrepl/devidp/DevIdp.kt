package chrepl.devidp

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** A user the dev IdP can sign in, with its OIDC groups. */
data class DevUser(
    val name: String,
    val groups: List<String>,
)

/**
 * A **development-only** OpenID Connect provider: authorization code flow with PKCE (S256) for the
 * portal, RS256 tokens with `preferred_username` and `groups` claims, a JWK set for the API, and a
 * `/dev/token` shortcut for the CLI. Users pick who they are on a sign-in page; there are no
 * passwords. It exists for the portal's E2E tests and the demo and must never be deployed.
 */
class DevIdp(
    private val issuer: String,
    private val users: List<DevUser>,
    /** Redirect URIs must start with one of these (the portal's origin). */
    private val redirectPrefixes: List<String>,
    private val audience: String = "chrepl",
    bind: InetSocketAddress = InetSocketAddress("127.0.0.1", 0),
) : AutoCloseable {
    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val kid = "dev-${System.currentTimeMillis()}"
    private val codes = ConcurrentHashMap<String, Grant>()
    private val random = SecureRandom()
    private val mapper = ObjectMapper()
    private val server = HttpServer.create(bind, 0)

    private class Grant(
        val clientId: String,
        val redirectUri: String,
        val challenge: String,
        val user: DevUser,
        val nonce: String?,
        val expires: Instant,
    )

    val port: Int get() = server.address.port

    fun start(): DevIdp {
        server.createContext("/") { ex -> runCatching { route(ex) }.onFailure { send(ex, 500, "text/plain", "error: ${it.message}") } }
        server.start()
        return this
    }

    override fun close() = server.stop(0)

    private fun route(ex: HttpExchange) {
        cors(ex)
        if (ex.requestMethod == "OPTIONS") return send(ex, 204, "text/plain", "")
        when (ex.requestURI.path) {
            "/.well-known/openid-configuration" -> json(ex, discovery())
            "/jwks" -> json(ex, mapOf("keys" to listOf(jwk())))
            "/authorize" -> if (ex.requestMethod == "POST") approve(ex) else signInPage(ex)
            "/token" -> token(ex)
            "/dev/token" -> devToken(ex)
            else -> send(ex, 404, "text/plain", "not found")
        }
    }

    private fun discovery() =
        mapOf(
            "issuer" to issuer,
            "authorization_endpoint" to "$issuer/authorize",
            "token_endpoint" to "$issuer/token",
            "jwks_uri" to "$issuer/jwks",
            "response_types_supported" to listOf("code"),
            "subject_types_supported" to listOf("public"),
            "id_token_signing_alg_values_supported" to listOf("RS256"),
            "code_challenge_methods_supported" to listOf("S256"),
            "grant_types_supported" to listOf("authorization_code"),
            "scopes_supported" to listOf("openid", "profile"),
        )

    private fun jwk(): Map<String, String> {
        val key = keys.public as RSAPublicKey
        return mapOf(
            "kty" to "RSA",
            "kid" to kid,
            "use" to "sig",
            "alg" to "RS256",
            "n" to b64(unsigned(key.modulus.toByteArray())),
            "e" to b64(unsigned(key.publicExponent.toByteArray())),
        )
    }

    private fun signInPage(ex: HttpExchange) {
        val q = query(ex.requestURI.rawQuery)
        val error = validateAuthorize(q)
        if (error != null) return send(ex, 400, "text/plain", error)
        val hidden =
            listOf("client_id", "redirect_uri", "state", "code_challenge", "nonce").joinToString("") { name ->
                "<input type=\"hidden\" name=\"$name\" value=\"${html(q[name] ?: "")}\">"
            }
        val buttons =
            users.joinToString("") { u ->
                "<li><button type=\"submit\" name=\"user\" value=\"${html(u.name)}\">Sign in as ${html(u.name)}</button> " +
                    "<span>${html(u.groups.joinToString(", "))}</span></li>"
            }
        send(
            ex,
            200,
            "text/html; charset=utf-8",
            """
            <!doctype html><html lang="en"><head><meta charset="utf-8"><title>Dev sign-in</title>
            <style>body{font-family:system-ui,sans-serif;margin:3rem;max-width:40rem}li{margin:.5rem 0}button{font-size:1rem;padding:.4rem .8rem}</style>
            </head><body><main><h1>Development sign-in</h1>
            <p>This identity provider is for tests and demos only. Choose a user:</p>
            <form method="post" action="/authorize">$hidden<ul>$buttons</ul></form></main></body></html>
            """.trimIndent(),
        )
    }

    private fun validateAuthorize(q: Map<String, String>): String? =
        when {
            q["response_type"] != null && q["response_type"] != "code" -> "only response_type=code is supported"
            q["code_challenge_method"] != null && q["code_challenge_method"] != "S256" -> "only S256 PKCE is supported"
            q["code_challenge"].isNullOrBlank() -> "PKCE code_challenge is required"
            q["client_id"].isNullOrBlank() -> "client_id is required"
            redirectPrefixes.none { q["redirect_uri"]?.startsWith(it) == true } -> "redirect_uri is not allowed"
            else -> null
        }

    private fun approve(ex: HttpExchange) {
        val form = query(ex.requestBody.readAllBytes().decodeToString())
        validateAuthorize(form)?.let { return send(ex, 400, "text/plain", it) }
        val user = users.firstOrNull { it.name == form["user"] } ?: return send(ex, 400, "text/plain", "unknown user")
        val code = randomToken()
        codes[code] =
            Grant(
                form.getValue("client_id"),
                form.getValue("redirect_uri"),
                form.getValue("code_challenge"),
                user,
                form["nonce"],
                Instant.now().plusSeconds(60),
            )
        val redirect = form.getValue("redirect_uri")
        val sep = if ('?' in redirect) '&' else '?'
        ex.responseHeaders.add("Location", "$redirect${sep}code=${enc(code)}&state=${enc(form["state"] ?: "")}")
        send(ex, 302, "text/plain", "")
    }

    private fun token(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return send(ex, 405, "text/plain", "POST only")
        val form = query(ex.requestBody.readAllBytes().decodeToString())
        val grant = form["code"]?.let { codes.remove(it) }
        val verifier = form["code_verifier"] ?: ""
        val expected = grant?.let { b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())) }
        val error =
            when {
                form["grant_type"] != "authorization_code" -> "unsupported_grant_type"
                grant == null || grant.expires.isBefore(Instant.now()) -> "invalid_grant"
                grant.clientId != form["client_id"] || grant.redirectUri != form["redirect_uri"] -> "invalid_grant"
                expected != grant.challenge -> "invalid_grant"
                else -> null
            }
        if (error != null) return json(ex, mapOf("error" to error), 400)
        json(ex, tokens(grant!!.user, grant.clientId, grant.nonce))
    }

    /** CLI shortcut: an access token for a configured user, without a browser. Dev only. */
    private fun devToken(ex: HttpExchange) {
        val user =
            users.firstOrNull { it.name == query(ex.requestURI.rawQuery)["user"] } ?: return send(ex, 404, "text/plain", "unknown user")
        send(ex, 200, "text/plain", tokens(user, "cli", null).getValue("access_token") as String)
    }

    fun tokens(
        user: DevUser,
        clientId: String,
        nonce: String?,
    ): Map<String, Any> {
        val now = Instant.now().epochSecond
        val base =
            mapOf(
                "iss" to issuer,
                "sub" to "dev-${user.name}",
                "preferred_username" to user.name,
                "groups" to user.groups,
                "iat" to now,
                "exp" to now + 3600,
            )
        val access = sign(base + ("aud" to audience) + ("azp" to clientId))
        val id = sign(base + ("aud" to clientId) + (if (nonce != null) mapOf("nonce" to nonce) else emptyMap()))
        return mapOf("access_token" to access, "id_token" to id, "token_type" to "Bearer", "expires_in" to 3600)
    }

    private fun sign(claims: Map<String, Any>): String {
        val header = b64(mapper.writeValueAsBytes(mapOf("alg" to "RS256", "typ" to "JWT", "kid" to kid)))
        val payload = b64(mapper.writeValueAsBytes(claims))
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(keys.private) }
        signature.update("$header.$payload".toByteArray())
        return "$header.$payload.${b64(signature.sign())}"
    }

    private fun cors(ex: HttpExchange) {
        val origin = ex.requestHeaders.getFirst("Origin") ?: return
        // The portal's origin, e.g. http://127.0.0.1:8080 for a redirect prefix http://127.0.0.1: or http://127.0.0.1:8080/.
        if (redirectPrefixes.none { "$origin/".startsWith(it) || it.startsWith("$origin/") }) return
        ex.responseHeaders.add("Access-Control-Allow-Origin", origin)
        ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        ex.responseHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization")
    }

    private fun json(
        ex: HttpExchange,
        body: Any,
        status: Int = 200,
    ) = send(ex, status, "application/json", mapper.writeValueAsString(body))

    private fun send(
        ex: HttpExchange,
        status: Int,
        type: String,
        body: String,
    ) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
    }

    private fun randomToken() = ByteArray(24).also { random.nextBytes(it) }.let { b64(it) }

    companion object {
        fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun unsigned(bytes: ByteArray) = if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

        private fun html(s: String) =
            s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")

        fun query(raw: String?): Map<String, String> =
            raw
                ?.split('&')
                ?.filter { it.isNotEmpty() }
                ?.associate { pair ->
                    val (k, v) = if ('=' in pair) pair.substringBefore('=') to pair.substringAfter('=') else pair to ""
                    URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
                } ?: emptyMap()

        /** `alice:qa-trading,bob:qa-trading|uat-leads` → users; groups are separated by `|`. */
        fun parseUsers(spec: String): List<DevUser> =
            spec.split(',').filter { it.isNotBlank() }.map { entry ->
                val (name, groups) = entry.trim().split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                DevUser(name, groups.split('|').filter { it.isNotBlank() })
            }

        val DEFAULT_USERS = "alice:qa-trading,bob:qa-trading,uma:uat-leads,olga:data-platform-ops,root:data-platform"
    }
}

/** `chrepl-devidp --port 9000 --issuer http://localhost:9000 --redirect http://localhost:8080/` */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { it[0].removePrefix("--") to it.getOrElse(1) { "" } }
    val port = (opts["port"] ?: System.getenv("DEVIDP_PORT") ?: "9000").toInt()
    val issuer = opts["issuer"] ?: System.getenv("DEVIDP_ISSUER") ?: "http://localhost:$port"
    val redirects = (opts["redirect"] ?: System.getenv("DEVIDP_REDIRECTS") ?: "http://localhost:8080/").split(',')
    val users = DevIdp.parseUsers(opts["users"] ?: System.getenv("DEVIDP_USERS") ?: DevIdp.DEFAULT_USERS)
    val bind = opts["bind"] ?: System.getenv("DEVIDP_BIND") ?: "127.0.0.1"
    val idp = DevIdp(issuer, users, redirects, bind = InetSocketAddress(bind, port)).start()
    System.err.println("DEVELOPMENT-ONLY OIDC provider at $issuer (port ${idp.port}); users: ${users.joinToString { it.name }}")
    URI.create(issuer) // validates the issuer
    Thread.currentThread().join()
}
