package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.domain.PlanLimit
import dev.wildware.feed.server.domain.Teams
import dev.wildware.feed.server.domain.Updates
import dev.wildware.feed.server.domain.feed
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The free plan (docs/TEAMS.md §7): 2 people, 3 agents, 500 MB of files and 30 days of history.
 * A team over a limit keeps working; the next person, agent or upload past it is refused with
 * `plan_limit`, and older history is hidden, never deleted, until the team is on the Team plan.
 */
class FreePlanTest {
    // Built, not written out, so the secret scan does not take it for a real key.
    private val key = "test-" + "k" + "ey-" + (('0'..'9') + ('a'..'f')).joinToString("").repeat(2)
    private var now = 1_763_629_200_000L
    private val day = 24L * 60 * 60 * 1000

    private fun config() = Config(
        dataDir = java.nio.file.Files.createTempDirectory("feed-free").toString(), adminPasswordHash = Passwords.hash("server password"),
        sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "https://agents.example.com", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "https://agents.example.com",
        teamDomain = "feed.test", adminApiKeys = listOf(key), controlPlaneOrigin = "https://account.feed.test", adminHost = "admin.feed.test",
    )

    private fun withApp(block: suspend ApplicationTestBuilder.(App) -> Unit) {
        val app = App(config(), Database.memory()) { now }
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
        host: String, method: String, path: String, body: String? = null, vararg headers: Pair<String, String>, type: String = "application/json",
    ): HttpResponse = (http ?: createClient { followRedirects = false }.also { http = it }).request(path) {
        this.method = HttpMethod.parse(method)
        header("Host", host)
        for ((k, v) in headers) header(k, v)
        if (body != null) {
            header("Content-Type", type)
            setBody(body)
        }
    }

    private suspend fun ApplicationTestBuilder.admin(method: String, path: String, body: String? = null, vararg headers: Pair<String, String>): HttpResponse {
        now += 1000
        val ts = (now / 1000).toString()
        val sig = AdminApi.sign(key, AdminApi.message(ts, method, path, body?.toByteArray() ?: ByteArray(0)))
        return send("feed.test", method, path, body, "Feed-Admin-Timestamp" to ts, "Feed-Admin-Signature" to sig, *headers)
    }

    private suspend fun HttpResponse.obj(): JsonObject = Wire.json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.o(k: String) = this[k]!!.jsonObject

    private fun acme(plan: String?) =
        """{"slug":"acme","name":"Acme","owner":{"email":"ann@acme.test","name":"Ann"},"limits":{"seats":5,"storageBytes":26843545600},"externalRef":"tm_1"""" +
            (plan?.let { ""","plan":"$it"""" } ?: "") + "}"

    /** Make acme on [plan], set Ann's password, sign her in: the team's id and her bearer header. */
    private suspend fun ApplicationTestBuilder.acmeWithAnn(plan: String? = "free"): Pair<String, Pair<String, String>> {
        val made = admin("POST", "/admin/teams", acme(plan), "Idempotency-Key" to "create-tm_1")
        assertEquals(201, made.status.value, made.bodyAsText())
        val link = made.obj().o("owner").s("setupUrl").removePrefix("https://acme.feed.test")
        send("acme.feed.test", "POST", link, "password=a+long+password&again=a+long+password", "Origin" to "https://acme.feed.test", type = "application/x-www-form-urlencoded")
        val signIn = send("acme.feed.test", "POST", "/api/devices/sign-in", """{"email":"ann@acme.test","password":"a long password","name":"Phone"}""")
        assertEquals(201, signIn.status.value, signIn.bodyAsText())
        return made.obj().o("team").s("id") to ("Authorization" to "Bearer ${signIn.obj().s("token")}")
    }

    private suspend fun ApplicationTestBuilder.invite(auth: Pair<String, String>, email: String) =
        send("acme.feed.test", "POST", "/api/invites", """{"role":"member","email":"$email"}""", auth)

    @Test
    fun `the control plane sets a team's plan, and the team object says which`() = withApp { _ ->
        val made = admin("POST", "/admin/teams", acme("free"), "Idempotency-Key" to "create-tm_1").obj().o("team")
        assertEquals("free", made.s("plan"))
        val id = made.s("id")
        assertEquals("team", admin("PUT", "/admin/teams/$id/plan", """{"plan":"team"}""").obj().o("team").s("plan"))
        assertEquals("team", admin("GET", "/admin/teams/$id").obj().o("team").s("plan"))
        assertEquals(400, admin("PUT", "/admin/teams/$id/plan", """{"plan":"gold"}""").status.value)
        assertEquals(404, admin("PUT", "/admin/teams/nope/plan", """{"plan":"free"}""").status.value)
        assertEquals("team", admin("GET", "/admin/teams/$id/usage").obj().s("plan"))
        assertEquals(400, admin("POST", "/admin/teams", acme("gold").replace("acme", "beta"), "Idempotency-Key" to "create-tm_2").status.value)
    }

    @Test
    fun `a free team has room for two people, counting waiting invites, and says where to upgrade`() = withApp { _ ->
        val (id, auth) = acmeWithAnn()
        assertEquals(201, invite(auth, "sam@acme.test").status.value)
        val full = invite(auth, "kim@acme.test")
        assertEquals(409, full.status.value)
        val body = full.obj()
        assertEquals("plan_limit", body.s("error"))
        assertEquals("people", body.s("limit"))
        assertEquals("https://account.feed.test/teams/acme", body.s("upgradeUrl"))
        // The control plane's own invites meet the same limit.
        assertEquals("plan_limit", admin("POST", "/admin/teams/$id/invites", """{"email":"kim@acme.test","role":"member"}""", "Idempotency-Key" to "i1").obj().s("error"))
        // On the Team plan, the seat limit (5) is what counts.
        admin("PUT", "/admin/teams/$id/plan", """{"plan":"team"}""")
        assertEquals(201, invite(auth, "kim@acme.test").status.value)
        // Back on free with three: nobody is removed, but nobody more can come in.
        admin("PUT", "/admin/teams/$id/plan", """{"plan":"free"}""")
        assertEquals("plan_limit", invite(auth, "lee@acme.test").obj().s("error"))
        assertEquals(200, send("acme.feed.test", "GET", "/api/summary", null, auth).status.value, "the team keeps working")
    }

    @Test
    fun `a free team has room for three agents, and a fourth is refused until one goes or the team upgrades`() = withApp { app ->
        val (id, _) = acmeWithAnn()
        Tenant.with(id) {
            val first = app.agents.mint("one").first
            app.agents.mint("two")
            app.agents.mint("three")
            val refused = assertFailsWith<PlanLimit> { app.agents.mint("four") }
            assertEquals("agents", refused.limit)
            assertEquals(409, refused.status)
            app.agents.revoke(first.id)
            app.agents.mint("four")
            assertFailsWith<PlanLimit> { app.agents.mint("five") }
            app.teams.setPlan(id, Teams.Plan.TEAM)
            app.agents.mint("five")
        }
    }

    @Test
    fun `a free team has room for 500 MB of files`() = withApp { app ->
        val (id, _) = acmeWithAnn()
        Tenant.with(id) {
            app.teams.checkStorage(400L * 1024 * 1024)
            val refused = assertFailsWith<PlanLimit> { app.teams.checkStorage(600L * 1024 * 1024) }
            assertEquals("storage", refused.limit)
            assertEquals(413, refused.status)
            app.teams.setPlan(id, Teams.Plan.TEAM)
            app.teams.checkStorage(600L * 1024 * 1024)
        }
    }

    @Test
    fun `history older than 30 days is hidden on the free plan, kept, and shows again on upgrade`() = withApp { app ->
        val (id, auth) = acmeWithAnn()
        val oldId = Tenant.with(id) {
            app.db.update("INSERT INTO projects (team_id, id, slug, name, created_at, updated_at) VALUES (:team, 'P1', 'web', 'Web', 1, 1)")
            val agent = app.agents.mint("scout").first
            val old = app.feed.updates.post(Updates.Post(project = "web", agentId = agent.id, body = "old news"))
            app.feed.updates.post(Updates.Post(project = "web", agentId = agent.id, body = "fresh news"))
            app.db.update("UPDATE updates SET created_at = ? WHERE team_id = :team AND id = ?", now - 31 * day, old.id)
            old.id
        }
        assertEquals(201, send("acme.feed.test", "POST", "/api/messages", """{"body":"old line"}""", auth).status.value)
        assertEquals(201, send("acme.feed.test", "POST", "/api/messages", """{"body":"new line"}""", auth).status.value)
        Tenant.with(id) { app.db.update("UPDATE messages SET created_at = ? WHERE team_id = :team AND body = 'old line'", now - 31 * day) }

        suspend fun feed() = send("acme.feed.test", "GET", "/api/snapshot", null, auth).bodyAsText()
        val hidden = feed()
        assertTrue("fresh news" in hidden && "new line" in hidden, hidden)
        assertTrue("old news" !in hidden && "old line" !in hidden, hidden)
        assertTrue("old line" !in send("acme.feed.test", "GET", "/api/messages", null, auth).bodyAsText())
        assertEquals(404, send("acme.feed.test", "GET", "/api/threads/$oldId", null, auth).status.value)
        // Nothing was deleted.
        assertEquals(2L, Tenant.with(id) { app.db.one("SELECT count(*) FROM updates WHERE team_id = :team") { it.getLong(1) } })

        admin("PUT", "/admin/teams/$id/plan", """{"plan":"team"}""")
        val shown = feed()
        assertTrue("old news" in shown && "old line" in shown, shown)
        assertEquals(200, send("acme.feed.test", "GET", "/api/threads/$oldId", null, auth).status.value)
    }

    @Test
    fun `the apps read the plan, its limits and what is used`() = withApp { app ->
        val (id, auth) = acmeWithAnn()
        Tenant.with(id) { app.agents.mint("scout") }
        invite(auth, "sam@acme.test")
        val plan = send("acme.feed.test", "GET", "/api/plan", null, auth).obj()
        assertEquals("free", plan.s("plan"))
        assertEquals(2, plan.o("limits").s("people").toInt())
        assertEquals(3, plan.o("limits").s("agents").toInt())
        assertEquals(500L * 1024 * 1024, plan.o("limits").s("storageBytes").toLong())
        assertEquals(30, plan.o("limits").s("historyDays").toInt())
        assertEquals(2, plan.o("used").s("people").toInt())
        assertEquals(1, plan.o("used").s("agents").toInt())
        assertEquals("https://account.feed.test/teams/acme", plan.s("upgradeUrl"))

        admin("PUT", "/admin/teams/$id/plan", """{"plan":"team"}""")
        val paid = send("acme.feed.test", "GET", "/api/plan", null, auth).obj()
        assertEquals("team", paid.s("plan"))
        assertEquals(5, paid.o("limits").s("people").toInt())
        assertEquals(JsonNull, paid.o("limits")["agents"])
        assertEquals(JsonNull, paid.o("limits")["historyDays"])
        assertEquals(JsonNull, paid["upgradeUrl"])
    }

    @Test
    fun `a team with no plan, like the home team, has none of the free plan's limits`() = withApp { app ->
        val home = send("agents.example.com", "POST", "/api/devices/sign-in", """{"password":"server password","name":"x"}""").obj().s("token")
        assertEquals(JsonNull, send("agents.example.com", "GET", "/api/plan", null, "Authorization" to "Bearer $home").obj()["plan"])
        Tenant.with(Tenant.HOME) { repeat(4) { app.agents.mint("agent$it") } }
        // A hosted team made before plans reads as the Team plan and has no free limits either.
        val (id, _) = acmeWithAnn(plan = null)
        assertEquals("team", admin("GET", "/admin/teams/$id").obj().o("team").s("plan"))
        Tenant.with(id) { repeat(4) { app.agents.mint("agent$it") } }
        assertTrue(admin("GET", "/admin/teams").obj()["teams"]!!.jsonArray.isNotEmpty())
    }
}
