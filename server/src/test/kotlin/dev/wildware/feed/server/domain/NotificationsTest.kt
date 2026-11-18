package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.events.AppEvent
import dev.wildware.feed.server.events.Bus
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NotificationsTest {
    private val now = 1_763_474_000_000L
    private val db = Database.memory()
    private val bus = Bus(clock = { now })
    private val notifications = Notifications(db, bus) { now }
    private val events = mutableListOf<AppEvent>()

    init {
        notifications.startRecorder()
        bus.subscribe { events += it }
        db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'web', 'Web', 1, 1)")
        db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'claude', 'h1', 1)")
    }

    @AfterTest fun close() = db.close()

    private fun rows() = db.query("SELECT kind, title, body, update_id, message_id, request_id FROM notifications ORDER BY seq") {
        listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4) ?: it.getString(5) ?: it.getString(6))
    }

    @Test
    fun `a request rings the bell once, after the event that caused it`() {
        val requests = Requests(db, bus, Reads(db) { now }, { now })
        val request = requests.create("A1", NewRequest(kind = "confirm", question = "Ship\n\n  it?", project = "web")).first
        assertEquals(listOf(listOf("request", "claude is waiting on you", "Ship it?", request.id)), rows())
        assertEquals(listOf("request.created", "notification.created"), events.map { it.type })
        val id = db.one("SELECT id FROM notifications") { it.getString(1) }
        assertEquals("""{"notificationId":"$id","kind":"request","projectId":"P1"}""", Wire.encode(events[1].payload))

        bus.publish("request.created", buildJsonObject { put("requestId", request.id) })
        assertEquals(1, rows().size, "one row per target")
    }

    @Test
    fun `updates and agent messages ring it, the owner's own words do not`() {
        db.update("INSERT INTO updates (id, project_id, agent_id, title, body, created_at) VALUES ('U1', 'P1', 'A1', NULL, 'did a thing', 1)")
        db.update("INSERT INTO updates (id, project_id, agent_id, title, body, created_at) VALUES ('U2', 'P1', 'A1', 'Titled', 'b', 1)")
        db.update("INSERT INTO messages (id, project_id, update_id, author, body, created_at) VALUES ('M1', 'P1', 'U1', 'human', 'hi', 1)")
        db.update("INSERT INTO messages (id, project_id, update_id, author, body, created_at) VALUES ('M2', 'P1', 'U1', 'agent:A1', 'answer', 1)")
        db.update("INSERT INTO messages (id, project_id, update_id, author, body, created_at) VALUES ('M3', 'P1', 'U2', 'agent:A1', 'note', 1)")
        db.update("INSERT INTO messages (id, project_id, author, body, created_at, reply_to) VALUES ('M4', 'P1', 'agent:A1', 'r', 1, 'M1')")
        for (u in listOf("U1", "U2")) bus.publish("update.created", buildJsonObject { put("updateId", u) })
        for (m in listOf("M1", "M2", "M3", "M4")) bus.publish("message.created", buildJsonObject { put("messageId", m) })
        assertEquals(
            listOf(
                listOf("update", "claude posted an update", "did a thing", "U1"),
                listOf("update", "Titled", "b", "U2"),
                listOf("reply", "claude replied to you", "answer", "M2"),
                listOf("reply", "claude commented", "note", "M3"),
                listOf("reply", "claude replied to you", "r", "M4"),
            ),
            rows(),
        )
    }

    @Test
    fun `first line collapses JS whitespace and cuts at 140`() {
        assertEquals("a b c", Notifications.firstLine(" a   b\tc﻿"))
        val long = "x".repeat(139) + " yz"
        assertEquals("x".repeat(139) + "…", Notifications.firstLine(long))
    }

    @Test
    fun `marking seen counts what changed and says so only then`() {
        listOf("U1", "U2").forEach {
            db.update("INSERT INTO updates (id, project_id, agent_id, body, created_at) VALUES (?, 'P1', 'A1', 'b', 1)", it)
            bus.publish("update.created", buildJsonObject { put("updateId", it) })
        }
        val first = db.one("SELECT id FROM notifications ORDER BY seq LIMIT 1") { it.getString(1) }!!
        events.clear()
        assertEquals(0, notifications.markSeen(emptyList()))
        assertEquals(1, notifications.markSeen(listOf(first, "nope")))
        assertEquals(1L, notifications.unseen())
        assertEquals(1, notifications.markSeen(null))
        assertEquals(listOf("""{"count":1}""", """{"count":1}"""), events.map { Wire.encode(it.payload) })
    }

    @Test
    fun `push preferences are checked and deduped`() {
        val prefs = buildJsonObject {
            put("priorities", JsonArray(listOf(JsonPrimitive("low"))))
            put("types", JsonArray(listOf("update", "request", "update").map(::JsonPrimitive)))
        }
        assertEquals("""{"types":["update","request"],"priorities":["low"]}""", Wire.encode(PushSubscriptions.checkPrefs(prefs)!!))
        assertNull(PushSubscriptions.checkPrefs(JsonNull))
        assertNull(PushSubscriptions.checkPrefs(buildJsonObject { }))
        fun refused(message: String, block: () -> Unit) = assertEquals(message, assertFailsWith<DomainError> { block() }.message)
        refused("preferences must be an object, or null for the default") { PushSubscriptions.checkPrefs(JsonArray(emptyList())) }
        refused("levels must be a list") { PushSubscriptions.checkPrefs(buildJsonObject { put("levels", "info") }) }
        refused("types must be any of: request, update, message, comment") {
            PushSubscriptions.checkPrefs(buildJsonObject { put("types", JsonArray(listOf(JsonPrimitive("bogus")))) })
        }
    }

    @Test
    fun `push subscriptions upsert on the endpoint and keep their prefs`() {
        val push = PushSubscriptions(db) { now }
        fun refused(message: String, block: () -> Unit) = assertEquals(message, assertFailsWith<DomainError> { block() }.message)
        refused("endpoint is required") { push.subscribe("  ", "k", "a", null) }
        refused("endpoint must be an absolute URL") { push.subscribe("nowhere", "k", "a", null) }
        refused("endpoint must be an https URL") { push.subscribe("http://x.example/p", "k", "a", null) }
        refused("endpoint must be an absolute URL") { push.subscribe("https://", "k", "a", null) }
        refused("keys.p256dh and keys.auth are required") { push.subscribe("https://x.example/p", " ", "a", null) }
        assertEquals("https://x.example/p", push.subscribe(" https://x.example/p ", "k", "a", "Firefox"))
        push.setPrefs("https://x.example/p", buildJsonObject { put("types", JsonArray(listOf(JsonPrimitive("request")))) })
        push.subscribe("https://x.example/p", "k2", "a2", null)
        assertEquals(
            listOf("k2", null, """{"types":["request"]}"""),
            db.one("SELECT p256dh, label, prefs FROM push_subscriptions") { listOf(it.getString(1), it.getString(2), it.getString(3)) },
        )
        refused("no such subscription: https://y") { push.setPrefs("https://y", null) }
        assertEquals(true, push.unsubscribe(" https://x.example/p"))
        assertEquals(false, push.unsubscribe("https://x.example/p"))
    }
}
