package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.Secrets
import dev.wildware.feed.server.events.AppEvent
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `request_secret_access` (docs/SECRETS.md): an agent asks to use a secret someone else saved, a
 * card in the thread asks a person, and Allow shares it as Settings would. No value is ever on the
 * card, in a tool result or in an event.
 */
class SecretAccessTest {
    private fun config(dir: String) = Config(
        dataDir = dir, adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "https://feed.example.com", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "https://feed.example.com",
        secretsKeys = Config.SecretsKeys("k".repeat(40)),
    )

    private val value = "sk-test-NOT-REAL-access-41bb"

    /** The founder, a member (Sam), the agent that saved the secret (browser) and the one asking (lead). */
    private class Team(val app: App) {
        val owner: People.Person = app.people.find(People.FOUNDER)!!
        val ownerKey = app.devices.mint("Owner phone").second
        val sam = join(People.Role.MEMBER, "Sam")
        val samKey = app.devices.mint("Sam phone", sam.id).second
        val browser: Agents.Agent
        val browserToken: String
        val lead: Agents.Agent
        val leadToken: String
        val events = mutableListOf<AppEvent>()
        val transcript = StringBuilder()

        init {
            app.agents.mint("browser").let { (a, t) -> browser = a; browserToken = t }
            app.agents.mint("lead").let { (a, t) -> lead = a; leadToken = t }
        }

        private fun join(role: People.Role, name: String): People.Person {
            val (_, token) = app.people.invite(app.people.find(People.FOUNDER)!!, role, "${name.lowercase()}@example.test")
            return app.people.accept(token, name, "${name.lowercase()}@example.test", "password for $name")
        }
    }

    private fun withTeam(block: suspend Team.(HttpClient) -> Unit) {
        val dir = Files.createTempDirectory("secret-access").toFile()
        val app = App(config(dir.path), Database.memory())
        try {
            testApplication {
                application { feed(app) }
                val team = Team(app)
                val stop = app.bus.subscribe { synchronized(team.events) { team.events += it } }
                try {
                    team.block(client)
                } finally {
                    stop()
                }
                // Whatever happened, no value is in any event or any tool result.
                synchronized(team.events) { for (e in team.events) assertFalse(value in e.payload.toString(), "${e.type} carried the value") }
                assertFalse(value in team.transcript.toString(), "a tool result carried the value")
            }
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    private suspend fun HttpClient.call(key: String, method: String, path: String, body: String? = null): HttpResponse = request(path) {
        this.method = HttpMethod.parse(method)
        header("Authorization", "Bearer $key")
        header("Accept", "application/json, text/event-stream")
        if (body != null) {
            header("Content-Type", "application/json")
            setBody(body)
        }
    }

    private suspend fun Team.json(client: HttpClient, key: String, method: String, path: String, body: String? = null, status: Int = 200): JsonObject {
        val r = client.call(key, method, path, body)
        val text = r.bodyAsText()
        assertEquals(status, r.status.value, "$method $path: $text")
        assertFalse(value in text, "$method $path answered with the value")
        return Wire.json.parseToJsonElement(text).jsonObject
    }

    private suspend fun Team.tool(client: HttpClient, token: String, name: String, args: JsonObject): JsonObject {
        val r = client.call(
            token, "POST", "/mcp",
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
                put("params", buildJsonObject { put("name", name); put("arguments", args) })
            }.toString(),
        )
        val text = r.bodyAsText()
        transcript.append(text)
        return Wire.json.parseToJsonElement(text).jsonObject["result"]!!.jsonObject
    }

    private fun JsonObject.error(): String? = this["structuredContent"]?.jsonObject?.get("error")?.jsonPrimitive?.content
    private fun JsonObject.data(): JsonObject = this["structuredContent"]!!.jsonObject
    private fun JsonObject.text(): String = this["content"]!!.jsonArray.joinToString(" ") { it.jsonObject["text"]!!.jsonPrimitive.content }
    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { for ((k, v) in pairs) put(k, v) }

    /** Channel `web`, and TURNSTILE_SECRET saved by the browser agent for itself alone. */
    private suspend fun Team.setUp(client: HttpClient) {
        tool(client, leadToken, "create_project", args("name" to "Web", "slug" to "web"))
        app.secrets.create("TURNSTILE_SECRET", value.toByteArray(), "Cloudflare Turnstile, live site", false, listOf(browser.id), Secrets.Actor.agent(browser))
    }

    private suspend fun Team.ask(client: HttpClient, vararg extra: Pair<String, String>): JsonObject =
        tool(client, leadToken, "request_secret_access", args("name" to "TURNSTILE_SECRET", "reason" to "the signup form needs it", "channel" to "web", *extra))

    private fun Team.cards(): Int = app.db.one("SELECT count(*) FROM approvals WHERE team_id = :team") { it.getInt(1) } ?: 0

    @Test
    fun `asking makes a card in the thread with the secret's note and who saved it, and no value`() = withTeam { client ->
        setUp(client)
        json(client, ownerKey, "POST", "/api/messages", """{"project":"web","body":"Wire up the signup form"}""", status = 201)
        val messageId = app.db.one("SELECT id FROM messages WHERE team_id = :team ORDER BY id DESC LIMIT 1") { it.getString(1) }!!

        val asked = ask(client, "message_id" to messageId)
        assertNull(asked.error(), asked.toString())
        assertEquals("pending", asked.data().s("state"))
        val requestId = asked.data().s("request_id")

        val card = json(client, ownerKey, "GET", "/api/snapshot/requests").let { snap ->
            (snap["requests"] ?: snap["pending"])!!.jsonArray.map { it.jsonObject }.single { it.s("id") == requestId }
        }
        assertEquals("confirm", card.s("kind"))
        assertEquals(messageId, card.s("messageId"))
        assertEquals("lead asks to use secret TURNSTILE_SECRET: the signup form needs it", card.s("question"))
        val detail = card.s("detail")
        assertContains(detail, "Note: Cloudflare Turnstile, live site")
        assertContains(detail, "Saved by browser.")
        val access = card["config"]!!.jsonObject["secretAccess"]!!.jsonObject
        assertEquals("TURNSTILE_SECRET", access.s("name"))
        assertEquals("agent:${browser.id}", access.s("createdBy"))
        assertEquals("browser", access.s("createdByName"))

        // The card's words are the server's: the agent can't change which secret it seems to ask for.
        val edited = tool(client, leadToken, "edit_request", args("request_id" to requestId, "question" to "lead asks to use secret OTHER"))
        assertEquals("invalid_argument", edited.error())
    }

    @Test
    fun `Allow shares the secret, then secret_url works, the agent hears it and the audit says who`() = withTeam { client ->
        setUp(client)
        val before = tool(client, leadToken, "secret_url", args("name" to "TURNSTILE_SECRET"))
        assertEquals("not_found", before.error())
        assertContains(before.text(), "request_secret_access")

        val requestId = ask(client).data().s("request_id")
        val answered = json(client, ownerKey, "POST", "/api/requests/$requestId/answer", """{"value":true}""")
        assertEquals("answered", answered["request"]!!.jsonObject.s("state"))
        // The app trying again after a lost reply is fine, and shares nothing twice.
        json(client, ownerKey, "POST", "/api/requests/$requestId/answer", """{"value":true}""")

        val secret = app.secrets.list().single()
        assertEquals(listOf(browser.id, lead.id).sorted(), secret.agentIds.sorted())

        val heard = tool(client, leadToken, "await_request", args("request_id" to requestId))
        assertEquals("answered", heard.data().s("state"))
        assertEquals("true", heard.data()["response"]!!.jsonObject.s("value"))
        assertContains(heard.text(), "allowed you to use the secret \"TURNSTILE_SECRET\"")
        assertTrue(events.any { it.type == "request.answered" && it.payload["requestId"]?.jsonPrimitive?.content == requestId })

        val url = tool(client, leadToken, "secret_url", args("name" to "TURNSTILE_SECRET")).data().s("url")
        val got = client.get(url.removePrefix("https://feed.example.com"))
        assertEquals(200, got.status.value)
        assertContentEquals(value.toByteArray(), got.readRawBytes())

        val audit = app.secrets.audit(secret.id).first { it.action == "access_allowed" }
        assertEquals("owner", audit.actor.kind)
        assertEquals("Owner phone", audit.actor.name)
        assertContains(audit.detail!!, "lead")
        assertContains(audit.detail!!, requestId)
        assertTrue(app.secrets.audit(secret.id).any { it.action == "scope_changed" })
    }

    @Test
    fun `Deny shares nothing and the agent hears no`() = withTeam { client ->
        setUp(client)
        val requestId = ask(client).data().s("request_id")
        json(client, ownerKey, "POST", "/api/requests/$requestId/answer", """{"value":false}""")

        assertEquals(listOf(browser.id), app.secrets.list().single().agentIds)
        val heard = tool(client, leadToken, "await_request", args("request_id" to requestId))
        assertEquals("false", heard.data()["response"]!!.jsonObject.s("value"))
        assertContains(heard.text(), "denied")
        assertEquals("not_found", tool(client, leadToken, "secret_url", args("name" to "TURNSTILE_SECRET")).error())
        // Settled: a later Allow can't share it.
        json(client, ownerKey, "POST", "/api/requests/$requestId/answer", """{"value":true}""", status = 409)
        assertEquals(listOf(browser.id), app.secrets.list().single().agentIds)
    }

    @Test
    fun `only a person who may share secrets can allow, and an agent's answer never counts`() = withTeam { client ->
        setUp(client)
        val requestId = ask(client).data().s("request_id")

        // An agent's token is not a person's key.
        assertEquals(401, client.call(browserToken, "POST", "/api/requests/$requestId/answer", """{"value":true}""").status.value)
        assertEquals(401, client.call(leadToken, "POST", "/api/requests/$requestId/answer", """{"value":true}""").status.value)
        // A member can't share a secret in Settings, so can't here either.
        json(client, samKey, "POST", "/api/requests/$requestId/answer", """{"value":true}""", status = 403)
        // Nor through the secret route, which is for request_secret.
        assertTrue(client.call(samKey, "POST", "/api/requests/$requestId/secret", """{"value":"x"}""").status.value >= 400)

        assertEquals(listOf(browser.id), app.secrets.list().single().agentIds)
        assertEquals("pending", app.requests.find(requestId)!!.state)
        assertEquals("pending", tool(client, leadToken, "await_request", args("request_id" to requestId)).data().s("state"))
    }

    @Test
    fun `an unknown name or one the agent can already read makes no card`() = withTeam { client ->
        setUp(client)
        val unknown = tool(client, leadToken, "request_secret_access", args("name" to "NOBODY_SAVED_THIS", "reason" to "x", "channel" to "web"))
        assertEquals("not_found", unknown.error())
        assertContains(unknown.text(), "request_secret")

        val mine = tool(client, browserToken, "request_secret_access", args("name" to "TURNSTILE_SECRET", "reason" to "x", "channel" to "web"))
        assertNull(mine.error(), mine.toString())
        assertEquals("already_shared", mine.data().s("state"))
        assertContains(mine.text(), "secret_url")

        assertEquals(0, cards())
    }

    @Test
    fun `a channel the agent has no access to is not_found and makes no card`() = withTeam { client ->
        setUp(client)
        json(client, ownerKey, "POST", "/api/projects", """{"name":"Ops","slug":"ops"}""", status = 201)
        json(client, ownerKey, "PUT", "/api/projects/ops/access/agent/${lead.id}", """{"level":"none"}""")
        val refused = tool(client, leadToken, "request_secret_access", args("name" to "TURNSTILE_SECRET", "reason" to "x", "channel" to "ops"))
        assertEquals("not_found", refused.error())
        assertEquals(0, cards())
    }
}
