package dev.wildware.feed.server.media

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.events.AppEvent
import dev.wildware.feed.server.events.Bus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Media retention: the owner's setting, the nightly removal, what is exempt, and how removed media reads. */
class RetentionTest {
    @TempDir
    lateinit var dir: Path

    private val day = 86_400_000L
    private var now = 1_800_000_000_000L
    private val db = Database.memory()
    private val bus = Bus(clock = { now })
    private val events = mutableListOf<AppEvent>()
    private val unsubscribe = bus.subscribe { events += it }
    private val paths by lazy { MediaPaths(dir.toString()) }
    private val retention by lazy { Retention(db, bus, paths) { now } }
    private var n = 0

    @AfterTest
    fun close() {
        unsubscribe()
        db.close()
    }

    init {
        db.update("INSERT INTO projects (id, slug, name, status, created_at, updated_at) VALUES ('P1', 'p', 'P', 'active', 1, 1)")
        db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'rover', 'h', 1)")
    }

    private fun id(): String = "01J0" + (++n).toString().padStart(22, 'X').replace('X', 'A')

    /** A card of [kind] and one medium on it, [age] old, with an original and a thumbnail on disk. */
    private fun mediaOn(kind: String = "update", mediaKind: String = "image", age: Long = 30 * day, pinned: Boolean = false): String {
        val card = id()
        when (kind) {
            "update" -> db.update("INSERT INTO updates (id, project_id, agent_id, body, created_at, pinned) VALUES (?, 'P1', 'A1', 'b', 1, ?)", card, pinned)
            "message" -> db.update("INSERT INTO messages (id, project_id, author, body, created_at, pinned) VALUES (?, 'P1', 'human', 'b', 1, ?)", card, pinned)
            "request" -> db.update("INSERT INTO approvals (id, agent_id, question, expires_at, state, pinned) VALUES (?, 'A1', 'Q?', ?, 'approved', ?)", card, now + day, pinned)
            "task" -> db.update("INSERT INTO tasks (id, project_id, title, created_at, pinned) VALUES (?, 'P1', 't', 1, ?)", card, pinned)
        }
        val column = mapOf("update" to "update_id", "message" to "message_id", "request" to "request_id", "task" to "task_id").getValue(kind)
        val media = id()
        val mime = mapOf("image" to "image/png", "video" to "video/mp4", "audio" to "audio/mpeg", "file" to "application/pdf").getValue(mediaKind)
        db.update(
            "INSERT INTO media (id, author, $column, kind, mime, bytes, sha256, status, created_at) VALUES (?, 'agent:A1', ?, ?, ?, 3, 'abc', 'ready', ?)",
            media, card, mediaKind, mime, now - age,
        )
        Files.createDirectories(paths.dir(media))
        Files.writeString(paths.original(media, mime), "abc")
        if (mediaKind == "image") {
            Files.writeString(paths.dir(media).resolve("thumb-640.webp"), "thumb")
            db.update("INSERT INTO derivatives (id, media_id, kind, path, bytes, width, height) VALUES (?, ?, 'thumb', ?, 5, 640, 1)", id(), media, paths.relative(media, "thumb-640.webp"))
        }
        return media
    }

    private fun cardOf(media: String) = db.one("SELECT coalesce(update_id, message_id, request_id, task_id) FROM media WHERE id = ?", media) { it.getString(1) }!!
    private fun removed(media: String) = db.one("SELECT removed_at FROM media WHERE id = ?", media) { it.getObject(1) } != null
    private fun set(days: Int?, vararg kinds: String) = retention.set(
        buildJsonObject {
            put("days", days?.let(::JsonPrimitive) ?: JsonNull)
            putJsonArray("kinds") { kinds.forEach { add(JsonPrimitive(it)) } }
        },
    )

    @Test
    fun `it is off until the owner turns it on, and then covers the kinds they chose`() {
        assertEquals(Retention.Settings(null, Retention.KINDS), retention.settings())
        val old = mediaOn()
        assertEquals(0, retention.run())
        assertFalse(removed(old))
        assertEquals(Retention.Settings(14, listOf("image", "video")), set(14, "video", "image", "image"))
        assertEquals(Retention.Settings(14, listOf("image", "video")), Retention(db, bus, paths) { now }.settings(), "kept across restarts")
    }

    @Test
    fun `the setting refuses what it cannot keep`() {
        fun refused(body: kotlinx.serialization.json.JsonObject) = assertFailsWith<DomainError> { retention.set(body) }.message
        assertEquals("days must be a whole number of days from 1 to 3650, or null for never", refused(buildJsonObject { put("days", 0); putJsonArray("kinds") { add(JsonPrimitive("image")) } }))
        assertEquals("days must be a whole number of days from 1 to 3650, or null for never", refused(buildJsonObject { put("days", 1.5); putJsonArray("kinds") { add(JsonPrimitive("image")) } }))
        assertEquals("days must be a whole number of days from 1 to 3650, or null for never", refused(buildJsonObject { put("days", "14") }))
        assertEquals("kinds must be a list of: image, video, audio, file", refused(buildJsonObject { put("days", 14); put("kinds", "image") }))
        assertEquals("kinds must be a list of: image, video, audio, file", refused(buildJsonObject { put("days", 14); putJsonArray("kinds") { add(JsonPrimitive("gif")) } }))
        assertEquals("choose at least one kind of media", refused(buildJsonObject { put("days", 14); putJsonArray("kinds") {} }))
        assertEquals(Retention.Settings(null, Retention.KINDS), retention.settings())
        assertEquals(Retention.Settings(null, listOf("image")), set(null, "image"), "off still remembers the kinds")
    }

    @Test
    fun `old media of a chosen kind loses its files, its derivatives and its addresses, and says why`() {
        set(14, "image")
        val old = mediaOn(age = 15 * day)
        val young = mediaOn(age = 13 * day)
        val video = mediaOn(mediaKind = "video", age = 400 * day)
        assertEquals(1, retention.run())

        assertTrue(removed(old))
        assertFalse(Files.exists(paths.dir(old)), "original and thumbnail gone from the store")
        assertEquals(0, db.one("SELECT count(*) FROM derivatives WHERE media_id = ?", old) { it.getInt(1) })
        assertNull(Serve(db, paths).open(old, "original"))
        assertNull(Serve(db, paths).open(old, "thumb-640"))
        val view = dev.wildware.feed.server.domain.Reads(db).mediaFor("update_id", cardOf(old)).single()
        assertEquals(JsonArray(emptyList()), view["variants"])
        assertEquals(buildJsonObject { put("at", "2027-01-15T08:00:00.000Z"); put("afterDays", 14) }, view["removed"])
        val event = events.single { it.type == "media.removed" }
        assertEquals(old, event.payload["mediaId"]!!.jsonPrimitive.content)
        assertEquals(cardOf(old), event.payload["updateId"]!!.jsonPrimitive.content)

        assertFalse(removed(young))
        assertFalse(removed(video))
        assertFalse(dev.wildware.feed.server.domain.Reads(db).mediaFor("update_id", cardOf(young)).single().containsKey("removed"), "untouched media reads as before")
    }

    @Test
    fun `pinned cards, cards with an open request, and project logos keep their media`() {
        set(7, "image")
        val pinnedUpdate = mediaOn("update", pinned = true)
        val pinnedMessage = mediaOn("message", pinned = true)
        val pinnedTask = mediaOn("task", pinned = true)
        val pinnedRequest = mediaOn("request", pinned = true)
        val openRequest = mediaOn("request")
        db.update("UPDATE approvals SET state = 'pending' WHERE id = ?", cardOf(openRequest))
        val updateAsked = mediaOn("update")
        db.update("INSERT INTO approvals (id, agent_id, question, expires_at, state, update_id) VALUES ('R9', 'A1', 'Q?', ?, 'pending', ?)", now + day, cardOf(updateAsked))
        val messageAsked = mediaOn("message")
        db.update("INSERT INTO approvals (id, agent_id, question, expires_at, state, message_id) VALUES ('R8', 'A1', 'Q?', ?, 'pending', ?)", now + day, cardOf(messageAsked))
        val logo = mediaOn("update")
        db.update("UPDATE projects SET theme = ? WHERE id = 'P1'", """{"logoMediaId":"$logo"}""")
        val plain = mediaOn("message")

        assertEquals(1, retention.run())
        assertTrue(removed(plain))
        for (kept in listOf(pinnedUpdate, pinnedMessage, pinnedTask, pinnedRequest, openRequest, updateAsked, messageAsked, logo)) {
            assertFalse(removed(kept), "$kept should be kept")
            assertTrue(Files.exists(paths.dir(kept)))
        }
    }

    @Test
    fun `a second run, or a run after a crash between deleting and marking, changes nothing more`() {
        set(14, "image", "video", "audio", "file")
        val a = mediaOn()
        val b = mediaOn()
        paths.dir(b).toFile().deleteRecursively()
        assertEquals(2, retention.run(), "files already gone is not an error")
        assertEquals(0, retention.run())
        assertEquals(2, events.count { it.type == "media.removed" }, "each removal is announced once")
        assertTrue(removed(a) && removed(b))
    }

    @Test
    fun `it runs once a night, catches up after a long gap, and remembers its last run across restarts`() {
        set(14, "image")
        // 2027-01-15 is the day `now` falls on; 03:10 UTC is inside the night window, 08:00 is not.
        val night = 1_800_000_000_000L - 8 * 3_600_000L + 3 * 3_600_000L + 10 * 60_000L
        now = night
        assertTrue(retention.due())
        retention.tick()
        assertFalse(retention.due(), "just ran")
        now = night + 3_600_000L
        assertFalse(Retention(db, bus, paths) { now }.due(), "a restart does not run it again the same night")
        now = night + day
        assertTrue(retention.due(), "the next night")
        now = night + 2 * day + 5 * 3_600_000L
        assertTrue(Retention(db, bus, paths) { now }.due(), "missed nights are caught up outside the window")
        set(null, "image")
        assertFalse(retention.due(), "never when off")
    }

    @Test
    fun `an agent reading the message is told the media was removed, with no link to fetch it`() {
        val config = dev.wildware.feed.server.Config(
            dataDir = dir.resolve("app").toString(), adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
            publicBaseUrl = "https://dashboard.example.com", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
            apns = null, port = 0, host = "127.0.0.1", origin = "https://dashboard.example.com",
        )
        dev.wildware.feed.server.App(config, db, clock = { now }).use { app ->
            set(14, "image")
            val gone = mediaOn("message")
            app.retention.run()
            val result = dev.wildware.feed.server.mcp.Attachments.forMessages(app, listOf(cardOf(gone)))
            assertTrue(result.blocks.isEmpty())
            assertEquals("message ${cardOf(gone)}: not shown: $gone (image, removed after 14 days).", result.notes.single())
            val view = result.views.getValue(cardOf(gone)).single()
            assertEquals("removed after 14 days: the owner keeps media for 14 days", view["reason"]!!.jsonPrimitive.content)
            assertFalse(view.containsKey("download_url"))
        }
    }

    @Test
    fun `a new upload with the same bytes as removed media is stored again, not linked to nothing`() {
        set(14, "image")
        val old = mediaOn()
        retention.run()
        val twin = db.one("SELECT id, mime FROM media WHERE sha256 = 'abc' AND removed_at IS NULL ORDER BY seq DESC LIMIT 1") { it.getString(1) }
        assertNull(twin, "the dedupe lookup must not offer removed media")
        assertTrue(removed(old))
    }

    @Test
    fun `a big clean-up is done a batch a run, so the job thread is given back between them`() {
        set(7, "image")
        val all = List(201) { mediaOn(age = 30 * day) }
        assertEquals(200, retention.run(), "one batch")
        assertEquals(1, all.count { !removed(it) }, "the rest waits")
        assertTrue(retention.due(), "the next tick carries on")
        assertEquals(1, retention.run())
        assertTrue(all.all(::removed))
        assertFalse(retention.due(), "done until the next night")
    }
}
