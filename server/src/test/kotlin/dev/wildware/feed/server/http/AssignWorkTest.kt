package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Viewer
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.events.AppEvent
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Assigning work (migration 072, spec/16): a work card's people and agents, who may assign, the
 * line in the thread, an agent told on its stream wherever the card is, a person told by the bell
 * and their phone, list_work's assigned_to, and the board's filters.
 *
 * Runs on SQLite, and on Postgres when PG_TEST_URL names one ([Database.memory]).
 */
class AssignWorkTest {
    private fun config(dir: String) = Config(
        dataDir = dir, adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    /** The founder, two members, two agents (rover and scout), and a key for each person. */
    private class Team(val app: App) {
        val owner: People.Person = app.people.find(People.FOUNDER)!!
        val ownerKey = app.devices.mint("Owner phone").second
        val sam = join(People.Role.MEMBER, "Sam")
        val samKey = app.devices.mint("Sam phone", sam.id).second
        val rin = join(People.Role.MEMBER, "Rin")
        val rinKey = app.devices.mint("Rin phone", rin.id).second
        val rover: Agents.Agent
        val roverToken: String
        val scout: Agents.Agent
        val scoutToken: String

        init {
            app.agents.mint("rover").let { (a, t) -> rover = a; roverToken = t }
            app.agents.mint("scout").let { (a, t) -> scout = a; scoutToken = t }
        }

        private fun join(role: People.Role, name: String): People.Person {
            val (_, token) = app.people.invite(app.people.find(People.FOUNDER)!!, role, "${name.lowercase()}@example.test")
            return app.people.accept(token, name, "${name.lowercase()}@example.test", "password for $name")
        }
    }

    private fun withTeam(block: suspend Team.(HttpClient) -> Unit) {
        val dir = Files.createTempDirectory("assign-work").toFile()
        val app = App(config(dir.path), Database.memory())
        try {
            testApplication {
                application { feed(app) }
                Team(app).block(client)
            }
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    private suspend fun HttpClient.call(key: String, method: String, path: String, body: String? = null): HttpResponse = request(path) {
        this.method = HttpMethod.parse(method)
        header("Authorization", "Bearer $key")
        header("Accept", "application/json, text/event-stream")
        if (body != null) {
            header("Content-Type", "application/json")
            setBody(body)
        }
    }

    private suspend fun HttpClient.json(key: String, method: String, path: String, body: String? = null, status: Int = 200): JsonObject {
        val r = call(key, method, path, body)
        val text = r.bodyAsText()
        assertEquals(status, r.status.value, "$method $path: $text")
        return Wire.json.parseToJsonElement(text).jsonObject
    }

    private suspend fun HttpClient.tool(token: String, name: String, args: JsonObject): JsonObject {
        val r = call(
            token, "POST", "/mcp",
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
                put("params", buildJsonObject { put("name", name); put("arguments", args) })
            }.toString(),
        )
        return Wire.json.parseToJsonElement(r.bodyAsText()).jsonObject["result"]!!.jsonObject
    }

    private fun JsonObject.error(): String? = this["structuredContent"]?.jsonObject?.get("error")?.jsonPrimitive?.content
    private fun JsonObject.data(): JsonObject = this["structuredContent"]!!.jsonObject

    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            is String -> put(k, v)
            is List<*> -> put(k, JsonArray(v.map { JsonPrimitive(it as String) }))
            is JsonElement -> put(k, v)
        }
    }

    private fun names(list: JsonElement?): List<String> = list!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    /** Channels `web` (rover follows it) and `ops` (nobody's agent follows it), each with an owner post. Returns the ops post. */
    private suspend fun Team.setUp(client: HttpClient): String {
        client.tool(roverToken, "create_project", args("name" to "Web", "slug" to "web"))
        client.json(ownerKey, "POST", "/api/projects", """{"name":"Ops","slug":"ops"}""", status = 201)
        client.json(ownerKey, "POST", "/api/messages", """{"project":"ops","body":"Rotate the keys"}""", status = 201)
        return latestMessage()
    }

    private fun Team.latestMessage(): String = app.db.one("SELECT id FROM messages WHERE team_id = :team ORDER BY seq DESC LIMIT 1") { it.getString(1) }!!

    private fun Team.line(card: String): Pair<String, String> =
        app.db.one("SELECT body, source FROM messages WHERE team_id = :team AND (reply_to = ? OR update_id = ?) ORDER BY seq DESC LIMIT 1", card, card) {
            it.getString(1) to it.getString(2)
        }!!

    @Test
    fun `the owner assigns an agent and it is told on its stream though it follows nothing there`() = withTeam { client ->
        val post = setUp(client)
        val events = mutableListOf<AppEvent>()
        val stop = app.bus.subscribe { events += it }
        val res = client.json(ownerKey, "POST", "/api/cards/message/$post/assignees", """{"assignees":["scout"],"mode":"add"}""")
        stop()
        assertEquals(listOf("scout"), names(res["assignees"]))
        // Not work yet, so assigning it made it work.
        assertEquals("todo", res["status"]!!.jsonPrimitive.content)

        // The line in the thread: who did what, and for scout how to take it.
        val (body, source) = line(post)
        assertEquals(Messages.ASSIGNMENT, source)
        assertTrue(body.startsWith("assigned @scout"), body)
        assertContains(body, "you were assigned: Rotate the keys — http://127.0.0.1/projects/ops?focus=$post")
        assertContains(body, "set_status in_progress")

        // Scout follows nothing, yet the line reaches it, and it now follows the thread.
        val unread = Viewer.with(Viewer.agent(scout.id)) { app.work.unread(scout.id, 50) }
        assertTrue(unread.any { it.body.startsWith("assigned @scout") }, unread.toString())
        val follows = client.tool(scoutToken, "list_subscriptions", JsonObject(emptyMap())).data()
        assertTrue(follows["threads"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == post }, follows.toString())
        // Rover follows web, not ops: the line is not for it.
        val roverUnread = Viewer.with(Viewer.agent(rover.id)) { app.work.unread(rover.id, 50) }
        assertTrue(roverUnread.none { it.body.contains("assigned") }, roverUnread.toString())

        // The event, and the card's reads.
        val assigned = events.single { it.type == "card.assigned" }.payload
        assertEquals(listOf("agent:${scout.id}"), assigned["added"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("member:${owner.id}", app.db.one("SELECT assigned_by FROM card_assignees WHERE team_id = :team") { it.getString(1) })
        val read = client.json(ownerKey, "GET", "/api/cards/message/$post/assignees")
        assertEquals(listOf("scout"), names(read["assignees"]))
        assertEquals(5, read["candidates"]!!.jsonArray.size, "three people and two agents")
        val board = client.json(ownerKey, "GET", "/api/board")["columns"]!!.jsonArray.first().jsonObject["cards"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("scout"), names(board["assignees"]))
    }

    @Test
    fun `an assignee who can't read the channel is refused with a clear error`() = withTeam { client ->
        val post = setUp(client)
        client.json(ownerKey, "PUT", "/api/projects/ops/access", """{"private":true}""")
        val refused = client.call(ownerKey, "POST", "/api/cards/message/$post/assignees", """{"assignees":["rover"]}""")
        assertEquals(403, refused.status.value)
        assertContains(refused.bodyAsText(), "rover can't read #ops")
        val person = client.call(ownerKey, "POST", "/api/cards/message/$post/assignees", """{"assignees":["Sam"]}""")
        assertEquals(403, person.status.value)
        assertEquals(0L, app.db.one("SELECT count(*) FROM card_assignees WHERE team_id = :team") { it.getLong(1) })
        // The picker says who can read it.
        val candidates = client.json(ownerKey, "GET", "/api/cards/message/$post/assignees")["candidates"]!!.jsonArray.map { it.jsonObject }
        assertEquals(false, candidates.single { it["name"]!!.jsonPrimitive.content == "rover" }["canRead"]!!.jsonPrimitive.content.toBoolean())
        // Given Read, rover can be assigned.
        client.json(ownerKey, "PUT", "/api/projects/ops/access/agent/${rover.id}", """{"level":"read"}""")
        assertEquals(listOf("rover"), names(client.json(ownerKey, "POST", "/api/cards/message/$post/assignees", """{"assignees":["rover"]}""")["assignees"]))
        // Someone who may only read can't assign.
        client.json(ownerKey, "PUT", "/api/projects/ops/access/member/${sam.id}", """{"level":"read"}""")
        assertEquals(403, client.call(samKey, "POST", "/api/cards/message/$post/assignees", """{"assignees":["me"]}""").status.value)
        // And someone who can't see it finds nothing.
        assertEquals(404, client.call(rinKey, "GET", "/api/cards/message/$post/assignees").status.value)
    }

    @Test
    fun `an agent assigns over MCP and list_work assigned_to me finds it in any channel`() = withTeam { client ->
        val post = setUp(client)
        client.json(ownerKey, "POST", "/api/cards/message/$post/status", """{"status":"todo"}""")
        // Rover doesn't follow ops: plain list_work misses the card.
        assertTrue(client.tool(roverToken, "list_work", JsonObject(emptyMap())).data()["work"]!!.jsonArray.isEmpty())
        val given = client.tool(scoutToken, "assign_work", args("message_id" to post, "assignees" to listOf("rover", "Sam")))
        assertNull(given.error(), given.toString())
        assertEquals(setOf("Sam", "rover"), names(given.data()["assignees"]).toSet())
        val mine = client.tool(roverToken, "list_work", args("assigned_to" to "me")).data()["work"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(post), mine.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(setOf("rover", "Sam"), names(mine.single()["assignees"]).toSet())
        // Scout's own list for it finds nothing; by name it finds rover's.
        assertTrue(client.tool(scoutToken, "list_work", args("assigned_to" to "me")).data()["work"]!!.jsonArray.isEmpty())
        assertEquals(1, client.tool(scoutToken, "list_work", args("assigned_to" to "rover")).data()["work"]!!.jsonArray.size)
        // The thread read carries them too.
        val thread = client.tool(roverToken, "get_messages", args("message_id" to post)).data()["thread"]!!.jsonObject["card"]!!.jsonObject
        assertEquals(setOf("rover", "Sam"), names(thread["assignees"]).toSet())

        // Remove, then set: each leaves a line, and an unknown name says so.
        val removed = client.tool(scoutToken, "assign_work", args("message_id" to post, "assignees" to listOf("Sam"), "mode" to "remove")).data()
        assertEquals(listOf("Sam"), names(removed["removed"]))
        assertTrue(line(post).first.startsWith("unassigned Sam"), line(post).first)
        val set = client.tool(scoutToken, "assign_work", args("message_id" to post, "assignees" to listOf("me"), "mode" to "set")).data()
        assertEquals(listOf("scout"), names(set["assignees"]))
        assertEquals(listOf("rover"), names(set["removed"]))
        assertTrue(line(post).first.startsWith("assigned @scout; unassigned @rover"), line(post).first)
        assertContains(line(post).first, "rover: you are no longer assigned")
        assertEquals("not_found", client.tool(scoutToken, "assign_work", args("message_id" to post, "assignees" to listOf("nobody"))).error())
        // Nothing changed: no new line.
        val lines = app.db.one("SELECT count(*) FROM messages WHERE team_id = :team AND source = 'assignment'") { it.getLong(1) }
        assertEquals(JsonNull, client.tool(scoutToken, "assign_work", args("message_id" to post, "assignees" to listOf("scout"))).data()["line_id"])
        assertEquals(lines, app.db.one("SELECT count(*) FROM messages WHERE team_id = :team AND source = 'assignment'") { it.getLong(1) })
    }

    @Test
    fun `a card takes at most five assignees and a request can't be assigned`() = withTeam { client ->
        val post = setUp(client)
        for (n in 1..4) app.agents.mint("helper$n")
        val six = listOf("rover", "scout", "helper1", "helper2", "helper3", "helper4")
        val refused = client.call(ownerKey, "POST", "/api/cards/message/$post/assignees", """{"assignees":${six.joinToString(",", "[", "]") { "\"$it\"" }}}""")
        assertEquals(400, refused.status.value)
        assertContains(refused.bodyAsText(), "at most 5")
        assertEquals(5, names(client.json(ownerKey, "POST", "/api/cards/message/$post/assignees", """{"assignees":${six.take(5).joinToString(",", "[", "]") { "\"$it\"" }}}""")["assignees"]).size)
        client.tool(roverToken, "request_input", args("kind" to "confirm", "question" to "Ship it?", "project" to "web", "timeout_s" to JsonPrimitive(3600)))
        val request = app.db.one("SELECT id FROM approvals WHERE team_id = :team") { it.getString(1) }!!
        assertEquals("invalid_argument", client.tool(roverToken, "assign_work", args("request_id" to request, "assignees" to listOf("me"))).error())
    }

    @Test
    fun `a person assigned gets a bell row and a phone alert and nobody else does`() = withTeam { client ->
        val post = setUp(client)
        client.json(ownerKey, "POST", "/api/cards/message/$post/assignees", """{"assignees":["Sam"]}""")
        val line = latestMessage()
        // Sam's bell: "<who> assigned you", with the card's title.
        val bell = client.json(samKey, "GET", "/api/notifications")["notifications"]!!.jsonArray.map { it.jsonObject }
        val row = bell.single { it["messageId"]?.jsonPrimitive?.content == line }
        assertEquals("${owner.name} assigned you", row["title"]!!.jsonPrimitive.content)
        assertEquals("Rotate the keys", row["body"]!!.jsonPrimitive.content)
        // Rin, who wasn't assigned, isn't told; the owner did it, so isn't either.
        assertTrue(client.json(rinKey, "GET", "/api/notifications")["notifications"]!!.jsonArray.none { it.jsonObject["messageId"]?.jsonPrimitive?.content == line })
        assertTrue(client.json(ownerKey, "GET", "/api/notifications")["notifications"]!!.jsonArray.none { it.jsonObject["messageId"]?.jsonPrimitive?.content == line })

        // Phones: Sam's alert says so; Rin's says nothing.
        val apns = dev.wildware.feed.server.push.Apns(app, Config.Apns("KEY123", "TEAM456", "/unused.p8", "dev.example.feed")) { dev.wildware.feed.server.push.ApnsResponse(200, null) }
        val event = AppEvent("message.created", 1, "", buildJsonObject { put("messageId", line) })
        val alert = assertNotNull(apns.alertFor(event, sam.id))
        assertEquals("${owner.name} assigned you", alert.message.title)
        assertEquals("Rotate the keys", alert.message.body)
        assertTrue(alert.followed)
        assertNull(apns.alertFor(event, rin.id))
        // The alert is a "message" (directed at them): a phone that turned those off hears nothing.
        val off = Wire.json.parseToJsonElement("""{"types":["request","update"]}""").jsonObject
        assertFalse(dev.wildware.feed.server.domain.AlertPrefs.notifies(off, alert.about))
        assertTrue(dev.wildware.feed.server.domain.AlertPrefs.notifies(null, alert.about))
        // Sam follows the thread now. No agent hears a line that assigns only people.
        val follows = client.json(samKey, "GET", "/api/subscriptions").toString()
        assertContains(follows, post)
        client.tool(roverToken, "subscribe", args("channel" to "ops"))
        client.json(ownerKey, "POST", "/api/cards/message/$post/assignees", """{"assignees":["Rin"]}""")
        val heard = Viewer.with(Viewer.agent(rover.id)) { app.work.unread(rover.id, 50) }
        assertTrue(heard.none { it.body.contains("assigned") }, heard.toString())
    }

    @Test
    fun `an agent posts a to-do for a person in one call and hears when they mark it done`() = withTeam { client ->
        setUp(client)
        client.tool(scoutToken, "subscribe", args("channel" to "web"))
        val posted = client.tool(roverToken, "post_update", args(
            "channel" to "web", "title" to "Approve the TestFlight build", "body" to "Build 42 is in TestFlight.", "assignees" to listOf("Sam"),
        ))
        assertNull(posted.error(), posted.toString())
        assertEquals("todo", posted.data()["status"]!!.jsonPrimitive.content)
        assertEquals(listOf("Sam"), names(posted.data()["assignees"]))
        val update = posted.data()["update"]!!.jsonObject["id"]!!.jsonPrimitive.content
        // Sam is told, as a person is: the bell.
        assertTrue(client.json(samKey, "GET", "/api/notifications")["notifications"]!!.jsonArray.any {
            it.jsonObject["title"]!!.jsonPrimitive.content == "rover assigned you"
        })
        val before = Viewer.with(Viewer.agent(rover.id)) { app.work.unread(rover.id, 50) }.map { it.id }.toSet()

        // Sam marks it done on the board: rover hears it in the thread, on its channel.
        client.json(samKey, "POST", "/api/cards/update/$update/status", """{"status":"done"}""")
        val heard = Viewer.with(Viewer.agent(rover.id)) { app.work.unread(rover.id, 50) }.filter { it.id !in before }
        val line = heard.single()
        assertEquals(Messages.STATUS_LINE, line.source)
        assertTrue(line.body.startsWith("marked this done"), line.body)
        assertContains(line.body, "@rover: Approve the TestFlight build is now done (assigned to Sam). Pick up whatever was waiting on it.")
        // Scout follows web but waits on nothing here: it hears nothing. Nobody's bell rings for it.
        val scoutHeard = Viewer.with(Viewer.agent(scout.id)) { app.work.unread(scout.id, 50) }
        assertTrue(scoutHeard.none { it.id == line.id }, scoutHeard.toString())
        assertTrue(client.json(ownerKey, "GET", "/api/notifications")["notifications"]!!.jsonArray.none { it.jsonObject["messageId"]?.jsonPrimitive?.content == line.id })
        // Rover moving it itself says nothing to itself.
        val lines = app.db.one("SELECT count(*) FROM messages WHERE team_id = :team AND source = 'status'") { it.getLong(1) }
        client.tool(roverToken, "set_status", args("update_id" to update, "status" to "in_progress"))
        assertEquals(lines, app.db.one("SELECT count(*) FROM messages WHERE team_id = :team AND source = 'status'") { it.getLong(1) })
    }

    @Test
    fun `moving unassigned work leaves no line and a refused assignee leaves no card`() = withTeam { client ->
        val post = setUp(client)
        client.json(ownerKey, "POST", "/api/cards/message/$post/status", """{"status":"done"}""")
        assertEquals(0L, app.db.one("SELECT count(*) FROM messages WHERE team_id = :team AND source IS NOT NULL") { it.getLong(1) })
        // Rin can't read a private channel: the post is refused before it is made.
        client.tool(roverToken, "create_project", args("name" to "Vault", "slug" to "vault"))
        client.json(ownerKey, "PUT", "/api/projects/vault/access", """{"private":true}""")
        client.json(ownerKey, "PUT", "/api/projects/vault/access/agent/${rover.id}", """{"level":"post"}""")
        val updates = app.db.one("SELECT count(*) FROM updates WHERE team_id = :team") { it.getLong(1) }
        val refused = client.tool(roverToken, "post_update", args("channel" to "vault", "body" to "Add the DNS record", "assignees" to listOf("Rin")))
        assertEquals("forbidden", refused.error(), refused.toString())
        assertEquals(updates, app.db.one("SELECT count(*) FROM updates WHERE team_id = :team") { it.getLong(1) })
    }

    @Test
    fun `the board filters by assigned to me unassigned and one assignee`() = withTeam { client ->
        setUp(client)
        val ids = (1..3).map { n ->
            client.json(ownerKey, "POST", "/api/messages", """{"project":"web","body":"Card $n"}""", status = 201)
            latestMessage().also { client.json(ownerKey, "POST", "/api/cards/message/$it/status", """{"status":"todo"}""") }
        }
        client.json(ownerKey, "POST", "/api/cards/message/${ids[0]}/assignees", """{"assignees":["me"]}""")
        client.json(ownerKey, "POST", "/api/cards/message/${ids[1]}/assignees", """{"assignees":["rover","Sam"]}""")
        suspend fun titles(query: String, key: String = ownerKey) =
            client.json(key, "GET", "/api/board$query")["columns"]!!.jsonArray.first().jsonObject["cards"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }
        assertEquals(listOf("Card 1"), titles("?assigned=me"))
        assertEquals(listOf("Card 3"), titles("?assigned=none"))
        assertEquals(listOf("Card 2"), titles("?assigned=rover"))
        assertEquals(listOf("Card 2"), titles("?assigned=me", samKey))
        assertEquals(listOf("Card 2"), titles("?assigned=agent:${rover.id}"))
        // A revoked agent no longer counts: the card shows only Sam.
        app.agents.revoke(rover.id)
        val card = client.json(samKey, "GET", "/api/board?assigned=me")
        assertEquals(listOf("Sam"), names(card["columns"]!!.jsonArray.first().jsonObject["cards"]!!.jsonArray.single().jsonObject["assignees"]))
    }
}
