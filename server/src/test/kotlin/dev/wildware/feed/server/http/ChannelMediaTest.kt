package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.NewRequest
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.Updates
import dev.wildware.feed.server.domain.feed
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A channel's Media tab: `GET /api/projects/{reference}/media` lists the pictures, videos and files
 * on one channel's cards, newest first, by kind and a page at a time; deleted cards, other channels
 * and other teams stay out, and a channel you can't read is not_found.
 *
 * Runs on SQLite, and on Postgres when PG_TEST_URL names one ([Database.memory]).
 */
class ChannelMediaTest {
    private fun config(dir: String) = Config(
        dataDir = dir, adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    /** The founder, a member (Sam), an agent (rover), and two channels: ops and web. */
    private class Team(val app: App) {
        val owner: People.Person = app.people.find(People.FOUNDER)!!
        val ownerKey = app.devices.mint("Owner phone").second
        val sam: People.Person
        val samKey: String
        val rover: Agents.Agent

        init {
            val (_, token) = app.people.invite(owner, People.Role.MEMBER, "sam@example.test")
            sam = app.people.accept(token, "Sam", "sam@example.test", "password for Sam")
            samKey = app.devices.mint("Sam phone", sam.id).second
            rover = app.agents.mint("rover").first
            app.feed.projects.create("Ops", null, null)
            app.feed.projects.create("Web", null, null)
        }

        /** A ready file of [kind] on the card in [anchor]: inserted as an upload attached there would be. */
        fun media(kind: String, anchor: String, cardId: String, name: String, agentId: String? = null, team: String? = null): String {
            val id = Ulid.next()
            val mime = when (kind) { "image" -> "image/png"; "video" -> "video/mp4"; "audio" -> "audio/mpeg"; else -> "application/pdf" }
            val sql = "INSERT INTO media (team_id, id, agent_id, author, $anchor, kind, mime, bytes, sha256, filename, status, created_at) " +
                "VALUES (:team, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ready', ?)"
            val args = arrayOf<Any?>(id, agentId, if (agentId != null) "agent:$agentId" else "human", cardId, kind, mime, 2048L, "f".repeat(64), name, app.clock())
            if (team == null) app.db.update(sql, *args) else Tenant.with(team) { app.db.update(sql, *args) }
            return id
        }
    }

    private fun withTeam(block: suspend Team.(HttpClient) -> Unit) {
        val dir = Files.createTempDirectory("channel-media").toFile()
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

    private suspend fun HttpClient.page(key: String, path: String, status: Int = 200): JsonObject {
        val r = get(path) { header("Authorization", "Bearer $key") }
        val text = r.bodyAsText()
        assertEquals(status, r.status.value, "GET $path: $text")
        return Wire.json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.names(): List<String> =
        this["items"]!!.jsonArray.map { it.jsonObject["media"]!!.jsonObject["filename"]!!.jsonPrimitive.content }

    @Test
    fun `lists one channel's pictures, videos and files newest first, by kind`() = withTeam { client ->
        val update = app.feed.updates.post(Updates.Post(project = "ops", agentId = rover.id, body = "shipped"))
        val post = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "a post", project = "ops", userId = sam.id))
        val reply = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "a reply", updateId = update.id))
        val request = app.requests.create(rover.id, NewRequest(kind = "confirm", question = "Ship it?", project = "ops")).first
        val requestId = request.json["id"]!!.jsonPrimitive.content
        val elsewhere = app.feed.updates.post(Updates.Post(project = "web", agentId = rover.id, body = "other channel"))

        media("image", "update_id", update.id, "shot.png", agentId = rover.id)
        media("video", "message_id", post.id, "clip.mp4")
        media("file", "message_id", reply.id, "notes.pdf")
        media("audio", "request_id", requestId, "memo.mp3", agentId = rover.id)
        media("image", "update_id", elsewhere.id, "web.png", agentId = rover.id)
        // Another team's file, on this team's update id: never this team's.
        media("image", "update_id", update.id, "theirs.png", team = "bravo")

        val all = client.page(ownerKey, "/api/projects/ops/media")
        assertEquals(listOf("memo.mp3", "notes.pdf", "clip.mp4", "shot.png"), all.names())
        assertEquals(JsonNull, all["nextCursor"])
        val first = all["items"]!!.jsonArray.last().jsonObject
        assertEquals("update", first["card"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals(update.id, first["card"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("rover", first["sentBy"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Sam", all["items"]!!.jsonArray[2].jsonObject["sentBy"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(first["media"]!!.jsonObject.containsKey("variants"), "media reads as a card's media does")

        assertEquals(listOf("shot.png"), client.page(ownerKey, "/api/projects/ops/media?kind=image").names())
        assertEquals(listOf("clip.mp4"), client.page(ownerKey, "/api/projects/ops/media?kind=video").names())
        assertEquals(listOf("memo.mp3", "notes.pdf"), client.page(ownerKey, "/api/projects/ops/media?kind=file").names(), "files hold sounds too")
        assertEquals(listOf("web.png"), client.page(ownerKey, "/api/projects/web/media").names())
        assertEquals("invalid_argument", client.page(ownerKey, "/api/projects/ops/media?kind=gif", status = 400)["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pages with a cursor until there is no more`() = withTeam { client ->
        val update = app.feed.updates.post(Updates.Post(project = "ops", agentId = rover.id, body = "lots"))
        repeat(5) { media("image", "update_id", update.id, "p$it.png", agentId = rover.id) }

        val one = client.page(ownerKey, "/api/projects/ops/media?limit=2")
        assertEquals(listOf("p4.png", "p3.png"), one.names())
        val cursor = one["nextCursor"]!!.jsonPrimitive.content
        val two = client.page(ownerKey, "/api/projects/ops/media?limit=2&cursor=$cursor")
        assertEquals(listOf("p2.png", "p1.png"), two.names())
        val three = client.page(ownerKey, "/api/projects/ops/media?limit=2&cursor=${two["nextCursor"]!!.jsonPrimitive.content}")
        assertEquals(listOf("p0.png"), three.names())
        assertEquals(JsonNull, three["nextCursor"])
        assertEquals(400, client.get("/api/projects/ops/media?cursor=nope") { header("Authorization", "Bearer $ownerKey") }.status.value)
    }

    @Test
    fun `a deleted card takes its files out of the tab`() = withTeam { client ->
        val update = app.feed.updates.post(Updates.Post(project = "ops", agentId = rover.id, body = "soon gone"))
        val reply = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "reply", updateId = update.id))
        val post = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "a post", project = "ops"))
        val kept = app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = "kept", project = "ops"))
        media("image", "update_id", update.id, "on-update.png", agentId = rover.id)
        media("file", "message_id", reply.id, "in-its-thread.pdf")
        media("file", "message_id", post.id, "on-post.pdf")
        media("file", "message_id", kept.id, "kept.pdf")
        assertEquals(4, client.page(ownerKey, "/api/projects/ops/media").names().size)

        app.feed.updates.delete(update.id)
        app.feed.messages.delete(post.id, Messages.HUMAN)
        assertEquals(listOf("kept.pdf"), client.page(ownerKey, "/api/projects/ops/media").names())
    }

    @Test
    fun `a channel you can't read has no media to show`() = withTeam { client ->
        val update = app.feed.updates.post(Updates.Post(project = "ops", agentId = rover.id, body = "private work"))
        media("image", "update_id", update.id, "secret.png", agentId = rover.id)
        assertEquals(listOf("secret.png"), client.page(samKey, "/api/projects/ops/media").names())

        val made = client.put("/api/projects/ops/access") {
            header("Authorization", "Bearer $ownerKey")
            header("Content-Type", "application/json")
            setBody("""{"private":true}""")
        }
        assertEquals(200, made.status.value, made.bodyAsText())
        assertEquals("not_found", client.page(samKey, "/api/projects/ops/media", status = 404)["error"]!!.jsonPrimitive.content)
        assertEquals("not_found", client.page(samKey, "/api/projects/nowhere/media", status = 404)["error"]!!.jsonPrimitive.content)
        assertEquals(listOf("secret.png"), client.page(ownerKey, "/api/projects/ops/media").names(), "the owner still reads it")
    }
}
