package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.TeamTables
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.domain.HomeMove
import dev.wildware.feed.server.domain.People
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `feed-server move-home`: a server that ran before it had many teams hands its home team to a fresh
 * hosted team, with every key, token and file where it was.
 */
class HomeMoveTest {
    private val key = "test-key-0123456789abcdef0123456789abcdef"
    private var now = 1_763_629_200_000L

    private fun config() = Config(
        dataDir = java.nio.file.Files.createTempDirectory("feed-move").toString(), adminPasswordHash = Passwords.hash("server password"),
        sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "https://agents.example.com", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "https://agents.example.com",
        teamDomain = "feed.test", adminApiKeys = listOf(key), controlPlaneOrigin = "https://feed.test", adminHost = "admin.feed.test",
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

    private suspend fun ApplicationTestBuilder.send(host: String, method: String, path: String, body: String? = null, vararg headers: Pair<String, String>): HttpResponse =
        (http ?: createClient { followRedirects = false }.also { http = it }).request(path) {
            this.method = HttpMethod.parse(method)
            header("Host", host)
            for ((k, v) in headers) header(k, v)
            if (body != null) {
                header("Content-Type", if (body.startsWith("{")) "application/json" else "application/x-www-form-urlencoded")
                setBody(body)
            }
        }

    private suspend fun ApplicationTestBuilder.admin(method: String, path: String, body: String? = null, idem: String? = null): HttpResponse {
        now += 1000
        val ts = (now / 1000).toString()
        val sig = AdminApi.sign(key, AdminApi.message(ts, method, path, body?.toByteArray() ?: ByteArray(0)))
        val headers = listOfNotNull("Feed-Admin-Timestamp" to ts, "Feed-Admin-Signature" to sig, idem?.let { "Idempotency-Key" to it })
        return send("feed.test", method, path, body, *headers.toTypedArray())
    }

    private suspend fun HttpResponse.obj(): JsonObject = Wire.json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private fun team(slug: String, email: String) =
        """{"slug":"$slug","name":"Wildware","owner":{"email":"$email","name":"Owner"},"limits":{"seats":10,"storageBytes":1000000},"externalRef":"tm_$slug","allowReserved":true}"""

    /** The owner's fresh team, with the password set from its link; its id. */
    private suspend fun ApplicationTestBuilder.freshTeam(slug: String = "wildware", email: String = "owner@example.test"): String {
        val made = admin("POST", "/admin/teams", team(slug, email), "create-$slug").obj()
        val link = made["owner"]!!.jsonObject.s("setupUrl").substringAfter(".feed.test")
        val set = send("$slug.feed.test", "POST", link, "password=a+long+password&again=a+long+password", "Origin" to "https://$slug.feed.test")
        assertEquals(200, set.status.value, set.bodyAsText())
        return made["team"]!!.jsonObject.s("id")
    }

    private fun rows(app: App, team: String): Map<String, Long> = Tenant.allTeams {
        TeamTables.ALL.associateWith { t -> app.db.one("SELECT count(*) FROM \"$t\" WHERE team_id = ?", team) { it.getLong(1) }!! }.filterValues { it > 0 }
    }

    @Test
    fun `an operator may give a reserved name, never home, and a customer never can`() = withApp { _ ->
        assertEquals("slug_reserved", admin("POST", "/admin/teams", team("wildware", "a@example.test").replace(",\"allowReserved\":true", ""), "k1").obj().s("error"))
        assertEquals("slug_reserved", admin("POST", "/admin/teams", team("home", "a@example.test"), "k2").obj().s("error"))
        assertEquals(201, admin("POST", "/admin/teams", team("wildware", "a@example.test"), "k3").status.value)
    }

    @Test
    fun `the home team moves into a fresh team and every key keeps working, at the old address and the new`() = withApp { app ->
        // Before: the home team with a signed-in phone, an agent and a card.
        val phone = send("agents.example.com", "POST", "/api/devices/sign-in", """{"password":"server password","name":"Phone"}""").obj().s("token")
        val agent = Tenant.with(Tenant.HOME) {
            app.db.update("INSERT INTO projects (team_id, id, slug, name, created_at, updated_at) VALUES (:team, 'P1', 'web', 'Web', 1, 1)")
            val (a, token) = app.agents.mint("scout")
            app.feed.updates.post(Updates.Post(project = "web", agentId = a.id, body = "Shipped"))
            token
        }
        val target = freshTeam()
        val before = rows(app, Tenant.HOME)

        // A dry run counts and changes nothing.
        val dry = HomeMove(app.db) { now }.run(target, dryRun = true)
        assertEquals(before.mapValues { it.value.toInt() }, dry.moved)
        assertEquals(before, rows(app, Tenant.HOME))

        val report = HomeMove(app.db) { now }.run(target)
        assertEquals(before.mapValues { it.value.toInt() }, report.moved, "every home row, once")
        assertEquals(emptyMap(), rows(app, Tenant.HOME), "the home team is left empty")
        assertEquals(before, rows(app, target), "and the team has exactly what it had: its own fresh rows went")
        now += 10_000 // past the address cache

        for (host in listOf("agents.example.com", "wildware.feed.test")) {
            val summary = send(host, "GET", "/api/summary", null, "Authorization" to "Bearer $phone")
            assertEquals(200, summary.status.value, host)
            val mcp = arrayOf("Authorization" to "Bearer $agent", "Accept" to "application/json, text/event-stream")
            assertEquals(200, send(host, "POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", *mcp).status.value, host)
            assertEquals(401, send(host, "GET", "/api/summary").status.value)
        }
        assertEquals(1L, Tenant.with(target) { app.db.one("SELECT count(*) FROM updates WHERE team_id = :team AND body = 'Shipped'") { it.getLong(1) } })
        // The owner's own account owns the old work: the founding membership is theirs now.
        val owner = send("wildware.feed.test", "POST", "/api/devices/sign-in", """{"email":"owner@example.test","password":"a long password","name":"Laptop"}""")
        assertEquals(201, owner.status.value, owner.bodyAsText())
        Tenant.with(target) {
            val founder = app.people.find(People.FOUNDER)!!
            assertEquals("owner@example.test", founder.email)
            assertEquals(People.Role.OWNER, founder.role)
            assertEquals(1, app.people.activeCount())
        }
        // The server's own password belonged to the home team: it opens nothing now.
        val server = send("agents.example.com", "POST", "/api/devices/sign-in", """{"password":"server password","name":"x"}""").status.value
        assertTrue(server in 400..401, "got $server")
        // Its files stay at the root of the store.
        assertEquals("", app.mediaPaths.teamPrefix(target))
        assertEquals("t/x/", app.mediaPaths.teamPrefix("x"))

        val again = assertFailsWith<DomainError> { HomeMove(app.db) { now }.run(target) }
        assertTrue(again.message!!.contains("already"), again.message)
    }

    @Test
    fun `only a fresh team can take over the home team`() = withApp { app ->
        val used = freshTeam("acme", "ann@acme.test")
        Tenant.with(used) { app.agents.mint("helper") }
        val e = assertFailsWith<DomainError> { HomeMove(app.db) { now }.run(used) }
        assertTrue(e.message!!.contains("agents"), e.message)
        assertFailsWith<DomainError> { HomeMove(app.db) { now }.run(Tenant.HOME) }
        assertFailsWith<DomainError> { HomeMove(app.db) { now }.run("nope") }
        assertTrue(rows(app, Tenant.HOME).containsKey("memberships"), "nothing moved")
    }

    @Test
    fun `deleting the team that took over the home team removes its own files and no one else's`() = withApp { app ->
        val store = app.mediaPaths.store
        fun media(id: String) = app.db.update(
            "INSERT INTO media (team_id, id, author, kind, mime, bytes, sha256, status, created_at) VALUES (:team, ?, 'human', 'image', 'image/png', 3, 'abc', 'ready', 1)", id,
        )
        val mine = "01M7WMWCTEDRE8GWMTQ2V3F6Q1"
        Tenant.with(Tenant.HOME) { media(mine) }
        val target = freshTeam()
        HomeMove(app.db) { now }.run(target)
        val other = freshTeam("acme", "ann@acme.test")
        // Its file at the root; a file at the root that isn't its; another team's under its prefix.
        store.writeText("01/$mine/original.png", "mine")
        store.writeText("01/01M7WMWCTEDRE8GWMTQ2V3F6Q9/original.png", "not mine")
        store.writeText("t/$other/01/01M7WMWCTEDRE8GWMTQ2V3F6Q8/original.png", "acme's")

        assertEquals(202, admin("DELETE", "/admin/teams/$target").status.value)
        app.teams.purgeDue()
        val left = store.list("").map { it.first }.sorted()
        assertEquals(listOf("01/01M7WMWCTEDRE8GWMTQ2V3F6Q9/original.png", "t/$other/01/01M7WMWCTEDRE8GWMTQ2V3F6Q8/original.png"), left)
        assertEquals(emptyMap(), rows(app, target))
    }
}
