package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.Updates
import dev.wildware.feed.server.domain.feed
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Once a card is deleted, nothing hands back what it said or what was on it. */
class DeletedCardsTest {
    private var now = 1_800_000_000_000L

    private fun config(dataDir: String) = Config(
        dataDir = dataDir, adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    private fun tool(app: App, agent: Agents.Agent, name: String, vararg args: Pair<String, String>): JsonObject = runBlocking {
        val arguments = buildJsonObject { for ((k, v) in args) put(k, JsonPrimitive(v)) }
        app.tools.call(app, agent, buildJsonObject { put("name", JsonPrimitive(name)); put("arguments", arguments) }).json
    }

    @Test
    fun `an unsent message's text goes back only to someone who could unsend it`() = App(config("unused"), Database.memory()).use { app ->
        val (alpha, _) = app.agents.mint("alpha")
        val (beta, _) = app.agents.mint("beta")
        app.feed.projects.create("Web", null, null)
        val said = tool(app, alpha, "post_message", "channel" to "web", "body" to "a password pasted by mistake")
        val id = said["structuredContent"]!!.jsonObject["message"]!!.jsonObject["id"]!!.jsonPrimitive.content
        tool(app, alpha, "delete_message", "message_id" to id)

        val asked = tool(app, beta, "delete_message", "message_id" to id).toString()
        assertFalse(asked.contains("pasted by mistake"), asked)
        assertEquals("invalid_argument", tool(app, beta, "delete_message", "message_id" to id)["structuredContent"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        assertEquals(id, tool(app, alpha, "delete_message", "message_id" to id)["structuredContent"]!!.jsonObject["message"]!!.jsonObject["id"]!!.jsonPrimitive.content, "its author still may")
    }

    @Test
    fun `a deleted card has no fields`() = App(config("unused"), Database.memory()).use { app ->
        val (alpha, _) = app.agents.mint("alpha")
        app.feed.projects.create("Web", null, null)
        val update = app.feed.updates.post(Updates.Post(project = "web", agentId = alpha.id, body = "working"))
        tool(app, alpha, "set_card_field", "update_id" to update.id, "name" to "secret", "value" to "do not keep")
        app.feed.updates.delete(update.id)
        val fields = tool(app, alpha, "get_card_fields", "update_id" to update.id).toString()
        assertFalse(fields.contains("do not keep"), fields)
    }

    @Test
    fun `a file on a deleted card is not served, and the sweeper removes it`() {
        val dir = Files.createTempDirectory("deleted-cards").toFile()
        val app = App(config(dir.path), Database.memory()) { now }
        val (_, ownerKey) = app.devices.mint("Phone")
        val (alpha, agentToken) = app.agents.mint("alpha")
        app.feed.projects.create("Web", null, null)
        // An agent fetches a file it may read (issue #304): this one hears the channel.
        dev.wildware.feed.server.domain.Subscriptions(app.db, app.clock).followProject(dev.wildware.feed.server.domain.Subscriptions.agent(alpha.id), app.feed.projects.resolve("web").id, chosen = true)
        try {
            testApplication {
                application { feed(app) }
                val upload = client.post("/api/media?filename=notes.txt") {
                    header("Authorization", "Bearer $ownerKey")
                    header("Content-Type", "text/plain")
                    setBody("the notes")
                }
                assertEquals(201, upload.status.value, upload.bodyAsText())
                val media = Wire.json.parseToJsonElement(upload.bodyAsText()).jsonObject["media"]!!.jsonObject["id"]!!.jsonPrimitive.content
                val message = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "see the file", project = "web"))
                app.db.update("UPDATE media SET message_id = ? WHERE team_id = :team AND id = ?", message.id, media)

                suspend fun owner() = client.get("/media/$media/original") { header("Authorization", "Bearer $ownerKey") }.status.value
                suspend fun agent() = client.get("/api/agent/media/$media") { header("Authorization", "Bearer $agentToken") }.status.value
                assertEquals(200, owner())
                assertEquals(200, agent())

                app.feed.messages.delete(message.id, Messages.HUMAN)
                assertEquals(404, owner())
                assertEquals(404, agent())

                app.uploads.sweep()
                assertEquals(1L, app.db.one("SELECT count(*) FROM media WHERE team_id = :team AND id = ?", media) { it.getLong(1) }, "kept for an hour")
                now += 3_600_001
                app.uploads.sweep()
                assertNull(app.db.one("SELECT 1 FROM media WHERE team_id = :team AND id = ?", media) { it.getInt(1) }, "the sweeper removed it")
            }
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }
}
