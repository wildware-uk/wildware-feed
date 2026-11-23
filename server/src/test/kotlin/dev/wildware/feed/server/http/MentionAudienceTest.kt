package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.ChannelAccess
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.access
import dev.wildware.feed.server.domain.agentMembers
import dev.wildware.feed.server.domain.feed
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who a message with @names reaches. A person's @name reaches that agent as well as everyone in the
 * channel; an agent's @name reaches only the agents it names. get_messages, the stream's message
 * event and the heartbeat's unread count all agree, and channel access still rules.
 */
class MentionAudienceTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    private fun tool(app: App, agent: Agents.Agent, name: String, vararg args: Pair<String, Any>): JsonObject = runBlocking {
        val arguments = buildJsonObject { for ((k, v) in args) put(k, if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString())) }
        app.tools.call(app, agent, buildJsonObject { put("name", JsonPrimitive(name)); put("arguments", arguments) }).json["structuredContent"]!!.jsonObject
    }

    /** The bodies get_messages hands [agent], without moving its cursor. */
    private fun read(app: App, agent: Agents.Agent): List<String> =
        tool(app, agent, "get_messages", "mark_read" to false)["messages"]!!.jsonArray.map { it.jsonObject["body"]!!.jsonPrimitive.content }

    /** Whether the stream would hand [agent] the message event (the bridge's notification). */
    private fun event(app: App, agent: Agents.Agent, message: String) = app.work.reaches(agent.id, message)

    private fun person(app: App, body: String, project: String) =
        app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = body, project = project)).id

    private fun byAgent(app: App, agent: Agents.Agent, body: String, project: String) =
        app.feed.messages.post(Messages.Post(author = "agent:${agent.id}", body = body, project = project)).id

    @Test
    fun `a person's @name reaches the agent named and the channel, an agent's only the agent named`() = App(config, Database.memory()).use { app ->
        val (alpha, _) = app.agents.mint("alpha")
        val (beta, _) = app.agents.mint("beta")
        val (lead, _) = app.agents.mint("lead")
        app.feed.projects.create("Web", null, null)
        app.feed.projects.create("Api", null, null)
        // beta follows web; alpha follows only api.
        tool(app, beta, "subscribe", "channel" to "web")
        tool(app, alpha, "subscribe", "channel" to "api")

        val asked = person(app, "@alpha can you look", "web")
        assertEquals(listOf("@alpha can you look"), read(app, alpha), "the agent named hears it outside its channels")
        assertEquals(listOf("@alpha can you look"), read(app, beta), "and the channel hears it too")
        assertTrue(event(app, alpha, asked) && event(app, beta, asked))
        assertEquals(1, app.work.countWork(alpha.id).unreadMessages)
        assertEquals(1, app.work.countWork(beta.id).unreadMessages)
        // A reply in the thread with no @name still reaches alpha, which now follows it.
        val reply = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "thanks", replyTo = asked)).id
        assertTrue(event(app, alpha, reply))

        val directed = byAgent(app, lead, "@alpha just you", "web")
        assertTrue("@alpha just you" in read(app, alpha))
        assertFalse("@alpha just you" in read(app, beta), "an agent's @name narrows")
        assertTrue(event(app, alpha, directed))
        assertFalse(event(app, beta, directed))
        assertEquals(2, app.work.countWork(beta.id).unreadMessages)
        // From a `since` cursor too.
        val since = tool(app, beta, "get_messages", "since" to "0", "mark_read" to false)["messages"]!!.jsonArray.map { it.jsonObject["body"]!!.jsonPrimitive.content }
        assertFalse("@alpha just you" in since, since.toString())
        assertTrue("@alpha can you look" in since, since.toString())
    }

    @Test
    fun `access still rules, and a removed agent hears a person's message only when it is named`() = App(config, Database.memory()).use { app ->
        val (alpha, _) = app.agents.mint("alpha")
        val (beta, _) = app.agents.mint("beta")
        val (gamma, _) = app.agents.mint("gamma")
        app.feed.projects.create("Web", null, null)
        for (agent in listOf(alpha, beta, gamma)) tool(app, agent, "subscribe", "channel" to "web")
        val owner = app.people.find(People.FOUNDER)!!
        val web = app.feed.projects.resolve("web")

        // gamma has no access to web: nothing there reaches it, even a person's @gamma.
        app.feed.access.setLevel("channel", web.id, "agent:${gamma.id}", ChannelAccess.Level.NONE, owner)
        val hidden = person(app, "@gamma and @alpha look", "web")
        assertFalse(event(app, gamma, hidden))
        assertEquals(0, app.work.countWork(gamma.id).unreadMessages)

        // beta is removed from web: a person's @alpha does not reach it, a person's @beta does.
        app.feed.agentMembers.setChannel(web, beta.id, hears = false, by = owner.id)
        tool(app, beta, "get_messages")
        val other = person(app, "@alpha only alpha is named", "web")
        assertTrue(event(app, alpha, other))
        assertFalse(event(app, beta, other))
        val named = person(app, "@beta a word", "web")
        assertTrue(event(app, beta, named))
        assertEquals(listOf("@beta a word"), read(app, beta))
    }
}
