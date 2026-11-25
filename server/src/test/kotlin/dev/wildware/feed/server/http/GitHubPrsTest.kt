package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.github.GitHub
import dev.wildware.feed.server.github.GitHubApi
import dev.wildware.feed.server.github.Installation
import dev.wildware.feed.server.github.PrState
import dev.wildware.feed.server.github.PullSnapshot
import dev.wildware.feed.server.github.Webhook
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GitHub on cards (migration 080, docs/GITHUB.md): the webhook's signature, each event read into
 * card state, the owner told once per failing head commit (and when it's green again), a change
 * request told once, and two teams on one server: an event never touches a team that didn't link
 * the installation, even when both linked the same PR.
 */
class GitHubPrsTest {
    private val secret = "w".repeat(40)
    private val config = Config(
        dataDir = java.nio.file.Files.createTempDirectory("feed-github").toString(), adminPasswordHash = Passwords.hash("server password"),
        sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32), publicBaseUrl = "http://localhost:5173",
        maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://localhost:5173", teamDomain = "feed.test",
    )

    /** GitHub, as the server asks it: installation 7 is on acme; the user with code "good" can see it. */
    private class FakeGitHub : GitHubApi {
        var calls = 0
        override fun installationFor(repo: String): Long? = if (repo == "acme/app") 7L else null
        override fun pull(installationId: Long, repo: String, number: Long): PullSnapshot? {
            calls++
            return PullSnapshot("Add the thing", PrState.OPEN, "a1", mapOf("build" to PrState.Check("build", 1, "in_progress", null, null)), emptyMap())
        }
        override fun userToken(code: String): String? = if (code == "good") "user-token" else null
        override fun userInstallations(token: String): List<Installation> = if (token == "user-token") listOf(Installation(7, "acme")) else emptyList()
    }

    private var strictBefore = false

    @BeforeTest fun strict() { strictBefore = Tenant.strict; Tenant.strict = true }

    @AfterTest fun relax() { Tenant.strict = strictBefore }

    private class Team(val id: String) {
        lateinit var ownerKey: String
        lateinit var ownerId: String
        lateinit var agentToken: String
        lateinit var agentId: String
        lateinit var updateId: String
    }

    private val alpha = Team("alpha")
    private val bravo = Team("bravo")

    private suspend fun ApplicationTestBuilder.send(host: String, method: String, path: String, body: String? = null, key: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse =
        client.request(path) {
            this.method = HttpMethod.parse(method)
            header("Host", host)
            header("Accept", "application/json, text/event-stream")
            if (key != null) header("Authorization", "Bearer $key")
            headers.forEach { (k, v) -> header(k, v) }
            if (body != null) {
                header("Content-Type", "application/json")
                setBody(body)
            }
        }

    private suspend fun ApplicationTestBuilder.tool(team: Team, name: String, args: JsonObject): JsonObject {
        val text = send(
            "${team.id}.feed.test", "POST", "/mcp",
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
                put("params", buildJsonObject { put("name", name); put("arguments", args) })
            }.toString(),
            key = team.agentToken,
        ).bodyAsText()
        val json = text.lines().firstOrNull { it.startsWith("data:") }?.removePrefix("data:") ?: text
        return Wire.json.parseToJsonElement(json).jsonObject["result"]!!.jsonObject
    }

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")); doFinal(body.toByteArray()) }
        return "sha256=" + mac.joinToString("") { "%02x".format(it) }
    }

    /** One webhook delivery, at the directory's address, as GitHub sends it. */
    private suspend fun ApplicationTestBuilder.hook(event: String, body: String, signature: String? = sign(body)): HttpResponse =
        send("feed.test", "POST", "/github/webhook", body, headers = buildMap { put("X-GitHub-Event", event); signature?.let { put("X-Hub-Signature-256", it) } })

    private fun checkRun(id: Long, name: String, sha: String, status: String, conclusion: String?, pr: Long = 42) = """
        {"action":"completed","installation":{"id":7},"repository":{"full_name":"Acme/App"},
         "check_run":{"id":$id,"name":"$name","head_sha":"$sha","status":"$status","conclusion":${conclusion?.let { "\"$it\"" } ?: "null"},
           "html_url":"https://github.com/acme/app/runs/$id","pull_requests":[{"number":$pr,"head":{"sha":"$sha"}}]}}
    """.trimIndent()

    private fun pull(action: String, sha: String, state: String = "open", draft: Boolean = false, merged: Boolean = false) = """
        {"action":"$action","number":42,"installation":{"id":7},"repository":{"full_name":"acme/app"},
         "pull_request":{"number":42,"title":"Add the thing","state":"$state","draft":$draft,"merged":$merged,"head":{"sha":"$sha"},"html_url":"https://github.com/acme/app/pull/42"}}
    """.trimIndent()

    private fun review(id: Long, state: String, action: String = "submitted") = """
        {"action":"$action","installation":{"id":7},"repository":{"full_name":"acme/app"},
         "review":{"id":$id,"state":"$state","body":"Please rename @rover's helper","user":{"login":"sam"},"html_url":"https://github.com/acme/app/pull/42#r$id"},
         "pull_request":{"number":42,"html_url":"https://github.com/acme/app/pull/42"}}
    """.trimIndent()

    /** The team's lines from GitHub, oldest first. */
    private fun lines(app: App, team: Team): List<String> = Tenant.with(team.id) {
        app.db.query("SELECT body FROM messages WHERE team_id = :team AND author = 'github' AND update_id = ? ORDER BY seq", team.updateId) { it.getString(1) }
    }

    private fun pr(app: App, team: Team): Map<String, String?> = Tenant.with(team.id) {
        app.db.one("SELECT state, ci, review, head_sha FROM card_prs WHERE team_id = :team AND update_id = ?", team.updateId) {
            mapOf("state" to it.getString(1), "ci" to it.getString(2), "review" to it.getString(3), "head" to it.getString(4))
        }!!
    }

    @Test
    fun `a linked PR follows GitHub, tells its owner once per commit, and never another team`() {
        val app = App(config, Database.memory())
        val fake = FakeGitHub()
        app.github = GitHub("feed-app", secret, fake, config.tokenSecret)
        Tenant.allTeams {
            for (t in listOf(alpha, bravo)) app.db.update("INSERT INTO teams (id, slug, name, created_at) VALUES (?, ?, ?, 1)", t.id, t.id, t.id.replaceFirstChar { it.uppercase() })
        }
        try {
            testApplication {
                application { feed(app) }
                for (t in listOf(alpha, bravo)) {
                    Tenant.with(t.id) {
                        val owner = app.people.createOwner("owner@${t.id}.test", "Owner ${t.id}", "password ${t.id} ${t.id}")
                        t.ownerId = owner.id
                        t.ownerKey = app.devices.mint("Phone", owner.id).second
                        val (agent, token) = app.agents.mint("rover")
                        t.agentId = agent.id
                        t.agentToken = token
                    }
                    tool(t, "create_project", buildJsonObject { put("name", "Web"); put("slug", "web-${t.id}") })
                    val posted = tool(t, "post_update", buildJsonObject { put("project", "web-${t.id}"); put("title", "The thing"); put("body", "Working on it") })
                    t.updateId = posted["structuredContent"]!!.jsonObject["id"]?.jsonPrimitive?.content
                        ?: Tenant.with(t.id) { app.db.one("SELECT id FROM updates WHERE team_id = :team ORDER BY seq DESC LIMIT 1") { it.getString(1) }!! }
                }

                // Only alpha connects GitHub: Settings gives the install page, GitHub sends the owner back.
                val status = Wire.json.parseToJsonElement(send("alpha.feed.test", "GET", "/api/github", key = alpha.ownerKey).bodyAsText()).jsonObject
                assertEquals("true", status["available"]!!.jsonPrimitive.content)
                assertEquals("true", status["canManage"]!!.jsonPrimitive.content)
                val connect = send("alpha.feed.test", "POST", "/api/github/connect", "{}", key = alpha.ownerKey)
                assertEquals(200, connect.status.value, connect.bodyAsText())
                val url = Wire.json.parseToJsonElement(connect.bodyAsText()).jsonObject["url"]!!.jsonPrimitive.content
                assertTrue(url.startsWith("https://github.com/apps/feed-app/installations/new?state="), url)
                val state = java.net.URLDecoder.decode(url.substringAfter("state="), Charsets.UTF_8)
                // A forged state, or a code GitHub didn't give, links nothing.
                assertEquals(400, send("feed.test", "GET", "/github/callback?code=good&installation_id=7&state=x.y").status.value)
                assertEquals(403, send("feed.test", "GET", "/github/callback?code=bad&installation_id=7&state=${java.net.URLEncoder.encode(state, Charsets.UTF_8)}").status.value)
                // An installation the person can't see on GitHub is refused.
                assertEquals(403, send("feed.test", "GET", "/github/callback?code=good&installation_id=8&state=${java.net.URLEncoder.encode(state, Charsets.UTF_8)}").status.value)
                val back = send("feed.test", "GET", "/github/callback?code=good&installation_id=7&setup_action=install&state=${java.net.URLEncoder.encode(state, Charsets.UTF_8)}")
                assertEquals(200, back.status.value, back.bodyAsText())
                assertEquals(listOf(7L), Tenant.with(alpha.id) { app.githubInstalls.list().map { it.installationId } })
                assertEquals(emptyList(), Tenant.with(bravo.id) { app.githubInstalls.list() })

                // Both teams' rover links the same PR. Alpha's card reads it from GitHub; bravo's can't.
                val linkedA = tool(alpha, "link_pr", buildJsonObject { put("update_id", alpha.updateId); put("url", "https://github.com/Acme/App/pull/42/files") })
                assertEquals("pending", linkedA["structuredContent"]!!.jsonObject["pr"]!!.jsonObject["ci"]!!.jsonPrimitive.content, linkedA.toString())
                val linkedB = tool(bravo, "link_pr", buildJsonObject { put("update_id", bravo.updateId); put("url", "https://github.com/acme/app/pull/42") })
                assertNotNull(linkedB["structuredContent"]!!.jsonObject["note"], linkedB.toString())
                assertEquals(1, fake.calls)
                val wrong = tool(alpha, "link_pr", buildJsonObject { put("update_id", alpha.updateId); put("url", "https://gitlab.com/acme/app/-/merge_requests/1") })
                assertEquals("true", wrong["isError"]?.jsonPrimitive?.content, wrong.toString())

                // A delivery that isn't signed with the secret changes nothing.
                val failing = checkRun(100, "build (android)", "a1", "completed", "failure")
                assertEquals(401, hook("check_run", failing, signature = "sha256=" + "0".repeat(64)).status.value)
                assertEquals(401, hook("check_run", failing, signature = null).status.value)
                assertEquals("pending", pr(app, alpha)["ci"])

                // CI goes red: alpha's rover is told, once, however often GitHub says so.
                assertEquals(200, hook("check_run", failing).status.value)
                assertEquals("failing", pr(app, alpha)["ci"])
                assertEquals(1, lines(app, alpha).size)
                val red = lines(app, alpha).single()
                assertTrue(red.startsWith("CI failed on #42 (acme/app): build (android) — https://github.com/acme/app/runs/100\n@rover Please fix"), red)
                hook("check_run", failing)
                hook("check_run", checkRun(101, "lint", "a1", "completed", "timed_out"))
                assertEquals(1, lines(app, alpha).size)
                // The line reaches rover (it names it), and rings no person's bell.
                val mentioned = Tenant.with(alpha.id) {
                    app.db.one("SELECT count(*) FROM message_mentions AS mm JOIN messages AS m ON m.team_id = mm.team_id AND m.id = mm.message_id WHERE mm.team_id = :team AND m.author = 'github' AND mm.agent_id = ?", alpha.agentId) { it.getLong(1) }
                }
                assertEquals(1L, mentioned)
                val news = tool(alpha, "get_messages", buildJsonObject { })
                assertTrue("CI failed on #42" in news.toString(), news.toString())
                // Bravo's card, linked to the same PR but not to the installation, heard nothing.
                assertNull(pr(app, bravo)["state"])
                assertEquals("none", pr(app, bravo)["ci"])
                assertEquals(emptyList(), lines(app, bravo))

                // Re-runs go green: said once.
                hook("check_run", checkRun(102, "build (android)", "a1", "completed", "success"))
                assertEquals(1, lines(app, alpha).size, "lint is still red")
                hook("check_run", checkRun(103, "lint", "a1", "completed", "success"))
                hook("check_run", checkRun(104, "build", "a1", "completed", "success"))
                assertEquals("passing", pr(app, alpha)["ci"])
                assertEquals(2, lines(app, alpha).size)
                assertEquals("CI is green again on #42 (acme/app).\n@rover It passes now.", lines(app, alpha)[1])
                // A late delivery of the old failed run can't make it red again.
                hook("check_run", failing)
                assertEquals("passing", pr(app, alpha)["ci"])

                // A new push: the checks start again, and a failure on it is told again.
                hook("pull_request", pull("synchronize", "b2"))
                assertEquals("none", pr(app, alpha)["ci"])
                assertEquals("b2", pr(app, alpha)["head"])
                hook("check_run", checkRun(200, "build", "b2", "in_progress", null))
                assertEquals("pending", pr(app, alpha)["ci"])
                hook("check_run", checkRun(200, "build", "b2", "completed", "failure"))
                assertEquals(3, lines(app, alpha).size)
                // A commit status counts too, by its context.
                hook("status", """{"installation":{"id":7},"repository":{"full_name":"acme/app"},"sha":"b2","context":"ci/deploy","state":"error","target_url":"https://ci.example/1"}""")
                assertEquals(3, lines(app, alpha).size)

                // A change request is told once; the board's "needs attention" holds the card.
                hook("pull_request_review", review(9, "changes_requested"))
                hook("pull_request_review", review(9, "changes_requested"))
                assertEquals(4, lines(app, alpha).size)
                val asked = lines(app, alpha)[3]
                assertTrue(asked.startsWith("Changes requested on #42 (acme/app) by sam — https://github.com/acme/app/pull/42#r9\n"), asked)
                assertTrue(asked.endsWith("@rover Please address the review and push."), asked)
                assertFalse("@rover's" in asked, "GitHub's own words must not @mention anyone: $asked")
                assertEquals("changes_requested", pr(app, alpha)["review"])
                tool(alpha, "set_status", buildJsonObject { put("update_id", alpha.updateId); put("status", "in_progress") })
                val board = Wire.json.parseToJsonElement(send("alpha.feed.test", "GET", "/api/board?attention=1", key = alpha.ownerKey).bodyAsText()).jsonObject
                val cards = board["columns"]!!.jsonArray.flatMap { it.jsonObject["cards"]!!.jsonArray }
                assertEquals(listOf(alpha.updateId), cards.map { it.jsonObject["card"]!!.jsonObject["id"]!!.jsonPrimitive.content })
                assertEquals("42", cards.single().jsonObject["prs"]!!.jsonArray.single().jsonObject["number"]!!.jsonPrimitive.content)

                // The feed's page carries the PR on the card.
                val page = send("alpha.feed.test", "GET", "/api/snapshot", key = alpha.ownerKey).bodyAsText()
                assertTrue("\"prs\":[{\"repo\":\"acme/app\",\"number\":42" in page, page.take(400))

                // Merged: open no more, so a failure after it isn't told.
                hook("pull_request", pull("closed", "b2", state = "closed", merged = true))
                assertEquals("merged", pr(app, alpha)["state"])
                hook("check_run", checkRun(300, "post", "b2", "completed", "failure"))
                assertEquals(4, lines(app, alpha).size)

                // Uninstalled on GitHub: gone from the team.
                hook("installation", """{"action":"deleted","installation":{"id":7}}""")
                assertEquals(emptyList(), Tenant.with(alpha.id) { app.githubInstalls.list() })
            }
        } finally {
            app.close()
        }
    }

    @Test
    fun `the webhook's signature is the HMAC of the exact body`() {
        val body = """{"zen":"Keep it logically awesome."}""".toByteArray()
        assertTrue(PrState.signed(secret, body, sign(String(body))))
        assertTrue(PrState.signed(secret, body, sign(String(body)).uppercase().replace("SHA256=", "sha256=")))
        assertFalse(PrState.signed(secret, body + ' '.code.toByte(), sign(String(body))))
        assertFalse(PrState.signed("x".repeat(40), body, sign(String(body))))
        assertFalse(PrState.signed(secret, body, sign(String(body)).replace("sha256=", "sha1=")))
        assertFalse(PrState.signed(secret, body, null))
        assertFalse(PrState.signed(secret, body, "sha256=abc"))
    }

    @Test
    fun `each event reads into the card state it means`() {
        fun state(json: String) = (Webhook.parse("pull_request", Wire.json.parseToJsonElement(json).jsonObject) as Webhook.Pull).state
        assertEquals(PrState.OPEN, state(pull("opened", "a")))
        assertEquals(PrState.DRAFT, state(pull("converted_to_draft", "a", draft = true)))
        assertEquals(PrState.MERGED, state(pull("closed", "a", state = "closed", merged = true)))
        assertEquals(PrState.CLOSED, state(pull("closed", "a", state = "closed")))
        val run = Webhook.parse("check_run", Wire.json.parseToJsonElement(checkRun(5, "test", "c", "completed", "failure")).jsonObject) as Webhook.CheckOn
        assertEquals("acme/app", run.repo)
        assertEquals(mapOf(42L to "c"), run.heads)
        assertTrue(run.check.failed)
        assertNull(Webhook.parse("check_suite", Wire.json.parseToJsonElement("""{"action":"completed","installation":{"id":7},"repository":{"full_name":"a/b"}}""").jsonObject))
        assertNull(Webhook.parse("pull_request", Wire.json.parseToJsonElement("""{"action":"opened"}""").jsonObject), "no installation: not ours")
        val dismissed = Webhook.parse("pull_request_review", Wire.json.parseToJsonElement(review(3, "changes_requested", action = "dismissed")).jsonObject) as Webhook.Review
        assertEquals("dismissed", dismissed.state)

        // CI: red beats running beats green; nothing is none.
        fun c(name: String, status: String, conclusion: String? = null, id: Long = 1) = PrState.Check(name, id, status, conclusion, null)
        assertEquals(PrState.CI_NONE, PrState.ci(emptyMap()))
        assertEquals(PrState.CI_PENDING, PrState.ci(mapOf("a" to c("a", "queued"), "b" to c("b", "completed", "success"))))
        assertEquals(PrState.CI_FAILING, PrState.ci(mapOf("a" to c("a", "queued"), "b" to c("b", "completed", "cancelled"))))
        assertEquals(PrState.CI_PASSING, PrState.ci(mapOf("a" to c("a", "completed", "skipped"), "b" to c("b", "completed", "neutral"))))
        // A newer run replaces an older one; an older one never replaces a newer.
        val newer = PrState.merged(mapOf("a" to c("a", "completed", "failure", id = 1)), c("a", "completed", "success", id = 2))
        assertEquals("success", newer["a"]!!.conclusion)
        assertEquals("success", PrState.merged(newer, c("a", "completed", "failure", id = 1))["a"]!!.conclusion)

        // Reviews: a later comment leaves a change request standing; a dismissal takes it away.
        var reviews = PrState.reviewed(emptyMap(), "sam", "CHANGES_REQUESTED")
        reviews = PrState.reviewed(reviews, "sam", "COMMENTED")
        reviews = PrState.reviewed(reviews, "kim", "APPROVED")
        assertEquals(PrState.CHANGES_REQUESTED, PrState.review(reviews))
        assertEquals(1, PrState.changesRequested(reviews))
        assertEquals(PrState.APPROVED, PrState.review(PrState.reviewed(reviews, "sam", "DISMISSED")))
    }

    @Test
    fun `a state is good only as made, and only for ten minutes`() {
        var now = 1_000_000L
        val github = GitHub("feed-app", secret, FakeGitHub(), "k".repeat(32)) { now }
        val state = github.state("alpha", "m1")
        assertEquals("alpha" to "m1", github.readState(state))
        assertNull(github.readState(state.dropLast(1) + if (state.last() == 'A') 'B' else 'A'))
        assertNull(GitHub("feed-app", secret, FakeGitHub(), "j".repeat(32)) { now }.readState(state))
        now += GitHub.STATE_TTL_MS + 1
        assertNull(github.readState(state))
    }
}
