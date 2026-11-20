package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.CardRef
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.Updates
import dev.wildware.feed.server.domain.feed
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A message that @names agents reaches only them. Its words must not reach the other agents some
 * other way either: as the quote on a reply, as a work summary, or as a followed thread's title.
 */
class AddressedQuotesTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    private fun tool(app: App, agent: Agents.Agent, name: String, vararg args: Pair<String, String>): JsonObject = runBlocking {
        val arguments = buildJsonObject { for ((k, v) in args) put(k, JsonPrimitive(v)) }
        app.tools.call(app, agent, buildJsonObject { put("name", JsonPrimitive(name)); put("arguments", arguments) }).json["structuredContent"]!!.jsonObject
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
    fun `on SQLite an addressed message's words stay with the agents it names`() = addressedStaysAddressed(App(config, Database.memory()))

    @Test
    fun `on Postgres an addressed message's words stay with the agents it names`() {
        val db = postgres()
        try {
            addressedStaysAddressed(App(config, db))
        } finally {
            db.close()
        }
    }

    private fun addressedStaysAddressed(app: App) {
        val (alpha, _) = app.agents.mint("alpha")
        val (beta, _) = app.agents.mint("beta")
        app.feed.projects.create("Web", null, null)
        for (agent in listOf(alpha, beta)) app.feed.updates.post(Updates.Post(project = "web", agentId = agent.id, body = "${agent.name} works in web"))
        for (agent in listOf(alpha, beta)) tool(app, agent, "get_messages")

        val addressed = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "@alpha the private plan", project = "web"))
        tool(app, alpha, "post_message", "message_id" to addressed.id, "body" to "on it")

        // beta hears alpha's reply, but not the words it answers.
        val reply = tool(app, beta, "get_messages")["messages"]!!.jsonArray.single().jsonObject
        assertEquals("on it", reply["body"]!!.jsonPrimitive.content)
        assertNull(reply["in_reply_to"]?.takeIf { it !is JsonNull }, reply.toString())
        val line = app.work.find(reply["id"]!!.jsonPrimitive.content)!!
        assertNull(app.work.quoted(line, "agent:${beta.id}"))
        // The agent it named, and the owner, still see the quote.
        assertEquals("@alpha the private plan", app.work.quoted(line, "agent:${alpha.id}")!!.second)
        assertEquals("@alpha the private plan", app.feed.messages.quoted(app.feed.messages.find(line.id)!!)!!.body)

        // Marked as work, it is on alpha's list and not on beta's.
        app.feed.cards.setStatus(CardRef("message", addressed.id), "in_progress")
        fun work(agent: Agents.Agent) = tool(app, agent, "list_work")["work"]!!.jsonArray.map { it.jsonObject["summary"]!!.jsonPrimitive.content }
        assertTrue("@alpha the private plan" in work(alpha))
        assertTrue(work(beta).none { "private plan" in it }, work(beta).toString())

        // Following its thread gives beta no title from it; alpha gets one.
        val followed = tool(app, beta, "subscribe", "message_id" to addressed.id)["subscribed"]!!.jsonObject
        assertNull(followed["title"]?.takeIf { it !is JsonNull }, followed.toString())
        val listed = tool(app, beta, "list_subscriptions")["threads"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == addressed.id }
        assertNull(listed["title"]?.takeIf { it !is JsonNull }, listed.toString())
        assertNotNull(tool(app, alpha, "subscribe", "message_id" to addressed.id)["subscribed"]!!.jsonObject["title"]?.takeIf { it !is JsonNull })
    }
}
