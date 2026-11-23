package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** delete_update: an agent deletes its own update cards, as delete_message unsends its own messages. */
class DeleteUpdateToolTest {
    private fun config(dir: String) = Config(
        dataDir = dir, adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    private class Ctx(val app: App, val client: HttpClient) {
        val ownerKey = app.devices.mint("Owner phone").second
        val scout = app.agents.mint("scout")
        val other = app.agents.mint("other")
        val events = mutableListOf<AppEvent>()
    }

    private fun withApp(block: suspend Ctx.() -> Unit) {
        val dir = Files.createTempDirectory("delete-update").toFile()
        val app = App(config(dir.path), Database.memory())
        try {
            testApplication {
                application { feed(app) }
                val c = Ctx(app, client)
                app.bus.subscribe { c.events += it }
                c.block()
            }
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    private suspend fun Ctx.call(key: String, method: String, path: String, body: String? = null): HttpResponse = client.request(path) {
        this.method = HttpMethod.parse(method)
        header("Authorization", "Bearer $key")
        header("Accept", "application/json, text/event-stream")
        if (body != null) {
            header("Content-Type", "application/json")
            setBody(body)
        }
    }

    private suspend fun Ctx.read(path: String): JsonObject {
        val r = call(ownerKey, "GET", path)
        assertEquals(200, r.status.value, r.bodyAsText())
        return Wire.json.parseToJsonElement(r.bodyAsText()).jsonObject
    }

    private suspend fun Ctx.tool(token: String, name: String, args: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
            put("params", buildJsonObject { put("name", name); put("arguments", args) })
        }.toString()
        return Wire.json.parseToJsonElement(call(token, "POST", "/mcp", body).bodyAsText()).jsonObject["result"]!!.jsonObject
    }

    private suspend fun Ctx.ok(token: String, name: String, args: JsonObject): JsonObject {
        val result = tool(token, name, args)
        assertTrue(result["isError"] == null, "$name failed: $result")
        return result["structuredContent"]!!.jsonObject
    }

    private fun JsonObject.error(): String? = this["structuredContent"]?.jsonObject?.s("error")
    private fun JsonElement?.s(key: String) = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { for ((k, v) in pairs) put(k, v) }

    private suspend fun Ctx.channel(slug: String) = ok(scout.second, "create_project", args("name" to slug, "slug" to slug))
    private suspend fun Ctx.post(token: String, slug: String, title: String) =
        ok(token, "post_update", args("channel" to slug, "title" to title, "body" to "words"))["update"].s("id")!!

    private suspend fun Ctx.feedIds(): List<String?> =
        read("/api/snapshot/updates")["updates"]!!.jsonObject["items"]!!.jsonArray.map { it.s("id") }

    @Test
    fun `an agent deletes its own update and it is gone for everyone`() = withApp {
        channel("web")
        val update = post(scout.second, "web", "Needs you: pick a colour")
        ok(scout.second, "post_message", args("update_id" to update, "body" to "a comment"))
        assertTrue(update in feedIds())
        events.clear()

        val deleted = ok(scout.second, "delete_update", args("update_id" to update))["update"]!!.jsonObject
        assertEquals(update, deleted.s("id"))
        assertNotNull(deleted.s("deleted_at"))
        val gone = events.filter { it.type.startsWith("update.") }
        assertEquals(listOf("update.deleted"), gone.map { it.type })
        assertEquals(update, gone.single().payload.s("updateId"))

        assertFalse(update in feedIds(), "the owner's feed no longer has it")
        assertEquals("not_found", tool(other.second, "get_messages", args("update_id" to update)).error(), "its thread can't be read")
        assertEquals("not_found", tool(other.second, "post_message", args("update_id" to update, "body" to "hi")).error(), "or replied on")
        assertEquals("not_found", tool(scout.second, "edit_update", args("update_id" to update, "body" to "back")).error())

        // Twice is a quiet success that announces nothing.
        events.clear()
        assertEquals(update, ok(scout.second, "delete_update", args("update_id" to update))["update"].s("id"))
        assertTrue(events.none { it.type.startsWith("update.") }, "$events")
        assertEquals("not_found", tool(scout.second, "delete_update", args("update_id" to "01ABCDEFGHJKMNPQRSTVWXYZ00")).error())
    }

    @Test
    fun `an agent can't delete another agent's update, before or after it is gone`() = withApp {
        channel("web")
        val update = post(scout.second, "web", "Scout's private plan")
        val refused = tool(other.second, "delete_update", args("update_id" to update))
        assertEquals("invalid_argument", refused.error())
        assertEquals("that update was posted by another agent", refused["structuredContent"].s("message"))
        assertTrue(update in feedIds(), "still there")

        ok(scout.second, "delete_update", args("update_id" to update))
        val after = tool(other.second, "delete_update", args("update_id" to update))
        assertEquals("invalid_argument", after.error())
        assertFalse("private plan" in after.toString(), "a deleted card's words go back to nobody else")
    }

    @Test
    fun `a channel it can only read answers forbidden, and one it can't see answers not_found`() = withApp {
        channel("secret")
        val update = post(other.second, "secret", "Mine")
        val path = "/api/projects/secret/access/agent/${other.first.id}"
        assertEquals(200, call(ownerKey, "PUT", path, """{"level":"read"}""").status.value)
        assertEquals("forbidden", tool(other.second, "delete_update", args("update_id" to update)).error())
        assertEquals(200, call(ownerKey, "PUT", path, """{"level":"none"}""").status.value)
        assertEquals("not_found", tool(other.second, "delete_update", args("update_id" to update)).error())
        assertTrue(update in feedIds(), "neither deleted it")
        assertEquals(200, call(ownerKey, "PUT", path, """{"level":"post"}""").status.value)
        ok(other.second, "delete_update", args("update_id" to update))
    }

    @Test
    fun `a card that points at a deleted update shows it as removed`() = withApp {
        channel("web")
        val target = post(scout.second, "web", "The old build")
        val refs = buildJsonArray { add(buildJsonObject { put("kind", "update"); put("id", target) }) }
        val pointer = ok(scout.second, "post_update", buildJsonObject {
            put("channel", "web"); put("body", "This replaces it"); put("refs", refs)
        })["update"].s("id")!!

        ok(scout.second, "delete_update", args("update_id" to target))
        val card = read("/api/snapshot/updates")["updates"]!!.jsonObject["items"]!!.jsonArray.single { it.s("id") == pointer }.jsonObject
        val ref = (card["refs"] as JsonArray).single().jsonObject
        assertEquals(target, ref.s("id"))
        assertEquals("true", ref.s("deleted"))
        assertNull(ref.s("title"), "nothing of what it said")
    }
}
