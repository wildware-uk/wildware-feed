package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.People
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A second person in the team, end to end (docs/TEAMS.md slice 1): joining by invite, signing in
 * by email, who wrote and answered what, and each person's own bell and follows. A team of one
 * stays exactly as it was.
 */
class TeamTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
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

    private suspend fun ApplicationTestBuilder.send(key: String?, method: String, path: String, body: String? = null): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            if (key != null) header("Authorization", "Bearer $key")
            if (body != null) {
                header("Content-Type", "application/json")
                setBody(body)
            }
        }
        return when (method) {
            "GET" -> client.get(path, build)
            "PUT" -> client.put(path, build)
            else -> client.post(path, build)
        }
    }

    private suspend fun HttpResponse.obj(): JsonObject = Wire.json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content

    private suspend fun tool(app: App, agent: dev.wildware.feed.server.domain.Agents.Agent, name: String, args: JsonObject): JsonObject =
        app.tools.call(app, agent, buildJsonObject { put("name", name); put("arguments", args) }).json

    /** Sam joins as a member through the HTTP routes and gets a key. */
    private suspend fun ApplicationTestBuilder.samJoins(owner: String): Pair<String, String> {
        val invite = send(owner, "POST", "/api/invites", """{"role":"member","email":"sam@example.com"}""").obj()
        val link = invite.s("link")
        assertTrue(link.startsWith("http://localhost:5173/join/"))
        val peek = send(null, "POST", "/api/invite/peek", """{"token":"$link"}""")
        assertEquals(200, peek.status.value)
        assertEquals("member", peek.obj()["invite"]!!.jsonObject.s("role"))
        val accepted = send(null, "POST", "/api/invite/accept", """{"token":"$link","name":"Sam","email":"sam@example.com","password":"a long password","deviceName":"Sam's phone"}""")
        assertEquals(201, accepted.status.value, accepted.bodyAsText())
        val body = accepted.obj()
        return body["person"]!!.jsonObject.s("id") to body.s("token")
    }

    @Test
    fun `joining by link, then signing in with email and password`() = withApp { _, owner ->
        assertEquals("""{"product":"wildware-feed","version":"${ServerInfo.version}","signIn":["password"],"team":false}""", send(null, "GET", "/api/server").bodyAsText())
        val (samId, samKey) = samJoins(owner)
        assertEquals("""{"product":"wildware-feed","version":"${ServerInfo.version}","signIn":["password","email-password"],"team":true}""", send(null, "GET", "/api/server").bodyAsText())
        assertEquals(samId, send(samKey, "GET", "/api/me").obj()["person"]!!.jsonObject.s("id"))
        val signIn = send(null, "POST", "/api/devices/sign-in", """{"email":"sam@example.com","password":"a long password","name":"Laptop"}""")
        assertEquals(201, signIn.status.value)
        val wrong = send(null, "POST", "/api/devices/sign-in", """{"email":"sam@example.com","password":"nope nope","name":"Laptop"}""")
        assertEquals("""{"error":"wrong_password","message":"That email and password don't match."}""", wrong.bodyAsText())
        // The old way in, with no email, is still the founder's.
        val legacy = send(null, "POST", "/api/devices/sign-in", """{"password":"owner password","name":"Old app"}""").obj()
        assertEquals("founder", send(legacy.s("token"), "GET", "/api/me").obj()["person"]!!.jsonObject.s("id"))
    }

    @Test
    fun `the join page shows the code and opens the app, and nothing else`() = withApp { _, owner ->
        val token = send(owner, "POST", "/api/invites", "{}").obj().s("token")
        val page = send(null, "GET", "/join/$token")
        assertEquals(200, page.status.value)
        assertTrue(page.bodyAsText().contains(token))
        assertTrue(page.bodyAsText().contains("agents://invite?server=http%3A%2F%2Flocalhost%3A5173&amp;token=$token"))
        assertEquals("no-referrer", page.headers["Referrer-Policy"])
        assertTrue(page.headers["Content-Security-Policy"]!!.startsWith("default-src 'none'"))
        assertEquals(404, send(null, "GET", "/join/<script>").status.value)
    }

    @Test
    fun `a team of one reads as before, and a team says who wrote what`() = withApp { app, owner ->
        send(owner, "POST", "/api/messages", """{"body":"from the owner","project":"web"}""")
        val alone = send(owner, "GET", "/api/snapshot").bodyAsText()
        assertFalse(alone.contains("\"people\"") || alone.contains("userId"), alone)

        val (samId, samKey) = samJoins(owner)
        send(samKey, "POST", "/api/messages", """{"body":"from sam","project":"web"}""")
        val snapshot = send(owner, "GET", "/api/snapshot").obj()
        val people = snapshot["people"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("founder", samId), people.map { it.s("id") })
        assertNull(people[1]["email"], "a snapshot names people, it doesn't hand out their emails")
        val messages = snapshot["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(samId, messages.single { it.s("body") == "from sam" }.s("userId"))
        assertNull(messages.single { it.s("body") == "from the owner" }["userId"], "the founder's rows say nothing extra")

        // Agents now see which person, as extra keys only.
        val (agent, _) = app.agents.mint("scout")
        val read = tool(app, agent, "get_messages", buildJsonObject {})["structuredContent"]!!.jsonObject["messages"]!!.jsonArray.map { it.jsonObject }
        val fromSam = read.single { it.s("body") == "from sam" }
        assertEquals("human", fromSam.s("author"))
        assertEquals("Sam", fromSam.s("author_name"))
        assertEquals(samId, fromSam.s("author_user_id"))
        assertEquals("Owner", read.single { it.s("body") == "from the owner" }.s("author_name"))
    }

    @Test
    fun `agents see nothing new on a team of one`() = withApp { app, owner ->
        send(owner, "POST", "/api/messages", """{"body":"hello","project":"web"}""")
        val (agent, _) = app.agents.mint("scout")
        val message = tool(app, agent, "get_messages", buildJsonObject {})["structuredContent"]!!.jsonObject["messages"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("id", "project_id", "update_id", "task_id", "reply_to", "author", "body", "created_at"), message.keys.toList())
    }

    @Test
    fun `whoever answers first answers for the team, and the agent learns who`() = withApp { app, owner ->
        val (samId, samKey) = samJoins(owner)
        val (agent, _) = app.agents.mint("scout")
        val asked = tool(app, agent, "request_input", buildJsonObject { put("kind", "confirm"); put("question", "Ship it?"); put("project", "web") })
        val id = asked["structuredContent"]!!.jsonObject.s("request_id")
        assertEquals(200, send(samKey, "POST", "/api/requests/$id/answer", """{"value":true}""").status.value)
        val second = send(owner, "POST", "/api/requests/$id/answer", """{"value":false}""")
        assertEquals(409, second.status.value)
        assertTrue(second.bodyAsText().contains("Sam already answered this"), second.bodyAsText())
        val result = tool(app, agent, "await_request", buildJsonObject { put("request_id", id) })
        val text = (result["content"] as JsonArray)[0].jsonObject.s("text")
        assertTrue(text.startsWith("Sam answered: "), text)
        val by = result["structuredContent"]!!.jsonObject["answered_by"]!!.jsonObject
        assertEquals(samId, by.s("user_id"))
        assertEquals("Sam", by.s("name"))
        assertEquals(samId, app.requests.find(id)!!.json["answeredBy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `each person has their own bell and their own follows`() = withApp { app, owner ->
        val (samId, samKey) = samJoins(owner)
        val (agent, _) = app.agents.mint("scout")
        suspend fun bell(key: String) = send(key, "GET", "/api/notifications").obj()["notifications"]!!.jsonArray.map { it.jsonObject.s("title") }

        tool(app, agent, "post_update", buildJsonObject { put("channel", "web"); put("body", "deployed"); put("title", "Deployed") })
        assertEquals(listOf("Deployed"), bell(owner))
        assertEquals(listOf("Deployed"), bell(samKey))

        // Sam mutes the channel: the owner still hears it, Sam doesn't.
        assertEquals(200, send(samKey, "PUT", "/api/projects/web/subscription", """{"level":"muted"}""").status.value)
        tool(app, agent, "post_update", buildJsonObject { put("channel", "web"); put("body", "again"); put("title", "Again") })
        assertEquals(listOf("Again", "Deployed"), bell(owner))
        assertEquals(listOf("Deployed"), bell(samKey))

        // Marking seen is one's own.
        send(samKey, "POST", "/api/notifications/seen", "{}")
        assertEquals(0L, app.notifications.unseen(samId))
        assertEquals(2L, app.notifications.unseen(People.FOUNDER))

        // A teammate's words reach the others through what they follow, never the writer.
        send(owner, "POST", "/api/messages", """{"body":"looks good","project":"web"}""")
        assertFalse(bell(owner).any { it.contains("commented") }, "nobody is told about their own message")
        assertFalse(bell(samKey).any { it.contains("commented") }, "Sam muted the channel")

        // An @mention reaches Sam anyway, and follows the thread for them.
        val posted = send(owner, "POST", "/api/messages", """{"body":"@sam can you look","project":"web"}""").obj()
        assertEquals("Owner commented", bell(samKey).first())
        val thread = posted["message"]!!.jsonObject.s("id")
        val follow = send(samKey, "GET", "/api/cards/message/$thread/followers").obj()
        assertEquals(true, follow["owner"]!!.jsonObject["following"]!!.jsonPrimitive.content.toBoolean())
    }
}
