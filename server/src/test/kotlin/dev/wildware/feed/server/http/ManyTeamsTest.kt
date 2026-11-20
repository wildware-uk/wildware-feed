package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Tenant
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Many teams on one server (docs/HOSTING.md §4, §7): each at its own address, made and run by the
 * control plane through the signed admin API, with its limits and its suspension enforced, and its
 * keys refused anywhere else.
 */
class ManyTeamsTest {
    private val key = "test-key-0123456789abcdef0123456789abcdef"
    private var now = 1_763_629_200_000L

    private fun config(keys: List<String> = listOf(key)) = Config(
        dataDir = java.nio.file.Files.createTempDirectory("feed-teams").toString(), adminPasswordHash = Passwords.hash("server password"),
        sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "https://agents.example.com", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "https://agents.example.com",
        teamDomain = "feed.test", adminApiKeys = keys, controlPlaneOrigin = "https://feed.test", adminHost = "admin.feed.test",
    )

    private fun withApp(keys: List<String> = listOf(key), block: suspend ApplicationTestBuilder.(App) -> Unit) {
        val app = App(config(keys), Database.memory()) { now }
        try {
            testApplication {
                application { feed(app) }
                block(app)
            }
        } finally {
            app.close()
        }
    }

    private var http: HttpClient? = null

    private suspend fun ApplicationTestBuilder.send(
        host: String, method: String, path: String, body: String? = null, type: String = "application/json", vararg headers: Pair<String, String>,
    ): HttpResponse = (http ?: createClient { followRedirects = false }.also { http = it }).request(path) {
        this.method = HttpMethod.parse(method)
        header("Host", host)
        for ((k, v) in headers) header(k, v)
        if (body != null) {
            header("Content-Type", type)
            setBody(body)
        }
    }

    /** A call signed as the control plane signs it. */
    private suspend fun ApplicationTestBuilder.admin(method: String, path: String, body: String? = null, vararg headers: Pair<String, String>, signWith: String = key, at: Long? = null): HttpResponse {
        // Each attempt is signed afresh, a second apart, as the control plane does.
        now += 1000
        val ts = (at ?: (now / 1000)).toString()
        val sig = AdminApi.sign(signWith, AdminApi.message(ts, method, path, body?.toByteArray() ?: ByteArray(0)))
        return send("feed.test", method, path, body, "application/json", "Feed-Admin-Timestamp" to ts, "Feed-Admin-Signature" to sig, *headers)
    }

    private suspend fun HttpResponse.obj(): JsonObject = Wire.json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.o(k: String) = this[k]!!.jsonObject

    private val acme = """{"slug":"acme","name":"Acme","owner":{"email":"Ann@Acme.test","name":"Ann"},"limits":{"seats":2,"storageBytes":1000},"externalRef":"tm_1"}"""

    @Test
    fun `the contract's test vector signs as written`() {
        val body = """{"slug":"acme"}"""
        assertEquals("0e671cb77c0d46e2a648694df49ee50c8c3d0ef1345e9e835175c8647589eb99", AdminApi.sha256Hex(body.toByteArray()))
        assertEquals(
            "v1=02819ba998415d8cc7379718c4a0a382f6c92281c7d7eeed16f092ad07309ab0",
            AdminApi.sign(key, AdminApi.message("1763629200", "POST", "/admin/teams", body.toByteArray())),
        )
    }

    @Test
    fun `only a fresh signature with a known key opens the admin API, and only at the bare address`() = withApp { _ ->
        assertEquals("bad_signature", send("feed.test", "GET", "/admin/teams").obj().s("error"))
        assertEquals(401, admin("GET", "/admin/teams", signWith = "another-key-0123456789abcdef0123456789").status.value)
        val stale = admin("GET", "/admin/teams", at = now / 1000 - 301)
        assertEquals(401, stale.status.value)
        assertEquals("stale_request", stale.obj().s("error"))
        assertEquals(200, admin("GET", "/admin/teams").status.value)
        // A signature over another path or body doesn't carry over.
        val ts = (now / 1000).toString()
        val sig = AdminApi.sign(key, AdminApi.message(ts, "GET", "/admin/teams", ByteArray(0)))
        assertEquals(401, send("feed.test", "GET", "/admin/usage", null, "application/json", "Feed-Admin-Timestamp" to ts, "Feed-Admin-Signature" to sig).status.value)
        // ADMIN_HOST is the directory too (when TEAM_DOMAIN itself is another site).
        val ts2 = (now / 1000).toString()
        val sig2 = AdminApi.sign(key, AdminApi.message(ts2, "GET", "/admin/teams", ByteArray(0)))
        assertEquals(200, send("admin.feed.test", "GET", "/admin/teams", null, "application/json", "Feed-Admin-Timestamp" to ts2, "Feed-Admin-Signature" to sig2).status.value)
        assertTrue(send("admin.feed.test", "GET", "/api/server").bodyAsText().contains("\"directory\":true"))
        assertEquals("no_such_team", send("admin.feed.test", "GET", "/api/summary").obj().s("error"))
        // Not at a team's address, nor the home team's.
        assertEquals(404, send("agents.example.com", "GET", "/admin/teams").status.value)
    }

    @Test
    fun `with no keys set, the admin API isn't there`() = withApp(keys = emptyList()) { _ ->
        assertEquals(404, admin("GET", "/admin/teams").status.value)
    }

    @Test
    fun `a team is made once per idempotency key, at its own address, with a one-time setup link`() = withApp { app ->
        assertEquals(400, admin("POST", "/admin/teams", acme).status.value, "an Idempotency-Key is required")
        val made = admin("POST", "/admin/teams", acme, "Idempotency-Key" to "create-tm_1")
        assertEquals(201, made.status.value, made.bodyAsText())
        val body = made.obj()
        val team = body.o("team")
        assertEquals("acme", team.s("slug"))
        assertEquals("https://acme.feed.test", team.s("address"))
        assertEquals("tm_1", team.s("externalRef"))
        assertEquals("active", team.s("state"))
        assertEquals(2, team.o("limits").s("seats").toInt())
        val owner = body.o("owner")
        assertEquals("ann@acme.test", owner.s("email"))
        assertEquals("true", owner.s("newAccount"))
        val setup = owner.s("setupUrl")
        assertTrue(setup.startsWith("https://acme.feed.test/setup/"), setup)

        val again = admin("POST", "/admin/teams", acme, "Idempotency-Key" to "create-tm_1")
        assertEquals(201, again.status.value)
        assertEquals("true", again.headers["Idempotent-Replayed"])
        val replay = again.obj()
        assertEquals(team.s("id"), replay.o("team").s("id"))
        val fresh = replay.o("owner").s("setupUrl")
        assertNotEquals(setup, fresh, "a replay makes a new link")
        assertEquals(1, admin("GET", "/admin/teams?externalRef=tm_1").obj()["teams"]!!.jsonArray.size, "made once")
        // The first link is revoked; the new one works.
        assertEquals(404, send("acme.feed.test", "GET", setup.removePrefix("https://acme.feed.test")).status.value)
        assertEquals(200, send("acme.feed.test", "GET", fresh.removePrefix("https://acme.feed.test")).status.value)
        assertEquals("idempotency_key_reused", admin("POST", "/admin/teams", acme.replace("Acme\"", "Other\""), "Idempotency-Key" to "create-tm_1").obj().s("error"))

        assertEquals("slug_taken", admin("POST", "/admin/teams", acme, "Idempotency-Key" to "k2").obj().s("error"))
        assertEquals("invalid_slug", admin("POST", "/admin/teams", acme.replace("\"acme\"", "\"a--b\""), "Idempotency-Key" to "k3").obj().s("error"))
        assertEquals("slug_reserved", admin("POST", "/admin/teams", acme.replace("\"acme\"", "\"www\""), "Idempotency-Key" to "k4").obj().s("error"))
        // Names the mail provider's records sit at.
        assertEquals("slug_reserved", admin("POST", "/admin/teams", acme.replace("\"acme\"", "\"send\""), "Idempotency-Key" to "k4b").obj().s("error"))

        // Where requests go: a team's address, the directory, an unknown team, any other address.
        assertTrue(send("acme.feed.test", "GET", "/api/server").bodyAsText().contains("\"product\":\"wildware-feed\""))
        assertEquals("""{"product":"wildware-feed","version":"${ServerInfo.version}","signIn":["account"],"directory":true}""", send("feed.test", "GET", "/api/server").bodyAsText())
        assertEquals("no_such_team", send("feed.test", "GET", "/api/summary").obj().s("error"))
        assertEquals("no_such_team", send("nobody.feed.test", "GET", "/api/server").obj().s("error"))
        assertEquals("no_such_team", send("home.feed.test", "GET", "/api/server").obj().s("error"))
        assertEquals(401, send("agents.example.com", "GET", "/api/summary").status.value, "the home team's address is the home team's")
        assertTrue(app.teams.find(team.s("id"))!!.open)
    }

    /** Make acme, set Ann's password through her link, sign her in; her key. */
    private suspend fun ApplicationTestBuilder.annSignedIn(): Pair<String, String> {
        val made = admin("POST", "/admin/teams", acme, "Idempotency-Key" to "create-tm_1").obj()
        val link = made.o("owner").s("setupUrl").removePrefix("https://acme.feed.test")
        val set = send("acme.feed.test", "POST", link, "password=a+long+password&again=a+long+password", "application/x-www-form-urlencoded", "Origin" to "https://acme.feed.test")
        assertEquals(200, set.status.value, set.bodyAsText())
        assertEquals(404, send("acme.feed.test", "GET", link).status.value, "a setup link works once")
        val signIn = send("acme.feed.test", "POST", "/api/devices/sign-in", """{"email":"ann@acme.test","password":"a long password","name":"Ann's phone"}""")
        assertEquals(201, signIn.status.value, signIn.bodyAsText())
        return made.o("team").s("id") to signIn.obj().s("token")
    }

    @Test
    fun `a team's founder signs in with their password alone, as the app's one-box form sends it`() = withApp { _ ->
        annSignedIn()
        val alone = send("acme.feed.test", "POST", "/api/devices/sign-in", """{"password":"a long password","name":"Ann's laptop"}""")
        assertEquals(201, alone.status.value, alone.bodyAsText())
        assertEquals("wrong_password", send("acme.feed.test", "POST", "/api/devices/sign-in", """{"password":"not her password","name":"x"}""").obj().s("error"))
        // The home team's ADMIN_PASSWORD_HASH opens no other team, and the home founder still signs in with it.
        assertEquals(400, send("acme.feed.test", "POST", "/api/devices/sign-in", """{"password":"server password","name":"x"}""").status.value)
        assertEquals(201, send("agents.example.com", "POST", "/api/devices/sign-in", """{"password":"server password","name":"x"}""").status.value)
    }

    @Test
    fun `before the founder sets a password, nothing signs in to their team without an email`() = withApp { _ ->
        assertEquals(201, admin("POST", "/admin/teams", acme, "Idempotency-Key" to "create-tm_1").status.value)
        assertEquals(400, send("acme.feed.test", "POST", "/api/devices/sign-in", """{"password":"server password","name":"x"}""").status.value)
        assertEquals(400, send("acme.feed.test", "POST", "/api/devices/sign-in", """{"password":"a long password","name":"x"}""").status.value)
    }

    @Test
    fun `a team's keys open only that team, and its cookie is a host-only one`() = withApp { app ->
        val (teamId, annKey) = annSignedIn()
        val auth = "Authorization" to "Bearer $annKey"
        assertEquals(200, send("acme.feed.test", "GET", "/api/summary", null, "application/json", auth).status.value)
        assertEquals(401, send("agents.example.com", "GET", "/api/summary", null, "application/json", auth).status.value)
        // The home team's founder signs in on the home address only.
        assertEquals(400, send("acme.feed.test", "POST", "/api/devices/sign-in", """{"password":"server password","name":"x"}""").status.value)
        val home = send("agents.example.com", "POST", "/api/devices/sign-in", """{"password":"server password","name":"x"}""").obj().s("token")
        assertEquals(401, send("acme.feed.test", "GET", "/api/summary", null, "application/json", "Authorization" to "Bearer $home").status.value)
        // An agent of acme's works at acme's /mcp only.
        val token = Tenant.with(teamId) { app.agents.mint("scout").second }
        val list = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        val mcp = arrayOf("Authorization" to "Bearer $token", "Accept" to "application/json, text/event-stream")
        assertEquals(200, send("acme.feed.test", "POST", "/mcp", list, "application/json", *mcp).status.value)
        assertEquals(401, send("agents.example.com", "POST", "/mcp", list, "application/json", *mcp).status.value)
        // The web app's cookie can't be set for the team by another subdomain.
        val cookie = send("acme.feed.test", "POST", "/api/devices/sign-in", """{"email":"ann@acme.test","password":"a long password","name":"Browser","cookie":true}""")
        assertTrue(cookie.headers["Set-Cookie"]!!.startsWith("__Host-feed_device="), cookie.headers["Set-Cookie"])
        assertTrue(cookie.headers["Set-Cookie"]!!.contains("; Secure"))
        // Links point at the team's own address.
        val invite = send("acme.feed.test", "POST", "/api/invites", """{"role":"member"}""", "application/json", auth).obj()
        assertTrue(invite.s("link").startsWith("https://acme.feed.test/join/"), invite.toString())
    }

    @Test
    fun `seats and storage are the team's limits, with clear refusals`() = withApp { app ->
        val (teamId, annKey) = annSignedIn()
        val auth = "Authorization" to "Bearer $annKey"
        // Ann is one of two logins; one invite takes the other.
        assertEquals(201, send("acme.feed.test", "POST", "/api/invites", """{"role":"member","email":"sam@acme.test"}""", "application/json", auth).status.value)
        val full = send("acme.feed.test", "POST", "/api/invites", """{"role":"member","email":"kim@acme.test"}""", "application/json", auth)
        assertEquals(409, full.status.value)
        assertEquals("seat_limit", full.obj().s("error"))
        assertEquals("seat_limit", admin("POST", "/admin/teams/$teamId/invites", """{"email":"kim@acme.test","role":"member"}""", "Idempotency-Key" to "i1").obj().s("error"))
        assertEquals(200, admin("PUT", "/admin/teams/$teamId/limits", """{"seats":3}""").status.value)
        val invited = admin("POST", "/admin/teams/$teamId/invites", """{"email":"kim@acme.test","role":"member"}""", "Idempotency-Key" to "i2")
        assertEquals(201, invited.status.value, invited.bodyAsText())
        val url = invited.obj().s("url")
        assertTrue(url.startsWith("https://acme.feed.test/join/"))
        val replayed = admin("POST", "/admin/teams/$teamId/invites", """{"email":"kim@acme.test","role":"member"}""", "Idempotency-Key" to "i2").obj()
        assertEquals(invited.obj().s("inviteId"), replayed.s("inviteId"))
        assertNotEquals(url, replayed.s("url"), "a replay makes the link again")
        assertEquals("already_member", admin("POST", "/admin/teams/$teamId/invites", """{"email":"ann@acme.test","role":"admin"}""", "Idempotency-Key" to "i3").obj().s("error"))

        // 1000 bytes of storage: a 2000-byte upload is refused before it starts.
        val token = Tenant.with(teamId) { app.agents.mint("scout").second }
        val call = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"create_upload","arguments":{"filename":"a.txt","mime":"text/plain","bytes":2000}}}"""
        val answer = send("acme.feed.test", "POST", "/mcp", call, "application/json", "Authorization" to "Bearer $token", "Accept" to "application/json, text/event-stream").bodyAsText()
        assertTrue(answer.contains("storage_full"), answer)

        val usage = admin("GET", "/admin/teams/$teamId/usage").obj()
        assertEquals(1, usage.o("seats").s("used").toInt())
        assertEquals(2, usage.o("seats").s("pendingInvites").toInt())
        assertEquals(3, usage.o("seats").s("limit").toInt())
        assertEquals(1, usage.s("agents").toInt())
        assertTrue(admin("GET", "/admin/usage?since=2020-01-01T00:00:00.000Z").obj()["usage"]!!.jsonArray.any { it.jsonObject.s("teamId") == teamId })
    }

    @Test
    fun `a suspended team reads but doesn't write, and says why`() = withApp { app ->
        val (teamId, annKey) = annSignedIn()
        val auth = "Authorization" to "Bearer $annKey"
        val token = Tenant.with(teamId) { app.agents.mint("scout").second }
        val mcp = arrayOf("Authorization" to "Bearer $token", "Accept" to "application/json, text/event-stream")
        fun tool(name: String, args: String) = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
        assertEquals(200, admin("PUT", "/admin/teams/$teamId/suspended", """{"suspended":true,"reason":"payment_failed","message":"Your payment didn't go through."}""").status.value)
        val write = send("acme.feed.test", "POST", "/api/messages", """{"body":"hi"}""", "application/json", auth)
        assertEquals(403, write.status.value)
        assertEquals("""{"error":"suspended","message":"Your payment didn't go through."}""", write.bodyAsText())
        val summary = send("acme.feed.test", "GET", "/api/summary", null, "application/json", auth).obj()
        assertEquals("true", summary.s("suspended"))
        assertTrue(send("acme.feed.test", "POST", "/mcp", tool("post_update", """{"body":"x"}"""), "application/json", *mcp).bodyAsText().contains("\"error\":\"suspended\""))
        assertTrue(!send("acme.feed.test", "POST", "/mcp", tool("list_projects", "{}"), "application/json", *mcp).bodyAsText().contains("suspended"))
        // Signing in still works while suspended.
        assertEquals(201, send("acme.feed.test", "POST", "/api/devices/sign-in", """{"email":"ann@acme.test","password":"a long password","name":"Laptop"}""").status.value)
        assertEquals(200, admin("PUT", "/admin/teams/$teamId/suspended", """{"suspended":false}""").status.value)
        assertEquals(201, send("acme.feed.test", "POST", "/api/messages", """{"body":"hi"}""", "application/json", auth).status.value)
        assertTrue(!send("acme.feed.test", "GET", "/api/summary", null, "application/json", auth).bodyAsText().contains("suspended"))
    }

    @Test
    fun `the control plane signs someone in with a ticket that works once, at their team only`() = withApp { _ ->
        val (teamId, _) = annSignedIn()
        val lookup = admin("POST", "/admin/memberships/lookup", """{"email":"ANN@acme.test"}""").obj()["memberships"]!!.jsonArray.single().jsonObject
        assertEquals("acme", lookup.o("team").s("slug"))
        assertEquals("owner", lookup.s("role"))
        assertEquals("no_such_member", admin("POST", "/admin/teams/$teamId/sign-in-tickets", """{"email":"nobody@acme.test"}""").obj().s("error"))
        val ticket = admin("POST", "/admin/teams/$teamId/sign-in-tickets", """{"email":"ann@acme.test"}""").obj()
        assertEquals("https://acme.feed.test", ticket.s("address"))
        val t = ticket.s("ticket")
        val redeem = """{"ticket":"$t","deviceName":"Ann's iPad","platform":"ios"}"""
        assertEquals(401, send("agents.example.com", "POST", "/api/devices/ticket", redeem).status.value, "not at another team's address")
        val first = send("acme.feed.test", "POST", "/api/devices/ticket", redeem)
        assertEquals(201, first.status.value, first.bodyAsText())
        assertTrue(first.obj().s("token").isNotEmpty())
        assertEquals("invalid_ticket", send("acme.feed.test", "POST", "/api/devices/ticket", redeem).obj().s("error"), "only once")
        // A browser's ticket sets the cookie and goes to the feed.
        val browser = admin("POST", "/admin/teams/$teamId/sign-in-tickets", """{"email":"ann@acme.test"}""").obj().s("ticket")
        // Only the control plane's page may post it.
        assertEquals(403, send("acme.feed.test", "POST", "/auth/ticket", "ticket=$browser", "application/x-www-form-urlencoded").status.value)
        assertEquals(403, send("acme.feed.test", "POST", "/auth/ticket", "ticket=$browser", "application/x-www-form-urlencoded", "Origin" to "https://evil.test").status.value)
        val page = send("acme.feed.test", "POST", "/auth/ticket", "ticket=$browser", "application/x-www-form-urlencoded", "Origin" to "https://feed.test")
        assertEquals(200, page.status.value)
        assertTrue(page.bodyAsText().contains("""<meta http-equiv="refresh" content="0;url=/">"""))
        assertTrue(page.headers["Set-Cookie"]!!.startsWith("__Host-feed_device="))
        now += 61_000
        val late = admin("POST", "/admin/teams/$teamId/sign-in-tickets", """{"email":"ann@acme.test"}""").obj().s("ticket")
        now += 61_000
        assertEquals(401, send("acme.feed.test", "POST", "/api/devices/ticket", """{"ticket":"$late","deviceName":"x"}""").status.value, "a minute, no more")
        // A setup link is for someone with no password yet.
        assertEquals("already_set_up", admin("POST", "/admin/teams/$teamId/setup-links", """{"email":"ann@acme.test"}""").obj().s("error"))
    }

    @Test
    fun `deleting a team closes its address at once and removes everything, then holds its address`() = withApp { app ->
        val (teamId, annKey) = annSignedIn()
        send("acme.feed.test", "POST", "/api/messages", """{"body":"hello"}""", "application/json", "Authorization" to "Bearer $annKey")
        val deleting = admin("DELETE", "/admin/teams/$teamId")
        assertEquals(202, deleting.status.value)
        assertEquals("deleting", deleting.obj().o("team").s("state"))
        assertEquals(202, admin("DELETE", "/admin/teams/$teamId").status.value, "a repeat says the same")
        assertEquals("no_such_team", send("acme.feed.test", "GET", "/api/summary", null, "application/json", "Authorization" to "Bearer $annKey").obj().s("error"))
        app.teams.purgeDue()
        assertEquals(404, admin("GET", "/admin/teams/$teamId").status.value)
        assertEquals(404, admin("DELETE", "/admin/teams/$teamId").status.value)
        Tenant.allTeams {
            for (table in dev.wildware.feed.server.db.TeamTables.ALL) {
                assertEquals(0L, app.db.one("SELECT count(*) FROM \"$table\" WHERE team_id = ?", teamId) { it.getLong(1) }, table)
            }
            assertEquals(0L, app.db.one("SELECT count(*) FROM users WHERE email = 'ann@acme.test'") { it.getLong(1) }, "her account went with her only team")
        }
        assertEquals("slug_reserved", admin("POST", "/admin/teams", acme, "Idempotency-Key" to "again").obj().s("error"))
        now += 31L * 24 * 60 * 60 * 1000
        app.teams.purgeDue()
        assertEquals(201, admin("POST", "/admin/teams", acme, "Idempotency-Key" to "again-later").status.value)
        assertEquals(400, admin("DELETE", "/admin/teams/home").status.value, "the home team stays")
    }

    @Test
    fun `an email typed in proves nothing, the control plane's proof makes a new account, and the claimer gets nowhere`() = withApp { _ ->
        // Eve runs her own team, and sets her account's email to Vic's.
        val evil = admin("POST", "/admin/teams", acme.replace("\"acme\"", "\"evil\"").replace("Ann@Acme.test", "eve@evil.test"), "Idempotency-Key" to "evil").obj()
        val link = evil.o("owner").s("setupUrl").removePrefix("https://evil.feed.test")
        send("evil.feed.test", "POST", link, "password=eve+password+1&again=eve+password+1", "application/x-www-form-urlencoded", "Origin" to "https://evil.feed.test")
        val eve = send("evil.feed.test", "POST", "/api/devices/sign-in", """{"email":"eve@evil.test","password":"eve password 1","name":"x"}""").obj().s("token")
        val claim = send("evil.feed.test", "PATCH", "/api/me", """{"email":"vic@corp.test","currentPassword":"eve password 1"}""", "application/json", "Authorization" to "Bearer $eve")
        assertEquals(200, claim.status.value, claim.bodyAsText())
        // Nothing proved, so the control plane's sign-in finds nobody.
        assertEquals(0, admin("POST", "/admin/memberships/lookup", """{"email":"vic@corp.test"}""").obj()["memberships"]!!.jsonArray.size)
        // Vic signs up; the control plane proved his email. He gets a new account, not Eve's.
        val vic = admin("POST", "/admin/teams", acme.replace("\"acme\"", "\"corp\"").replace("Ann@Acme.test", "vic@corp.test"), "Idempotency-Key" to "corp").obj()
        assertEquals("true", vic.o("owner").s("newAccount"))
        assertTrue(vic.o("owner").s("setupUrl").startsWith("https://corp.feed.test/setup/"))
        assertEquals(400, send("corp.feed.test", "POST", "/api/devices/sign-in", """{"email":"vic@corp.test","password":"eve password 1","name":"x"}""").status.value)
        assertEquals(400, send("evil.feed.test", "POST", "/api/devices/sign-in", """{"email":"vic@corp.test","password":"eve password 1","name":"x"}""").status.value)
        val teams = admin("POST", "/admin/memberships/lookup", """{"email":"vic@corp.test"}""").obj()["memberships"]!!.jsonArray
        assertEquals(listOf("corp"), teams.map { it.jsonObject.o("team").s("slug") })
        // Eve's own key still opens her own team, as before.
        assertEquals(200, send("evil.feed.test", "GET", "/api/summary", null, "application/json", "Authorization" to "Bearer $eve").status.value)
    }

    @Test
    fun `a setup link only proves the email it was sent to, and a new email cancels the links already out`() = withApp { app ->
        // Eve signs up twice before choosing a password, so she holds a link for each team.
        fun team(slug: String) = acme.replace("\"acme\"", "\"$slug\"").replace("Ann@Acme.test", "eve@evil.test")
        val first = admin("POST", "/admin/teams", team("evil"), "Idempotency-Key" to "evil").obj().o("owner").s("setupUrl").removePrefix("https://evil.feed.test")
        val second = admin("POST", "/admin/teams", team("evil2"), "Idempotency-Key" to "evil2").obj().o("owner").s("setupUrl").removePrefix("https://evil2.feed.test")
        val form = "password=eve+password+1&again=eve+password+1"
        val type = "application/x-www-form-urlencoded"
        assertEquals(200, send("evil.feed.test", "POST", first, form, type, "Origin" to "https://evil.feed.test").status.value)
        val eve = send("evil.feed.test", "POST", "/api/devices/sign-in", """{"email":"eve@evil.test","password":"eve password 1","name":"x"}""").obj().s("token")
        val claim = send("evil.feed.test", "PATCH", "/api/me", """{"email":"vic@corp.test","currentPassword":"eve password 1"}""", "application/json", "Authorization" to "Bearer $eve")
        assertEquals(200, claim.status.value, claim.bodyAsText())
        // The link sent to her old email no longer works, in any of her teams.
        assertEquals(404, send("evil2.feed.test", "GET", second).status.value, "a new email cancels the links already out")
        assertEquals(404, send("evil2.feed.test", "POST", second, form, type, "Origin" to "https://evil2.feed.test").status.value)
        Tenant.allTeams {
            assertEquals(null, app.db.one("SELECT email_verified_at FROM users WHERE email = 'vic@corp.test'") { it.getObject(1) }, "nothing proved Vic's email")
        }
        assertEquals(0, admin("POST", "/admin/memberships/lookup", """{"email":"vic@corp.test"}""").obj()["memberships"]!!.jsonArray.size)
        // Vic signs up: a new account, and Eve can't get into his team.
        val vic = admin("POST", "/admin/teams", acme.replace("\"acme\"", "\"corp\"").replace("Ann@Acme.test", "vic@corp.test"), "Idempotency-Key" to "corp").obj()
        assertEquals("true", vic.o("owner").s("newAccount"))
        assertEquals(400, send("corp.feed.test", "POST", "/api/devices/sign-in", """{"email":"vic@corp.test","password":"eve password 1","name":"x"}""").status.value)
        assertEquals(401, send("corp.feed.test", "GET", "/api/summary", null, "application/json", "Authorization" to "Bearer $eve").status.value)
    }

    @Test
    fun `the team view says whether the owner has used their setup link, without making a new one`() = withApp { _ ->
        val made = admin("POST", "/admin/teams", acme, "Idempotency-Key" to "create-tm_1").obj()
        val id = made.o("team").s("id")
        assertEquals("false", made.o("team").s("ownerSetupDone"))
        val link = made.o("owner").s("setupUrl").removePrefix("https://acme.feed.test")
        assertEquals("false", admin("GET", "/admin/teams/$id").obj().o("team").s("ownerSetupDone"))
        assertEquals("false", admin("GET", "/admin/teams?externalRef=tm_1").obj()["teams"]!!.jsonArray.single().jsonObject.s("ownerSetupDone"))
        // Reading it left the link as it was.
        val set = send("acme.feed.test", "POST", link, "password=a+long+password&again=a+long+password", "application/x-www-form-urlencoded", "Origin" to "https://acme.feed.test")
        assertEquals(200, set.status.value, set.bodyAsText())
        assertEquals("true", admin("GET", "/admin/teams/$id").obj().o("team").s("ownerSetupDone"))
    }

    @Test
    fun `a setup link minted for one email doesn't prove another`() = withApp { app ->
        val made = admin("POST", "/admin/teams", acme, "Idempotency-Key" to "create-tm_1").obj()
        val link = made.o("owner").s("setupUrl").removePrefix("https://acme.feed.test")
        // The account's email moves without going through the API (an older server, a hand edit).
        Tenant.allTeams { app.db.update("UPDATE users SET email = 'vic@corp.test', email_verified_at = NULL WHERE email = 'ann@acme.test'") }
        val set = send("acme.feed.test", "POST", link, "password=a+long+password&again=a+long+password", "application/x-www-form-urlencoded", "Origin" to "https://acme.feed.test")
        assertEquals(404, set.status.value, "the link was for another email")
        Tenant.allTeams {
            assertEquals(null, app.db.one("SELECT email_verified_at FROM users WHERE email = 'vic@corp.test'") { it.getObject(1) })
        }
    }

    @Test
    fun `a signed write is taken once, and a stranger learns only that a team is read-only`() = withApp { _ ->
        val ts = (now / 1000).toString()
        val body = """{"email":"nobody@acme.test"}"""
        val sig = AdminApi.sign(key, AdminApi.message(ts, "POST", "/admin/memberships/lookup", body.toByteArray()))
        val headers = arrayOf("Feed-Admin-Timestamp" to ts, "Feed-Admin-Signature" to sig)
        assertEquals(200, send("feed.test", "POST", "/admin/memberships/lookup", body, "application/json", *headers).status.value)
        val again = send("feed.test", "POST", "/admin/memberships/lookup", body, "application/json", *headers)
        assertEquals(401, again.status.value)
        assertEquals("stale_request", again.obj().s("error"))
        val (teamId, _) = annSignedIn()
        admin("PUT", "/admin/teams/$teamId/suspended", """{"suspended":true,"message":"Your card was declined."}""")
        val stranger = send("acme.feed.test", "POST", "/api/pair", """{"name":"x"}""")
        assertEquals(403, stranger.status.value)
        assertEquals(Suspension.GENERIC, stranger.obj().s("message"))
    }

    @Test
    fun `the home team's address can't sit under the team domain`() {
        val env = mapOf(
            "ADMIN_PASSWORD_HASH" to "\$argon2id\$x", "SESSION_SECRET" to "s".repeat(32), "TOKEN_SECRET" to "t".repeat(32),
            "DATABASE_URL" to "postgres://u:p@localhost/db", "TEAM_DOMAIN" to "feed.test",
        )
        val e = kotlin.test.assertFailsWith<Config.Invalid> { Config.load(env + ("PUBLIC_BASE_URL" to "https://x.feed.test")) }
        assertTrue(e.message!!.contains("PUBLIC_BASE_URL"))
        assertEquals("feed.test", Config.load(env + ("PUBLIC_BASE_URL" to "https://agents.example.com")).teamDomain)
    }
}
