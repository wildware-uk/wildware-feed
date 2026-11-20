package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.feed
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** A message an agent reads with get_messages counts as handed to it, like one its live stream sends. */
class GetMessagesDeliveryTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    private fun tool(app: App, agent: Agents.Agent, name: String, vararg args: Pair<String, String>): JsonObject = runBlocking {
        val arguments = buildJsonObject { for ((k, v) in args) put(k, JsonPrimitive(v)) }
        app.tools.call(app, agent, buildJsonObject { put("name", JsonPrimitive(name)); put("arguments", arguments) }).json
    }

    private fun deliveries(app: App, agent: Agents.Agent): Int =
        app.db.one("SELECT count(*) FROM message_deliveries WHERE team_id = :team AND agent_id = ?", agent.id) { it.getInt(1) } ?: 0

    @Test
    fun `get_messages records a delivery for each message it hands over`() {
        val app = App(config, Database.memory())
        val (alpha, _) = app.agents.mint("alpha")
        app.feed.projects.create("Web", null, null)
        tool(app, alpha, "subscribe", "channel" to "web")
        app.feed.messages.post(Messages.Post(author = "human", body = "is staging back up", project = "web"))
        assertEquals(0, deliveries(app, alpha))

        val read = tool(app, alpha, "get_messages")["structuredContent"]!!.jsonObject["messages"]!!.jsonArray
        assertEquals(1, read.size)
        assertEquals(1, deliveries(app, alpha))

        // The next read hands over only the new line, and records only that.
        app.feed.messages.post(Messages.Post(author = "human", body = "and the queue", project = "web"))
        tool(app, alpha, "get_messages")
        tool(app, alpha, "get_messages")
        assertEquals(2, deliveries(app, alpha))
    }
}
