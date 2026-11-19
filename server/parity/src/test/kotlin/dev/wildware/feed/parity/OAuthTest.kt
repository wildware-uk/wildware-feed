package dev.wildware.feed.parity

import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.parseQueryString
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OAuth for MCP clients, Kotlin server only (spec/11-oauth.md): an MCP client finds the metadata,
 * registers, the owner signs in and allows it on the page, and the token it gets works on /mcp.
 */
class OAuthTest : ParityTest() {
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun form(vararg p: Pair<String, String>) = p.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }

    @Test
    fun `an MCP client signs in through the browser and its token works like an agent's`() = parity {
        assumeTrue(Parity.kotlin, "OAuth is the Kotlin server's")
        val base = Parity.baseUrl
        val challenge = call(HttpMethod.Post, "/mcp", obj("jsonrpc" to "2.0", "id" to 1, "method" to "tools/list")) {
            header(HttpHeaders.Accept, "application/json, text/event-stream")
        }.headers[HttpHeaders.WWWAuthenticate]!!
        val metadataUrl = Regex("resource_metadata=\"([^\"]+)\"").find(challenge)!!.groupValues[1]
        val resource = call(HttpMethod.Get, metadataUrl.removePrefix(base)).obj()
        assertEquals("$base/mcp", resource["resource"].s)
        val server = call(HttpMethod.Get, "/.well-known/oauth-authorization-server").obj()
        assertEquals(base, server["issuer"].s)
        assertEquals("S256", server["code_challenge_methods_supported"][0].s)

        val redirect = "http://127.0.0.1:47999/callback"
        val client = call(HttpMethod.Post, "/oauth/register", obj("client_name" to "Parity client", "redirect_uris" to arr(redirect)))
        assertEquals(201, client.status.value)
        val clientId = client.obj()["client_id"].s

        val verifier = Parity.suffix().repeat(8)
        val pkce = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val query = form(
            "response_type" to "code", "client_id" to clientId, "redirect_uri" to redirect, "state" to "s1",
            "code_challenge" to pkce, "code_challenge_method" to "S256", "resource" to "$base/mcp",
        )
        val page = call(HttpMethod.Get, "/oauth/authorize?$query")
        assertEquals(200, page.status.value)
        assertTrue(page.bodyAsText().contains("Parity client"))

        val formType = io.ktor.http.ContentType.Application.FormUrlEncoded
        val signIn = call(HttpMethod.Post, "/oauth/authorize") { setBodyText("$query&${form("action" to "sign_in", "password" to Parity.adminPassword)}", formType) }
        assertEquals(303, signIn.status.value)
        val cookie = signIn.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        val consent = call(HttpMethod.Get, "/oauth/authorize?$query") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
        val csrf = Regex("name=\"csrf\" value=\"([^\"]+)\"").find(consent)!!.groupValues[1]
        val name = uniq("oauth")
        val allowed = call(HttpMethod.Post, "/oauth/authorize") {
            header(HttpHeaders.Cookie, cookie)
            setBodyText("$query&${form("action" to "allow", "csrf" to csrf, "choice" to "new", "name" to name)}", formType)
        }
        assertEquals(303, allowed.status.value)
        val answer = parseQueryString(allowed.headers[HttpHeaders.Location]!!.substringAfter('?'))
        assertEquals("s1", answer["state"])

        val tokens = call(HttpMethod.Post, "/oauth/token") {
            setBodyText(
                form("grant_type" to "authorization_code", "code" to answer["code"]!!, "client_id" to clientId, "redirect_uri" to redirect, "code_verifier" to verifier),
                formType,
            )
        }
        assertEquals(200, tokens.status.value, tokens.bodyAsText())
        val access = tokens.obj()["access_token"].s
        val registered = call(HttpMethod.Post, "/mcp", obj("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call", "params" to obj("name" to "register_session", "arguments" to obj()))) {
            bearer(access)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
        }
        assertTrue(registered.bodyAsText().contains("for agent \\\"$name\\\""), registered.bodyAsText())
    }
}
