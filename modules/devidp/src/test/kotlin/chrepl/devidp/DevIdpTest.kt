package chrepl.devidp

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DevIdpTest {
    private val idp = DevIdp("http://127.0.0.1:0", DevIdp.parseUsers("alice:qa-trading|uat-leads"), listOf("http://app/")).start()
    private val base = "http://127.0.0.1:${idp.port}"
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val mapper = ObjectMapper()

    @AfterEach
    fun stop() = idp.close()

    private fun form(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }

    private fun post(
        path: String,
        body: String,
    ) = http.send(
        HttpRequest
            .newBuilder(URI.create("$base$path"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `authorization code with PKCE yields a token with the user's groups`() {
        val verifier = "verifier-0123456789-0123456789-0123456789"
        val challenge = DevIdp.b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val approve =
            post(
                "/authorize",
                form(
                    "client_id" to "portal",
                    "redirect_uri" to "http://app/",
                    "state" to "s1",
                    "code_challenge" to challenge,
                    "user" to "alice",
                ),
            )
        assertEquals(302, approve.statusCode())
        val location = approve.headers().firstValue("Location").get()
        assertContains(location, "state=s1")
        val code = DevIdp.query(URI.create(location).rawQuery).getValue("code")

        val wrong =
            post(
                "/token",
                form(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "code_verifier" to "nope",
                    "client_id" to "portal",
                    "redirect_uri" to "http://app/",
                ),
            )
        assertEquals(400, wrong.statusCode(), "a wrong verifier must fail (and burns the code)")

        val again =
            post(
                "/authorize",
                form(
                    "client_id" to "portal",
                    "redirect_uri" to "http://app/",
                    "state" to "s2",
                    "code_challenge" to challenge,
                    "user" to "alice",
                ),
            )
        val code2 = DevIdp.query(URI.create(again.headers().firstValue("Location").get()).rawQuery).getValue("code")
        val ok =
            post(
                "/token",
                form(
                    "grant_type" to "authorization_code",
                    "code" to code2,
                    "code_verifier" to verifier,
                    "client_id" to "portal",
                    "redirect_uri" to "http://app/",
                ),
            )
        assertEquals(200, ok.statusCode(), ok.body())
        val access = mapper.readTree(ok.body())["access_token"].asText()
        val claims = mapper.readTree(Base64.getUrlDecoder().decode(access.split('.')[1]))
        assertEquals("alice", claims["preferred_username"].asText())
        assertEquals(listOf("qa-trading", "uat-leads"), claims["groups"].map { it.asText() })
    }

    @Test
    fun `redirects outside the allowed prefixes are refused`() {
        val response =
            post(
                "/authorize",
                form(
                    "client_id" to "portal",
                    "redirect_uri" to "http://evil/",
                    "code_challenge" to "x",
                    "user" to "alice",
                ),
            )
        assertEquals(400, response.statusCode())
    }
}
