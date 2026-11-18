package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReadsTest {
    private val now = 1_763_474_000_000L
    private val db = Database.memory()
    private val reads = Reads(db) { now }

    @AfterTest fun close() = db.close()

    private fun project(id: String, slug: String, name: String = slug, pinned: Int = 0, status: String = "active") =
        db.update("INSERT INTO projects (id, slug, name, status, pinned, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 1, 1)", id, slug, name, status, pinned)

    private fun agent(id: String, name: String) =
        db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES (?, ?, ?, 1)", id, name, "hash-$id")

    private fun update(id: String, project: String = "P1") =
        db.update("INSERT INTO updates (id, project_id, agent_id, body, created_at) VALUES (?, ?, 'A1', 'b', 1)", id, project)

    private fun message(id: String, replyTo: String? = null, deleted: Boolean = false, project: String? = "P1") = db.update(
        "INSERT INTO messages (id, project_id, author, body, created_at, reply_to, deleted_at) VALUES (?, ?, 'human', 'm', 1, ?, ?)",
        id, project, replyTo, if (deleted) 5L else null,
    )

    private fun media(id: String, kind: String = "image", mime: String = "image/png", status: String = "ready", sha: String = "abc", update: String = "U1") = db.update(
        "INSERT INTO media (id, author, update_id, kind, mime, bytes, sha256, status, created_at) VALUES (?, 'human', ?, ?, ?, 10, ?, ?, 1)",
        id, update, kind, mime, sha, status,
    )

    private fun derivative(media: String, kind: String, width: Long?) = db.update(
        "INSERT INTO derivatives (id, media_id, kind, path, bytes, width) VALUES (?, ?, ?, 'p', 1, ?)", "D$media$kind$width", media, kind, width,
    )

    private fun ids(list: List<JsonObject>) = list.map { it["id"]!!.jsonPrimitive.content }

    private fun setup() {
        project("P1", "web", "Web")
        agent("A1", "claude")
    }

    @Test
    fun `a reference that looks like an id tries the id first, anything else the slug first`() {
        project("01ARZ3NDEKTSV4RRFFQ69G5FAV", "first")
        project("P2", "01arz3ndektsv4rrffq69g5fav")
        project("P3", "web")
        assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAV", reads.resolveProject("01ARZ3NDEKTSV4RRFFQ69G5FAV").id)
        assertEquals("P3", reads.resolveProject("  WEB ").id)
        assertEquals("P3", reads.resolveProject("P3").id)
        assertEquals("project is required", assertFailsWith<DomainError> { reads.resolveProject(" ") }.message)
        assertEquals("no such project: nope", assertFailsWith<DomainError> { reads.resolveProject(" nope ") }.message)
    }

    @Test
    fun `variants follow derivativesFor`() {
        setup()
        update("U1")
        media("M1")
        derivative("M1", "thumb", 1600)
        derivative("M1", "thumb", 640)
        derivative("M1", "thumb", 320)
        media("M2", status = "failed")
        derivative("M2", "thumb", 640)
        media("M3", kind = "file", mime = "application/zip")
        media("M4", kind = "file", mime = "application/zip", sha = "")
        media("M5", status = "pending", sha = "")
        media("M6", kind = "video", mime = "video/quicktime")
        derivative("M6", "poster", 1080)
        derivative("M6", "mp4", 1920)
        media("M7", mime = "image/apng")
        val variants = reads.mediaFor("update_id", "U1").associate { m ->
            m["id"]!!.jsonPrimitive.content to m["variants"]!!.jsonArray.map { it.jsonPrimitive.content }
        }
        assertEquals(
            mapOf(
                "M1" to listOf("original", "thumb-640", "thumb-1600"), "M2" to emptyList(), "M3" to listOf("original"),
                "M4" to emptyList(), "M5" to emptyList(), "M6" to listOf("original", "video", "poster"), "M7" to emptyList(),
            ),
            variants,
        )
        assertEquals(
            """{"id":"M1","updateId":"U1","messageId":null,"requestId":null,"taskId":null,"kind":"image","mime":"image/png",""" +
                """"status":"ready","bytes":10,"width":null,"height":null,"durationMs":null,"filename":null,"variants":["original","thumb-640","thumb-1600"]}""",
            Wire.encode(reads.mediaFor("update_id", "U1").first()),
        )
    }

    @Test
    fun `a thread keeps the newest live messages, oldest first, and puts back the posts they hang off`() {
        setup()
        message("M1")
        message("M2", replyTo = "M1")
        message("M3")
        message("M4", deleted = true)
        message("M5", replyTo = "M1")
        message("M6", project = null)
        assertEquals(listOf("M1", "M2", "M3", "M5", "M6"), ids(reads.listThread(Reads.ThreadQuery())))
        assertEquals(listOf("M1", "M5", "M6"), ids(reads.listThread(Reads.ThreadQuery(limit = 2.0))))
        assertEquals(listOf("M1", "M2", "M3", "M5"), ids(reads.listThread(Reads.ThreadQuery(project = "web"))))
        // `slice(-(limit - 1))`: limit 1 keeps every reply, limit 2 the newest one.
        assertEquals(listOf("M1", "M2", "M5"), ids(reads.listThread(Reads.ThreadQuery(replyTo = "M1", limit = 1.0))))
        assertEquals(listOf("M1", "M5"), ids(reads.listThread(Reads.ThreadQuery(replyTo = "M1", limit = 2.0))))
        assertEquals(emptyList(), reads.listThread(Reads.ThreadQuery(replyTo = "M4")))
        for (bad in listOf(0.0, Double.POSITIVE_INFINITY)) {
            assertEquals("limit must be a positive integer", assertFailsWith<DomainError> { reads.listThread(Reads.ThreadQuery(limit = bad)) }.message)
        }
        assertEquals(5, reads.listThread(Reads.ThreadQuery(limit = 1e30)).size)
    }

    @Test
    fun `an update page checks its cursor before its project, and carries no share (share links are not ported)`() {
        setup()
        (1..3).forEach { update("U$it") }
        db.update("INSERT INTO update_shares (id, update_id, token_hash, created_at, revoked_at, views) VALUES ('S1', 'U3', 'h1', 7, 8, 1)")
        db.update("INSERT INTO update_shares (id, update_id, token_hash, created_at, views) VALUES ('S2', 'U3', 'h2', 9, 4)")
        val page = reads.updatesPage(null, 2, null)
        assertEquals(listOf("U3", "U2"), page.ids)
        assertEquals("2", page.json["nextCursor"]!!.jsonPrimitive.content)
        assertEquals(null, page.json["items"]!!.jsonArray[0].jsonObject["share"], "a live share row from the TS server is ignored")
        assertEquals(null, page.json["items"]!!.jsonArray[1].jsonObject["share"])
        assertEquals(listOf("U1"), reads.updatesPage("web", 50, "2").ids)
        assertEquals(emptyList(), reads.updatesPage(null, 50, "99999999999999999999999").ids.drop(3))
        assertEquals(
            "cursor is not one this server issued",
            assertFailsWith<DomainError> { reads.updatesPage("nope", 50, "x") }.message,
        )
    }

    @Test
    fun `a request reads its settled states as answered, and a legacy row as a confirm`() {
        setup()
        fun approval(id: String, state: String, answer: String?, config: String? = null, expires: Long = now + 1000) = db.update(
            "INSERT INTO approvals (id, agent_id, project_id, question, state, expires_at, kind, config, answer) VALUES (?, 'A1', 'P1', 'Q?', ?, ?, 'confirm', ?, ?)",
            id, state, expires, config, answer,
        )
        approval("R1", "approved", null)
        approval("R2", "rejected", """{"kind":"confirm","value":false}""")
        approval("R3", "pending", null, """{"default":"true","min":1.0}""")
        approval("R4", "pending", null, expires = now)
        val rows = db.query("SELECT ${Rows.APPROVAL} FROM approvals ORDER BY seq") { Rows.request(it) }
        assertEquals(listOf("answered", "answered", "pending", "pending"), rows.map { it["state"]!!.jsonPrimitive.content })
        assertEquals("""{"kind":"confirm","value":true}""", Wire.encode(rows[0]["answer"]!!))
        assertEquals("""{"default":"true","min":1}""", Wire.encode(rows[2]["config"]!!))
        assertEquals(listOf("R3"), ids(reads.pendingRequests()))
    }

    @Test
    fun `the summary sorts pinned projects first, then by localeCompare`() {
        setup()
        project("P2", "zeta", "Zeta")
        project("P3", "alpha", "alpha")
        project("P4", "a-b", "a-b")
        project("P5", "ab", "ab")
        project("P6", "pin", "Pinned", pinned = 1)
        project("P7", "old", "Old", status = "archived")
        db.update("INSERT INTO approvals (id, agent_id, question, expires_at, kind, options) VALUES ('R1', 'A1', 'Q?', ?, 'choice', '[]')", now + 1)
        db.update("UPDATE agents SET id = id WHERE 0")
        val summary = reads.summary(Wire::isoOf)
        assertEquals(
            listOf("pin", "a-b", "ab", "alpha", "web", "zeta"),
            summary["projects"]!!.jsonArray.map { it.jsonObject["slug"]!!.jsonPrimitive.content },
        )
        assertEquals(
            """{"id":"R1","agentId":"A1","agentName":"claude","projectSlug":null,"projectName":null,"question":"Q?","detail":null,""" +
                """"default":null,"kind":"choice","options":null,"expiresAt":"2025-11-18T13:53:20.001Z"}""",
            Wire.encode(summary["requests"]!!.jsonArray.single()),
        )
    }

    @Test
    fun `live agents are one row per agent, newest beat first, with clean meta`() {
        setup()
        agent("A2", "scout")
        fun session(id: String, agent: String, beat: Long, meta: String?, ended: Long? = null) = db.update(
            "INSERT INTO sessions (id, agent_id, started_at, last_heartbeat_at, ended_at, meta) VALUES (?, ?, 1, ?, ?, ?)", id, agent, beat, ended, meta,
        )
        session("S1", "A1", now - 1000, """{"host":"box","cwd":"","model":5}""")
        session("S2", "A2", now - 500, null)
        session("S3", "A1", now - 2000, null)
        session("S4", "A2", now - 100_000, null)
        session("S5", "A2", now, null, ended = now)
        assertEquals(
            """[{"agentId":"A2","name":"scout","sessionId":"S2","startedAt":1,"lastHeartbeatAt":${now - 500},"sessions":1,"host":null,"cwd":null,"model":null},""" +
                """{"agentId":"A1","name":"claude","sessionId":"S1","startedAt":1,"lastHeartbeatAt":${now - 1000},"sessions":2,"host":"box","cwd":null,"model":null}]""",
            Wire.encode(JsonArray(reads.liveAgents())),
        )
    }

    @Test
    fun `a notification names its project and the path it opens`() {
        setup()
        update("U1")
        db.update("INSERT INTO notifications (id, kind, project_id, update_id, title, body, created_at) VALUES ('N1', 'update', 'P1', 'U1', 't', 'b', 1)")
        db.update("INSERT INTO notifications (id, kind, title, body, created_at, seen_at) VALUES ('N2', 'reply', 't', 'b', 2, 3)")
        val list = reads.notifications(unseenOnly = false, limit = 50)
        assertEquals(listOf("N2", "N1"), ids(list))
        assertEquals(listOf("/", "/projects/web?focus=U1"), list.map { it["path"]!!.jsonPrimitive.content })
        assertEquals(listOf("N1"), ids(reads.notifications(unseenOnly = true, limit = 50)))
        assertEquals(1, reads.unseenNotifications())
        assertEquals(JsonPrimitive("Web"), list[1]["projectName"])
    }
}
