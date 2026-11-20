package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.push.PlainText
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundedBodiesTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    /** A real server, so the test controls the bytes on the wire. */
    private fun withServer(block: (port: Int) -> Unit) {
        val app = App(config, Database.memory())
        val server = FeedServer(app).start()
        try {
            block(server.port)
        } finally {
            server.stop()
            app.close()
        }
    }

    /** The status line of the answer to [head] plus [body], read with a timeout so a server that waits fails the test. */
    private fun statusOf(port: Int, head: String, body: (java.io.OutputStream) -> Unit): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        val out = socket.getOutputStream()
        out.write(head.toByteArray())
        body(out)
        out.flush()
        socket.getInputStream().bufferedReader().readLine()
    }

    @Test
    fun `a sign-in that says it is too big is refused before any of it is read`() = withServer { port ->
        val status = statusOf(
            port,
            "POST /api/devices/sign-in HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${200L * 1024 * 1024}\r\n\r\n",
        ) { it.write("{".toByteArray()) }
        assertTrue(status.startsWith("HTTP/1.1 413"), status)
    }

    @Test
    fun `a streamed sign-in is cut off at its cap, without waiting for the rest`() = withServer { port ->
        // Chunked, with no length: twice the cap is sent, the body is never finished, and the answer still comes.
        val status = statusOf(
            port,
            "POST /api/devices/sign-in HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n",
        ) { out ->
            val chunk = ByteArray(8192) { 'x'.code.toByte() }
            repeat(2 * BodyLimits.PUBLIC / chunk.size) {
                out.write("${Integer.toHexString(chunk.size)}\r\n".toByteArray())
                out.write(chunk)
                out.write("\r\n".toByteArray())
            }
        }
        assertTrue(status.startsWith("HTTP/1.1 413"), status)
    }

    @Test
    fun `bodies at the cap still work, and over it get payload_too_large`() {
        val app = App(config, Database.memory())
        val (_, key) = app.devices.mint("Phone")
        testApplication {
            application { feed(app) }
            // A sign-in just under its cap is read and judged on what it says.
            val padded = """{"name":"Laptop","password":"nope","pad":"${"x".repeat(BodyLimits.PUBLIC - 100)}"}"""
            val signIn = client.post("/api/devices/sign-in") { header("Content-Type", "application/json"); setBody(padded) }
            assertTrue(signIn.status.value != 413, signIn.bodyAsText())
            val over = """{"name":"Laptop","password":"nope","pad":"${"x".repeat(BodyLimits.PUBLIC)}"}"""
            val refused = client.post("/api/devices/sign-in") { header("Content-Type", "application/json"); setBody(over) }
            assertEquals(413, refused.status.value)
            assertTrue(refused.bodyAsText().contains("\"payload_too_large\""))

            // An owner's message at its longest goes through.
            val message = client.post("/api/messages") {
                header("Authorization", "Bearer $key"); header("Content-Type", "application/json")
                setBody("""{"body":"${"é".repeat(10_000)}"}""")
            }
            assertEquals(201, message.status.value, message.bodyAsText())
            val huge = client.post("/api/messages") {
                header("Authorization", "Bearer $key"); header("Content-Type", "application/json")
                setBody("""{"body":"${"x".repeat(BodyLimits.DEFAULT)}"}""")
            }
            assertEquals(413, huge.status.value)
        }
        app.close()
    }

    @Test
    fun `an MCP post with a full body and eight full blocks goes through`() {
        val app = App(config, Database.memory())
        val token = app.agents.mint("scout").second
        app.db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'garden', 'Garden Game', 1, 1)")
        val body = "é".repeat(100_000)
        val args = buildJsonObject {
            put("channel", "garden")
            put("body", body)
            put("blocks", buildJsonArray {
                repeat(8) { i -> add(buildJsonObject { put("id", "log$i"); put("type", "log"); put("text", ("日".repeat(999) + "\n").repeat(79)) }) }
            })
        }
        val call = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
            put("params", buildJsonObject { put("name", "post_update"); put("arguments", args) })
        }
        val wire = Wire.encode(call)
        assertTrue(wire.toByteArray().size > BodyLimits.DEFAULT, "bigger than the default cap")
        testApplication {
            application { feed(app) }
            val answer = client.post("/mcp") {
                header("Authorization", "Bearer $token"); header("Accept", "application/json, text/event-stream")
                header("Content-Type", "application/json")
                setBody(wire)
            }
            assertEquals(200, answer.status.value)
            assertTrue(!answer.bodyAsText().contains("\"isError\":true"), answer.bodyAsText().take(500))
        }
        assertEquals(1, app.db.one("SELECT count(*) FROM updates WHERE length(body) = ?", body.length) { it.getInt(1) })
        app.close()
    }

    @Test
    fun `plain text survives markdown nested thousands deep`() {
        assertEquals("deep", PlainText.of("> ".repeat(10_000) + "deep"))
        assertEquals("deep", PlainText.of(">".repeat(50_000) + "deep"))
        assertTrue(PlainText.of("- ".repeat(5_000) + "item").endsWith("item"))
        assertEquals("alt", PlainText.of("![".repeat(5_000) + "alt" + "](x)".repeat(5_000)))
        assertEquals("a\n• b\n2. c", PlainText.of("a\n\n- b\n\n1. x\n2. c").lines().let { listOf(it[0], it[1], it[3]) }.joinToString("\n"))
    }

    @Test
    fun `the summary still answers with a card nested 10,000 deep in progress`() {
        val app = App(config, Database.memory())
        val (_, key) = app.devices.mint("Phone")
        app.db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'Rover', 'x', 1)")
        app.db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'garden', 'Garden Game', 1, 1)")
        app.db.update(
            "INSERT INTO updates (id, project_id, agent_id, title, body, level, status, status_at, created_at) VALUES " +
                "('U1', 'P1', 'A1', NULL, ?, 'info', 'in_progress', 1700000500000, 1)",
            "> ".repeat(10_000) + "deep",
        )
        testApplication {
            application { feed(app) }
            val summary = client.get("/api/summary") { header("Authorization", "Bearer $key") }
            assertEquals(200, summary.status.value)
            val working = Wire.json.parseToJsonElement(summary.bodyAsText()).jsonObject["working"]!!.jsonArray
            assertEquals("deep", working.single().jsonObject["task"]!!.jsonPrimitive.content)
        }
        app.close()
    }
}
