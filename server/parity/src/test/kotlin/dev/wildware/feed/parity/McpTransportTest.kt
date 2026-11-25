package dev.wildware.feed.parity

import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MCP over Streamable HTTP: the envelope, not the tools. Spec: server/spec/05-mcp.md §1-3. */
class McpTransportTest : ParityTest() {
    private val toolNames = listOf(
        "create_project", "list_projects", "set_project_theme", "post_update", "edit_update", "create_upload",
        "attach_media", "list_embed_types", "register_embed_type", "list_apps", "register_app", "set_app_data",
        "register_session", "heartbeat", "end_session", "set_status", "list_work", "list_tags", "tag_card",
        "set_card_field", "get_card_fields", "get_messages", "acknowledge", "post_message", "edit_message",
        "delete_message", "react", "request_input", "await_request", "edit_request", "withdraw_request",
    )

    private suspend fun raw(body: String, accept: String = "application/json, text/event-stream", type: ContentType = ContentType.Application.Json, token: String = alpha.token, origin: String? = null) =
        call(HttpMethod.Post, "/mcp") {
            bearer(token)
            if (origin != null) header(HttpHeaders.Origin, origin)
            header(HttpHeaders.Accept, accept)
            setBodyText(body, type)
        }

    @Test
    fun `initialize answers with the server's identity and instructions`() = parity {
        val response = alpha.rpc("initialize", obj(
            "protocolVersion" to "2025-06-18",
            "capabilities" to obj(),
            "clientInfo" to obj("name" to "parity", "version" to "1"),
        ))
        assertEquals(200, response.status.value)
        assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith("application/json"))
        val result = response.obj()["result"].o
        assertEquals("2025-06-18", result["protocolVersion"].s)
        // The TS server says listChanged: true; the Kotlin one says false on purpose, since it can
        // never send the notice (spec/05-mcp.md, Surprises 2). Either way it is the only key.
        val tools = result["capabilities"].o["tools"].o
        assertEquals(setOf("tools"), result["capabilities"].o.keys)
        assertEquals(setOf("listChanged"), tools.keys)
        assertTrue(tools["listChanged"].toString() in setOf("true", "false"))
        assertEquals(obj("name" to "agent-dashboard", "version" to "0.1.0"), result["serverInfo"])
        val instructions = result["instructions"].s
        assertTrue(instructions.startsWith("This is an Agent Dashboard: a status wall a human owner watches live while you work."))
        assertContains(instructions, "call request_input rather than guessing or stopping")

        val unknown = alpha.rpcResult("initialize", obj(
            "protocolVersion" to "1999-01-01", "capabilities" to obj(), "clientInfo" to obj("name" to "p", "version" to "1"),
        ))
        assertEquals("2025-11-25", unknown["result"]["protocolVersion"].s)
    }

    @Test
    fun `the session is stateless - no session id, notifications get 202, ping is empty`() = parity {
        val init = alpha.rpc("initialize", obj("protocolVersion" to "2025-06-18", "capabilities" to obj(), "clientInfo" to obj("name" to "p", "version" to "1")))
        assertEquals(null, init.headers["mcp-session-id"])

        val notification = raw("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, notification.status.value)
        assertEquals("", notification.bodyAsText())

        assertEquals("""{"result":{},"jsonrpc":"2.0","id":5}""", raw("""{"jsonrpc":"2.0","id":5,"method":"ping"}""").bodyAsText())
    }

    @Test
    fun `tools list has every tool in order, each with a schema`() = parity {
        // `?tools=all` is the Kotlin server's full list (plain /mcp lists the discovery tools); the TS server ignores it.
        val tools = alpha.rpcResult("tools/list", path = ALL_TOOLS)["result"]["tools"].arr
        // The Kotlin server adds the tools in tools-list-kotlin-changes.json, after the TS server's.
        assertEquals(if (Parity.kotlin) toolNames + ToolsListChanges.added() else toolNames, tools.map { it["name"].s })
        for (tool in tools) {
            assertEquals("object", tool["inputSchema"]["type"].s, tool["name"].s)
            assertTrue(tool["description"].s.isNotBlank())
            assertTrue(tool["title"].s.isNotBlank())
        }
    }

    @Test
    fun `plain mcp lists the discovery tools on the Kotlin server`() = parity {
        if (!Parity.kotlin) return@parity
        val tools = alpha.rpcResult("tools/list")["result"]["tools"].arr
        assertEquals(listOf("list_tools", "describe_tools", "call_tool"), tools.map { it["name"].s })
    }

    @Test
    fun `only POST is served`() = parity {
        for (method in listOf(HttpMethod.Get, HttpMethod.Delete)) {
            val response = call(method, "/mcp") { bearer(alpha.token) }
            assertEquals(405, response.status.value, method.value)
            assertEquals("${method.value} method not allowed", response.bodyAsText())
            assertEquals("POST", response.headers[HttpHeaders.Allow])
        }
    }

    @Test
    fun `malformed transport requests are refused by the SDK`() = parity {
        assertEquals(406, raw("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", accept = "application/json").status.value)
        // A text/plain POST is a "form" to SvelteKit's CSRF check: without a matching Origin it never reaches MCP.
        if (Parity.web) assertEquals(403, raw("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", type = ContentType.Text.Plain).status.value)
        assertEquals(415, raw("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", type = ContentType.Text.Plain, origin = Parity.baseUrl).status.value)
        val notJson = raw("{not json")
        assertEquals(400, notJson.status.value)
        assertEquals(-32700, notJson.obj()["error"]["code"].int)
        val notRpc = raw("""{"foo":1}""")
        assertEquals(400, notRpc.status.value)
        assertEquals(-32700, notRpc.obj()["error"]["code"].int)

        val badVersion = call(HttpMethod.Post, "/mcp", obj("jsonrpc" to "2.0", "id" to 1, "method" to "tools/list")) {
            bearer(alpha.token)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header("mcp-protocol-version", "1999-01-01")
        }
        assertEquals(400, badVersion.status.value)
    }

    @Test
    fun `a batch gets an array of answers`() = parity {
        val batch = raw("""[{"jsonrpc":"2.0","id":1,"method":"tools/list"},{"jsonrpc":"2.0","id":2,"method":"ping"}]""")
        assertEquals(200, batch.status.value)
        val answers = batch.json().arr
        assertEquals(2, answers.size)
        assertEquals(setOf(1, 2), answers.map { it["id"].int }.toSet())
    }

    @Test
    fun `tool-level errors come back inside the result`() = parity {
        val unknown = alpha.tool("nope")
        assertTrue(unknown.isError)
        assertEquals("MCP error -32602: Tool nope not found", unknown.text)

        val wrongType = alpha.tool("create_project", obj("name" to 5))
        assertTrue(wrongType.isError)
        assertContains(wrongType.text, "Invalid input: expected string, received number at name")

        val noArgs = alpha.rpcResult("tools/call", obj("name" to "list_projects"))["result"].o
        assertEquals(true, noArgs["isError"].bool)
        assertContains(ToolResult("list_projects", noArgs).text, "Invalid input: expected object, received undefined")

        val domain = alpha.tool("post_update", obj("project" to "nope-${Parity.suffix()}", "body" to "x"))
        assertTrue(domain.isError)
        assertEquals("not_found", domain.structured["error"].s)
        assertTrue(domain.text.startsWith("not_found: no such project: nope-"))
    }

    @Test
    fun `unknown and revoked tokens are told apart`() = parity {
        val unknown = raw("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", token = "a".repeat(43))
        assertEquals(401, unknown.status.value)
        assertEquals("unknown_token", unknown.obj()["error"]["data"]["error"].s)

        val doomed = Parity.mintAgent(uniq("doomed"))
        val id = doomed.ok("post_update", obj("project" to Fixtures.project(doomed).slug, "body" to "x"))["update"]["agent_id"].s
        Parity.revokeAgent(id)
        val revoked = raw("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", token = doomed.token)
        assertEquals(401, revoked.status.value)
        assertEquals("revoked_token", revoked.obj()["error"]["data"]["error"].s)
        assertEquals("revoked bearer token: ask the dashboard owner to mint a new one", revoked.obj()["error"]["message"].s)
    }
}

/**
 * The whole tools/list payload, exactly, as JSON: agents read these names, descriptions and schemas.
 * The Kotlin server's is the TS server's with the changes in tools-list-kotlin-changes.json, no more.
 */
class McpToolsListTest : ParityTest() {
    @Test
    fun `tools list matches the recorded payload exactly`() = parity {
        val recorded = Parity.json.parseToJsonElement(
            McpToolsListTest::class.java.getResource("/tools-list.json")!!.readText(),
        ).jsonArray
        val expected = if (Parity.kotlin) ToolsListChanges.apply(recorded) else recorded
        val source = if (Parity.kotlin) "tools-list.json with tools-list-kotlin-changes.json" else "tools-list.json"
        val live = alpha.rpcResult("tools/list", path = ALL_TOOLS)["result"]["tools"].arr
        assertEquals(expected.map { it["name"].s }, live.map { it["name"].s })
        for ((want, got) in expected.zip(live)) {
            assertEquals(want, got, "tool ${want["name"].s} differs from server/parity/src/test/resources/$source")
        }
    }
}

/** Where tools/list gives every tool: tool discovery's switch on the Kotlin server, ignored by the TS one. */
const val ALL_TOOLS = "/mcp?tools=all"
