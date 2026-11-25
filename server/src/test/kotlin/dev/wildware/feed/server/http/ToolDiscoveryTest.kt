package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.mcp.Discovery
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tool discovery: plain `/mcp` lists only list_tools, describe_tools and call_tool;
 * `/mcp?tools=all` lists every tool as before; call_tool runs a tool exactly as a direct call
 * does, and a direct call by a tool's own name keeps working in both modes.
 */
class ToolDiscoveryTest {
    private fun config(dir: String) = Config(
        dataDir = dir, adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
        secretsKeys = Config.SecretsKeys("k".repeat(40)),
    )

    private class Ctx(val app: App, val client: HttpClient) {
        val ownerKey = app.devices.mint("Owner phone").second
        val scout: Pair<Agents.Agent, String> = app.agents.mint("scout")
        val other: Pair<Agents.Agent, String> = app.agents.mint("other")
        val scoutToken get() = scout.second
        val otherToken get() = other.second
    }

    private fun withApp(block: suspend Ctx.() -> Unit) {
        val dir = Files.createTempDirectory("tool-discovery").toFile()
        val app = App(config(dir.path), Database.memory())
        try {
            testApplication {
                application { feed(app) }
                Ctx(app, client).block()
            }
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    private suspend fun Ctx.rpc(token: String, method: String, params: JsonObject? = null, path: String = "/mcp"): JsonObject {
        val r = client.request(path) {
            this.method = HttpMethod.Post
            header("Authorization", "Bearer $token")
            header("Accept", "application/json, text/event-stream")
            header("Content-Type", "application/json")
            setBody(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", 1); put("method", method)
                if (params != null) put("params", params)
            }.toString())
        }
        assertEquals(200, r.status.value, r.bodyAsText())
        return Wire.json.parseToJsonElement(r.bodyAsText()).jsonObject
    }

    private suspend fun Ctx.tools(path: String = "/mcp") = rpc(scoutToken, "tools/list", path = path)["result"]!!.jsonObject["tools"]!!.jsonArray

    /** A direct tools/call by the tool's own name. */
    private suspend fun Ctx.direct(token: String, name: String, args: JsonObject = JsonObject(emptyMap()), path: String = "/mcp") =
        rpc(token, "tools/call", buildJsonObject { put("name", name); put("arguments", args) }, path)["result"]!!.jsonObject

    /** The same tool run through call_tool. */
    private suspend fun Ctx.viaCall(token: String, name: String, args: JsonElement = JsonObject(emptyMap())) =
        direct(token, "call_tool", buildJsonObject { put("name", name); put("arguments", args) })

    private fun JsonObject.error(): String? = this["structuredContent"]?.jsonObject?.get("error")?.jsonPrimitive?.content
    private fun JsonObject.text(): String = this["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    private fun JsonObject.data(): JsonObject = this["structuredContent"]!!.jsonObject
    private fun names(tools: JsonArray) = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { for ((k, v) in pairs) put(k, v) }

    private val advertised: JsonArray =
        Wire.json.parseToJsonElement(javaClass.getResource("/mcp/tools-list.json")!!.readText()).jsonArray

    @Test
    fun `plain mcp lists only the discovery tools and tools=all lists every tool as before`() = withApp {
        assertEquals(listOf("list_tools", "describe_tools", "call_tool"), names(tools()))
        assertEquals(advertised, tools("/mcp?tools=all"))
        // Anything else asks for the default.
        assertEquals(listOf("list_tools", "describe_tools", "call_tool"), names(tools("/mcp?tools=some")))
        for (tool in tools()) {
            val t = tool.jsonObject
            assertEquals("object", t["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertTrue(t["title"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue(t["description"]!!.jsonPrimitive.content.isNotBlank())
        }
    }

    @Test
    fun `the instructions teach the discovery flow only where it is used, and keep the rules`() = withApp {
        val init = buildJsonObject { put("protocolVersion", "2025-06-18"); put("capabilities", JsonObject(emptyMap())) }
        val discovery = rpc(scoutToken, "initialize", init)["result"]!!.jsonObject["instructions"]!!.jsonPrimitive.content
        val all = rpc(scoutToken, "initialize", init, "/mcp?tools=all")["result"]!!.jsonObject["instructions"]!!.jsonPrimitive.content
        for (text in listOf(discovery, all)) {
            assertTrue(text.startsWith("This is an Agent Dashboard: a status wall a human owner watches live while you work."))
            assertContains(text, "acknowledge({message_id, state: \"thinking\"}) before you start")
            assertContains(text, "Secrets (API keys, passwords) never go through request_input")
            assertContains(text, "priority \"critical\" pages your owner")
            assertContains(text, "Talk to people only through the feed tools (post_message, post_update, request_input).")
            assertFalse("{{tools}}" in text)
        }
        assertContains(discovery, "call_tool({name, arguments})")
        for (everyday in listOf("acknowledge {", "post_message {", "get_messages {", "request_input {", "heartbeat {", "read_doc {")) {
            assertContains(discovery, everyday)
        }
        // Nothing teaches the retired wait any more.
        for (text in listOf(discovery, all)) assertFalse("await_request" in text, text)
        assertFalse("call_tool" in all)
    }

    @Test
    fun `every tool has an area and list_tools lists each once`() = withApp {
        assertEquals(names(advertised).toSet(), Discovery.AREAS.values.flatten().toSet())
        assertEquals(Discovery.AREAS.values.flatten().size, Discovery.AREAS.values.flatten().toSet().size)

        val listed = direct(scoutToken, "list_tools")
        assertNull(listed["isError"])
        val areas = listed.data()["areas"]!!.jsonArray.map { it.jsonObject }
        assertEquals(Discovery.AREAS.keys.toList(), areas.map { it["area"]!!.jsonPrimitive.content })
        val inList = areas.flatMap { a -> a["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content } }
        assertEquals(names(advertised).sorted(), inList.sorted())
        assertEquals(advertised.size, listed.data()["count"]!!.jsonPrimitive.content.toInt())
        // A summary is the tool's title.
        val post = areas.first { it["area"]!!.jsonPrimitive.content == "updates" }["tools"]!!.jsonArray.first().jsonObject
        assertEquals("post_update", post["name"]!!.jsonPrimitive.content)
        assertEquals("Post a status update", post["summary"]!!.jsonPrimitive.content)
    }

    @Test
    fun `list_tools filters by every word of its query`() = withApp {
        fun JsonObject.listed() = data()["areas"]!!.jsonArray.flatMap { a -> a.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content } }
        val secrets = direct(scoutToken, "list_tools", args("query" to "SECRET")).listed()
        assertTrue(secrets.containsAll(listOf("list_secrets", "secret_url", "create_secret_upload", "request_secret")), secrets.toString())
        assertFalse("create_doc" in secrets)
        val editDoc = direct(scoutToken, "list_tools", args("query" to "edit doc")).listed()
        assertTrue("edit_doc" in editDoc, editDoc.toString())
        assertFalse("list_projects" in editDoc)
        val none = direct(scoutToken, "list_tools", args("query" to "zzzz-nothing"))
        assertEquals(0, none.data()["count"]!!.jsonPrimitive.content.toInt())
        assertContains(none.text(), "No tool matches")
    }

    @Test
    fun `describe_tools gives the same entries the full list does`() = withApp {
        val full = tools("/mcp?tools=all").associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        val described = direct(scoutToken, "describe_tools", buildJsonObject {
            put("names", buildJsonArray { add(JsonPrimitive("post_update")); add(JsonPrimitive("mcp__plugin_x_agent-dashboard__read_doc")); add(JsonPrimitive("nope")) })
        })
        assertNull(described["isError"])
        val got = described.data()["tools"]!!.jsonArray
        assertEquals(listOf(full["post_update"], full["read_doc"]), got.toList())
        assertEquals(listOf("nope"), described.data()["unknown"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertContains(described.text(), "list_tools")
        // Every tool, in batches of the most it takes.
        for (batch in full.keys.chunked(20)) {
            val each = direct(scoutToken, "describe_tools", buildJsonObject { put("names", JsonArray(batch.map(::JsonPrimitive))) })
            assertEquals(batch.map { full[it] }, each.data()["tools"]!!.jsonArray.toList())
        }
        val tooMany = direct(scoutToken, "describe_tools", buildJsonObject { put("names", JsonArray(full.keys.take(21).map(::JsonPrimitive))) })
        assertEquals("true", tooMany["isError"]!!.jsonPrimitive.content)
        assertContains(tooMany.text(), "Too big: expected array to have <=20 items at names")
    }

    @Test
    fun `call_tool answers exactly as the direct call does, and the direct call works in both modes`() = withApp {
        val made = viaCall(scoutToken, "create_project", args("name" to "Web", "slug" to "web"))
        assertNull(made["isError"], made.toString())
        assertEquals("web", made.data()["slug"]?.jsonPrimitive?.content ?: made.data()["project"]!!.jsonObject["slug"]!!.jsonPrimitive.content)
        assertEquals(direct(scoutToken, "list_projects"), viaCall(scoutToken, "list_projects"))
        assertEquals(direct(scoutToken, "list_projects", path = "/mcp?tools=all"), viaCall(scoutToken, "list_projects"))
        val posted = viaCall(scoutToken, "post_update", args("channel" to "web", "body" to "hello from call_tool"))
        assertNull(posted["isError"], posted.toString())
        val direct = direct(scoutToken, "post_update", args("channel" to "web", "body" to "hello directly"))
        assertEquals(direct.data().keys, posted.data().keys)
        // A Claude Code style name, with its mcp__<server>__ prefix, finds the same tool.
        assertEquals(direct(scoutToken, "list_projects"), viaCall(scoutToken, "mcp__plugin_wildware-feed_agent-dashboard__list_projects"))
        // A tool with no arguments may leave them out.
        val bare = direct(scoutToken, "call_tool", args("name" to "list_projects"))
        assertEquals(direct(scoutToken, "list_projects"), bare)
    }

    @Test
    fun `call_tool keeps every permission check`() = withApp {
        viaCall(scoutToken, "create_project", args("name" to "Secret", "slug" to "secret"))
        val otherId = other.first.id
        client.request("/api/projects/secret/access/agent/$otherId") {
            method = HttpMethod.Put
            header("Authorization", "Bearer $ownerKey")
            header("Content-Type", "application/json")
            setBody("""{"level":"none"}""")
        }.also { assertEquals(200, it.status.value, it.bodyAsText()) }
        val hidden = viaCall(otherToken, "post_message", args("channel" to "secret", "body" to "hi"))
        assertEquals("not_found", hidden.error())
        assertEquals(direct(otherToken, "post_message", args("channel" to "secret", "body" to "hi")), hidden)
        assertEquals("not_found", viaCall(otherToken, "get_messages", args("channel" to "secret")).error())

        client.request("/api/projects/secret/access/agent/$otherId") {
            method = HttpMethod.Put
            header("Authorization", "Bearer $ownerKey")
            header("Content-Type", "application/json")
            setBody("""{"level":"read"}""")
        }.also { assertEquals(200, it.status.value, it.bodyAsText()) }
        assertNull(viaCall(otherToken, "get_messages", args("channel" to "secret")).error())
        val refused = viaCall(otherToken, "post_message", args("channel" to "secret", "body" to "hi"))
        assertEquals("forbidden", refused.error())
        assertEquals(direct(otherToken, "post_message", args("channel" to "secret", "body" to "hi")), refused)
        assertEquals("forbidden", viaCall(otherToken, "post_update", args("project" to "secret", "body" to "hi")).error())
    }

    @Test
    fun `bad arguments give the tool's own error plus a pointer to describe_tools`() = withApp {
        val bad = buildJsonObject { put("body", 5) }
        val direct = direct(scoutToken, "post_message", bad)
        val through = viaCall(scoutToken, "post_message", bad)
        assertEquals("true", through["isError"]!!.jsonPrimitive.content)
        assertTrue(direct.text().startsWith("MCP error -32602: Input validation error: Invalid arguments for tool post_message:"), direct.text())
        assertEquals(direct.text() + "\nCall describe_tools({names: [\"post_message\"]}) for its arguments.", through.text())
        assertFalse("describe_tools" in direct.text())

        // call_tool's own arguments are checked too.
        val notObject = direct(scoutToken, "call_tool", buildJsonObject { put("name", "list_projects"); put("arguments", "nope") })
        assertContains(notObject.text(), "Invalid arguments for tool call_tool: Invalid input: expected record, received string at arguments")
        val noName = direct(scoutToken, "call_tool", JsonObject(emptyMap()))
        assertContains(noName.text(), "Invalid arguments for tool call_tool")
    }

    @Test
    fun `call_tool runs forms, waits, secrets and uploads as the direct call does`() = withApp {
        viaCall(scoutToken, "create_project", args("name" to "Web", "slug" to "web"))
        fun form(question: String) = buildJsonObject {
            put("kind", "form"); put("question", question); put("channel", "web")
            put("fields", buildJsonArray {
                add(buildJsonObject { put("id", "name"); put("label", "Name"); put("type", "text"); put("required", true) })
                add(buildJsonObject { put("id", "size"); put("label", "Size"); put("type", "select"); put("options", buildJsonArray { add(JsonPrimitive("S")); add(JsonPrimitive("L")) }) })
            })
        }
        fun same(through: JsonObject, direct: JsonObject) {
            assertNull(through["isError"], through.toString())
            assertNull(direct["isError"], direct.toString())
            assertEquals(direct.data().keys, through.data().keys)
        }
        val asked = viaCall(scoutToken, "request_input", form("Which one?"))
        same(asked, direct(scoutToken, "request_input", form("Which other?")))
        val requestId = asked.data()["request_id"]!!.jsonPrimitive.content

        // The retired await_request, through call_tool, answers at once as the direct call does.
        val waited = viaCall(scoutToken, "await_request", args("request_id" to requestId))
        assertEquals(direct(scoutToken, "await_request", args("request_id" to requestId)).data()["state"], waited.data()["state"])
        assertNull(waited["isError"], waited.toString())

        val secret = viaCall(scoutToken, "request_secret", args("name" to "STRIPE_KEY", "question" to "The Stripe key, please", "channel" to "web"))
        same(secret, direct(scoutToken, "request_secret", args("name" to "OTHER_KEY", "question" to "Another key", "channel" to "web")))
        assertEquals(direct(scoutToken, "secret_url", args("name" to "NO_SUCH")), viaCall(scoutToken, "secret_url", args("name" to "NO_SUCH")))

        val upload = buildJsonObject { put("filename", "shot.png"); put("mime", "image/png"); put("bytes", 1000) }
        same(viaCall(scoutToken, "create_upload", upload), direct(scoutToken, "create_upload", upload))
    }

    @Test
    fun `await_request is in no list, and an older session calling it hears where the request stands at once`() = withApp {
        assertFalse("await_request" in names(tools()) + names(tools("/mcp?tools=all")))
        val listed = direct(scoutToken, "list_tools").data().toString()
        assertFalse("await_request" in listed, listed)
        val described = direct(scoutToken, "describe_tools", buildJsonObject { put("names", buildJsonArray { add(JsonPrimitive("await_request")) }) })
        assertEquals(listOf("await_request"), described.data()["unknown"]!!.jsonArray.map { it.jsonPrimitive.content })
        for (tool in tools("/mcp?tools=all")) {
            assertFalse("await_request" in tool.toString(), tool.jsonObject["name"].toString())
        }

        viaCall(scoutToken, "create_project", args("name" to "Web", "slug" to "web"))
        val started = System.nanoTime()
        val asked = direct(scoutToken, "request_input", args("kind" to "confirm", "question" to "Ship it?", "channel" to "web"))
        val requestId = asked.data()["request_id"]!!.jsonPrimitive.content
        assertEquals("pending", asked.data()["state"]!!.jsonPrimitive.content)
        for (legacy in listOf(direct(scoutToken, "await_request", args("request_id" to requestId)), viaCall(scoutToken, "await_request", args("request_id" to requestId)))) {
            assertNull(legacy["isError"], legacy.toString())
            assertEquals("pending", legacy.data()["state"]!!.jsonPrimitive.content)
            assertContains(legacy.text(), "await_request is retired and never waits now.")
            assertContains(legacy.text(), "get_messages({request_id})")
        }
        assertTrue(System.nanoTime() - started < 5_000_000_000L, "nothing waited")
        // Bad arguments still get the schema's error.
        assertContains(direct(scoutToken, "await_request").text(), "Invalid arguments for tool await_request")
    }

    @Test
    fun `the server does not promise tool list change notices it cannot send`() = withApp {
        val init = buildJsonObject { put("protocolVersion", "2025-06-18"); put("capabilities", JsonObject(emptyMap())) }
        for (path in listOf("/mcp", "/mcp?tools=all")) {
            val tools = rpc(scoutToken, "initialize", init, path)["result"]!!.jsonObject["capabilities"]!!.jsonObject["tools"]!!.jsonObject
            assertEquals(JsonPrimitive(false), tools["listChanged"], path)
        }
    }

    @Test
    fun `an unknown tool is refused with a pointer to list_tools`() = withApp {
        val unknown = viaCall(scoutToken, "post_everything")
        assertEquals("true", unknown["isError"]!!.jsonPrimitive.content)
        assertContains(unknown.text(), "Tool post_everything not found")
        assertContains(unknown.text(), "list_tools")
        // A direct call answers as it always has.
        assertEquals("MCP error -32602: Tool post_everything not found", direct(scoutToken, "post_everything").text())
        // The discovery tools themselves are called directly, not through call_tool.
        val nested = viaCall(scoutToken, "call_tool", args("name" to "list_projects"))
        assertEquals("true", nested["isError"]!!.jsonPrimitive.content)
        assertContains(nested.text(), "call it directly")
    }
}
