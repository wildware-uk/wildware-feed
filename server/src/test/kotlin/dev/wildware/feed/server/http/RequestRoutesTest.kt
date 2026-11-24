package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
            "GET" -> c.get(path, build)
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
    fun `a form with fields goes from MCP to the app and back through await_request`() = withApp { app, key ->
        val (agent, _) = app.agents.mint("scout")
        fun text(result: JsonObject) = (result["content"] as kotlinx.serialization.json.JsonArray)[0].jsonObject["text"]!!.jsonPrimitive.content
        val fields = kotlinx.serialization.json.Json.parseToJsonElement(
            """[{"id":"name","label":"Name","type":"text","required":true},
                {"id":"plan","label":"Plan","type":"select","options":["Free","Team"],"default":"Free"},
                {"id":"seats","label":"Seats","type":"number","min":1,"max":9},
                {"id":"notify","label":"Notify me","type":"toggle"}]""",
        )
        val args = { f: kotlinx.serialization.json.JsonElement ->
            buildJsonObject { put("kind", "form"); put("question", "Set it up?"); put("project", "web"); put("fields", f) }
        }
        // A key the field doesn't take refuses the call, with what it does take.
        val wrong = tool(app, agent, "request_input", args(kotlinx.serialization.json.Json.parseToJsonElement("""[{"id":"a","label":"A","type":"text","colour":"red"}]""")))
        assertEquals("invalid_argument: fields[0]: unknown key \"colour\" (a field takes id, label, type, hint, required, default, options, min, max, placeholder)", text(wrong))

        val pending = tool(app, agent, "request_input", args(fields))["structuredContent"]!!.jsonObject
        val id = pending["request_id"]!!.jsonPrimitive.content
        assertEquals("""["Submit"]""", Wire.encode(pending["request"]!!.jsonObject["options"]!!))
        // The app reads the fields from the question's config.
        val waiting = send(key, "GET", "/api/snapshot/requests").bodyAsText()
        assertTrue(""""fields":[{"id":"name","label":"Name","type":"text","required":true}""" in waiting)
        assertTrue("\"answered\"" !in waiting)

        // An older app's one box is refused, and the question still waits.
        val old = send(key, "POST", "/api/requests/$id/answer", """{"value":{"action":"Submit","text":"hi"}}""")
        assertEquals(400, old.status.value)
        assertEquals("""{"error":"invalid_argument","message":"This form has 4 fields. Update the app, or answer it on the web, to fill them in."}""", old.bodyAsText())
        val short = send(key, "POST", "/api/requests/$id/answer", """{"value":{"action":"Submit","values":{"plan":"Free"}}}""")
        assertEquals("""{"error":"invalid_argument","message":"\"Name\" is required"}""", short.bodyAsText())

        val ok = send(key, "POST", "/api/requests/$id/answer", """{"value":{"action":"Submit","values":{"name":"Sam","plan":"Team","seats":3,"notify":true}}}""")
        assertEquals(200, ok.status.value)
        val awaited = tool(app, agent, "await_request", buildJsonObject { put("request_id", id) })
        assertEquals(
            """{"kind":"form","value":{"action":"Submit","values":{"name":"Sam","plan":"Team","seats":3,"notify":true}}}""",
            Wire.encode(awaited["structuredContent"]!!.jsonObject["response"]!!),
        )
        assertTrue(text(awaited).startsWith("Your owner answered: \"Submit\" with values for name, plan, seats, notify."))
        // Answered, it stays in a team of one's feed, so the card can show what was sent.
        val after = kotlinx.serialization.json.Json.parseToJsonElement(send(key, "GET", "/api/snapshot/requests").bodyAsText()).jsonObject
        assertEquals(listOf(id), (after["answered"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject["id"]!!.jsonPrimitive.content })
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
        // The page itself is sandboxed however it is opened: its script runs with no origin, so it has
        // no cookie, no storage and no reach into the dashboard, even opened in a tab of its own.
        val page = send(key, "GET", "/apps/V1")
        assertEquals(200, page.status.value)
        // Never as a page of its own, where it could pass itself off as the dashboard.
        assertEquals(403, client.get("/apps/V1") { header("Authorization", "Bearer $key"); header("Sec-Fetch-Dest", "document") }.status.value)
        assertEquals(200, client.get("/apps/V1") { header("Authorization", "Bearer $key"); header("Sec-Fetch-Dest", "iframe") }.status.value)
        val csp = page.headers["Content-Security-Policy"]!!
        assertTrue(csp.endsWith("; sandbox allow-scripts"), csp)
        assertTrue("allow-same-origin" !in csp && "allow-top-navigation" !in csp && "allow-forms" !in csp && "allow-popups" !in csp, csp)
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
