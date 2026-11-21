package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.feed
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A voice note (migration 065) is marked as one in data, not in its words: people read exactly what
 * was said, with `source: "voice"`; agents read the words with a line saying they were transcribed.
 */
class VoiceNotesTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    private val said = "Move the deploy to Friday and tell the others"

    private fun postgres(): Database {
        val url = System.getenv("PG_TEST_URL")
        org.junit.jupiter.api.Assumptions.assumeTrue(!url.isNullOrBlank(), "PG_TEST_URL not set")
        val schema = "t_" + java.util.UUID.randomUUID().toString().replace("-", "")
        Database.postgresConnection(url!!, null).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA \"$schema\"") } }
        return Database.openPostgres(url, schema = schema, onClose = {
            Database.postgresConnection(url, null).use { c -> c.createStatement().use { it.execute("DROP SCHEMA \"$schema\" CASCADE") } }
        })
    }

    private fun tool(app: App, agent: Agents.Agent, name: String, vararg args: Pair<String, String>): JsonObject = runBlocking {
        val arguments = buildJsonObject { for ((k, v) in args) put(k, JsonPrimitive(v)) }
        app.tools.call(app, agent, buildJsonObject { put("name", JsonPrimitive(name)); put("arguments", arguments) }).json["structuredContent"]!!.jsonObject
    }

    private fun withApp(db: Database, block: suspend ApplicationTestBuilder.(App, String) -> Unit) {
        val app = App(config, db)
        app.feed.projects.create("Web", null, null)
        val (_, key) = app.devices.mint("Phone")
        try {
            testApplication {
                application { feed(app) }
                block(app, key)
            }
        } finally {
            app.close()
        }
    }

    private suspend fun ApplicationTestBuilder.post(key: String, body: String): HttpResponse = client.post("/api/messages") {
        header("Authorization", "Bearer $key")
        header("Content-Type", "application/json")
        setBody(body)
    }

    private fun message(response: String): JsonObject = Json.parseToJsonElement(response).jsonObject["message"]!!.jsonObject

    @Test
    fun `on SQLite a voice note is stored as said and read with a hint only by agents`() = withApp(Database.memory()) { app, key -> voiceNote(app, key) }

    @Test
    fun `on Postgres a voice note is stored as said and read with a hint only by agents`() = withApp(postgres()) { app, key -> voiceNote(app, key) }

    private suspend fun ApplicationTestBuilder.voiceNote(app: App, key: String) {
        val (alpha, _) = app.agents.mint("alpha")
        tool(app, alpha, "subscribe", "channel" to "web")

        val sent = post(key, """{"project":"web","body":"$said","source":"voice"}""")
        assertEquals(201, sent.status.value)
        val stored = message(sent.bodyAsText())
        assertEquals(said, stored["body"]!!.jsonPrimitive.content, "the words stay exactly as said")
        assertEquals("voice", stored["source"]!!.jsonPrimitive.content)
        val id = stored["id"]!!.jsonPrimitive.content
        val typed = message(post(key, """{"project":"web","body":"typed words"}""").bodyAsText())
        assertNull(typed["source"], "a typed message has no source")

        // People's reads: the words as said, the source to draw a mic from, and no hint.
        val read = client.get("/api/messages?project=web") { header("Authorization", "Bearer $key") }.bodyAsText()
        val listed = Json.parseToJsonElement(read).jsonObject["messages"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == id }
        assertEquals(said, listed["body"]!!.jsonPrimitive.content)
        assertEquals("voice", listed["source"]!!.jsonPrimitive.content)
        assertTrue(Messages.VOICE_HINT !in read)

        // The agent stream's message frame: the words, then the hint, which the bridge's notice shows.
        val frame = messageItem(app, app.work.find(id)!!, alpha.id)
        assertEquals("$said\n\n${Messages.VOICE_HINT}", frame["body"]!!.jsonPrimitive.content)
        assertEquals("voice", frame["source"]!!.jsonPrimitive.content)
        assertEquals("typed words", messageItem(app, app.work.find(typed["id"]!!.jsonPrimitive.content)!!, alpha.id)["body"]!!.jsonPrimitive.content)

        // get_messages: the same.
        val got = tool(app, alpha, "get_messages")["messages"]!!.jsonArray.map { it.jsonObject }
        val voice = got.single { it["id"]!!.jsonPrimitive.content == id }
        assertEquals("$said\n\n${Messages.VOICE_HINT}", voice["body"]!!.jsonPrimitive.content)
        assertEquals("voice", voice["source"]!!.jsonPrimitive.content)
        val plain = got.single { it["id"]!!.jsonPrimitive.content == typed["id"]!!.jsonPrimitive.content }
        assertEquals("typed words", plain["body"]!!.jsonPrimitive.content)
        assertNull(plain["source"])

        // A reply quoting it quotes the words only.
        val reply = tool(app, alpha, "post_message", "message_id" to id, "answers" to id, "body" to "Will do")["message"]!!.jsonObject
        assertEquals(said, app.feed.messages.quoted(app.feed.messages.find(reply["id"]!!.jsonPrimitive.content)!!)!!.body)

        // An edit keeps it a voice note and never takes on the hint.
        val edited = app.feed.messages.edit(id, Messages.HUMAN, "Move the deploy to Monday", null)
        assertEquals("Move the deploy to Monday", edited.body)
        assertEquals("voice", edited.source)
    }

    @Test
    fun `a source other than voice is refused`() = withApp(Database.memory()) { app, key ->
        val refused = post(key, """{"project":"web","body":"hello","source":"telepathy"}""")
        assertEquals(400, refused.status.value)
        assertTrue("source" in refused.bodyAsText(), refused.bodyAsText())
        assertEquals(0, app.db.one("SELECT count(*) FROM messages") { it.getLong(1) })
        assertEquals(400, post(key, """{"project":"web","body":"hello","source":7}""").status.value)
    }
}
