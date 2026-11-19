package dev.wildware.feed.server.push

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When the server starts, updates and ends the Live Activity on a phone. */
class LiveActivityTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "https://dashboard.example.com", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "https://dashboard.example.com",
    )
    private var now = 1_700_000_000_000
    private val app = App(config, Database.memory(), clock = { now })
    private val sent = mutableListOf<ApnsRequest>()
    private val apns = Apns(app, Config.Apns("KEY123", "TEAM456", "/unused.p8", "dev.example.feed")) { sent += it; ApnsResponse(200, null) }
    private val activity = LiveActivity(app, apns, "dev.example.feed")

    @AfterTest
    fun close() = app.close()

    private fun phone(updateToken: String?, startToken: String?) {
        app.db.update(
            "INSERT INTO owner_devices (id, name, token_hash, created_at, apns_token, live_activity_token, push_to_start_token) VALUES ('D1', 'Phone', 'h', 1, 'aa', ?, ?)",
            updateToken, startToken,
        )
    }

    private fun agentOnline() {
        app.db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'Rover', 'x', 1)")
        app.db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'garden', 'Garden Game', 1, 1)")
        app.db.update("INSERT INTO sessions (id, agent_id, started_at, last_heartbeat_at) VALUES ('S1', 'A1', ?, ?)", now, now)
    }

    private fun startWork() {
        app.db.update(
            "INSERT INTO updates (id, project_id, agent_id, body, level, status, status_at, created_at) VALUES " +
                "('U1', 'P1', 'A1', 'Fixing fee rounding', 'info', 'in_progress', ?, 1)",
            now,
        )
    }

    private fun ask() {
        app.db.update(
            "INSERT INTO approvals (id, agent_id, project_id, question, options, state, expires_at, kind, config) VALUES " +
                "('R1', 'A1', 'P1', 'Ship it?', NULL, 'pending', ?, 'confirm', '{}')",
            now + 3_600_000,
        )
        // How the bus tells the pusher a request is new.
        activity.start().also { stop ->
            app.bus.publish("request.created", buildJsonObject { put("requestId", JsonPrimitive("R1")) })
            stop()
        }
    }

    private fun aps(request: ApnsRequest): JsonObject = Json.parseToJsonElement(request.payload).jsonObject["aps"]!!.jsonObject

    private fun events() = sent.map { aps(it)["event"]!!.jsonPrimitive.content }

    private fun updateToken() = app.db.one("SELECT live_activity_token FROM owner_devices WHERE id = 'D1'") { it.getString(1) }

    private fun state(waiting: Int, inProgress: Int, online: Int) = buildJsonObject {
        put("online", JsonPrimitive(online))
        put("waiting", JsonPrimitive(waiting))
        put("inProgress", JsonPrimitive(inProgress))
    }

    @Test
    fun `busy means a question waiting or agents online with work in progress`() {
        assertTrue(LiveActivity.busy(state(waiting = 1, inProgress = 0, online = 0)))
        assertTrue(LiveActivity.busy(state(waiting = 0, inProgress = 2, online = 1)))
        assertFalse(LiveActivity.busy(state(waiting = 0, inProgress = 2, online = 0)), "a card left in progress with every agent gone")
        assertFalse(LiveActivity.busy(state(waiting = 0, inProgress = 0, online = 3)), "agents online with nothing to do")
    }

    @Test
    fun `agents starting work push a phone with no activity one that says who is on what`() {
        phone(updateToken = null, startToken = "beef")
        agentOnline()
        activity.run()
        assertTrue(sent.isEmpty(), "nothing is busy yet")
        startWork()
        activity.run()
        val start = aps(sent.single())
        assertEquals("start", start["event"]!!.jsonPrimitive.content)
        assertEquals("beef", sent.single().deviceToken)
        assertEquals("Rover is working", start["alert"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("Fixing fee rounding", start["alert"]!!.jsonObject["body"]!!.jsonPrimitive.content)
        activity.run()
        assertEquals(1, sent.size, "work that carries on starts nothing more")
    }

    @Test
    fun `work already going when the server starts starts nothing`() {
        phone(updateToken = null, startToken = "beef")
        agentOnline()
        startWork()
        activity.run()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a new question still pushes a phone one`() {
        phone(updateToken = null, startToken = "beef")
        agentOnline()
        ask()
        activity.run()
        assertEquals(listOf("start"), events())
        assertEquals("Rover is waiting on you", aps(sent.single())["alert"]!!.jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an activity ends after ten quiet minutes and its token is forgotten`() {
        phone(updateToken = "cafe", startToken = "beef")
        agentOnline()
        startWork()
        activity.run()
        assertEquals(listOf("update"), events())
        app.db.update("UPDATE updates SET status = 'done' WHERE id = 'U1'")
        activity.run()
        assertEquals(listOf("update", "update"), events(), "idle, but not for long enough")
        // The agent stays online throughout, so only the work stopping counts.
        now += LiveActivity.IDLE_END_MS - 1
        app.db.update("UPDATE sessions SET last_heartbeat_at = ?", now)
        activity.run()
        assertEquals(2, sent.size, "the same state is not sent again")
        now += 1
        app.db.update("UPDATE sessions SET last_heartbeat_at = ?", now)
        activity.run()
        assertEquals(listOf("update", "update", "end"), events())
        val end = aps(sent.last())
        assertTrue(end["dismissal-date"]!!.jsonPrimitive.content.toLong() < end["timestamp"]!!.jsonPrimitive.content.toLong(), "dismissed at once")
        assertEquals("10", sent.last().headers.toMap()["apns-priority"], "the end goes out promptly")
        // A whole state the phone can decode, so iOS never draws the ending activity as an empty box.
        val final = end["content-state"]!!.jsonObject
        assertEquals(activity.state().keys, final.keys)
        assertEquals(0, final["waiting"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, final["online"]!!.jsonPrimitive.content.toInt())
        assertEquals("cafe", sent.last().deviceToken)
        assertNull(updateToken())
        activity.run()
        assertEquals(3, sent.size, "an ended activity is not pushed again")
    }

    @Test
    fun `the all-clear state keeps every key and drops the question and the work`() {
        agentOnline()
        startWork()
        ask()
        val state = activity.state()
        assertEquals("Ship it?", state["topQuestion"]!!.jsonPrimitive.content)
        val clear = LiveActivity.allClear(state)
        assertEquals(state.keys, clear.keys)
        assertEquals(0, clear["waiting"]!!.jsonPrimitive.content.toInt())
        assertEquals(state["online"], clear["online"])
        assertEquals(state["inProgress"], clear["inProgress"])
        for (key in listOf("topAgent", "topQuestion", "topRequestId", "topKind", "topExpiresAt", "working")) {
            assertEquals(JsonNull, clear[key], key)
        }
    }

    @Test
    fun `work coming back before ten minutes keeps the activity`() {
        phone(updateToken = "cafe", startToken = "beef")
        agentOnline()
        startWork()
        activity.run()
        app.db.update("UPDATE updates SET status = 'done' WHERE id = 'U1'")
        now += 5 * 60 * 1000
        app.db.update("UPDATE sessions SET last_heartbeat_at = ?", now)
        activity.run()
        app.db.update("UPDATE updates SET status = 'in_progress' WHERE id = 'U1'")
        now += 6 * 60 * 1000
        app.db.update("UPDATE sessions SET last_heartbeat_at = ?", now)
        activity.run()
        assertFalse("end" in events())
        assertEquals("cafe", updateToken())
    }
}
