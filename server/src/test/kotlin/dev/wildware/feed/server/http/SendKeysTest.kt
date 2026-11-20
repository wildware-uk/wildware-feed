package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.feed
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** A send tried again after its answer was lost is stored once (migration 061). */
class SendKeysTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    /** The server's clock: tests move it on to step past the repeat window. */
    private var now = 1_000_000_000L

    private fun withApp(block: suspend ApplicationTestBuilder.(App, String) -> Unit) {
        val app = App(config, Database.memory(), clock = { now })
        app.db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'web', 'Web', 1, 1)")
        val (_, key) = app.devices.mint("Phone")
        testApplication {
            application { feed(app) }
            block(app, key)
        }
        app.close()
    }

    private suspend fun ApplicationTestBuilder.post(key: String, path: String, body: String): HttpResponse = client.post(path) {
        header("Authorization", "Bearer $key")
        header("Content-Type", "application/json")
        setBody(body)
    }

    private fun idOf(response: String): String = Json.parseToJsonElement(response).jsonObject["message"]!!.jsonObject["id"]!!.jsonPrimitive.content

    private fun messages(app: App): Long = app.db.one("SELECT count(*) FROM messages") { it.getLong(1) }!!

    @Test
    fun `two posts with the same send key make one message and both get it back`() = withApp { app, key ->
        val body = """{"project":"web","body":"Get it working on every session","sendKey":"k-1"}"""
        val first = post(key, "/api/messages", body)
        val second = post(key, "/api/messages", body)
        assertEquals(201, first.status.value)
        assertEquals(201, second.status.value)
        assertEquals(idOf(first.bodyAsText()), idOf(second.bodyAsText()))
        assertEquals(1, messages(app))
    }

    @Test
    fun `a reply tried again lands once in its thread`() = withApp { app, key ->
        val post = idOf(post(key, "/api/messages", """{"project":"web","body":"Ship it?"}""").bodyAsText())
        val reply = """{"replyTo":"$post","body":"Yes","sendKey":"k-2"}"""
        val a = idOf(post(key, "/api/messages", reply).bodyAsText())
        val b = idOf(post(key, "/api/messages", reply).bodyAsText())
        assertEquals(a, b)
        assertEquals(1, app.db.one("SELECT count(*) FROM messages WHERE reply_to = ?", post) { it.getLong(1) })
    }

    @Test
    fun `different send keys are different messages, even with the same words`() = withApp { app, key ->
        val one = idOf(post(key, "/api/messages", """{"project":"web","body":"Same words","sendKey":"a"}""").bodyAsText())
        val two = idOf(post(key, "/api/messages", """{"project":"web","body":"Same words","sendKey":"b"}""").bodyAsText())
        assertNotEquals(one, two)
        assertEquals(2, messages(app))
    }

    @Test
    fun `an app with no send keys repeating itself in a thread is stored once, until the window passes`() = withApp { app, key ->
        val plain = """{"project":"web","body":"Same words"}"""
        val first = idOf(post(key, "/api/messages", plain).bodyAsText())
        now += 11 * 60 * 1000L
        assertEquals(first, idOf(post(key, "/api/messages", plain).bodyAsText()), "eleven minutes on, as seen live: still the same send")
        assertNotEquals(first, idOf(post(key, "/api/messages", """{"project":"web","body":"Other words"}""").bodyAsText()))
        val post = first
        assertNotEquals(first, idOf(post(key, "/api/messages", """{"replyTo":"$post","body":"Same words"}""").bodyAsText()), "another thread")
        now += Messages.REPEAT_WINDOW_MS
        assertNotEquals(first, idOf(post(key, "/api/messages", plain).bodyAsText()), "long after, it is said again on purpose")
        assertEquals(4, messages(app))
    }

    @Test
    fun `a send stuck in an old queue stops copying once the app sends keys`() = withApp { app, key ->
        val old = idOf(post(key, "/api/messages", """{"project":"web","body":"Stuck"}""").bodyAsText())
        val keyed = """{"project":"web","body":"Stuck","sendKey":"k-9"}"""
        assertEquals(old, idOf(post(key, "/api/messages", keyed).bodyAsText()))
        now += Messages.REPEAT_WINDOW_MS * 4
        assertEquals(old, idOf(post(key, "/api/messages", keyed).bodyAsText()), "the key now names the old copy, window or not")
        assertEquals(1, messages(app))
    }

    @Test
    fun `the domain alone never guesses at repeats`() = withApp { app, _ ->
        val a = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "Hi", project = "web"))
        val b = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "Hi", project = "web"))
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun `a retry after the message was edited gets the edited message, not a copy`() = withApp { app, key ->
        val body = """{"project":"web","body":"Frist","sendKey":"k-3"}"""
        val id = idOf(post(key, "/api/messages", body).bodyAsText())
        app.feed.messages.edit(id, Messages.HUMAN, "First")
        val again = Json.parseToJsonElement(post(key, "/api/messages", body).bodyAsText()).jsonObject["message"]!!.jsonObject
        assertEquals(id, again["id"]!!.jsonPrimitive.content)
        assertEquals("First", again["body"]!!.jsonPrimitive.content)
        assertEquals(1, messages(app))
    }

    @Test
    fun `the same key from another person is their own message`() = withApp { app, _ ->
        val (sam, _) = app.people.addOwner("sam@example.com", "Sam")
        val mine = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "Hi", project = "web", sendKey = "k-4"))
        val again = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "Hi", project = "web", sendKey = "k-4"))
        val theirs = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "Hi", project = "web", sendKey = "k-4", userId = sam.id))
        assertEquals(mine.id, again.id)
        assertNotEquals(mine.id, theirs.id)
        assertEquals(2, messages(app))
    }

    @Test
    fun `a send key must be short printable text`() = withApp { _, key ->
        for (bad in listOf("\"\"", "\"${"x".repeat(201)}\"", "\"tab\\there\"", "7")) {
            val refused = post(key, "/api/messages", """{"project":"web","body":"Hi","sendKey":$bad}""")
            assertEquals(400, refused.status.value, bad)
        }
    }

    @Test
    fun `the same answer sent again succeeds, a different one is still refused`() = withApp { app, key ->
        val (agent, _) = app.agents.mint("scout")
        val asked = app.tools.call(
            app, agent,
            buildJsonObject {
                put("name", "request_input")
                put("arguments", buildJsonObject { put("kind", "confirm"); put("question", "Ship it?"); put("project", "web") })
            },
        ).json["structuredContent"] as JsonObject
        val id = asked["request_id"]!!.jsonPrimitive.content
        assertEquals(200, post(key, "/api/requests/$id/answer", """{"value":true}""").status.value)
        val retried = post(key, "/api/requests/$id/answer", """{"value":true}""")
        assertEquals(200, retried.status.value, retried.bodyAsText())
        assertEquals(409, post(key, "/api/requests/$id/answer", """{"value":false}""").status.value)
    }
}
