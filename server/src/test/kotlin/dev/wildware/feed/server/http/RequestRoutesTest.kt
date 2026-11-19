package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestRoutesTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://localhost:5173", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://localhost:5173",
    )

    private fun withApp(block: suspend ApplicationTestBuilder.(App, String) -> Unit) {
        val app = App(config, Database.memory())
        app.db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'web', 'Web', 1, 1)")
        val (_, key) = app.devices.mint("Phone")
        testApplication {
            application { feed(app) }
            block(app, key)
        }
        app.close()
    }

    private suspend fun ApplicationTestBuilder.send(key: String, method: String, path: String, body: String? = null): HttpResponse {
        val c = client
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header("Authorization", "Bearer $key")
            if (body != null) {
                header("Content-Type", "application/json")
                setBody(body)
            }
        }
        return when (method) {
            "POST" -> c.post(path, build)
            "PATCH" -> c.patch(path, build)
            else -> c.delete(path, build)
        }
    }

    private suspend fun tool(app: App, agent: dev.wildware.feed.server.domain.Agents.Agent, name: String, args: JsonObject): JsonObject =
        app.tools.call(app, agent, buildJsonObject { put("name", name); put("arguments", args) }).json

    @Test
    fun `the agent asks over MCP, the owner answers over HTTP, the agent hears it`() = withApp { app, key ->
        val (agent, _) = app.agents.mint("scout")
        val pending = tool(app, agent, "request_input", buildJsonObject { put("kind", "confirm"); put("question", "Ship it?"); put("project", "web") })
        val structured = pending["structuredContent"]!!.jsonObject
        assertEquals("pending", structured["state"]!!.jsonPrimitive.content)
        assertEquals(listOf("state", "request_id", "request", "poll_after_ms"), structured.keys.toList())
        assertEquals(
            listOf("id", "kind", "question", "detail", "options", "project_id", "update_id", "message_id", "expires_at"),
            structured["request"]!!.jsonObject.keys.toList(),
        )
        val id = structured["request_id"]!!.jsonPrimitive.content
        val text = (pending["content"] as kotlinx.serialization.json.JsonArray)[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.startsWith("Nobody has answered yet. This is not a refusal — call await_request({request_id: \"$id\"})"))

        val missing = send(key, "POST", "/api/requests/$id/answer", "{}")
        assertEquals(400, missing.status.value)
        assertEquals("""{"error":"invalid_argument","message":"an answer must carry a value"}""", missing.bodyAsText())
        assertEquals("application/json; charset=utf-8", missing.headers["Content-Type"])

        val ok = send(key, "POST", "/api/requests/$id/answer", """{"value":true}""")
        assertEquals(200, ok.status.value)
        assertTrue(ok.bodyAsText().startsWith("""{"request":{"id":"$id","seq":"""))

        val awaited = tool(app, agent, "await_request", buildJsonObject { put("request_id", id) })
        val answered = awaited["structuredContent"]!!.jsonObject
        assertEquals("""{"kind":"confirm","value":true}""", Wire.encode(answered["response"]!!))
        assertTrue((awaited["content"] as kotlinx.serialization.json.JsonArray)[0].jsonObject["text"]!!.jsonPrimitive.content.startsWith("Your owner answered: yes.\n{\n  \"state\": \"answered\""))

        val again = send(key, "DELETE", "/api/requests/$id")
        assertEquals(409, again.status.value)
        assertEquals("""{"error":"conflict","message":"request $id has already been answered"}""", again.bodyAsText())

        val edit = tool(app, agent, "edit_request", buildJsonObject { put("request_id", id); put("question", "x") })
        assertEquals("conflict: request $id has already been answered", (edit["content"] as kotlinx.serialization.json.JsonArray)[0].jsonObject["text"]!!.jsonPrimitive.content)

        val bell = app.reads.notifications(unseenOnly = false, limit = 5)
        assertEquals("/projects/web?focus=$id", bell.single()["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `withdraw and edit speak the TS summaries`() = withApp { app, _ ->
        val (agent, _) = app.agents.mint("scout")
        val id = app.requests.create(agent.id, dev.wildware.feed.server.domain.NewRequest(kind = "text", question = "Name?")).first.id
        val edited = tool(app, agent, "edit_request", buildJsonObject { put("request_id", id); put("question", " Name it? ") })
        assertTrue((edited["content"] as kotlinx.serialization.json.JsonArray)[0].jsonObject["text"]!!.jsonPrimitive.content.startsWith("Edited \"Name it?\". Your owner sees the new question.\n"))
        assertTrue("edited_at" in edited["structuredContent"]!!.jsonObject["request"]!!.jsonObject)
        val withdrawn = tool(app, agent, "withdraw_request", buildJsonObject { put("request_id", id) })
        assertEquals("""{"request_id":"$id","state":"withdrawn"}""", Wire.encode(withdrawn["structuredContent"]!!))
        val awaited = tool(app, agent, "await_request", buildJsonObject { put("request_id", id) })["structuredContent"]!!.jsonObject
        assertEquals("withdrawn", awaited["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clearing the bell refuses an ids that is not a list (decided fix 9)`() = withApp { _, key ->
        val bad = send(key, "POST", "/api/notifications/seen", """{"ids":"x"}""")
        assertEquals(400, bad.status.value)
        assertEquals(
            """{"error":"invalid_argument","message":"ids must be a list of notification ids, or left out to clear them all"}""",
            bad.bodyAsText(),
        )
        assertEquals(400, send(key, "POST", "/api/notifications/seen", """{"ids":null}""").status.value)
        assertEquals("""{"changed":0,"unseen":0}""", send(key, "POST", "/api/notifications/seen", "nonsense").bodyAsText())
        assertEquals("""{"changed":0,"unseen":0}""", send(key, "POST", "/api/notifications/seen").bodyAsText())
    }

    @Test
    fun `a card read on one device clears its alerts and the bell on all of them`() = withApp { app, key ->
        app.db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'a', 'h', 1)")
        app.db.update("INSERT INTO updates (id, project_id, agent_id, body, created_at) VALUES ('U1', 'P1', 'A1', 'b', 1)")
        app.bus.publish("update.created", buildJsonObject { put("updateId", "U1") })
        val cleared = mutableListOf<String>()
        app.bus.subscribe { if (it.type == "notifications.cleared") cleared += Wire.encode(it.payload) }

        assertEquals("""{"handled":[]}""", send(key, "POST", "/api/notifications/handled", """{"tags":["update-U1","update-check"]}""").bodyAsText())
        assertEquals("""{"changed":1,"unseen":0}""", send(key, "POST", "/api/notifications/cards-seen", """{"cards":["U1"]}""").bodyAsText())
        assertEquals(listOf("""{"tags":["update-U1"]}"""), cleared)
        assertEquals("""{"handled":["update-U1"]}""", send(key, "POST", "/api/notifications/handled", """{"tags":["update-U1","update-check"]}""").bodyAsText())

        // Never "everything", as a missing ids is on /seen.
        assertEquals(400, send(key, "POST", "/api/notifications/cards-seen", "{}").status.value)
        assertEquals(400, send(key, "POST", "/api/notifications/handled", """{"tags":"update-U1"}""").status.value)
        assertEquals(400, send(key, "POST", "/api/notifications/handled", """{"tags":[1]}""").status.value)
        val tooMany = (0..500).joinToString(",") { "\"message-$it\"" }
        assertEquals(400, send(key, "POST", "/api/notifications/handled", """{"tags":[$tooMany]}""").status.value)
    }

    @Test
    fun `an app view keeps the owner's state`() = withApp { app, key ->
        app.db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'a', 'h', 1)")
        app.db.update("INSERT INTO updates (id, project_id, agent_id, body, created_at) VALUES ('U1', 'P1', 'A1', 'b', 1)")
        app.db.update("INSERT INTO apps (id, uri, name, agent_id, html, csp, created_at, updated_at) VALUES ('AP', 'ui://b', 'B', 'A1', '<p>', '{}', 1, 1)")
        app.db.update("INSERT INTO app_views (id, app_id, update_id, agent_id, author, data, created_at, updated_at) VALUES ('V1', 'AP', 'U1', 'A1', 'agent:A1', '{}', 1, 1)")
        assertEquals("""{"view_id":"V1","state":{"tab":2}}""", send(key, "POST", "/api/apps/V1/state", """{"state":{"tab":2}}""").bodyAsText())
        assertEquals("""{"view_id":"V1","state":null}""", send(key, "POST", "/api/apps/V1/state", """{"state":null}""").bodyAsText())
        assertEquals(
            """{"error":"invalid_argument","message":"state must be an object, or null to forget it"}""",
            send(key, "POST", "/api/apps/V1/state", "{}").bodyAsText(),
        )
        assertEquals("""{"error":"not_found","message":"no app view \"nope\""}""", send(key, "POST", "/api/apps/nope/state", """{"state":{}}""").bodyAsText())
        app.db.update("UPDATE updates SET deleted_at = 5 WHERE id = 'U1'")
        assertEquals("""{"error":"not_found","message":"no such update: U1"}""", send(key, "POST", "/api/apps/V1/state", """{"state":{}}""").bodyAsText())
    }
}
