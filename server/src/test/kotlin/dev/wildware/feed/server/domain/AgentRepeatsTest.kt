package dev.wildware.feed.server.domain

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Tenant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * An agent's MCP client sends a post again when the server is slow to answer. The same post from the
 * same agent in the same place within [Messages.REPEAT_WINDOW_MS] is that send, not a new one: the
 * first comes back and nothing new happens.
 */
class AgentRepeatsTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )
    private var now = 1_000_000_000L
    private val M1 = "01M7WMWCTEDRE8GWMTQ2V3F6Q1"
    private val M2 = "01M7WMWCTEDRE8GWMTQ2V3F6Q2"

    private fun withApp(block: (App, String, MutableList<String>) -> Unit) {
        val app = App(config, Database.memory(), clock = { now })
        try {
            Tenant.with(Tenant.HOME) {
                app.db.update("INSERT INTO projects (team_id, id, slug, name, created_at, updated_at) VALUES (:team, 'P1', 'web', 'Web', 1, 1)")
                app.db.update("INSERT INTO projects (team_id, id, slug, name, created_at, updated_at) VALUES (:team, 'P2', 'ops', 'Ops', 1, 1)")
                val agent = app.agents.mint("scout").first.id
                val events = mutableListOf<String>()
                val check = app.bus.beforePublish
                app.bus.beforePublish = { check(it); events += it }
                block(app, agent, events)
            }
        } finally {
            app.close()
        }
    }

    private fun count(app: App, table: String) = app.db.one("SELECT count(*) FROM $table WHERE team_id = :team") { it.getLong(1) }!!

    private fun media(app: App, agent: String, id: String) = app.db.update(
        "INSERT INTO media (team_id, id, agent_id, author, kind, mime, bytes, sha256, status, created_at) VALUES (:team, ?, ?, ?, 'image', 'image/png', 3, 'abc', 'ready', 1)",
        id, agent, "agent:$agent",
    )

    @Test
    fun `a card posted again a moment later is the same card, with no new event`() = withApp { app, agent, events ->
        fun post(body: String = "Deployed", project: String = "web", title: String? = "Deploy", media: List<String>? = null) =
            app.feed.updates.post(Updates.Post(project = project, agentId = agent, body = body, title = title, mediaIds = media))
        val first = post()
        events.clear()
        now += 6 * 60_000L
        assertEquals(first.id, post().id, "a resend minutes later, as seen live")
        assertEquals(emptyList(), events.filter { it.startsWith("update.") })
        assertEquals(1, count(app, "updates"))

        assertNotEquals(first.id, post(body = "Deployed again").id, "other words")
        assertNotEquals(first.id, post(project = "ops").id, "another channel")
        assertNotEquals(first.id, post(title = "Other").id, "another title")
        media(app, agent, M1)
        val withPicture = post(body = "Screenshot", media = listOf(M1))
        assertEquals(withPicture.id, post(body = "Screenshot", media = listOf(M1)).id, "the same picture, already on the first")
        media(app, agent, M2)
        assertNotEquals(withPicture.id, post(body = "Screenshot", media = listOf(M2)).id, "another picture")
        // Blocks carry their own times, so a card with them is never taken for a resend.
        val blocks = listOf(kotlinx.serialization.json.Json.parseToJsonElement("""{"id":"b","type":"checklist","title":"Build","items":[{"id":"1","text":"Tests"}]}"""))
        val a = app.feed.updates.post(Updates.Post(project = "web", agentId = agent, body = "Build", blocks = blocks))
        assertNotEquals(a.id, app.feed.updates.post(Updates.Post(project = "web", agentId = agent, body = "Build", blocks = blocks)).id)

        app.feed.updates.delete(first.id)
        assertNotEquals(first.id, post().id, "said again after deleting it: meant")
        val again = post(body = "Once more")
        now += Messages.REPEAT_WINDOW_MS + 1
        assertNotEquals(again.id, post(body = "Once more").id, "long after, it is said again on purpose")
    }

    @Test
    fun `a reply posted again lands once in its thread`() = withApp { app, agent, events ->
        val card = app.feed.updates.post(Updates.Post(project = "web", agentId = agent, body = "Card"))
        fun reply(body: String = "On it", update: String? = card.id, project: String? = null, author: String = "agent:$agent") =
            app.feed.messages.post(Messages.Post(author = author, body = body, updateId = update, project = project))
        val first = reply()
        events.clear()
        now += 33_000L
        assertEquals(first.id, reply().id)
        now += 5 * 60_000L
        assertEquals(first.id, reply().id, "three tries, as seen live")
        assertEquals(emptyList(), events.filter { it.startsWith("message.") })
        assertEquals(1, app.db.one("SELECT count(*) FROM messages WHERE team_id = :team AND update_id = ?", card.id) { it.getLong(1) })

        assertNotEquals(first.id, reply(body = "Done").id, "other words")
        assertNotEquals(first.id, reply(update = null, project = "web").id, "another place")
        val other = app.agents.mint("helper").first.id
        assertNotEquals(first.id, reply(author = "agent:$other").id, "another agent")
        assertNotEquals(first.id, reply(author = Messages.HUMAN).id, "the owner, posting the same words, is not a resend")
        media(app, agent, M1)
        val pic = app.feed.messages.post(Messages.Post(author = "agent:$agent", body = "See", updateId = card.id, mediaIds = listOf(M1)))
        assertEquals(pic.id, app.feed.messages.post(Messages.Post(author = "agent:$agent", body = "See", updateId = card.id, mediaIds = listOf(M1))).id)

        app.feed.messages.delete(first.id, "agent:$agent")
        assertNotEquals(first.id, reply().id, "said again after unsending it: meant")
        now += Messages.REPEAT_WINDOW_MS
        val late = reply(body = "Later")
        now += Messages.REPEAT_WINDOW_MS + 1
        assertNotEquals(late.id, reply(body = "Later").id, "long after, it is said again on purpose")
    }
}
