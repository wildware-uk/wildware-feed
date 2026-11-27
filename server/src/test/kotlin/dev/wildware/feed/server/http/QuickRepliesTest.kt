package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.feed
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Quick replies (migration 081, docs/QUICK_REPLIES.md): buttons or a small form on a message, answered as a reply. */
class QuickRepliesTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    private class Ctx(val app: App, val base: String, val key: String, val alpha: Agents.Agent)

    private fun withServer(db: Database = Database.memory(), block: Ctx.() -> Unit) {
        val app = App(config, db)
        app.feed.projects.create("Web", null, null)
        val (_, key) = app.devices.mint("Phone")
        val (alpha, _) = app.agents.mint("alpha")
        val server = FeedServer(app).start()
        try {
            Ctx(app, "http://127.0.0.1:${server.port}", key, alpha).block()
        } finally {
            server.stop()
            app.close()
        }
    }

    private fun postgres(): Database {
        val url = System.getenv("PG_TEST_URL")
        org.junit.jupiter.api.Assumptions.assumeTrue(!url.isNullOrBlank(), "PG_TEST_URL not set")
        val schema = "t_" + java.util.UUID.randomUUID().toString().replace("-", "")
        Database.postgresConnection(url!!, null).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA \"$schema\"") } }
        return Database.openPostgres(url, schema = schema, onClose = {
            Database.postgresConnection(url, null).use { c -> c.createStatement().use { it.execute("DROP SCHEMA \"$schema\" CASCADE") } }
        })
    }

    private fun j(text: String): JsonElement = Wire.json.parseToJsonElement(text)

    private fun Ctx.tool(name: String, args: String): JsonObject = runBlocking {
        app.tools.call(app, alpha, buildJsonObject { put("name", name); put("arguments", j(args)) }).json
    }

    private fun Ctx.ok(name: String, args: String): JsonObject {
        val result = tool(name, args)
        assertTrue(result["isError"] == null, "$name failed: $result")
        return result["structuredContent"]!!.jsonObject
    }

    private fun Ctx.refused(name: String, args: String, message: String) {
        val result = tool(name, args)
        val data = result["structuredContent"]?.jsonObject ?: error("no structuredContent: $result")
        assertEquals("invalid_argument", data.s("error"), "$name: $data")
        assertEquals(message, data.s("message"))
    }

    private val http = HttpClient.newHttpClient()

    private fun Ctx.owner(method: String, path: String, body: String? = null): Pair<Int, JsonObject> {
        val request = HttpRequest.newBuilder(URI.create("$base$path")).header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to Wire.json.parseToJsonElement(response.body()).jsonObject
    }

    private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.o(key: String) = this[key]!!.jsonObject
    private fun JsonObject.a(key: String) = this[key]!!.jsonArray
    private fun JsonArray.strings() = map { (it as JsonPrimitive).content }

    private fun Ctx.count(sql: String) = app.db.one(sql) { it.getLong(1) }!!

    @Test
    fun `buttons on a reply are answered as the owner's reply in the same thread, once`() = withServer { buttons() }

    @Test
    fun `on Postgres buttons are answered once, by index`() = withServer(postgres()) { buttons() }

    private fun Ctx.buttons() {
        val updateId = ok("post_update", """{"channel":"web","body":"Sounds"}""").o("update").s("id")!!
        val requestsBefore = count("SELECT count(*) FROM approvals")
        // What a plain reply adds to the bell, to compare with one that has buttons.
        val notesStart = count("SELECT count(*) FROM notifications")
        ok("post_message", """{"update_id":"$updateId","body":"Plain"}""")
        val plainAdds = count("SELECT count(*) FROM notifications") - notesStart
        val notesBefore = count("SELECT count(*) FROM notifications")
        val posted = ok("post_message", """{"update_id":"$updateId","body":"Sounds are in 1.0.32","buttons":["👍 Ship it","Try another"]}""")
        val asked = posted.o("message")
        val askedId = asked.s("id")!!
        assertEquals(listOf("👍 Ship it", "Try another"), asked.o("replies").a("buttons").strings())
        assertEquals(0, asked.o("replies").a("answers").size)

        // Not urgent: no request, no "Waiting on you", nothing extra to alert about.
        assertEquals(requestsBefore, count("SELECT count(*) FROM approvals"))
        assertEquals(0, owner("GET", "/api/snapshot/requests").second.a("requests").size)
        assertEquals(0, owner("GET", "/api/summary").second.a("requests").size)
        assertEquals(plainAdds, count("SELECT count(*) FROM notifications") - notesBefore, "no more alerts than a plain reply")

        // The owner's app reads the buttons on the message.
        val page = owner("GET", "/api/messages?update=$updateId").second
        val shown = page.a("messages").map { it.jsonObject }.first { it.s("id") == askedId }
        assertEquals(listOf("👍 Ship it", "Try another"), shown.o("replies").a("buttons").strings())

        // A tap is the owner's reply, answering the message, in the same thread.
        val (status, tapped) = owner("POST", "/api/messages/$askedId/reply", """{"button":"👍 Ship it","sendKey":"tap-1"}""")
        assertEquals(201, status, tapped.toString())
        val reply = tapped.o("message")
        assertEquals("human", reply.s("author"))
        assertEquals("👍 Ship it", reply.s("body"))
        assertEquals(askedId, reply.s("answers"))
        assertEquals(updateId, reply.s("updateId"))
        assertEquals(askedId, reply.s("replyFor"))
        assertEquals("👍 Ship it", reply.o("replyValue").s("button"))
        val answers = tapped.o("answered").o("replies").a("answers")
        assertEquals(1, answers.size)
        assertEquals("👍 Ship it", answers[0].jsonObject.s("button"))
        assertEquals("founder", answers[0].jsonObject.s("userId"))
        assertEquals(reply.s("id"), answers[0].jsonObject.s("messageId"))
        assertEquals(requestsBefore, count("SELECT count(*) FROM approvals"), "still no request")

        // A double tap sends once; another answer by the same person is refused.
        val (again, same) = owner("POST", "/api/messages/$askedId/reply", """{"button":"👍 Ship it","sendKey":"tap-1"}""")
        assertEquals(201, again)
        assertEquals(reply.s("id"), same.o("message").s("id"))
        val (twice, refusal) = owner("POST", "/api/messages/$askedId/reply", """{"button":"Try another","sendKey":"tap-2"}""")
        assertEquals(409, twice, refusal.toString())
        assertEquals("you have already answered that message", refusal.s("message"))
        assertEquals(1L, count("SELECT count(*) FROM messages WHERE reply_for IS NOT NULL"))

        // The agent hears it as an ordinary message, with what was chosen on it.
        val read = ok("get_messages", "{}").a("messages").map { it.jsonObject }
        val heard = read.single { it.s("id") == reply.s("id") }
        assertEquals("👍 Ship it", heard.s("body"))
        assertEquals(askedId, heard.s("answers"))
        assertEquals(updateId, heard.s("update_id"))
        assertEquals(askedId, heard.o("reply").s("to"))
        assertEquals("👍 Ship it", heard.o("reply").s("button"))
        val frame = messageItem(app, app.work.find(reply.s("id")!!)!!, alpha.id)
        assertEquals("👍 Ship it", frame.o("reply").s("button"))

        // Reading its own message back, the agent sees who chose what.
        val thread = ok("get_messages", """{"update_id":"$updateId"}""").a("messages").map { it.jsonObject }
        val mine = thread.single { it.s("id") == askedId }.o("replies")
        assertEquals("👍 Ship it", mine.a("answers")[0].jsonObject.s("button"))
        assertNotNull(mine.a("answers")[0].jsonObject.s("answered_at"))

        // Unsending the reply takes the choice back, so it can be made again.
        assertEquals(200, owner("DELETE", "/api/messages/${reply.s("id")}").first)
        val after = owner("GET", "/api/messages?update=$updateId").second.a("messages").map { it.jsonObject }.first { it.s("id") == askedId }
        assertEquals(0, after.o("replies").a("answers").size)
        val (spent, why) = owner("POST", "/api/messages/$askedId/reply", """{"button":"Try another","sendKey":"tap-1"}""")
        assertEquals(409, spent, "the unsent answer's key is not handed back")
        assertEquals("that sendKey was used for an earlier send: make a new one", why.s("message"))
        assertEquals(201, owner("POST", "/api/messages/$askedId/reply", """{"button":"Try another","sendKey":"tap-3"}""").first)
    }

    @Test
    fun `a tap on a feed post lands under that post`() = withServer {
        val postId = ok("post_message", """{"channel":"web","body":"Pick a name","buttons":["Atlas","Beacon"]}""").o("message").s("id")!!
        val (status, tapped) = owner("POST", "/api/messages/$postId/reply", """{"button":"Beacon"}""")
        assertEquals(201, status, tapped.toString())
        assertEquals(postId, tapped.o("message").s("replyTo"))
        assertEquals(postId, tapped.o("message").s("answers"))
        assertNull(tapped.o("message").s("updateId"))
    }

    @Test
    fun `a form is sent as readable text with its values on the reply`() = withServer {
        val fields = """[{"id":"name","label":"Name","type":"text","required":true},{"id":"plan","label":"Plan","type":"select","options":["Free","Team"]},""" +
            """{"id":"regions","label":"Regions","type":"checkboxes","options":["EU","US"]},{"id":"notify","label":"Tell me","type":"toggle"}]"""
        val asked = ok("post_message", """{"channel":"web","body":"Set it up?","fields":$fields}""").o("message")
        assertEquals(listOf("Send"), asked.o("replies").a("buttons").strings())
        assertEquals(4, asked.o("replies").a("fields").size)
        val id = asked.s("id")!!

        val (missing, why) = owner("POST", "/api/messages/$id/reply", """{"button":"Send","values":{}}""")
        assertEquals(400, missing)
        assertEquals("\"Name\" is required", why.s("message"))
        assertEquals(0L, count("SELECT count(*) FROM messages WHERE reply_for IS NOT NULL"), "a refused form posts nothing")

        val (status, sent) = owner("POST", "/api/messages/$id/reply", """{"button":"Send","values":{"name":"Sam","plan":"Team","regions":["US","EU"],"notify":true}}""")
        assertEquals(201, status, sent.toString())
        val reply = sent.o("message")
        assertEquals("- **Name:** Sam\n- **Plan:** Team\n- **Regions:** EU, US\n- **Tell me:** yes", reply.s("body"))
        val values = reply.o("replyValue").o("values")
        assertEquals("Sam", values.s("name"))
        assertEquals(listOf("EU", "US"), values.a("regions").strings())
        val heard = ok("get_messages", "{}").a("messages").map { it.jsonObject }.single { it.s("id") == reply.s("id") }
        assertEquals("Team", heard.o("reply").o("values").s("plan"))
    }

    @Test
    fun `a form with several buttons says which was pressed`() = withServer {
        val id = ok("post_message", """{"channel":"web","body":"Rename?","buttons":["Rename","Keep"],"fields":[{"id":"to","label":"New name","type":"text"}]}""")
            .o("message").s("id")!!
        val (_, sent) = owner("POST", "/api/messages/$id/reply", """{"button":"Keep","values":{}}""")
        assertEquals("Keep", sent.o("message").s("body"))
    }

    @Test
    fun `bad offers and bad taps are refused`() = withServer {
        // The schema stops a fifth button before the tool runs.
        val five = tool("post_message", """{"channel":"web","body":"x","buttons":["a","b","c","d","e"]}""")
        assertEquals("true", five.s("isError"))
        assertTrue("<=4 items at buttons" in five.toString(), five.toString())
        refused("post_message", """{"channel":"web","body":"x","buttons":["a","a"]}""", "buttons must be distinct")
        refused("post_message", """{"channel":"web","body":"x","buttons":[]}""", "buttons needs at least one label, or leave it out")
        refused("post_message", """{"channel":"web","body":"x","buttons":["${"x".repeat(81)}"]}""", "buttons[0] must be at most 80 characters")
        refused("post_message", """{"channel":"web","body":"x","fields":[{"id":"a","label":"A","type":"radar"}]}""",
            "fields[0].type \"radar\" is not a field type: use one of text, textarea, number, email, url, date, select, radio, checkbox, checkboxes, toggle")
        assertEquals(0L, count("SELECT count(*) FROM messages"))

        val id = ok("post_message", """{"channel":"web","body":"Go?","buttons":["Yes","No"]}""").o("message").s("id")!!
        assertEquals(400, owner("POST", "/api/messages/$id/reply", """{"button":"Maybe"}""").first)
        assertEquals(400, owner("POST", "/api/messages/$id/reply", """{"button":"Yes","values":{"a":"b"}}""").first)
        assertEquals(400, owner("POST", "/api/messages/$id/reply", """{"button":"Yes","extra":1}""").first)
        val plain = ok("post_message", """{"channel":"web","body":"No buttons"}""").o("message").s("id")!!
        assertNull(ok("get_messages", """{"message_id":"$plain"}""").a("messages")[0].jsonObject["replies"])
        assertEquals(400, owner("POST", "/api/messages/$plain/reply", """{"button":"Yes"}""").first)
        assertEquals(404, owner("POST", "/api/messages/nope/reply", """{"button":"Yes"}""").first)
    }

    @Test
    fun `an answer tells the apps the message changed`() = withServer {
        val seen = LinkedBlockingQueue<Pair<String, JsonObject>>()
        val id = ok("post_message", """{"channel":"web","body":"Go?","buttons":["Yes"]}""").o("message").s("id")!!
        app.bus.subscribe { seen += it.type to it.payload }
        owner("POST", "/api/messages/$id/reply", """{"button":"Yes"}""")
        val events = generateSequence { seen.poll(1, TimeUnit.SECONDS) }.take(10).toList()
        assertTrue(events.any { it.first == "message.created" }, events.toString())
        assertTrue(events.any { it.first == "message.updated" && it.second.s("messageId") == id }, events.toString())
    }
}
