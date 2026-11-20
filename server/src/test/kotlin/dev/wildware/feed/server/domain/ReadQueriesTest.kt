package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The snapshot, a messages page and the request list cost the same number of queries however many
 * cards, media, embeds and app views they hold. On Postgres each query is a network round trip, so
 * one query per card made a full snapshot take seconds.
 */
class ReadQueriesTest {
    private val db = Database.memory()
    private val reads = Reads(db) { 1_800_000_000_000L }
    private var n = 0
    private fun id(prefix: String) = prefix + (++n).toString().padStart(8, '0')

    @AfterTest
    fun close() = db.close()

    init {
        db.update("INSERT INTO projects (id, slug, name, status, created_at, updated_at) VALUES ('P1', 'p', 'P', 'active', 1, 1)")
        db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'rover', 'h', 1)")
        db.update("INSERT INTO apps (id, uri, name, html, csp, created_at, updated_at) VALUES ('AP1', 'app://x', 'X', '<p>', '', 1, 1)")
    }

    private val embedType: String by lazy { db.one("SELECT id FROM embed_types ORDER BY seq LIMIT 1") { it.getString(1) }!! }

    /** One of each thing a card can carry, on a card anchored by [column]. */
    private fun dress(column: String, card: String) {
        repeat(2) {
            val media = id("M")
            db.update(
                "INSERT INTO media (id, author, $column, kind, mime, bytes, sha256, status, created_at) VALUES (?, 'agent:A1', ?, 'image', 'image/png', 3, 'abc', 'ready', 1)",
                media, card,
            )
            db.update("INSERT INTO derivatives (id, media_id, kind, path, bytes, width, height) VALUES (?, ?, 'thumb', ?, 5, 640, 1)", id("D"), media, "x/$media/t.webp")
        }
        db.update("INSERT INTO embeds (id, type_id, $column, author, data, created_at) VALUES (?, ?, ?, 'agent:A1', '{}', 1)", id("E"), embedType, card)
        db.update("INSERT INTO app_views (id, app_id, $column, author, data, created_at, updated_at) VALUES (?, 'AP1', ?, 'agent:A1', '{}', 1, 1)", id("V"), card)
        db.update("INSERT INTO reactions (id, $column, actor, emoji, created_at) VALUES (?, ?, 'human', '👍', 1)", id("R"), card)
    }

    private fun seed(cards: Int) {
        repeat(cards) {
            val update = id("U")
            db.update("INSERT INTO updates (id, project_id, agent_id, body, created_at) VALUES (?, 'P1', 'A1', 'b', 1)", update)
            dress("update_id", update)
            val post = id("G")
            db.update("INSERT INTO messages (id, project_id, author, body, created_at) VALUES (?, 'P1', 'human', 'post', 1)", post)
            dress("message_id", post)
            val request = id("Q")
            db.update("INSERT INTO approvals (id, agent_id, question, expires_at, state) VALUES (?, 'A1', 'Q?', ?, 'pending')", request, 1_900_000_000_000L)
            dress("request_id", request)
        }
        // Replies whose posts fall outside a short window, so parents are fetched too.
        db.query("SELECT id FROM messages ORDER BY seq") { it.getString(1) }.forEach { post ->
            db.update("INSERT INTO messages (id, project_id, author, body, created_at, reply_to) VALUES (?, 'P1', 'agent:A1', 'reply', 1, ?)", id("G"), post)
        }
    }

    private fun queriesFor(block: () -> Unit): Long {
        val before = db.statements
        block()
        return db.statements - before
    }

    private fun snapshot() = queriesFor { reads.snapshot(Reads.SnapshotRequest(project = null, status = null, limit = 50, cursor = null)) }
    private fun messages() = queriesFor { reads.messagesPage(Reads.ThreadQuery(limit = 2.0)) }
    private fun requests() = queriesFor { reads.requestsSnapshot() }

    @Test
    fun `reads cost the same number of queries for 3 cards as for 30`() {
        seed(3)
        val few = Triple(snapshot(), messages(), requests())
        seed(27)
        val many = Triple(snapshot(), messages(), requests())
        assertEquals(few, many, "query counts (snapshot, messages, requests) grew with the number of cards")
        assertTrue(many.first <= SNAPSHOT_MAX, "the snapshot took ${many.first} queries")
        assertTrue(many.second <= MESSAGES_MAX, "a messages page took ${many.second} queries")
    }

    @Test
    fun `batched reads give the same answer as reading card by card`() {
        seed(5)
        val updates = db.query("SELECT id FROM updates ORDER BY seq") { it.getString(1) }
        val batched = reads.mediaForMany("update_id", updates)
        val embeds = reads.embedsForMany("update_id", updates)
        for (u in updates) {
            assertEquals(Wire.encode(kotlinx.serialization.json.JsonArray(reads.mediaFor("update_id", u))), Wire.encode(kotlinx.serialization.json.JsonArray(batched[u].orEmpty())))
            assertEquals(Wire.encode(kotlinx.serialization.json.JsonArray(reads.embedsFor("update_id", u))), Wire.encode(kotlinx.serialization.json.JsonArray(embeds[u].orEmpty())))
        }
        assertEquals(emptyMap<String, List<JsonObject>>(), reads.mediaForMany("update_id", emptyList()))
    }

    private companion object {
        // Blocks (migration 057) add a query for the updates' and one for the messages'.
        const val SNAPSHOT_MAX = 31L
        const val MESSAGES_MAX = 12L
    }
}
