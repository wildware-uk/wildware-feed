package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.domain.AgentWork
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.Updates
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.media.Chunks
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An agent the owner @mentions in a thread reads the whole thread: the post that started it (which
 * named nobody and reached nobody), every reply, the card's details and the files on them. An agent
 * nobody brought in still can't, nor can another team. (spec/04 §4.9)
 */
class MentionOpensThreadTest {
    private val dir = Files.createTempDirectory("mention-thread").toFile()
    private val config = Config(
        dataDir = dir.path, adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    private fun call(app: App, agent: Agents.Agent, name: String, vararg args: Pair<String, Any>): JsonObject = runBlocking {
        val arguments = buildJsonObject { for ((k, v) in args) put(k, if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString())) }
        app.tools.call(app, agent, buildJsonObject { put("name", JsonPrimitive(name)); put("arguments", arguments) }).json
    }

    private fun tool(app: App, agent: Agents.Agent, name: String, vararg args: Pair<String, Any>): JsonObject {
        val result = call(app, agent, name, *args)
        assertNull(result["isError"], "$name failed: $result")
        return result["structuredContent"]!!.jsonObject
    }

    private fun refused(app: App, agent: Agents.Agent, name: String, vararg args: Pair<String, Any>): String {
        val result = call(app, agent, name, *args)
        assertEquals("true", result["isError"]?.jsonPrimitive?.content, "$name should be refused: $result")
        return result.toString()
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

    @Test
    fun `on SQLite a mention in a thread opens the whole thread`() = mentionOpensThread(App(config, Database.memory()))

    @Test
    fun `on Postgres a mention in a thread opens the whole thread`() {
        val db = postgres()
        mentionOpensThread(App(config, db))
    }

    @Test
    fun `on SQLite a mention on an update's thread opens the update and its comments`() = updateThread(App(config, Database.memory()))

    @Test
    fun `on Postgres a mention on an update's thread opens the update and its comments`() = updateThread(App(config, postgres()))

    private fun upload(app: App, name: String, text: String): String = runBlocking {
        var sent = false
        val bytes = text.toByteArray()
        app.uploads.ownerUpload(name, "text/plain", Chunks { if (sent) null else bytes.also { sent = true } }, bytes.size.toLong()).id
    }

    private fun mentionOpensThread(app: App) {
        try {
            val (helper, helperToken) = app.agents.mint("helper")
            val (other, _) = app.agents.mint("other")
            val (bystander, _) = app.agents.mint("bystander")
            app.feed.projects.create("General", null, null)
            // "other" follows the channel; the bystander follows nothing here.
            tool(app, other, "subscribe", "channel" to "general")

            // The owner posts a file with no @mention, then brings helper in by name in its thread.
            val file = upload(app, "launch.sh", "echo start the teammate")
            val root = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "here is the launcher", project = "general", mediaIds = listOf(file)))
            val aside = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "@other private aside", replyTo = root.id))
            val ask = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "@helper can you spin a teammate up please", replyTo = root.id))
            val agentToAgent = app.feed.messages.post(Messages.Post(author = "agent:${other.id}", body = "@bystander between us two", replyTo = root.id))
            val later = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "thanks", replyTo = root.id))

            // The mention reaches helper, and says how to read the rest; the post that started it does not.
            val unread = tool(app, helper, "get_messages", "mark_read" to false)
            val unreadIds = unread["messages"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertTrue(ask.id in unreadIds && root.id !in unreadIds, unreadIds.toString())
            val mention = unread["messages"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == ask.id }.jsonObject
            assertEquals(root.id, mention["thread"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            // The stream frame carries the same line in the words the bridge shows.
            val line = AgentWork.Line(ask.seq, ask.id, ask.projectId, null, null, root.id, null, null, ask.author, ask.body, ask.createdAt, null)
            val frame = messageItem(app, line, helper.id)
            assertTrue("get_messages({message_id: \"${ask.id}\"})" in frame["body"]!!.jsonPrimitive.content, frame.toString())
            assertEquals("message", frame["thread"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
            // A later reply in a thread helper now follows needs no such line.
            val laterLine = AgentWork.Line(later.seq, later.id, later.projectId, null, null, root.id, null, null, later.author, later.body, later.createdAt, null)
            assertEquals("thanks", messageItem(app, laterLine, helper.id)["body"]!!.jsonPrimitive.content)

            // helper reads the whole thread: the post with its file, the owner's lines, the replies.
            val read = tool(app, helper, "get_messages", "message_id" to ask.id)
            val ids = read["messages"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertEquals(listOf(root.id, aside.id, ask.id, later.id), ids, "the owner's lines are shared; one agent's line to another is not")
            assertEquals(1, read["withheld"]!!.jsonPrimitive.content.toInt())
            assertEquals("message", read["thread"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
            val first = read["messages"]!!.jsonArray[0].jsonObject
            assertEquals("here is the launcher", first["body"]!!.jsonPrimitive.content)
            val attached = first["attachments"]!!.jsonArray.single().jsonObject
            assertEquals(file, attached["media_id"]!!.jsonPrimitive.content)
            assertEquals("http://127.0.0.1/api/agent/media/$file", attached["download_url"]!!.jsonPrimitive.content)
            // The file's words come along too, and the read cursor did not move.
            assertTrue(call(app, helper, "get_messages", "message_id" to root.id).toString().contains("echo start the teammate"))
            assertEquals(unreadIds, tool(app, helper, "get_messages", "mark_read" to false)["messages"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })

            // helper downloads the file with its own token.
            testApplication {
                application { feed(app) }
                val got = client.get("/api/agent/media/$file") { header("Authorization", "Bearer $helperToken") }
                assertEquals(200, got.status.value)
                assertEquals("echo start the teammate", got.bodyAsText())
            }

            // "other" hears the channel, and the owner named it in the aside: it reads every line, its own included.
            val otherIds = tool(app, other, "get_messages", "message_id" to root.id)["messages"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertEquals(listOf(root.id, aside.id, ask.id, agentToAgent.id, later.id), otherIds)

            // The bystander was named only by an agent: it reads every line, as a person's @name hides none.
            val byIds = tool(app, bystander, "get_messages", "message_id" to root.id)["messages"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertEquals(listOf(root.id, aside.id, ask.id, agentToAgent.id, later.id), byIds)

            // An agent nobody named, that follows neither the channel nor the thread, can't read it.
            val (outsider, _) = app.agents.mint("outsider")
            assertTrue("forbidden" in refused(app, outsider, "get_messages", "message_id" to root.id))

            // Another team can't, by any id.
            val (stranger, _) = Tenant.with("elsewhere") { app.agents.mint("helper") }
            Tenant.with("elsewhere") {
                assertTrue("not_found" in refused(app, stranger, "get_messages", "message_id" to root.id))
                assertTrue("not_found" in refused(app, stranger, "get_messages", "message_id" to ask.id))
            }
        } finally {
            app.close()
        }
    }

    private fun updateThread(app: App) {
        try {
            val (author, _) = app.agents.mint("author")
            val (helper, _) = app.agents.mint("helper")
            app.feed.projects.create("Web", null, null)
            val update = app.feed.updates.post(Updates.Post(project = "web", agentId = author.id, title = "Release notes", body = "the full notes"))
            app.feed.fields.set(dev.wildware.feed.server.domain.CardRef("update", update.id), "Version", "1.2", Messages.HUMAN)
            val first = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "looks good", updateId = update.id))
            val ask = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "@helper check these", updateId = update.id))

            val read = tool(app, helper, "get_messages", "update_id" to update.id)
            val card = read["thread"]!!.jsonObject["card"]!!.jsonObject
            assertEquals("Release notes", card["title"]!!.jsonPrimitive.content)
            assertEquals("the full notes", card["body"]!!.jsonPrimitive.content)
            assertEquals("1.2", card["fields"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content)
            assertEquals(listOf(first.id, ask.id), read["messages"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            assertNotNull(tool(app, helper, "get_messages", "message_id" to ask.id)["thread"])
        } finally {
            app.close()
        }
    }
}
