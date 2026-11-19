package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.auth.Tokens
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.OAuth
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parseQueryString
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthRoutesTest {
    private companion object {
        const val PASSWORD = "correct horse"
        val HASH: String = Passwords.hash(PASSWORD)
        const val BASE = "https://dashboard.example.com"
        const val REDIRECT = "http://127.0.0.1:33418/callback"
    }

    private val config = Config(
        dataDir = "unused", adminPasswordHash = HASH, sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = BASE, maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = BASE,
    )

    private var now = 1_800_000_000_000L

    /** One test's world: the app, a client that does not follow redirects, and the owner's cookie once signed in. */
    private inner class World(val app: App, val http: HttpClient) {
        var cookie: String? = null
    }

    private fun withApp(block: suspend World.() -> Unit) {
        val app = App(config, Database.memory()) { now }
        testApplication {
            application { feed(app) }
            val http = createClient { followRedirects = false }
            World(app, http).block()
        }
        app.close()
    }

    private fun String.json() = Wire.json.parseToJsonElement(this).jsonObject
    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun form(vararg pairs: Pair<String, String?>) = pairs.filter { it.second != null }.joinToString("&") { "${enc(it.first)}=${enc(it.second!!)}" }

    private fun verifier() = Tokens.random() + Tokens.random()
    private fun challenge(v: String) = Tokens.base64url(MessageDigest.getInstance("SHA-256").digest(v.toByteArray()))

    private suspend fun World.register(body: String): HttpResponse = http.post("/oauth/register") {
        header("Content-Type", "application/json")
        setBody(body)
    }

    private suspend fun World.newClient(name: String = "Cursor", redirect: String = REDIRECT): String =
        register("""{"client_name":"$name","redirect_uris":["$redirect"],"token_endpoint_auth_method":"none"}""").bodyAsText().json().s("client_id")

    private fun authorizeQuery(clientId: String, challenge: String, redirect: String? = REDIRECT, extra: List<Pair<String, String?>> = emptyList()) = form(
        "response_type" to "code", "client_id" to clientId, "redirect_uri" to redirect, "state" to "st-123",
        "code_challenge" to challenge, "code_challenge_method" to "S256", "resource" to "$BASE/mcp", "scope" to "agent",
        *extra.toTypedArray(),
    )

    private suspend fun World.post(path: String, body: String, vararg headers: Pair<String, String>): HttpResponse = http.post(path) {
        header("Content-Type", "application/x-www-form-urlencoded")
        cookie?.let { header("Cookie", it) }
        headers.forEach { (k, v) -> header(k, v) }
        setBody(body)
    }

    private suspend fun World.page(query: String): HttpResponse = http.get("/oauth/authorize?$query") { cookie?.let { header("Cookie", it) } }

    private suspend fun World.signIn(query: String, password: String = PASSWORD): HttpResponse {
        val r = post("/oauth/authorize", "$query&${form("action" to "sign_in", "password" to password)}")
        r.headers["Set-Cookie"]?.let { cookie = it.substringBefore(';') }
        return r
    }

    private fun csrfOf(html: String) = Regex("name=\"csrf\" value=\"([^\"]+)\"").find(html)!!.groupValues[1]

    /** Sign in and allow; the redirect's query. */
    private suspend fun World.allow(query: String, vararg choice: Pair<String, String>): Map<String, String> {
        if (cookie == null) assertEquals(303, signIn(query).status.value)
        val html = page(query).bodyAsText()
        val r = post("/oauth/authorize", "$query&${form("action" to "allow", "csrf" to csrfOf(html), *choice)}")
        assertEquals(303, r.status.value, r.bodyAsText())
        val location = r.headers["Location"]!!
        assertTrue(location.startsWith("$REDIRECT?"), location)
        return parseQueryString(location.substringAfter('?')).let { p -> p.names().associateWith { p[it]!! } }
    }

    private suspend fun World.token(vararg pairs: Pair<String, String?>): HttpResponse = post("/oauth/token", form(*pairs))

    private suspend fun World.mcp(token: String, method: String = "tools/call", params: String = """{"name":"register_session","arguments":{}}"""): HttpResponse =
        http.post("/mcp") {
            header("Authorization", "Bearer $token")
            header("Accept", "application/json, text/event-stream")
            header("Content-Type", "application/json")
            setBody("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")
        }

    /** The whole flow for a new client: its tokens and the verifier used. */
    private suspend fun World.connect(name: String = "Cursor", vararg choice: Pair<String, String> = arrayOf("choice" to "new", "name" to name)): JsonObject {
        val clientId = newClient(name)
        val v = verifier()
        val query = authorizeQuery(clientId, challenge(v))
        val code = allow(query, *choice)["code"]!!
        val r = token("grant_type" to "authorization_code", "code" to code, "client_id" to clientId, "redirect_uri" to REDIRECT, "code_verifier" to v, "resource" to "$BASE/mcp")
        assertEquals(200, r.status.value, r.bodyAsText())
        return r.bodyAsText().json() + ("client_id" to kotlinx.serialization.json.JsonPrimitive(clientId))
    }

    private operator fun JsonObject.plus(pair: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(this + mapOf(pair))

    // ── Discovery ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the metadata documents say where to sign in, and that only PKCE S256 is taken`() = withApp {
        val root = http.get("/.well-known/oauth-protected-resource")
        assertEquals(200, root.status.value)
        assertEquals("*", root.headers["Access-Control-Allow-Origin"])
        val prm = root.bodyAsText().json()
        assertEquals(BASE, prm.s("resource"))
        assertEquals(listOf(BASE), prm["authorization_servers"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("$BASE/mcp", http.get("/.well-known/oauth-protected-resource/mcp").bodyAsText().json().s("resource"))

        val asm = http.get("/.well-known/oauth-authorization-server").bodyAsText().json()
        assertEquals(BASE, asm.s("issuer"))
        assertEquals("$BASE/oauth/authorize", asm.s("authorization_endpoint"))
        assertEquals("$BASE/oauth/token", asm.s("token_endpoint"))
        assertEquals("$BASE/oauth/register", asm.s("registration_endpoint"))
        assertEquals(listOf("S256"), asm["code_challenge_methods_supported"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("none"), asm["token_endpoint_auth_methods_supported"]!!.jsonArray.map { it.jsonPrimitive.content })

        val pre = http.options("/oauth/token")
        assertEquals(204, pre.status.value)
        assertEquals("*", pre.headers["Access-Control-Allow-Origin"])
    }

    @Test
    fun `MCP without a token answers 401 with a challenge that points at the metadata, and minted tokens still work`() = withApp {
        val r = http.post("/mcp") { header("Accept", "application/json, text/event-stream"); header("Content-Type", "application/json"); setBody("{}") }
        assertEquals(401, r.status.value)
        assertEquals("Bearer resource_metadata=\"$BASE/.well-known/oauth-protected-resource/mcp\", scope=\"agent\"", r.headers["WWW-Authenticate"])
        assertEquals("missing_token", r.bodyAsText().json()["error"]!!.jsonObject["data"]!!.jsonObject.s("error"))

        val unknown = mcp("x".repeat(43))
        assertEquals(401, unknown.status.value)
        assertTrue(unknown.headers["WWW-Authenticate"]!!.startsWith("Bearer error=\"invalid_token\", error_description=\"unknown bearer token"))
        assertTrue(unknown.headers["WWW-Authenticate"]!!.contains("resource_metadata="))

        val (_, minted) = app.agents.mint("old-style")
        assertTrue(mcp(minted).bodyAsText().contains("for agent \\\"old-style\\\""))
    }

    // ── Registration ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `registration takes https, loopback and app schemes, and refuses the rest`() = withApp {
        val ok = register("""{"client_name":"VS Code","redirect_uris":["http://127.0.0.1:33418/","https://vscode.dev/redirect","vscode://ms-vscode.mcp/cb","cursor://anysphere.cursor-retrieval/oauth/callback"],"token_endpoint_auth_method":"client_secret_post"}""")
        assertEquals(201, ok.status.value, ok.bodyAsText())
        val body = ok.bodyAsText().json()
        assertEquals("none", body.s("token_endpoint_auth_method"))
        assertFalse("client_secret" in body)
        assertEquals("VS Code", body.s("client_name"))
        assertTrue(Tokens.wellFormed(body.s("client_id")))

        for (bad in listOf("http://evil.example.com/cb", "javascript:alert(1)", "data:text/html,x", "https://ok.example.com/cb#frag", "file:///etc/passwd", "notauri", "https://user@evil.example.com/")) {
            val r = register("""{"redirect_uris":["$bad"]}""")
            assertEquals(400, r.status.value, bad)
            assertEquals("invalid_redirect_uri", r.bodyAsText().json().s("error"), bad)
        }
        assertEquals("invalid_redirect_uri", register("""{"client_name":"x"}""").bodyAsText().json().s("error"))
        assertEquals("invalid_client_metadata", register("""{"redirect_uris":["$REDIRECT"],"grant_types":["client_credentials"]}""").bodyAsText().json().s("error"))
        assertEquals("invalid_client_metadata", register("""{"redirect_uris":["$REDIRECT"],"response_types":["token"]}""").bodyAsText().json().s("error"))
        assertEquals("invalid_client_metadata", register("[1]").bodyAsText().json().s("error"))
        assertEquals(413, register("""{"client_name":"${"x".repeat(20_000)}"}""").status.value)
    }

    @Test
    fun `registration is limited per address`() = withApp {
        repeat(20) { assertEquals(201, register("""{"redirect_uris":["$REDIRECT"]}""").status.value) }
        val limited = register("""{"redirect_uris":["$REDIRECT"]}""")
        assertEquals(429, limited.status.value)
        assertNotNull(limited.headers["Retry-After"])
    }

    // ── The authorize page ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a bad client or redirect URI is shown on the page and never redirected to`() = withApp {
        val clientId = newClient()
        val c = challenge(verifier())
        for (query in listOf(
            authorizeQuery("nobody", c),
            authorizeQuery(clientId, c, redirect = "https://evil.example.com/cb"),
            authorizeQuery(clientId, c, redirect = "http://127.0.0.1:33418/other"),
            authorizeQuery(clientId, c) + "&client_id=$clientId",
        )) {
            val r = page(query)
            assertEquals(400, r.status.value, query)
            assertNull(r.headers["Location"])
            assertTrue(r.headers["Content-Type"]!!.startsWith("text/html"))
        }
    }

    @Test
    fun `other request errors are shown on the page too, so a registered client cannot use them to redirect`() = withApp {
        val clientId = newClient(redirect = "https://evil.example.com/cb")
        val c = challenge(verifier())
        suspend fun errorOf(r: HttpResponse): Map<String, String?> {
            assertEquals(400, r.status.value)
            assertNull(r.headers["Location"])
            val error = Regex("\\((\\w+):").find(r.bodyAsText())?.groupValues?.get(1)
            return mapOf("error" to error)
        }
        val r = "https://evil.example.com/cb"
        val noPkce = form("response_type" to "code", "client_id" to clientId, "redirect_uri" to r, "state" to "st-123")
        assertEquals("invalid_request", errorOf(page(noPkce))["error"])
        assertEquals("invalid_request", errorOf(page(authorizeQuery(clientId, c, r).replace("S256", "plain")))["error"])
        assertEquals("invalid_request", errorOf(page(authorizeQuery(clientId, "short", r)))["error"])
        assertEquals("unsupported_response_type", errorOf(page(authorizeQuery(clientId, c, r).replace("response_type=code", "response_type=token")))["error"])
        assertEquals("invalid_target", errorOf(page(authorizeQuery(clientId, c, r).replace(enc("$BASE/mcp"), enc("https://other.example.com/mcp"))))["error"])
    }

    @Test
    fun `the page asks a stranger for the password, escapes what the client says, and cannot be framed`() = withApp {
        val clientId = newClient(name = "<script>alert(1)</script>")
        val query = authorizeQuery(clientId, challenge(verifier()))
        val r = page(query)
        assertEquals(200, r.status.value)
        val html = r.bodyAsText()
        assertTrue(html.contains("type=\"password\""))
        assertFalse(html.contains("<script>alert"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertFalse(html.contains("name=\"csrf\""), "no Allow before signing in")
        assertTrue(html.contains("127.0.0.1:33418"), "the page shows where the answer goes")
        assertEquals("DENY", r.headers["X-Frame-Options"])
        assertTrue(r.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'none'"))
        assertEquals("no-store", r.headers["Cache-Control"])

        val wrong = signIn(query, "nope")
        assertEquals(400, wrong.status.value)
        assertNull(wrong.headers["Set-Cookie"])
        assertTrue(wrong.bodyAsText().contains("That password is not right."))

        val right = signIn(query)
        assertEquals(303, right.status.value)
        assertEquals("/oauth/authorize?$query".length, right.headers["Location"]!!.length)
        val set = right.headers["Set-Cookie"]!!
        assertTrue(set.startsWith("__Host-feed_owner="), set)
        listOf("HttpOnly", "SameSite=Lax", "Secure", "Path=/").forEach { assertTrue(set.contains(it), it) }
        val consent = page(query).bodyAsText()
        assertTrue(consent.contains("name=\"csrf\""))
        assertTrue(consent.contains("wants to connect as an agent"))
    }

    @Test
    fun `Allow needs the signed-in owner, this page's CSRF token, and a same-origin post`() = withApp {
        val clientId = newClient()
        val query = authorizeQuery(clientId, challenge(verifier()))
        // Not signed in: no grant, whatever the form says.
        assertEquals(401, post("/oauth/authorize", "$query&${form("action" to "allow", "csrf" to "x", "choice" to "new", "name" to "a")}").status.value)

        signIn(query)
        val csrf = csrfOf(page(query).bodyAsText())
        assertEquals(403, post("/oauth/authorize", "$query&${form("action" to "allow", "csrf" to "forged", "choice" to "new", "name" to "a")}").status.value)
        assertEquals(403, post("/oauth/authorize", "$query&${form("action" to "allow", "choice" to "new", "name" to "a")}").status.value)
        // The token is bound to this request: it does not allow another client's.
        val other = authorizeQuery(newClient("Other"), challenge(verifier()))
        assertEquals(403, post("/oauth/authorize", "$other&${form("action" to "allow", "csrf" to csrf, "choice" to "new", "name" to "a")}").status.value)
        // Nor a different challenge (an attacker swapping in their own PKCE).
        val swapped = authorizeQuery(clientId, challenge(verifier()))
        assertEquals(403, post("/oauth/authorize", "$swapped&${form("action" to "allow", "csrf" to csrf, "choice" to "new", "name" to "a")}").status.value)
        // A cross-site post, even with the right token and cookie.
        val body = "$query&${form("action" to "allow", "csrf" to csrf, "choice" to "new", "name" to "a")}"
        assertEquals(403, post("/oauth/authorize", body, "Origin" to "https://evil.example.com").status.value)
        assertEquals(403, post("/oauth/authorize", body, "Sec-Fetch-Site" to "cross-site").status.value)
        // A forged cookie is not a session.
        cookie = "__Host-feed_owner=v1.${now + 100_000}.${Tokens.random()}.${Tokens.random()}"
        assertEquals(401, post("/oauth/authorize", body).status.value)
        assertTrue(app.agents.list(false).none { it.name == "a" }, "nothing was allowed")
    }

    @Test
    fun `Deny sends access_denied back and makes nothing`() = withApp {
        val clientId = newClient()
        val query = authorizeQuery(clientId, challenge(verifier()))
        signIn(query)
        val r = post("/oauth/authorize", "$query&${form("action" to "deny", "csrf" to csrfOf(page(query).bodyAsText()))}")
        assertEquals(303, r.status.value)
        val q = parseQueryString(r.headers["Location"]!!.substringAfter('?'))
        assertEquals("access_denied", q["error"])
        assertEquals("st-123", q["state"])
        assertNull(q["code"])
        assertTrue(app.agents.list(false).isEmpty())
    }

    // ── The token endpoint ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the whole flow makes a new agent whose access token works on MCP like a minted one`() = withApp {
        val tokens = connect("Cursor")
        assertEquals("Bearer", tokens.s("token_type"))
        assertEquals("3600", tokens.s("expires_in"))
        assertEquals("agent", tokens.s("scope"))
        val access = tokens.s("access_token")
        assertTrue(Tokens.wellFormed(access))
        assertTrue(mcp(access).bodyAsText().contains("for agent \\\"Cursor\\\""))
        assertEquals(listOf("Cursor"), app.agents.list(false).map { it.name })
        // Nothing secret is stored as given.
        val stored = app.db.query("SELECT token_hash FROM oauth_tokens") { it.getString(1) } + app.db.query("SELECT code_hash FROM oauth_grants") { it.getString(1) }
        assertTrue(stored.none { it == access || it == tokens.s("refresh_token") })
    }

    @Test
    fun `a code is good once, for its own client, redirect URI and verifier, and not for long`() = withApp {
        val clientId = newClient()
        val v = verifier()
        val query = authorizeQuery(clientId, challenge(v))
        fun redeem(code: String, verifier: String = v, client: String = clientId, redirect: String = REDIRECT, resource: String? = "$BASE/mcp") =
            arrayOf("grant_type" to "authorization_code", "code" to code, "client_id" to client, "redirect_uri" to redirect, "code_verifier" to verifier, "resource" to resource)

        val code = allow(query, "choice" to "new", "name" to "laptop")["code"]!!
        assertEquals("invalid_grant", token(*redeem(code, verifier = verifier())).bodyAsText().json().s("error"))
        assertEquals("invalid_client", token(*redeem(code, client = "nobody")).bodyAsText().json().s("error"))
        assertEquals("invalid_grant", token(*redeem(code, client = newClient("Other"))).bodyAsText().json().s("error"))
        assertEquals("invalid_grant", token(*redeem(code, redirect = "http://127.0.0.1:1/cb")).bodyAsText().json().s("error"))
        assertEquals("invalid_target", token(*redeem(code, resource = "https://other.example.com")).bodyAsText().json().s("error"))
        val first = token(*redeem(code))
        assertEquals(200, first.status.value, first.bodyAsText())
        assertEquals("no-store", first.headers["Cache-Control"])
        val access = first.bodyAsText().json().s("access_token")
        assertEquals(200, mcp(access).status.value)

        // Used twice: refused, and what the first use made is revoked, since someone else has the code.
        assertEquals("invalid_grant", token(*redeem(code)).bodyAsText().json().s("error"))
        assertEquals(401, mcp(access).status.value)

        // A code left too long is no good.
        val late = allow(query, "choice" to "new", "name" to "late")["code"]!!
        now += OAuth.CODE_MS + 1
        assertEquals("invalid_grant", token(*redeem(late)).bodyAsText().json().s("error"))
        assertEquals("unsupported_grant_type", token("grant_type" to "password", "client_id" to clientId).bodyAsText().json().s("error"))
        assertEquals(400, http.post("/oauth/token") { header("Content-Type", "application/json"); setBody("{}") }.status.value)
    }

    @Test
    fun `access tokens expire, refresh tokens rotate, and a spent one coming back late revokes the grant`() = withApp {
        val tokens = connect("Codex")
        val clientId = tokens.s("client_id")
        now += OAuth.ACCESS_MS + 1
        val expired = mcp(tokens.s("access_token"))
        assertEquals(401, expired.status.value)
        assertTrue(expired.headers["WWW-Authenticate"]!!.contains("expired access token"))

        fun refresh(t: String) = arrayOf("grant_type" to "refresh_token", "refresh_token" to t, "client_id" to clientId)
        val second = token(*refresh(tokens.s("refresh_token")))
        assertEquals(200, second.status.value, second.bodyAsText())
        val pair2 = second.bodyAsText().json()
        assertTrue(mcp(pair2.s("access_token")).bodyAsText().contains("for agent \\\"Codex\\\""))
        assertEquals("invalid_grant", token("grant_type" to "refresh_token", "refresh_token" to pair2.s("refresh_token"), "client_id" to newClient("x")).bodyAsText().json().s("error"))

        // The spent one again, at once (a client racing itself): refused, nothing revoked.
        assertEquals("invalid_grant", token(*refresh(tokens.s("refresh_token"))).bodyAsText().json().s("error"))
        assertEquals(200, mcp(pair2.s("access_token")).status.value)
        // Later: someone else has it, so the whole grant goes.
        now += OAuth.REUSE_GRACE_MS + 1
        assertEquals("invalid_grant", token(*refresh(tokens.s("refresh_token"))).bodyAsText().json().s("error"))
        assertEquals(401, mcp(pair2.s("access_token")).status.value)
        assertEquals("invalid_grant", token(*refresh(pair2.s("refresh_token"))).bodyAsText().json().s("error"))
    }

    @Test
    fun `an existing agent can be chosen, and keeps its own token too`() = withApp {
        val (agent, minted) = app.agents.mint("laptop")
        val tokens = connect("laptop", "choice" to "existing", "agent_id" to agent.id)
        assertTrue(mcp(tokens.s("access_token")).bodyAsText().contains("for agent \\\"laptop\\\""))
        assertTrue(mcp(minted).bodyAsText().contains("for agent \\\"laptop\\\""))
        assertEquals(1, app.agents.list(false).size)
    }

    @Test
    fun `a new agent is the default, whatever the client calls itself, and a taken name is refused`() = withApp {
        // An attacker registers "Cursor" to take over the owner's Cursor agent with one click on Allow.
        val (agent, _) = app.agents.mint("Cursor")
        val clientId = newClient("Cursor", redirect = "https://evil.example.com/cb")
        val query = authorizeQuery(clientId, challenge(verifier()), redirect = "https://evil.example.com/cb")
        signIn(query)
        val html = page(query).bodyAsText()
        assertTrue(html.contains("value=\"new\" checked"))
        assertFalse(html.contains(" selected>"))
        assertTrue(html.contains("https://evil.example.com/cb"), "the full redirect URI is shown")
        val r = post("/oauth/authorize", "$query&${form("action" to "allow", "csrf" to csrfOf(html), "choice" to "new", "name" to "cursor")}")
        assertEquals(409, r.status.value)
        val again = r.bodyAsText()
        assertTrue(again.contains("already called"))
        assertTrue(again.contains("value=\"new\" checked") && !again.contains(" selected>"), "the clash does not preselect the agent either")
        val blank = post("/oauth/authorize", "$query&${form("action" to "allow", "csrf" to csrfOf(html), "choice" to "new", "name" to "  ")}")
        assertEquals(400, blank.status.value)
        assertEquals(listOf(agent.id), app.agents.list(false).map { it.id })
    }

    @Test
    fun `an agent is offered first only when it was allowed before for the same redirect target`() = withApp {
        val target = "https://app.example.com/oauth/cb"
        val first = newClient("Some app", redirect = target)
        val v1 = verifier()
        val q1 = authorizeQuery(first, challenge(v1), redirect = target)
        signIn(q1)
        val made = post("/oauth/authorize", "$q1&${form("action" to "allow", "csrf" to csrfOf(page(q1).bodyAsText()), "choice" to "new", "name" to "web-agent")}")
        assertEquals(303, made.status.value)
        val agent = app.agents.list(false).single()
        // Only a grant whose code was used counts as a connection.
        assertTrue(app.oauth.agentsAllowedFor(target).isEmpty())
        val code = parseQueryString(made.headers["Location"]!!.substringAfter('?'))["code"]!!
        assertEquals(200, token("grant_type" to "authorization_code", "code" to code, "client_id" to first, "redirect_uri" to target, "code_verifier" to v1).status.value)

        val second = newClient("Some app", redirect = target)
        val q2 = authorizeQuery(second, challenge(verifier()), redirect = target)
        val html = page(q2).bodyAsText()
        assertTrue(html.contains("value=\"existing\" checked"))
        assertTrue(html.contains("<option value=\"${agent.id}\" selected>web-agent</option>"))

        // Another site, same name: not offered.
        val other = newClient("Some app", redirect = "https://other.example.com/cb")
        val q3 = authorizeQuery(other, challenge(verifier()), redirect = "https://other.example.com/cb")
        assertFalse(page(q3).bodyAsText().contains(" selected>"))
    }

    @Test
    fun `revoking the agent ends its OAuth tokens and refreshes`() = withApp {
        val tokens = connect("Gemini")
        val agent = app.agents.list(false).single()
        app.agents.revoke(agent.id)
        val r = mcp(tokens.s("access_token"))
        assertEquals(401, r.status.value)
        assertTrue(r.bodyAsText().contains("revoked_token"))
        assertEquals("invalid_grant", token("grant_type" to "refresh_token", "refresh_token" to tokens.s("refresh_token"), "client_id" to tokens.s("client_id")).bodyAsText().json().s("error"))
    }

    @Test
    fun `the revocation endpoint ends the grant, and a refresh token is not an access token`() = withApp {
        val tokens = connect("Windsurf")
        assertEquals(401, mcp(tokens.s("refresh_token")).status.value)
        val r = post("/oauth/revoke", form("token" to tokens.s("refresh_token"), "client_id" to tokens.s("client_id")))
        assertEquals(200, r.status.value)
        assertEquals(401, mcp(tokens.s("access_token")).status.value)
        assertEquals(200, post("/oauth/revoke", form("token" to "x".repeat(43), "client_id" to tokens.s("client_id"))).status.value)
    }

    @Test
    fun `a loopback redirect matches on any port`() = withApp {
        val clientId = newClient(redirect = "http://127.0.0.1/callback")
        val v = verifier()
        val query = authorizeQuery(clientId, challenge(v))
        val code = allow(query, "choice" to "new", "name" to "port")["code"]!!
        val r = token("grant_type" to "authorization_code", "code" to code, "client_id" to clientId, "redirect_uri" to REDIRECT, "code_verifier" to v)
        assertEquals(200, r.status.value, r.bodyAsText())
    }

    @Test
    fun `the sweep forgets expired tokens, unused codes and clients never allowed`() = withApp {
        val tokens = connect("Cline")
        newClient("never")
        now += OAuth.REFRESH_MS + OAuth.UNAPPROVED_KEEP_MS
        app.oauth.sweep()
        assertEquals(0, app.db.one("SELECT count(*) FROM oauth_tokens") { it.getInt(1) })
        assertEquals(listOf("Cline"), app.db.query("SELECT name FROM oauth_clients") { it.getString(1) })
        assertEquals(401, mcp(tokens.s("access_token")).status.value)
    }

    @Test
    fun `redirect URI rules`() {
        assertNull(OAuth.redirectProblem("http://localhost:1234/cb"))
        assertNull(OAuth.redirectProblem("http://[::1]:1234/cb"))
        assertNull(OAuth.redirectProblem("https://claude.ai/api/mcp/auth_callback"))
        assertNull(OAuth.redirectProblem("cursor://anysphere.cursor-retrieval/oauth/user-x/callback"))
        assertNotNull(OAuth.redirectProblem("http://127.0.0.1.evil.example.com/cb"))
        assertNotNull(OAuth.redirectProblem("http://localhost.evil.example.com/cb"))
        assertNotNull(OAuth.redirectProblem("JavaScript:alert(1)"))
        assertNotNull(OAuth.redirectProblem("https://x.example.com/cb\n"))
        assertEquals("https://dashboard.example.com", OAuthUrls.canonical("HTTPS://Dashboard.Example.com:443/"))
        assertNull(URI("https://a.example.com").rawFragment)
    }

    @Test
    fun `refreshing replaces the access token, keeps one spent refresh token, and the rate limit is per agent`() = withApp {
        val tokens = connect("Busy")
        val clientId = tokens.s("client_id")
        var refresh = tokens.s("refresh_token")
        var access = tokens.s("access_token")
        val accesses = mutableListOf(access)
        repeat(5) {
            val next = token("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId).bodyAsText().json()
            refresh = next.s("refresh_token"); access = next.s("access_token"); accesses += access
        }
        // One access, one live refresh, one spent refresh: refreshing does not pile up rows.
        assertEquals(listOf("access" to 1, "refresh" to 2), app.db.query("SELECT kind, count(*) FROM oauth_tokens GROUP BY kind ORDER BY kind") { it.getString(1) to it.getInt(2) })
        for (old in accesses.dropLast(1)) assertEquals(401, mcp(old).status.value)
        assertEquals(200, mcp(access, "ping", "{}").status.value)

        // Fresh tokens buy no more requests: 120 a minute for the agent, across all its tokens.
        val agent = app.agents.list(false).single()
        repeat(119) { assertEquals(200, mcp(access, "ping", "{}").status.value, "request $it") }
        assertEquals(429, mcp(access, "ping", "{}").status.value)
        val fresh = token("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId).bodyAsText().json().s("access_token")
        assertEquals(429, mcp(fresh, "ping", "{}").status.value)
        assertEquals("Busy", agent.name)
    }

    @Test
    fun `a full registry makes room by dropping the oldest client nobody allowed`() = withApp {
        val first = app.oauth.register("first", null, listOf(REDIRECT), "192.0.2.1")
        repeat(OAuth.MAX_UNAPPROVED_CLIENTS - 1) { app.oauth.register("filler", null, listOf(REDIRECT), "192.0.2.1") }
        val newest = app.oauth.register("real client", null, listOf(REDIRECT), "198.51.100.7")
        assertNull(app.oauth.client(first.id))
        assertNotNull(app.oauth.client(newest.id))
        assertEquals(OAuth.MAX_UNAPPROVED_CLIENTS, app.db.one("SELECT count(*) FROM oauth_clients") { it.getInt(1) })
        // And they are gone after an hour anyway.
        now += OAuth.UNAPPROVED_KEEP_MS + 1
        app.oauth.sweep()
        assertEquals(0, app.db.one("SELECT count(*) FROM oauth_clients") { it.getInt(1) })
    }

    @Test
    fun `IPv6 addresses are limited by their 64-bit prefix`() {
        assertEquals("2001:0db8:0001:0002::/64", rateKey("2001:db8:1:2:aaaa::1"))
        assertEquals(rateKey("2001:db8:1:2::1"), rateKey("[2001:db8:1:2:ffff:ffff:ffff:ffff]"))
        assertEquals("192.0.2.7", rateKey("192.0.2.7"))
    }

    @Test
    fun `a client name loses characters that hide or reorder text`() = withApp {
        val r = register("""{"client_name":"Cur\u202esor\u200b\u2066 App\u0007\uDB40\uDC41","redirect_uris":["$REDIRECT"]}""")
        assertEquals("Cursor App", r.bodyAsText().json().s("client_name"))
        assertEquals("An MCP client", register("""{"client_name":"\u200b\u202e","redirect_uris":["$REDIRECT"]}""").bodyAsText().json().s("client_name"))
    }

    @Test
    fun `the token request must repeat a redirect URI the authorize request sent`() = withApp {
        val clientId = newClient()
        val v = verifier()
        val code = allow(authorizeQuery(clientId, challenge(v)), "choice" to "new", "name" to "strict")["code"]!!
        val r = token("grant_type" to "authorization_code", "code" to code, "client_id" to clientId, "code_verifier" to v)
        assertEquals("invalid_request", r.bodyAsText().json().s("error"))
        // Without one in the authorize request, the client's only redirect URI is used and need not be repeated.
        val v2 = verifier()
        val code2 = allow(authorizeQuery(clientId, challenge(v2), redirect = null), "choice" to "new", "name" to "lenient")["code"]!!
        assertEquals(200, token("grant_type" to "authorization_code", "code" to code2, "client_id" to clientId, "code_verifier" to v2).status.value)
    }

    @Test
    fun `the loser of a race to use a code revokes the grant too`() = withApp {
        val clientId = newClient()
        val v = verifier()
        val code = allow(authorizeQuery(clientId, challenge(v)), "choice" to "new", "name" to "race")["code"]!!
        // The other use lands between this one's checks and its write.
        val refused = runCatching {
            app.oauth.redeem(code, clientId, REDIRECT, v, null) { app.db.update("UPDATE oauth_grants SET code_used_at = ?", now); true }
        }.exceptionOrNull()
        assertEquals("invalid_grant", (refused as OAuth.Refused).error)
        assertNotNull(app.db.one("SELECT revoked_at FROM oauth_grants") { it.getLong(1).takeIf { _ -> !it.wasNull() } })
    }

    @Test
    fun `the owner lists an agent's connections and revokes one`() = withApp {
        val key = app.devices.mint("Phone").second
        val tokens = connect("Claude Code")
        val agent = app.agents.list(false).single()
        mcp(tokens.s("access_token"), "ping", "{}")
        assertEquals(401, http.get("/api/agents/${agent.id}/connections").status.value)
        val listed = http.get("/api/agents/${agent.id}/connections") { header("Authorization", "Bearer $key") }.bodyAsText().json()
        val row = listed["connections"]!!.jsonArray.single().jsonObject
        assertEquals("Claude Code", row.s("clientName"))
        assertEquals("http://127.0.0.1:33418", row.s("redirectOrigin"))
        assertTrue(row.s("lastUsedAt").isNotEmpty())
        val revoked = http.delete("/api/agents/${agent.id}/connections/${row.s("id")}") { header("Authorization", "Bearer $key") }
        assertEquals("true", revoked.bodyAsText().json().s("revoked"))
        assertEquals(401, mcp(tokens.s("access_token")).status.value)
        assertTrue(http.get("/api/agents/${agent.id}/connections") { header("Authorization", "Bearer $key") }.bodyAsText().json()["connections"]!!.jsonArray.isEmpty())
        assertEquals(404, http.delete("/api/agents/${agent.id}/connections/nope") { header("Authorization", "Bearer $key") }.status.value)
    }

    @Test
    fun `signing out ends the page's session`() = withApp {
        val query = authorizeQuery(newClient(), challenge(verifier()))
        signIn(query)
        assertTrue(page(query).bodyAsText().contains("Sign out of this page"))
        val out = post("/oauth/authorize", "$query&${form("action" to "sign_out")}")
        assertEquals(303, out.status.value)
        assertTrue(out.headers["Set-Cookie"]!!.contains("Max-Age=0"))
        cookie = null
        assertTrue(page(query).bodyAsText().contains("type=\"password\""))
    }
}
