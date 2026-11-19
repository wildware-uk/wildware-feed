package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.tableNames
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingRoutesTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "https://feed.example.com", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "https://feed.example.com",
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

    private fun String.json() = Wire.json.parseToJsonElement(this).jsonObject
    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.pair(body: String): HttpResponse = client.post("/api/pair") {
        header("Content-Type", "application/json")
        setBody(body)
    }

    private suspend fun ApplicationTestBuilder.poll(id: String, secret: String?): HttpResponse = client.get("/api/pair/$id") {
        if (secret != null) header("Authorization", "Bearer $secret")
    }

    @Test
    fun `an agent with no token pairs over HTTP, and its token works on MCP`() = withApp { app, key ->
        assertTrue(client.get("/api/pair").bodyAsText().contains("POST https://feed.example.com/api/pair"))

        val started = pair("""{"name":"laptop-personal","host":"laptop","cwd":"/work"}""")
        assertEquals(201, started.status.value)
        assertEquals("no-store", started.headers["Cache-Control"])
        val body = started.bodyAsText().json()
        assertEquals(
            listOf("state", "pairing_id", "poll_secret", "code", "name", "expires_at", "poll_url", "instructions"),
            body.keys.toList(),
        )
        val id = body.s("pairing_id")
        val secret = body.s("poll_secret")
        assertEquals("https://feed.example.com/api/pair/$id", body.s("poll_url"))
        assertTrue(body.s("instructions").contains(body.s("code")))

        assertEquals(401, poll(id, null).status.value)
        assertEquals(404, poll(id, "x".repeat(43)).status.value)

        // The owner's side: a stranger gets nothing, the owner sees it without the secret.
        assertEquals(401, client.get("/api/pairings").status.value)
        val listed = client.get("/api/pairings") { header("Authorization", "Bearer $key") }.bodyAsText()
        assertFalse(listed.contains(secret))
        val row = listed.json()["pairings"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("laptop-personal", "laptop", "/work", body.s("code")), listOf(row.s("name"), row.s("host"), row.s("cwd"), row.s("code")))
        assertEquals(401, client.post("/api/pairings/$id/approve").status.value)

        val approved = client.post("/api/pairings/$id/approve") {
            header("Authorization", "Bearer $key")
            header("Content-Type", "application/json")
            setBody("""{"name":"laptop"}""")
        }
        assertEquals(200, approved.status.value)
        val approvedText = approved.bodyAsText()
        assertEquals("laptop", approvedText.json()["agent"]!!.jsonObject.s("name"))

        val collected = poll(id, secret).bodyAsText().json()
        assertEquals("approved", collected.s("state"))
        val token = collected.s("token")
        assertEquals("https://feed.example.com/mcp", collected.s("mcp_url"))
        assertEquals("Authorization: Bearer $token", collected.s("authorization_header"))
        assertTrue(collected.s("claude_code_command").contains("--header \"Authorization: Bearer $token\""))
        assertFalse(approvedText.contains(token))

        // An answer lost on the way is not a lost token: until it is used, a repeat poll gets it again.
        val again = poll(id, secret).bodyAsText().json()
        assertEquals("approved", again.s("state"))
        assertEquals(token, again.s("token"))

        val mcp = client.post("/mcp") {
            header("Authorization", "Bearer $token")
            header("Accept", "application/json, text/event-stream")
            header("Content-Type", "application/json")
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"register_session","arguments":{}}}""")
        }
        assertEquals(200, mcp.status.value)
        assertTrue(mcp.bodyAsText().contains("for agent \\\"laptop\\\""), mcp.bodyAsText())
    
        // Used once, it is never shown again.
        val afterUse = poll(id, secret).bodyAsText().json()
        assertEquals("collected", afterUse.s("state"))
        assertFalse("token" in afterUse)
    }

    @Test
    fun `nothing a pairing logs or stores can be used as its token`() {
        val logged = java.io.ByteArrayOutputStream()
        val (out, err) = System.out to System.err
        val tee = java.io.PrintStream(logged, true)
        System.setOut(tee)
        System.setErr(tee)
        var token = ""
        var secret = ""
        val app = App(config, Database.memory())
        try {
            val (_, key) = app.devices.mint("Phone")
            testApplication {
                application { feed(app) }
                val body = pair("""{"name":"quiet"}""").bodyAsText().json()
                val id = body.s("pairing_id")
                secret = body.s("poll_secret")
                client.post("/api/pairings/$id/approve") { header("Authorization", "Bearer $key") }
                token = poll(id, secret).bodyAsText().json().s("token")
                assertEquals(token, poll(id, secret).bodyAsText().json().s("token"))
            }
            val everything = app.db.tableNames().flatMap { table ->
                app.db.query("SELECT * FROM \"$table\"") { rs -> (1..rs.metaData.columnCount).joinToString("|") { rs.getString(it).orEmpty() } }
            }
            assertTrue(everything.isNotEmpty())
            for (secretThing in listOf(token, secret)) {
                assertTrue(everything.none { secretThing in it }, "stored in the database")
            }
        } finally {
            System.setOut(out)
            System.setErr(err)
            app.close()
        }
        assertTrue(token.isNotEmpty())
        assertFalse(token in logged.toString(), "logged the token")
        assertFalse(secret in logged.toString(), "logged the poll secret")
    }

    @Test
    fun `starting is limited per address, and bad bodies are refused`() = withApp { _, _ ->
        assertEquals(400, pair("""{"host":"x"}""").status.value)
        assertEquals(400, pair("""{"name":7}""").status.value)
        assertEquals(413, pair("""{"name":"${"x".repeat(5000)}"}""").status.value)
        // Five a window, the refused ones above included; the sixth is refused with Retry-After.
        assertEquals(201, pair("""{"name":"a"}""").status.value)
        assertEquals(201, pair("""{"name":"b"}""").status.value)
        val limited = pair("""{"name":"c"}""")
        assertEquals(429, limited.status.value)
        assertTrue(limited.headers["Retry-After"]!!.toInt() > 0)
    }

    @Test
    fun `an agent with a token pairs another over MCP, and a taken name is a conflict`() = withApp { app, key ->
        val (agent, _) = app.agents.mint("shared")
        suspend fun tool(name: String, args: JsonObject) =
            app.tools.call(app, agent, buildJsonObject { put("name", name); put("arguments", args) }).json["structuredContent"]!!.jsonObject
        val started = tool("start_pairing", buildJsonObject { put("name", "shared"); put("host", "laptop") })
        assertEquals("pending", started.s("state"))
        assertTrue(started.s("instructions").contains("await_pairing"))
        val id = started.s("pairing_id")

        val row = app.pairings.ownerJson(app.pairings.find(id)!!)
        assertEquals("shared", row["askedBy"]!!.jsonObject.s("name"))

        val clash = client.post("/api/pairings/$id/approve") { header("Authorization", "Bearer $key") }
        assertEquals(409, clash.status.value)
        assertEquals("name_taken", clash.bodyAsText().json().s("error"))
        client.post("/api/pairings/$id/approve") {
            header("Authorization", "Bearer $key")
            header("Content-Type", "application/json")
            setBody("""{"name":"shared-2"}""")
        }.also { assertEquals(200, it.status.value) }

        val done = tool("await_pairing", buildJsonObject { put("pairing_id", id); put("poll_secret", started.s("poll_secret")) })
        assertEquals("shared-2", done["agent"]!!.jsonObject.s("name"))
        assertEquals("shared-2", app.agents.authenticate(done.s("token")).first!!.name)
        assertEquals("shared", app.agents.find(agent.id)!!.name, "the asker keeps its own token and name")

        val wrong = app.tools.call(app, agent, buildJsonObject {
            put("name", "await_pairing"); put("arguments", buildJsonObject { put("pairing_id", id); put("poll_secret", "nope") })
        }).json
        assertEquals("not_found", wrong["structuredContent"]!!.jsonObject.s("error"))
    }
}
