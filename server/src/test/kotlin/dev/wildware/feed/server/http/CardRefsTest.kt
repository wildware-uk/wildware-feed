package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.CardRef
import dev.wildware.feed.server.domain.CardRefs
import dev.wildware.feed.server.domain.DomainError
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Card refs (migration 056): a card points at other cards, and the owner's reads show them. */
class CardRefsTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    private class Ctx(val app: App, val key: String, val alpha: Agents.Agent, val beta: Agents.Agent, val gamma: Agents.Agent)

    private fun withApp(block: suspend ApplicationTestBuilder.(Ctx) -> Unit) {
        val app = App(config, Database.memory())
        app.db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'web', 'Web', 1, 1)")
        val (_, key) = app.devices.mint("Phone")
        val (alpha, _) = app.agents.mint("alpha")
        val (beta, _) = app.agents.mint("beta")
        val (gamma, _) = app.agents.mint("gamma")
        testApplication {
            application { feed(app) }
            block(Ctx(app, key, alpha, beta, gamma))
        }
        app.close()
    }

    private suspend fun Ctx.tool(name: String, args: JsonObject, by: Agents.Agent = alpha): JsonObject =
        app.tools.call(app, by, buildJsonObject { put("name", name); put("arguments", args) }).json

    private suspend fun Ctx.ok(name: String, args: JsonObject, by: Agents.Agent = alpha): JsonObject {
        val result = tool(name, args, by)
        assertTrue(result["isError"] == null, "$name failed: $result")
        return result["structuredContent"]!!.jsonObject
    }

    private suspend fun Ctx.refused(name: String, args: JsonObject, code: String, message: String, by: Agents.Agent = alpha) {
        val data = tool(name, args, by)["structuredContent"]!!.jsonObject
        assertEquals(code, data.s("error"), "$name: $data")
        assertEquals(message, data.s("message"))
    }

    private suspend fun ApplicationTestBuilder.read(key: String, path: String): JsonObject {
        val r = client.get(path) { header("Authorization", "Bearer $key") }
        assertEquals(200, r.status.value, r.bodyAsText())
        return Wire.json.parseToJsonElement(r.bodyAsText()).jsonObject
    }

    private fun refs(vararg refs: Pair<String, String>) = buildJsonArray {
        for ((kind, id) in refs) add(buildJsonObject { put("kind", kind); put("id", id) })
    }

    private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.o(key: String) = this[key]!!.jsonObject
    private fun JsonObject.a(key: String) = this[key]!!.jsonArray
    private fun JsonArray.o(i: Int) = this[i].jsonObject

    /** A ready video on [messageId], as if it had been uploaded and processed. */
    private fun Ctx.video(messageId: String) = app.db.update(
        "INSERT INTO media (id, agent_id, author, message_id, kind, mime, bytes, sha256, width, height, duration_ms, filename, status, created_at) " +
            "VALUES ('MV1', ?, ?, ?, 'video', 'video/mp4', 1000, 'abc', 640, 360, 4000, 'demo.mp4', 'ready', 1)",
        alpha.id, "agent:${alpha.id}", messageId,
    )

    @Test
    fun `a video reply on the work card shows inside the question that points at it`() = withApp { c ->
        val update = c.ok("post_update", buildJsonObject { put("channel", "web"); put("title", "Login page"); put("body", "Building the login page") })
            .o("update").s("id")!!
        val reply = c.ok("post_message", buildJsonObject { put("update_id", update); put("body", "**Here it is** running\nsecond line") })
            .o("message").s("id")!!
        c.video(reply)

        val asked = c.ok("request_input", buildJsonObject {
            put("kind", "confirm"); put("question", "Does this look right?"); put("channel", "web")
            put("refs", refs("message" to reply))
        })
        val requestId = asked.s("request_id")!!

        val request = read(c.key, "/api/snapshot/requests").a("requests").single().jsonObject
        assertEquals(requestId, request.s("id"))
        assertEquals(listOf("refs", "blocks"), request.keys.toList().takeLast(2), "refs come after everything the TS server sent, then blocks (migration 057)")
        val ref = request.a("refs").single().jsonObject
        assertEquals(
            listOf("kind", "id", "projectId", "deleted", "author", "authorName", "title", "text", "media", "mediaCount", "updateId", "replyTo", "requestId", "state"),
            ref.keys.toList(),
        )
        assertEquals("message", ref.s("kind"))
        assertEquals(reply, ref.s("id"))
        assertEquals("P1", ref.s("projectId"))
        assertEquals("false", ref.s("deleted"))
        assertEquals("agent:${c.alpha.id}", ref.s("author"))
        assertEquals("alpha", ref.s("authorName"))
        assertNull(ref.s("title"))
        assertEquals("Here it is running", ref.s("text"), "the first line, as plain text")
        assertEquals("video", ref.o("media").s("kind"))
        assertEquals("MV1", ref.o("media").s("id"))
        assertEquals("1", ref.s("mediaCount"))
        assertEquals(update, ref.s("updateId"), "enough to open the reply's thread")

        // The reply, deleted afterwards, reads as removed and the question still loads.
        assertEquals(200, client.delete("/api/messages/$reply") { header("Authorization", "Bearer ${c.key}") }.status.value)
        val removed = read(c.key, "/api/snapshot/requests").a("requests").single().jsonObject.a("refs").single().jsonObject
        assertEquals("true", removed.s("deleted"))
        assertEquals(reply, removed.s("id"))
        for (key in listOf("author", "authorName", "title", "text", "updateId")) assertNull(removed.s(key), key)
        assertEquals(JsonNull, removed["media"])
        assertEquals("0", removed.s("mediaCount"))
    }

    @Test
    fun `refs round-trip through the snapshot, a thread, and an update, in mixed kinds and in order`() = withApp { c ->
        val update = c.ok("post_update", buildJsonObject { put("channel", "web"); put("title", "Release 1.2"); put("body", "Shipped") }).o("update").s("id")!!
        val first = c.ok("post_message", buildJsonObject { put("update_id", update); put("body", "first") }).o("message").s("id")!!
        val question = c.ok("request_input", buildJsonObject {
            put("kind", "text"); put("question", "Which branch?"); put("detail", "We have two\nand more"); put("channel", "web")
        }).s("request_id")!!

        val posted = c.ok("post_message", buildJsonObject {
            put("update_id", update); put("body", "see these")
            // Mixed kinds, a repeat dropped, the order kept.
            put("refs", refs("request" to question, "update" to update, "message" to first, "request" to question))
        })
        val echoed = posted.a("refs")
        assertEquals(listOf("request", "update", "message"), echoed.map { it.jsonObject.s("kind") })
        assertEquals("Which branch?", echoed.o(0).s("title"))
        assertEquals("We have two", echoed.o(0).s("text"))
        assertEquals("false", echoed.o(0).s("removed"))
        val message = posted.o("message").s("id")!!

        val snapshot = read(c.key, "/api/snapshot")
        assertEquals(listOf("messageRefs", "messageBlocks", "messageSecrets"), snapshot.keys.toList().takeLast(3))
        val onMessage = snapshot.o("messageRefs").a(message)
        assertEquals(listOf(question, update, first), onMessage.map { it.jsonObject.s("id") })
        assertEquals("Release 1.2", onMessage.o(1).s("title"))
        assertEquals("pending", onMessage.o(0).s("state"))
        assertEquals(setOf(message), snapshot.o("messageRefs").keys, "only messages that point at something")

        val thread = read(c.key, "/api/messages?update=$update")
        assertEquals(listOf("refs", "blocks", "secrets"), thread.keys.toList().takeLast(3))
        assertEquals(3, thread.o("refs").a(message).size)

        val later = c.ok("post_update", buildJsonObject { put("channel", "web"); put("body", "Follow-up"); put("refs", refs("message" to message)) })
            .o("update").s("id")!!
        val items = read(c.key, "/api/snapshot/updates").o("updates").a("items")
        val byId = items.associateBy { it.jsonObject.s("id") }
        assertEquals(listOf("refs", "blocks", "secrets", "prs"), byId[later]!!.jsonObject.keys.toList().takeLast(4), "then secrets (migration 066), then pull requests (079)")
        assertEquals("see these", byId[later]!!.jsonObject.a("refs").single().jsonObject.s("text"))
        assertEquals(JsonArray(emptyList()), byId[update]!!.jsonObject["refs"], "an update that points at nothing has an empty list")
    }

    @Test
    fun `a ref must be there, live, readable, and few`() = withApp { c ->
        val update = c.ok("post_update", buildJsonObject { put("channel", "web"); put("body", "work") }).o("update").s("id")!!
        val gone = c.ok("post_message", buildJsonObject { put("update_id", update); put("body", "soon gone") }).o("message").s("id")!!
        c.ok("delete_message", buildJsonObject { put("message_id", gone) })

        val unknown = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        c.refused("post_message", buildJsonObject { put("update_id", update); put("body", "x"); put("refs", refs("message" to unknown)) },
            "not_found", "no such message: $unknown")
        c.refused("post_message", buildJsonObject { put("update_id", update); put("body", "x"); put("refs", refs("message" to gone)) },
            "not_found", "no such message: $gone")
        c.refused("post_update", buildJsonObject { put("channel", "web"); put("body", "x"); put("refs", refs("request" to update)) },
            "not_found", "no such request: $update", )
        c.refused("request_input", buildJsonObject {
            put("kind", "confirm"); put("question", "?"); put("channel", "web"); put("refs", refs("update" to unknown))
        }, "not_found", "no such update: $unknown")

        // Nine is one too many: the schema says so, and so does the domain behind it.
        val nine = refs(*Array(9) { "update" to update })
        val tooMany = c.tool("post_message", buildJsonObject { put("update_id", update); put("body", "x"); put("refs", nine) })
        assertEquals(JsonPrimitive(true), tooMany["isError"])
        assertTrue(tooMany.toString().contains("Too big: expected array to have <=8 items"), tooMany.toString())
        val cardRefs = CardRefs(c.app.db, c.app.clock)
        val distinct = (1..9).map { CardRef("update", "U$it") }
        assertEquals("refs takes at most 8 cards", assertFailsWith<DomainError> { cardRefs.check("agent:${c.alpha.id}", distinct) }.message)
        assertEquals("a ref's kind is one of: update, message, request", assertFailsWith<DomainError> {
            cardRefs.check("agent:${c.alpha.id}", listOf(CardRef("task", update)))
        }.message)

        // A message that @names beta reaches only beta: gamma cannot point at it, beta and its author can.
        val private = c.ok("post_message", buildJsonObject { put("update_id", update); put("body", "@beta only you") }).o("message").s("id")!!
        c.refused("post_message", buildJsonObject { put("update_id", update); put("body", "x"); put("refs", refs("message" to private)) },
            "not_found", "no such message: $private", by = c.gamma)
        c.ok("post_message", buildJsonObject { put("update_id", update); put("body", "x"); put("refs", refs("message" to private)) }, by = c.beta)
        c.ok("post_message", buildJsonObject { put("update_id", update); put("body", "x"); put("refs", refs("message" to private)) })
    }

    @Test
    fun `an edit replaces the refs, keeps a removed one it already had, and cannot point at itself`() = withApp { c ->
        val update = c.ok("post_update", buildJsonObject { put("channel", "web"); put("body", "work") }).o("update").s("id")!!
        val a = c.ok("post_message", buildJsonObject { put("update_id", update); put("body", "a") }).o("message").s("id")!!
        val b = c.ok("post_message", buildJsonObject { put("update_id", update); put("body", "b") }).o("message").s("id")!!
        val m = c.ok("post_message", buildJsonObject { put("update_id", update); put("body", "m"); put("refs", refs("message" to a)) })
            .o("message").s("id")!!
        c.ok("delete_message", buildJsonObject { put("message_id", a) })

        val edited = c.ok("edit_message", buildJsonObject { put("message_id", m); put("body", "m2"); put("refs", refs("message" to a, "message" to b)) })
        assertEquals(listOf("true", "false"), edited.a("refs").map { it.jsonObject.s("removed") })
        c.refused("edit_message", buildJsonObject { put("message_id", m); put("body", "m3"); put("refs", refs("message" to m)) },
            "invalid_argument", "a card cannot point at itself")
        // Left out: kept. Empty: all off.
        c.ok("edit_message", buildJsonObject { put("message_id", m); put("body", "m4") })
        assertEquals(2, read(c.key, "/api/messages?update=$update").o("refs").a(m).size)
        c.ok("edit_message", buildJsonObject { put("message_id", m); put("body", "m5"); put("refs", JsonArray(emptyList())) })
        assertTrue(m !in read(c.key, "/api/messages?update=$update").o("refs"))

        // edit_update takes refs alone as a change; edit_request too.
        c.ok("edit_update", buildJsonObject { put("update_id", update); put("refs", refs("message" to b)) })
        assertEquals(b, read(c.key, "/api/snapshot/updates").o("updates").a("items").single().jsonObject.a("refs").single().jsonObject.s("id"))
        val question = c.ok("request_input", buildJsonObject { put("kind", "confirm"); put("question", "Ship?"); put("channel", "web") }).s("request_id")!!
        val rewritten = c.ok("edit_request", buildJsonObject { put("request_id", question); put("refs", refs("update" to update)) })
        assertEquals(update, rewritten.a("refs").single().jsonObject.s("id"))
        assertEquals(update, read(c.key, "/api/snapshot/requests").a("requests").single().jsonObject.a("refs").single().jsonObject.s("id"))
    }

    @Test
    fun `get_messages shows another agent the cards a message points at`() = withApp { c ->
        val update = c.ok("post_update", buildJsonObject { put("channel", "web"); put("title", "Login"); put("body", "work") }).o("update").s("id")!!
        c.ok("subscribe", buildJsonObject { put("channel", "web") }, by = c.beta)
        c.ok("post_message", buildJsonObject { put("update_id", update); put("body", "look"); put("refs", refs("update" to update)) })
        val read = c.ok("get_messages", JsonObject(emptyMap()), by = c.beta)
        val ref = read.a("messages").single().jsonObject.a("refs").single().jsonObject
        assertEquals(listOf("kind", "id", "project_id", "author", "title", "text", "removed"), ref.keys.toList())
        assertEquals("Login", ref.s("title"))
        assertEquals("false", ref.s("removed"))
    }
}
