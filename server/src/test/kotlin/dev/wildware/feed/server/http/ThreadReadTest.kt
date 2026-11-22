package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.feed
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET /api/threads/{id}`: one card and its thread, by the card's id or a reply's, so the apps can
 * open a card the feed hasn't paged to on its own. A channel hidden from the reader is a 404.
 */
class ThreadReadTest {
    private val config = Config(
        dataDir = java.nio.file.Files.createTempDirectory("feed-thread-read").toString(), adminPasswordHash = Passwords.hash("server password"),
        sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://localhost:5173", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://localhost:5173",
    )

    private suspend fun ApplicationTestBuilder.send(key: String, method: String, path: String, body: String? = null): HttpResponse =
        client.request(path) {
            this.method = HttpMethod.parse(method)
            header("Authorization", "Bearer $key")
            if (body != null) {
                header("Content-Type", "application/json")
                setBody(body)
            }
        }

    private suspend fun ApplicationTestBuilder.tool(token: String, name: String, vararg args: Pair<String, String>): JsonObject {
        val call = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0")); put("id", JsonPrimitive(1)); put("method", JsonPrimitive("tools/call"))
            put("params", buildJsonObject {
                put("name", JsonPrimitive(name))
                put("arguments", buildJsonObject { for ((k, v) in args) put(k, JsonPrimitive(v)) })
            })
        }
        val text = client.request("/mcp") {
            method = HttpMethod.Post
            header("Authorization", "Bearer $token")
            header("Accept", "application/json, text/event-stream")
            header("Content-Type", "application/json")
            setBody(call.toString())
        }.bodyAsText()
        return Wire.json.parseToJsonElement(text).jsonObject["result"]!!.jsonObject["structuredContent"]!!.jsonObject
    }

    private suspend fun ApplicationTestBuilder.read(key: String, id: String): Pair<Int, JsonObject?> {
        val r = send(key, "GET", "/api/threads/$id")
        return r.status.value to (if (r.status.value == 200) Wire.json.parseToJsonElement(r.bodyAsText()).jsonObject else null)
    }

    private fun JsonObject.card() = this["card"]!!.jsonObject.let { it["kind"]!!.jsonPrimitive.content to it["id"]!!.jsonPrimitive.content }
    private fun JsonObject.messageIds() = this["messages"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
    private fun JsonObject.updateIds() = this["updates"]!!.jsonObject["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun `an update or a post opens with its whole thread by its own id or a reply's`() = App(config, Database.memory()).use { app ->
        testApplication {
            application { feed(app) }
            val ownerKey = app.devices.mint("Owner phone").second
            val scout = app.agents.mint("scout").second
            tool(scout, "create_project", "name" to "Web", "slug" to "web")
            val update = tool(scout, "post_update", "project" to "web", "title" to "Old plan", "body" to "an old update")["update"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val reply = tool(scout, "post_message", "update_id" to update, "body" to "a reply on it")["message"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val post = tool(scout, "post_message", "project" to "web", "body" to "an old post")["message"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val answer = tool(scout, "post_message", "message_id" to post, "body" to "an answer to it")["message"]!!.jsonObject["id"]!!.jsonPrimitive.content

            for (id in listOf(update, reply)) {
                val (status, body) = read(ownerKey, id)
                assertEquals(200, status, "reading $id")
                assertEquals("update" to update, body!!.card())
                assertEquals(listOf(update), body.updateIds())
                assertTrue(reply in body.messageIds(), body.toString())
                assertTrue(post !in body.messageIds(), "only this card's thread: $body")
                assertEquals("scout", body["agentNames"]!!.jsonObject.values.single().jsonPrimitive.content)
            }
            for (id in listOf(post, answer)) {
                val (status, body) = read(ownerKey, id)
                assertEquals(200, status, "reading $id")
                assertEquals("message" to post, body!!.card())
                assertEquals(emptyList(), body.updateIds())
                assertEquals(listOf(post, answer), body.messageIds())
            }
            assertEquals(404, read(ownerKey, "01JXXXXXXXXXXXXXXXXXXXXXXX").first, "no such card")

            app.feed.updates.delete(update)
            assertEquals(404, read(ownerKey, update).first, "a deleted card is gone")
            assertEquals(404, read(ownerKey, reply).first, "and so is a reply on it")
        }
    }

    @Test
    fun `a card in a channel hidden from the reader is a 404`() = App(config, Database.memory()).use { app ->
        testApplication {
            application { feed(app) }
            val ownerKey = app.devices.mint("Owner phone").second
            val (_, invite) = app.people.invite(app.people.find(People.FOUNDER)!!, People.Role.MEMBER, "sam@example.test")
            val sam = app.people.accept(invite, "Sam", "sam@example.test", "password for sam")
            val samKey = app.devices.mint("Sam phone", sam.id).second
            val scout = app.agents.mint("scout").second
            tool(scout, "create_project", "name" to "Vault", "slug" to "vault")
            tool(scout, "create_project", "name" to "Web", "slug" to "web")
            val hidden = tool(scout, "post_update", "project" to "vault", "body" to "a vault update")["update"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val hiddenReply = tool(scout, "post_message", "update_id" to hidden, "body" to "a vault reply")["message"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val hiddenPost = tool(scout, "post_message", "project" to "vault", "body" to "a vault post")["message"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val open = tool(scout, "post_update", "project" to "web", "body" to "an open update")["update"]!!.jsonObject["id"]!!.jsonPrimitive.content
            assertEquals(200, send(ownerKey, "PUT", "/api/projects/vault/access", """{"private":true}""").status.value)

            for (id in listOf(hidden, hiddenReply, hiddenPost)) {
                val r = send(samKey, "GET", "/api/threads/$id")
                assertEquals(404, r.status.value, "Sam reading $id")
                assertTrue("vault" !in r.bodyAsText(), r.bodyAsText())
                assertEquals(200, read(ownerKey, id).first, "the owner reading $id")
            }
            assertEquals(200, read(samKey, open).first, "Sam reads the open channel's card")
        }
    }
}
