package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The owner lists agents and removes one: it is revoked, leaves the live list, and its posts keep its name. */
class AgentRemoveTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "https://dashboard.example.com", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "https://dashboard.example.com",
    )

    private fun withApp(block: suspend ApplicationTestBuilder.(App, String) -> Unit) {
        val app = App(config, Database.memory())
        val (_, key) = app.devices.mint("Phone")
        testApplication {
            application { feed(app) }
            block(app, key)
        }
        app.close()
    }

    private suspend fun ApplicationTestBuilder.mcp(token: String, tool: String) = client.post("/mcp") {
        header("Authorization", "Bearer $token")
        header("Accept", "application/json, text/event-stream")
        header("Content-Type", "application/json")
        setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":{}}}""")
    }

    @Test
    fun `removing an agent revokes it, ends its sessions and says so, and only the owner can`() = withApp { app, key ->
        val (broken, token) = app.agents.mint("broken")
        val (kept, _) = app.agents.mint("kept")
        assertEquals(200, mcp(token, "register_session").status.value)
        suspend fun live() = client.get("/api/snapshot/agents") { header("Authorization", "Bearer $key") }.bodyAsText()
            .let { Wire.json.parseToJsonElement(it).jsonObject["agents"]!!.jsonArray.map { a -> a.jsonObject["agentId"]!!.jsonPrimitive.content } }
        assertEquals(listOf(broken.id), live())

        assertEquals(401, client.get("/api/agents").status.value)
        assertEquals(401, client.delete("/api/agents/${broken.id}").status.value)
        assertEquals(401, client.delete("/api/agents/${broken.id}") { header("Authorization", "Bearer $token") }.status.value, "an agent cannot remove anyone")

        val events = mutableListOf<String>()
        val stop = app.bus.subscribe { e -> events += "${e.type}:${e.payload["agentId"]}" }
        val removed = client.delete("/api/agents/${broken.id}") { header("Authorization", "Bearer $key") }
        stop()
        assertEquals(200, removed.status.value)
        assertEquals("true", Wire.json.parseToJsonElement(removed.bodyAsText()).jsonObject["revoked"]!!.jsonPrimitive.content)
        assertTrue("agent.presence:${JsonPrimitive(broken.id)}" in events, "$events")
        assertTrue("agent.revoked:${JsonPrimitive(broken.id)}" in events, "$events")

        assertEquals(401, mcp(token, "register_session").status.value)
        assertEquals(emptyList(), live())

        val listed = Wire.json.parseToJsonElement(client.get("/api/agents") { header("Authorization", "Bearer $key") }.bodyAsText())
            .jsonObject["agents"]!!.jsonArray.associate { a -> a.jsonObject["name"]!!.jsonPrimitive.content to a.jsonObject["revokedAt"]!! }
        assertEquals(setOf("broken", "kept"), listed.keys)
        assertTrue(listed["broken"] is JsonPrimitive)
        assertEquals(kotlinx.serialization.json.JsonNull, listed["kept"])
        assertTrue(app.agents.find(kept.id)!!.revokedAt == null)

        val again = client.delete("/api/agents/${broken.id}") { header("Authorization", "Bearer $key") }
        assertEquals("false", Wire.json.parseToJsonElement(again.bodyAsText()).jsonObject["revoked"]!!.jsonPrimitive.content)
        assertEquals(404, client.delete("/api/agents/nobody") { header("Authorization", "Bearer $key") }.status.value)
    }
}
