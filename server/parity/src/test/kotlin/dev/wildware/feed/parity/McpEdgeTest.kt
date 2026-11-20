package dev.wildware.feed.parity

import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * The MCP tools on their edges: the SDK's argument errors word for word, every domain refusal with
 * its code, blanks, unknown and deleted rows, other agents' rows, repeats, singular and plural
 * summaries, and the long-poll. Each answer was first taken from the TypeScript server.
 * Spec: server/spec/05-mcp.md §1.3, §3, §4, §5, §7.
 */
class McpEdgeTest : ParityTest() {
    private val nobody = "01ZZZZZZZZZZZZZZZZZZZZZZZZ"

    // Agents of their own, so scope-sensitive counts are exact and no one token hits the rate limit.
    private val sdkAgent by lazy { Parity.mintAgent(uniq("edge-sdk")) }
    private val feedAgent by lazy { Parity.mintAgent(uniq("edge-feed")) }
    private val cardAgent by lazy { Parity.mintAgent(uniq("edge-cards")) }
    private val messageAgent by lazy { Parity.mintAgent(uniq("edge-msgs")) }
    private val tagAgent by lazy { Parity.mintAgent(uniq("edge-tags")) }
    private val fieldAgent by lazy { Parity.mintAgent(uniq("edge-fields")) }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** A domain refusal, the whole result: `<code>: <message>` and structuredContent. */
    private suspend fun Agent.refused(tool: String, args: JsonObject, code: String, message: String) {
        val result = tool(tool, args)
        assertEquals(
            obj(
                "content" to arr(obj("type" to "text", "text" to "$code: $message")),
                "structuredContent" to obj("error" to code, "message" to message),
                "isError" to true,
            ),
            result.raw,
            "$tool ${args}",
        )
    }

    /** An argument error from the SDK, the whole result: text only, no structuredContent. */
    private suspend fun Agent.sdk(tool: String, args: JsonObject?, issues: String) {
        val envelope = rpcResult("tools/call", buildJsonObject {
            put("name", tool)
            if (args != null) put("arguments", args)
        })
        assertEquals(
            obj(
                "content" to arr(obj("type" to "text", "text" to "MCP error -32602: Input validation error: Invalid arguments for tool $tool: $issues")),
                "isError" to true,
            ),
            envelope["result"],
            "$tool $args",
        )
    }

    /**
     * A success, the whole result: one text block of `<summary>\n<the JSON, 2-space indented>`, and
     * structuredContent equal to [expected] once timestamps are replaced by [TS].
     */
    private fun whole(result: ToolResult, summary: String, expected: JsonObject) {
        assertEquals(listOf("content", "structuredContent"), result.raw.keys.toList(), result.raw.toString())
        val content = result.raw["content"].arr
        assertEquals(1, content.size, result.raw.toString())
        val text = content[0]["text"].s
        assertEquals(summary, text.substringBefore('\n'), result.raw.toString())
        val body = text.substringAfter('\n')
        assertTrue(body.startsWith("{\n  \""), "the JSON is indented by two spaces: $body")
        assertEquals(result.structured, Parity.json.parseToJsonElement(body), "the text carries the same JSON")
        assertEquals(expected, stamped(result.structured), result.raw.toString())
    }

    private suspend fun Agent.whole(tool: String, args: JsonObject, summary: String, expected: JsonObject): JsonObject {
        val result = tool(tool, args)
        whole(result, summary, expected)
        return result.structured
    }

    private suspend fun Agent.summary(tool: String, args: JsonObject): String {
        val result = tool(tool, args)
        if (result.isError) fail("$tool failed: ${result.raw}")
        return result.text.substringBefore('\n')
    }

    private val iso = Regex("""^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$""")

    /** Every ISO timestamp replaced by [TS], so a whole result can be compared. */
    private fun stamped(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { stamped(it.value) })
        is JsonArray -> JsonArray(e.map(::stamped))
        is JsonPrimitive -> if (e.isString && iso.matches(e.content)) JsonPrimitive(TS) else e
    }

    private fun project(p: JsonObject, status: String = "active", theme: Any? = null) = obj(
        "id" to p["id"], "slug" to p["slug"], "name" to p["name"], "description" to p["description"],
        "status" to status, "pinned" to false, "created_at" to TS, "updated_at" to TS, "theme" to theme,
    )

    /** A channel as list_projects shows it: the Kotlin server adds the project (group) it is in (spec/12). */
    private fun listed(p: JsonObject, status: String = "active") =
        if (Parity.kotlin) JsonObject(project(p, status) + ("group" to JsonNull)) else project(p, status)

    private fun update(id: String, projectId: String, agentId: String, bodyChars: Int, title: String? = null, level: String = "info", session: String? = null, edited: Boolean = false) = obj(
        "id" to id, "project_id" to projectId, "agent_id" to agentId, "session_id" to session, "title" to title,
        "level" to level, "pinned" to false, "body_chars" to bodyChars, "created_at" to TS,
        "edited_at" to (if (edited) TS else null), "status" to null,
    )

    private fun card(kind: String, id: String) = obj("kind" to kind, "id" to id)

    private suspend fun ownerMessage(update: String, body: String): String =
        owner.post("/api/messages", obj("update" to update, "body" to body)).obj()["message"]["id"].s

    // ── the SDK ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `argument errors carry the SDK's wording, every issue, with nested paths`() = parity {
        val a = sdkAgent
        val x = { n: Int -> "x".repeat(n) }
        a.sdk("list_projects", null, "Invalid input: expected object, received undefined")
        a.sdk("create_project", obj(), "Invalid input: expected string, received undefined at name")
        a.sdk("create_project", obj("name" to 5), "Invalid input: expected string, received number at name")
        a.sdk("create_project", obj("name" to null), "Invalid input: expected string, received null at name")
        a.sdk("create_project", obj("name" to x(201)), "Too big: expected string to have <=200 characters at name")
        a.sdk("create_project", obj("name" to "😀".repeat(101)), "Too big: expected string to have <=200 characters at name")
        a.sdk("create_project", obj("name" to "n", "slug" to x(65)), "Too big: expected string to have <=64 characters at slug")
        a.sdk(
            "create_project", obj("name" to true, "slug" to 7, "description" to x(2001)),
            "Invalid input: expected string, received boolean at name\nInvalid input: expected string, received number at slug\nToo big: expected string to have <=2000 characters at description",
        )
        a.sdk("list_projects", obj("status" to 5), "Invalid option: expected one of \"active\"|\"archived\" at status")
        a.sdk("list_projects", obj("status" to null), "Invalid option: expected one of \"active\"|\"archived\" at status")

        val p = obj("project" to "p", "body" to "b")
        fun pu(vararg extra: Pair<String, Any?>) = JsonObject(p + obj(*extra))
        // The Kotlin server takes channel or project (its older name), so the tool, not the schema, asks for one.
        if (Parity.kotlin) {
            a.sdk("post_update", obj(), "Invalid input: expected string, received undefined at body")
            a.refused("post_update", obj("body" to "b"), "invalid_argument", "channel is required")
        } else {
            a.sdk("post_update", obj(), "Invalid input: expected string, received undefined at project\nInvalid input: expected string, received undefined at body")
        }
        a.sdk("post_update", pu("level" to "loud"), "Invalid option: expected one of \"info\"|\"success\"|\"warn\"|\"error\" at level")
        // The Kotlin server also takes "critical" (tools-list-kotlin-changes.json).
        val priorities = if (Parity.kotlin) "\"low\"|\"medium\"|\"high\"|\"critical\"" else "\"low\"|\"medium\"|\"high\""
        a.sdk("post_update", pu("priority" to null), "Invalid option: expected one of $priorities at priority")
        a.sdk("post_update", pu("body" to x(100_001)), "Too big: expected string to have <=100000 characters at body")
        a.sdk("post_update", pu("title" to x(201)), "Too big: expected string to have <=200 characters at title")
        a.sdk("post_update", pu("media_ids" to "x"), "Invalid input: expected array, received string at media_ids")
        a.sdk("post_update", pu("media_ids" to arr("ok", 1)), "Invalid input: expected string, received number at media_ids[1]")
        a.sdk("post_update", pu("media_ids" to (0 until 25).map { "m$it" }), "Too big: expected array to have <=24 items at media_ids")
        a.sdk("post_update", pu("embeds" to arr(obj("type" to "x"))), "Invalid input: expected record, received undefined at embeds[0].data")
        a.sdk(
            "post_update", pu("embeds" to arr(obj("type" to 1, "data" to arr()))),
            "Invalid input: expected string, received number at embeds[0].type\nInvalid input: expected record, received array at embeds[0].data",
        )
        a.sdk(
            "post_update", pu("embeds" to arr(obj("type" to "t", "data" to "x", "source_url" to 3))),
            "Invalid input: expected record, received string at embeds[0].data\nInvalid input: expected string, received number at embeds[0].source_url",
        )
        a.sdk("post_update", pu("embeds" to (0 until 9).map { obj("type" to "t", "data" to obj()) }), "Too big: expected array to have <=8 items at embeds")
        a.sdk("post_update", pu("apps" to arr(obj())), "Invalid input: expected string, received undefined at apps[0].app")
        a.sdk("post_update", pu("apps" to arr(obj("app" to "ui://a"), obj("app" to "ui://b", "title" to 5))), "Invalid input: expected string, received number at apps[1].title")
        a.sdk("post_update", pu("session_id" to 5), "Invalid input: expected string, received number at session_id")

        a.sdk("edit_update", obj("update_id" to "x", "title" to 5), "Invalid input: expected string, received number at title")
        a.sdk("edit_update", obj("update_id" to "x", "title" to x(201)), "Too big: expected string to have <=200 characters at title")
        a.sdk("edit_update", obj("body" to "x"), "Invalid input: expected string, received undefined at update_id")

        a.sdk("register_session", obj("meta" to "x"), "Invalid input: expected object, received string at meta")
        a.sdk("register_session", obj("meta" to null), "Invalid input: expected object, received null at meta")
        a.sdk(
            "register_session", obj("meta" to obj("host" to 5, "cwd" to x(1001), "model" to x(101))),
            "Invalid input: expected string, received number at meta.host\nToo big: expected string to have <=1000 characters at meta.cwd\nToo big: expected string to have <=100 characters at meta.model",
        )
        a.sdk("heartbeat", obj(), "Invalid input: expected string, received undefined at session_id")
        a.sdk("heartbeat", obj("session_id" to null), "Invalid input: expected string, received null at session_id")
        a.sdk("end_session", obj("session_id" to 1), "Invalid input: expected string, received number at session_id")

        val statuses = "Invalid option: expected one of \"todo\"|\"in_progress\"|\"blocked\"|\"done\"|\"cancelled\""
        a.sdk("set_status", obj(), "$statuses at status")
        a.sdk("set_status", obj("status" to "nope"), "$statuses at status")
        a.sdk("set_status", obj("status" to 3), "$statuses at status")
        a.sdk("list_work", obj("limit" to 1.5), "Invalid input: expected int, received number at limit")
        a.sdk("list_work", obj("limit" to 201), "Too big: expected number to be <=200 at limit")
        a.sdk("list_work", obj("limit" to -1), "Too small: expected number to be >0 at limit")
        a.sdk("list_work", obj("limit" to "5"), "Invalid input: expected number, received string at limit")
        a.sdk("list_work", obj("status" to arr("todo", "nope")), "$statuses at status[1]")
        a.sdk("list_work", obj("status" to "todo"), "Invalid input: expected array, received string at status")
        a.sdk("list_tags", obj("project" to 1), "Invalid input: expected string, received number at project")
        a.sdk(
            "tag_card", obj("tag" to 5, "on" to "yes", "create" to 1),
            "Invalid input: expected string, received number at tag\nInvalid input: expected boolean, received string at on\nInvalid input: expected boolean, received number at create",
        )
        a.sdk("tag_card", obj(), "Invalid input: expected string, received undefined at tag")
        a.sdk("set_card_field", obj("name" to "n"), "Invalid input: expected string, received undefined at value")
        a.sdk("set_card_field", obj("name" to "n", "value" to 5), "Invalid input: expected string, received number at value")
        a.sdk("set_card_field", obj(), "Invalid input: expected string, received undefined at name\nInvalid input: expected string, received undefined at value")
        a.sdk("get_card_fields", obj("update_id" to 5), "Invalid input: expected string, received number at update_id")
        a.sdk("get_messages", obj("mark_read" to "false"), "Invalid input: expected boolean, received string at mark_read")
        a.sdk("get_messages", obj("since" to 5), "Invalid input: expected string, received number at since")
        a.sdk("acknowledge", obj(), "Invalid option: expected one of \"thinking\"|\"read\"|\"done\" at state")
        a.sdk("acknowledge", obj("state" to "nope", "message_id" to "x"), "Invalid option: expected one of \"thinking\"|\"read\"|\"done\" at state")
        a.sdk("post_message", obj(), "Invalid input: expected string, received undefined at body")
        a.sdk("edit_message", obj("message_id" to "x", "body" to x(10_001)), "Too big: expected string to have <=10000 characters at body")
        a.sdk("edit_message", obj(), "Invalid input: expected string, received undefined at message_id\nInvalid input: expected string, received undefined at body")
        a.sdk("delete_message", obj(), "Invalid input: expected string, received undefined at message_id")
        a.sdk("react", obj(), "Invalid input: expected string, received undefined at emoji")
        a.sdk("react", obj("emoji" to "x", "on" to 1), "Invalid input: expected boolean, received number at on")

        val kinds = "Invalid option: expected one of \"text\"|\"confirm\"|\"buttons\"|\"choice\"|\"multi_choice\"|\"form\" at kind"
        val q = obj("kind" to "text", "question" to "q")
        fun ri(vararg extra: Pair<String, Any?>) = JsonObject(q + obj(*extra))
        a.sdk("request_input", obj(), "$kinds\nInvalid input: expected string, received undefined at question")
        a.sdk("request_input", obj("kind" to "nope"), "$kinds\nInvalid input: expected string, received undefined at question")
        a.sdk("request_input", ri("min" to 1.5), "Invalid input: expected int, received number at min")
        a.sdk("request_input", ri("min" to 9007199254740992L), "Too big: expected int to be <=9007199254740991 at min")
        a.sdk("request_input", ri("max" to -9007199254740992L), "Too small: expected int to be >=-9007199254740991 at max")
        a.sdk("request_input", ri("timeout_s" to "5"), "Invalid input: expected number, received string at timeout_s")
        a.sdk("request_input", ri("timeout_s" to 5.5), "Invalid input: expected int, received number at timeout_s")
        a.sdk("request_input", ri("multiline" to "yes"), "Invalid input: expected boolean, received string at multiline")
        a.sdk("request_input", ri("embeds" to arr(obj("data" to obj()))), "Invalid input: expected string, received undefined at embeds[0].type")
        val choice = obj("kind" to "choice", "question" to "q", "options" to arr("a"))
        a.sdk(
            "request_input", JsonObject(choice + obj("option_media" to obj("a" to 1, "b c" to null))),
            "Invalid input: expected string, received number at option_media.a\nInvalid input: expected string, received null at option_media.b c",
        )
        a.sdk("request_input", JsonObject(choice + obj("option_media" to arr("x"))), "Invalid input: expected record, received array at option_media")
        a.sdk(
            "request_input", obj("kind" to "choice", "question" to "q", "options" to arr("a", 2, null)),
            "Invalid input: expected string, received number at options[1]\nInvalid input: expected string, received null at options[2]",
        )
        a.sdk("await_request", obj(), "Invalid input: expected string, received undefined at request_id")
        a.sdk("edit_request", obj("request_id" to "x", "options" to arr("a", x(201))), "Too big: expected string to have <=200 characters at options[1]")
        a.sdk(
            "edit_request", obj("request_id" to "x", "detail" to x(4001), "default" to x(10_001), "question" to x(501)),
            "Too big: expected string to have <=500 characters at question\nToo big: expected string to have <=4000 characters at detail\nToo big: expected string to have <=10000 characters at default",
        )
        a.sdk("edit_request", obj("request_id" to "x", "detail" to 5), "Invalid input: expected string, received number at detail")
        a.sdk("withdraw_request", obj("request_id" to 5), "Invalid input: expected string, received number at request_id")

        // A domain rule with no zod limit behind it is the domain's error, not the SDK's.
        a.refused("post_message", obj("body" to "b", "media_ids" to (0 until 30).map { "m$it" }), "invalid_argument", "an update may carry at most 24 media items")
        // Keys the schema does not name are dropped, nested ones too; a dropped card id leaves none.
        assertTrue(!a.tool("list_projects", obj("bogus" to 1, "status" to "active")).isError)
        assertTrue(!a.tool("register_session", obj("meta" to obj("host" to "h", "extra" to 1), "bogus" to 2)).isError)
        a.refused("set_status", obj("request_id" to nobody, "status" to "todo"), "invalid_argument", "name one of: update, message, request")

        // Unknown tools.
        for (name in listOf("nope", "")) {
            assertEquals(
                obj("content" to arr(obj("type" to "text", "text" to "MCP error -32602: Tool $name not found")), "isError" to true),
                a.rpcResult("tools/call", obj("name" to name, "arguments" to obj()))["result"],
            )
        }
    }

    @Test
    fun `tools-call params themselves are checked first, as a JSON-RPC error`() = parity {
        fun issue(expected: String, path: List<String>, received: String) =
            "  {\n    \"expected\": \"$expected\",\n    \"code\": \"invalid_type\",\n    \"path\": [\n" +
                path.joinToString(",\n") { "      \"$it\"" } + "\n    ],\n    \"message\": \"Invalid input: expected $expected, received $received\"\n  }"
        suspend fun expect(params: JsonObject?, vararg issues: String) {
            val envelope = sdkAgent.rpcResult("tools/call", params)
            assertEquals(listOf("jsonrpc", "id", "error"), envelope.keys.toList(), envelope.toString())
            assertEquals(obj("code" to -32603, "message" to "[\n${issues.joinToString(",\n")}\n]"), envelope["error"], envelope.toString())
        }
        expect(null, issue("object", listOf("params"), "undefined"))
        expect(obj("arguments" to obj()), issue("string", listOf("params", "name"), "undefined"))
        expect(obj("name" to "list_projects", "arguments" to null), issue("record", listOf("params", "arguments"), "null"))
        expect(obj("name" to "list_projects", "arguments" to arr()), issue("record", listOf("params", "arguments"), "array"))
        expect(obj("name" to "list_projects", "arguments" to "hi"), issue("record", listOf("params", "arguments"), "string"))
        expect(obj("name" to 5, "arguments" to 5), issue("string", listOf("params", "name"), "number"), issue("record", listOf("params", "arguments"), "number"))
    }

    // ── projects ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `create_project - blanks, slugs from odd names, explicit slugs, repeats`() = parity {
        val a = feedAgent
        val sfx = Parity.suffix()
        for (blank in listOf("   ", "", "\ufeff", "\u2028\u00a0\u3000")) a.refused("create_project", obj("name" to blank), "invalid_argument", "name is required")
        for (unsluggable in listOf("!!!", "😀".repeat(100), "\u001c", "\u0085", "\u200b")) {
            a.refused("create_project", obj("name" to unsluggable), "invalid_argument", "could not derive a slug from name; pass slug explicitly")
        }
        val badSlug = "slug must be lowercase letters, digits and single hyphens, at most 64 characters"
        for (slug in listOf("bad slug", "a--b", "café", "İ")) a.refused("create_project", obj("name" to "n", "slug" to slug), "invalid_argument", badSlug)

        val padded = a.tool("create_project", obj("name" to "  Padded Name $sfx  ", "description" to "   "))
        val pp = padded.structured["project"].o
        whole(padded, "Created project \"Padded Name $sfx\" (slug padded-name-$sfx).", obj(
            "project" to obj("id" to pp["id"], "slug" to "padded-name-$sfx", "name" to "Padded Name $sfx", "description" to null, "status" to "active",
                "pinned" to false, "created_at" to TS, "updated_at" to TS, "theme" to null),
            "created" to true,
        ))
        assertEquals("cafe-unicode-mega-$sfx", a.ok("create_project", obj("name" to "Café Ünïcode Ωmega $sfx"))["project"]["slug"].s)
        assertEquals("stra-e-fine-xii-dz-$sfx", a.ok("create_project", obj("name" to "Straße ﬁne Ⅻ Ǆ $sfx"))["project"]["slug"].s)
        assertEquals("istanbul-$sfx", a.ok("create_project", obj("name" to "İstanbul $sfx"))["project"]["slug"].s)
        // A long name's slug is cut to 64 and loses its trailing hyphen.
        val words = a.ok("create_project", obj("name" to "$sfx " + "word ".repeat(30)))["project"]["slug"].s
        assertEquals("$sfx-" + "word-".repeat(30).take(64 - sfx.length - 1).trimEnd('-'), words)
        // A blank slug is no slug: it is derived from the name.
        assertEquals("blank-slug-$sfx", a.ok("create_project", obj("name" to "Blank Slug $sfx", "slug" to "   "))["project"]["slug"].s)

        // An explicit slug is trimmed and lowered; a repeat on it returns the first project unchanged, for anyone.
        val first = a.tool("create_project", obj("name" to "!!!", "slug" to "  Edge-Slug-$sfx  "))
        val fp = first.structured["project"].o
        assertEquals("edge-slug-$sfx", fp["slug"].s)
        whole(first, "Created project \"!!!\" (slug edge-slug-$sfx).", obj("project" to project(fp), "created" to true))
        val repeat = beta.tool("create_project", obj("name" to "Other", "slug" to "edge-slug-$sfx", "description" to "new"))
        whole(repeat, "Project \"!!!\" already exists (slug edge-slug-$sfx); returning it unchanged.", obj("project" to project(fp), "created" to false))
        // The arguments are still checked on a repeat.
        a.sdk("create_project", obj("name" to "Other", "slug" to "edge-slug-$sfx", "description" to "x".repeat(2001)), "Too big: expected string to have <=2000 characters at description")
    }

    @Test
    fun `set_project_theme - normalising, clearing, references, nothing to change`() = parity {
        val a = feedAgent
        val p = Fixtures.project(a, "Theme Edge ${Parity.suffix()}")
        val created = obj("id" to p.id, "slug" to p.slug, "name" to p.name, "description" to null)
        suspend fun styled(args: JsonObject, theme: Any?, agent: Agent = a) =
            agent.whole("set_project_theme", args, "Styled project ${p.slug}.", obj("project" to project(created, theme = theme)))

        styled(obj("project" to p.slug), null)
        styled(obj("project" to "  ${p.slug.uppercase()}  ", "background" to "  #ABC  ", "accent" to "#A0b1C2"), obj("background" to "#aabbcc", "accent" to "#a0b1c2"))
        styled(obj("project" to p.id), obj("background" to "#aabbcc", "accent" to "#a0b1c2"))
        a.refused("set_project_theme", obj("project" to p.id.lowercase()), "not_found", "no such project: ${p.id.lowercase()}")
        styled(obj("project" to p.slug, "background" to null), obj("accent" to "#a0b1c2"))
        styled(obj("project" to p.slug, "accent" to null), null)
        a.refused("set_project_theme", obj("project" to p.slug, "background" to ""), "invalid_argument", "background must be a hex colour like #1a2b3c")
        a.refused("set_project_theme", obj("project" to p.slug, "accent" to "#abcd"), "invalid_argument", "accent must be a hex colour like #1a2b3c")
        a.refused("set_project_theme", obj("project" to "  "), "invalid_argument", "project is required")
        a.refused("set_project_theme", obj("project" to p.slug, "logo_media_id" to "nope"), "not_found", "no such media: nope")
        a.refused("set_project_theme", obj("project" to p.slug, "logo_media_id" to null, "logo_replaces_name" to true), "invalid_argument", "logoReplacesName needs a logo to show instead of the name")
        styled(obj("project" to p.slug, "logo_replaces_name" to false), null)
        styled(obj("project" to p.slug, "logo_replaces_name" to null), null)
        // No ownership: another agent may restyle it.
        styled(obj("project" to p.slug, "accent" to "#123"), obj("accent" to "#112233"), agent = beta)
    }

    @Test
    fun `list_projects - the archived filter and the summary`() = parity {
        val a = feedAgent
        val p = Fixtures.project(a, "Archive Edge ${Parity.suffix()}")
        owner.patch("/api/projects/${p.slug}", obj("status" to "archived"))
        val archived = a.tool("list_projects", obj("status" to "archived"))
        val listed = archived.structured["projects"].arr
        assertEquals(listed(obj("id" to p.id, "slug" to p.slug, "name" to p.name, "description" to null), status = "archived"), stamped(listed.single { it["id"].s == p.id }))
        assertTrue(listed.all { it["status"].s == "archived" })
        val n = listed.size
        // The Kotlin server says channel, its word for what the TS server called a project.
        val word = if (Parity.kotlin) "channel" else "project"
        whole(archived, "$n $word${if (n == 1) "" else "s"}: ${listed.joinToString(", ") { it["slug"].s }}.", stamped(archived.structured) as JsonObject)
        assertTrue(a.ok("list_projects", obj("status" to "active"))["projects"].arr.none { it["id"].s == p.id })
        // An archived project still takes updates.
        assertEquals(p.id, a.ok("post_update", obj("project" to p.slug, "body" to "into the archive"))["update"]["project_id"].s)
    }

    // ── updates ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `post_update - trimming, references as typed, sessions, attachments`() = parity {
        val a = feedAgent
        val p = Fixtures.project(a, "Update Edge ${Parity.suffix()}")
        val first = a.tool("post_update", obj("project" to p.slug.uppercase(), "body" to "  hi  ", "title" to "   "))
        val agentId = first.structured["update"]["agent_id"].s
        val id1 = first.structured["update"]["id"].s
        whole(first, "Posted a info update to project ${p.slug.uppercase()} as agent \"${a.name}\".", obj("update" to update(id1, p.id, agentId, 2)))

        val second = a.tool("post_update", obj("project" to p.id, "body" to "b", "title" to "  T  ", "level" to "error", "priority" to "low"))
        whole(second, "Posted a error update to project ${p.id} as agent \"${a.name}\".",
            obj("update" to update(second.structured["update"]["id"].s, p.id, agentId, 1, title = "T", level = "error")))
        assertEquals("Posted a warn update to project  ${p.slug}  as agent \"${a.name}\".", a.summary("post_update", obj("project" to " ${p.slug} ", "body" to "b", "level" to "warn")))
        assertEquals(3, a.ok("post_update", obj("project" to p.slug, "body" to "😀é"))["update"]["body_chars"].int)

        a.refused("post_update", obj("project" to p.slug, "body" to "  \n "), "invalid_argument", "body is required")
        a.refused("post_update", obj("project" to "no-such-edge", "body" to "b"), "not_found", "no such project: no-such-edge")
        a.refused("post_update", obj("project" to " ", "body" to "b"), "invalid_argument", "project is required")
        // The project is checked before the body.
        a.refused("post_update", obj("project" to " ", "body" to " "), "invalid_argument", "project is required")
        a.refused("post_update", obj("project" to "nope", "body" to " "), "not_found", "no such project: nope")

        // Sessions: another agent's is refused, a blank one is looked up as given, an ended one is fine.
        val theirs = beta.ok("register_session")["session_id"].s
        a.refused("post_update", obj("project" to p.slug, "body" to "b", "session_id" to theirs), "invalid_argument", "session belongs to another agent")
        a.refused("post_update", obj("project" to p.slug, "body" to "b", "session_id" to "  "), "not_found", "no such session:   ")
        a.refused("post_update", obj("project" to p.slug, "body" to "b", "session_id" to ""), "not_found", "no such session: ")
        val mine = a.ok("register_session")["session_id"].s
        a.ok("end_session", obj("session_id" to mine))
        val onEnded = a.tool("post_update", obj("project" to p.slug, "body" to "b", "session_id" to mine))
        whole(onEnded, "Posted a info update to project ${p.slug} as agent \"${a.name}\".",
            obj("update" to update(onEnded.structured["update"]["id"].s, p.id, agentId, 1, session = mine)))

        a.refused("post_update", obj("project" to p.slug, "body" to "b", "media_ids" to arr("nope")), "invalid_argument", "not a media id: \"nope\"")
        a.refused("post_update", obj("project" to p.slug, "body" to "b", "media_ids" to arr(nobody)), "not_found", "no such media: $nobody")
        a.refused("post_update", obj("project" to p.slug, "body" to "b", "embeds" to arr(obj("type" to "no-such-look", "data" to obj()))), "invalid_argument",
            "embeds[0]: no embed type \"no-such-look\". Call list_embed_types to see what is registered, or register_embed_type to add it.")
        a.refused("post_update", obj("project" to p.slug, "body" to "b", "apps" to arr(obj("app" to "ui://no-such-app"))), "invalid_argument",
            "apps[0]: no app \"ui://no-such-app\". Call register_app to ship it, or list_apps to see what is already registered.")
        // Empty lists are no attachments, and no `apps` key in the answer.
        for (empty in listOf("media_ids", "apps")) {
            val r = a.ok("post_update", obj("project" to p.slug, "body" to "b", empty to arr()))
            assertEquals(listOf("update"), r.keys.toList())
        }
    }

    @Test
    fun `edit_update - unknown, padded, deleted, someone else's, repeats`() = parity {
        val a = feedAgent
        val p = Fixtures.project(a, "Edit Edge ${Parity.suffix()}")
        val base = a.ok("post_update", obj("project" to p.slug, "body" to "base", "title" to "Base"))["update"].o
        val id = base["id"].s
        val agentId = base["agent_id"].s
        suspend fun edited(args: JsonObject, title: String? = "Base", level: String = "info", chars: Int = 4) =
            a.whole("edit_update", JsonObject(obj("update_id" to id) + args), "Edited update $id.", obj("update" to update(id, p.id, agentId, chars, title, level, edited = true)))

        a.refused("edit_update", obj("update_id" to nobody, "body" to "x"), "not_found", "no such update: $nobody")
        a.refused("edit_update", obj("update_id" to nobody), "not_found", "no such update: $nobody")
        a.refused("edit_update", obj("update_id" to "  ", "body" to "x"), "not_found", "no such update:   ")
        a.refused("edit_update", obj("update_id" to " $id ", "body" to "x"), "not_found", "no such update:  $id ")
        a.refused("edit_update", obj("update_id" to id, "body" to "  "), "invalid_argument", "body is required")
        // Who it belongs to is checked before whether anything changes.
        beta.refused("edit_update", obj("update_id" to id), "invalid_argument", "that update was posted by another agent")

        edited(obj("title" to "Base", "body" to "base")) // the same text is still an edit
        edited(obj("title" to "  "), title = null)
        edited(obj("title" to "Base"))
        edited(obj("level" to "warn"), level = "warn")
        edited(obj("priority" to "high"), level = "warn")
        edited(obj("body" to "  padded  "), level = "warn", chars = 6)

        owner.delete("/api/updates/$id")
        a.refused("edit_update", obj("update_id" to id, "body" to "x"), "not_found", "no such update: $id")
    }

    // ── presence ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `sessions - blanks, ownership, ended, and the waiting counts in the singular and plural`() = parity {
        val a = Parity.mintAgent(uniq("edge-presence"))
        val registered = a.tool("register_session", obj("meta" to obj("host" to "  ", "cwd" to "", "model" to " m ")))
        val s = registered.structured["session_id"].s
        whole(registered,
            "Registered session $s for agent \"${a.name}\". Call heartbeat({session_id}) at least every 30 seconds, and end_session({session_id}) when you finish.",
            obj("session_id" to s, "heartbeat_interval_s" to 30))
        val online = owner.get("/api/snapshot/agents").obj()["agents"].arr.single { it["sessionId"].str == s }
        assertTrue(online["host"].isNull && online["cwd"].isNull, "blank meta is dropped: $online")
        assertTrue(!a.tool("register_session").isError, "a second session is a new one")

        for (tool in listOf("heartbeat", "end_session")) {
            a.refused(tool, obj("session_id" to nobody), "not_found", "no such session: $nobody")
            a.refused(tool, obj("session_id" to ""), "not_found", "no such session: ")
            a.refused(tool, obj("session_id" to " $s "), "not_found", "no such session:  $s ")
            beta.refused(tool, obj("session_id" to s), "invalid_argument", "session belongs to another agent")
        }

        // Give it a project of its own, so the counts are about that project only.
        val p = Fixtures.project(a, "Presence Edge ${Parity.suffix()}")
        val u1 = Fixtures.update(p, a)["id"].s
        val u2 = Fixtures.update(p, a)["id"].s
        a.ok("get_messages")
        suspend fun beat(summary: String, unread: Int, tasks: Int) = a.whole("heartbeat", obj("session_id" to s), summary,
            obj("ok" to true, "unread_messages" to unread, "open_tasks" to tasks, "pending_approvals" to 0))
        beat("Heartbeat recorded. Nothing is waiting for you; carry on.", 0, 0)
        a.ok("set_status", obj("update_id" to u1, "status" to "todo"))
        beat("Heartbeat recorded. Waiting for you: 1 open task.", 0, 1)
        ownerMessage(u1, "owner one")
        beat("Heartbeat recorded. Waiting for you: 1 unread message, 1 open task.", 1, 1)
        beta.ok("set_status", obj("update_id" to u2, "status" to "blocked")) // anyone's status counts
        ownerMessage(u1, "owner two")
        beat("Heartbeat recorded. Waiting for you: 2 unread messages, 2 open tasks.", 2, 2)
        a.ok("set_status", obj("update_id" to u2, "status" to "done")) // done is not open
        beat("Heartbeat recorded. Waiting for you: 2 unread messages, 1 open task.", 2, 1)

        a.whole("end_session", obj("session_id" to s), "Ended session $s. Agent \"${a.name}\" is no longer shown as working.", obj("session_id" to s, "ended" to true))
        a.whole("end_session", obj("session_id" to s), "Session $s was already ended; nothing to do.", obj("session_id" to s, "ended" to false))
        beta.refused("end_session", obj("session_id" to s), "invalid_argument", "session belongs to another agent")
        a.refused("heartbeat", obj("session_id" to s), "conflict", "session $s has ended; call register_session to start a new one")
    }

    // ── cards ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `set_status - every status, repeats, blanks, unknown and deleted cards`() = parity {
        val a = cardAgent
        val p = Fixtures.project(a, "Status Edge ${Parity.suffix()}")
        val u = Fixtures.update(p, a)["id"].s
        suspend fun marked(status: String?, summary: String, agent: Agent = a) =
            agent.whole("set_status", obj("update_id" to u, "status" to status), summary, obj("card" to card("update", u), "status" to status))
        marked("blocked", "Marked blocked. Your owner sees it on the card.", agent = beta)
        marked("blocked", "Marked blocked. Your owner sees it on the card.")
        marked("todo", "Marked todo. Your owner sees it on the card.")
        marked("done", "Marked done. Your owner sees it on the card.")
        marked("cancelled", "Marked cancelled. Your owner sees it on the card.")
        marked("in_progress", "Marked in progress. Your owner sees it on the card.")
        marked(null, "Unmarked. It is an ordinary card again.")
        marked(null, "Unmarked. It is an ordinary card again.")

        a.refused("set_status", obj("update_id" to "  ", "message_id" to "", "status" to "todo"), "invalid_argument", "name one of: update, message, request")
        // A blank id is no id, so the other one is read as the card.
        a.refused("set_status", obj("update_id" to "  ", "message_id" to u, "status" to "todo"), "not_found", "no such message: $u")
        a.refused("set_status", obj("update_id" to u, "message_id" to u, "status" to "todo"), "invalid_argument", "name one of: update, message, request — not 2 of them")
        a.refused("set_status", obj("update_id" to nobody, "status" to "todo"), "not_found", "no such update: $nobody")
        a.refused("set_status", obj("message_id" to nobody, "status" to "todo"), "not_found", "no such message: $nobody")

        val loose = Fixtures.message(a, obj("body" to "loose"))["id"].s
        a.whole("set_status", obj("message_id" to loose, "status" to "todo"), "Marked todo. Your owner sees it on the card.", obj("card" to card("message", loose), "status" to "todo"))
        a.ok("delete_message", obj("message_id" to loose))
        a.refused("set_status", obj("message_id" to loose, "status" to "done"), "not_found", "no such message: $loose")
        owner.delete("/api/updates/$u")
        a.refused("set_status", obj("update_id" to u, "status" to "done"), "not_found", "no such update: $u")
    }

    @Test
    fun `list_work - filters, limit, order, groups, the cut summary`() = parity {
        val a = cardAgent
        val p = Fixtures.project(a, "Work Edge ${Parity.suffix()}")
        val long = Fixtures.update(p, a, body = "one " + "x".repeat(300))["id"].s
        val w1 = Fixtures.update(p, a, body = "w1")["id"].s
        val w2 = Fixtures.update(p, a, body = "  w2  ")["id"].s
        val m = Fixtures.message(a, obj("body" to "msg work", "project" to p.slug))["id"].s
        val author = "agent:" + Fixtures.update(p, a, body = "never marked")["agent_id"].s
        for ((key, id, status) in listOf(Triple("update_id", long, "todo"), Triple("update_id", w1, "blocked"), Triple("update_id", w2, "todo"), Triple("message_id", m, "todo"))) {
            a.ok("set_status", obj(key to id, "status" to status))
            delay(5) // distinct status times, so the order is by them
        }
        fun item(kind: String, id: String, status: String, summary: String) = obj(
            "kind" to kind, "id" to id, "project_id" to p.id, "status" to status, "title" to null, "summary" to summary,
            "author" to author, "created_at" to TS, "status_at" to TS,
        )
        val all = listOf(
            item("message", m, "todo", "msg work"), item("update", w2, "todo", "w2"),
            item("update", w1, "blocked", "w1"), item("update", long, "todo", "one " + "x".repeat(236) + "…"),
        )
        a.whole("list_work", obj("project" to p.slug), "4 cards: 3 todo, 1 blocked.", obj("work" to all, "count" to 4))
        a.whole("list_work", obj("project" to p.slug, "limit" to 1), "1 card: 1 todo.", obj("work" to all.take(1), "count" to 1))
        a.whole("list_work", obj("project" to p.slug.uppercase(), "status" to arr("blocked")), "1 card: 1 blocked.", obj("work" to listOf(all[2]), "count" to 1))
        a.whole("list_work", obj("project" to p.slug, "status" to arr("todo")), "3 cards: 3 todo.", obj("work" to all.filter { it["status"].s == "todo" }, "count" to 3))
        val none = obj("work" to arr(), "count" to 0)
        a.whole("list_work", obj("project" to p.slug, "status" to arr()), "No work matches. Nothing is marked in that state.", none)
        a.whole("list_work", obj("project" to p.slug, "status" to arr("done", "cancelled")), "No work matches. Nothing is marked in that state.", none)
        // An empty project is none; a blank one is refused.
        assertEquals(4, a.ok("list_work", obj("project" to "")).let { r -> r["work"].arr.count { it["project_id"].s == p.id } })
        a.refused("list_work", obj("project" to "  "), "invalid_argument", "project is required")
        a.refused("list_work", obj("project" to "nope-edge"), "not_found", "no such project: nope-edge")
    }

    @Test
    fun `tags - names, toggles, idempotent on and off, limits, other projects`() = parity {
        val a = tagAgent
        val p = Fixtures.project(a, "Tags Edge ${Parity.suffix()}")
        val u = Fixtures.update(p, a, body = "tag me")["id"].s
        a.refused("list_tags", obj("project" to "nope-edge"), "not_found", "no such project: nope-edge")
        a.refused("list_tags", obj("project" to "  "), "invalid_argument", "project is required")
        a.whole("list_tags", obj("project" to p.slug), "No tags yet. tag_card with create: true decides the first one.", obj("tags" to arr(), "count" to 0))

        val created = a.tool("tag_card", obj("update_id" to u, "tag" to "  Urgent  ", "create" to true))
        val tag = obj("id" to created.structured["tag"]["id"], "name" to "Urgent", "colour" to null)
        suspend fun tagged(args: JsonObject, on: Boolean, agent: Agent = a) =
            agent.whole("tag_card", JsonObject(obj("update_id" to u) + args), if (on) "Tagged \"Urgent\". Your owner sees it on the card." else "Took \"Urgent\" off.",
                obj("card" to card("update", u), "tag" to tag, "on" to on))
        whole(created, "Tagged \"Urgent\". Your owner sees it on the card.", obj("card" to card("update", u), "tag" to tag, "on" to true))
        tagged(obj("tag" to "URGENT", "on" to true), true)
        tagged(obj("tag" to "urgent", "on" to false), false)
        tagged(obj("tag" to "urgent", "on" to false), false)
        tagged(obj("tag" to "urgent"), true, agent = beta) // anyone may, and no `on` toggles
        tagged(obj("tag" to tag["id"].s), false) // by id

        // create with on:false makes the tag and leaves it off.
        val later = a.ok("tag_card", obj("update_id" to u, "tag" to "later", "create" to true, "on" to false))
        assertEquals(false, later["on"].bool)
        val agentId = Fixtures.update(p, a)["agent_id"].s
        fun listed(name: String, id: JsonElement?) = obj("id" to id, "project_id" to p.id, "name" to name, "colour" to null, "agent_id" to agentId, "created_at" to TS)
        a.whole("list_tags", obj("project" to p.slug), "2 tags: Urgent, later.",
            obj("tags" to arr(listed("Urgent", tag["id"]), listed("later", later["tag"]["id"])), "count" to 2))

        a.refused("tag_card", obj("update_id" to u, "tag" to "  ", "create" to true), "invalid_argument", "a tag needs a word")
        a.refused("tag_card", obj("update_id" to u, "tag" to "  "), "not_found", "this project has no tag called \"  \"")
        for (word in listOf("!!!", "日本", "🔥")) a.refused("tag_card", obj("update_id" to u, "tag" to word, "create" to true), "invalid_argument", "\"$word\" has no letters or digits in it")
        a.refused("tag_card", obj("update_id" to u, "tag" to "x".repeat(33), "create" to true), "invalid_argument", "a tag may be at most 32 characters")
        a.refused("tag_card", obj("update_id" to nobody, "tag" to "urgent"), "not_found", "no such update: $nobody")
        a.refused("tag_card", obj("tag" to "urgent"), "invalid_argument", "name one of: update, message, request")
        // Case folding is ASCII-and-accents only: "STRASSE" is not "straße".
        a.ok("tag_card", obj("update_id" to u, "tag" to "STRASSE", "create" to true))
        a.refused("tag_card", obj("update_id" to u, "tag" to "straße"), "not_found", "this project has no tag called \"straße\"")
        a.ok("tag_card", obj("update_id" to u, "tag" to "Café", "create" to true))
        assertEquals("Took \"Café\" off.", a.summary("tag_card", obj("update_id" to u, "tag" to "CAFÉ")))

        val other = Fixtures.project(a, "Tags Other ${Parity.suffix()}")
        val ou = Fixtures.update(other, a)["id"].s
        a.refused("tag_card", obj("update_id" to ou, "tag" to tag["id"].s), "invalid_argument", "that tag belongs to another project")

        // Eight at most on a card; a refused ninth is still defined (spec surprise 19).
        val v = Fixtures.update(p, a)["id"].s
        for (i in 0 until 8) a.ok("tag_card", obj("update_id" to v, "tag" to "t$i", "create" to true, "on" to true))
        a.refused("tag_card", obj("update_id" to v, "tag" to "ninth", "create" to true), "invalid_argument", "a card may carry at most 8 tags")
        assertTrue(a.ok("list_tags", obj("project" to p.slug))["tags"].arr.any { it["name"].s == "ninth" })
    }

    @Test
    fun `card fields - names, values, limits, any card kind, deleted cards`() = parity {
        val a = fieldAgent
        val p = Fixtures.project(a, "Fields Edge ${Parity.suffix()}")
        val u = Fixtures.update(p, a, body = "fields")["id"].s
        val me = "agent:" + Fixtures.update(p, a)["agent_id"].s
        val them = "agent:" + Fixtures.update(p, beta)["agent_id"].s
        fun field(name: String, value: String, author: String = me) = obj("name" to name, "value" to value, "author" to author, "created_at" to TS, "updated_at" to TS)

        a.refused("set_card_field", obj("update_id" to u, "name" to "  ", "value" to "v"), "invalid_argument", "a field needs a name")
        a.refused("set_card_field", obj("update_id" to u, "name" to "x".repeat(41), "value" to "v"), "invalid_argument", "a field name may be at most 40 characters")
        a.refused("set_card_field", obj("update_id" to u, "name" to "😀".repeat(20) + "a", "value" to "v"), "invalid_argument", "a field name may be at most 40 characters")
        a.refused("set_card_field", obj("update_id" to u, "name" to "!!!", "value" to "v"), "invalid_argument", "\"!!!\" has no letters or digits in it")
        for (blank in listOf("", "   ")) a.refused("set_card_field", obj("update_id" to u, "name" to "n", "value" to blank), "invalid_argument", "a field needs a value, or null to remove it")
        a.refused("set_card_field", obj("update_id" to u, "name" to "n", "value" to "x".repeat(501)), "invalid_argument", "a field value may be at most 500 characters")
        a.refused("set_card_field", obj("update_id" to u, "name" to "Nope", "value" to null), "not_found", "this card has no field called \"Nope\"")
        a.refused("set_card_field", obj("update_id" to nobody, "name" to "n", "value" to "v"), "not_found", "no such update: $nobody")
        a.refused("set_card_field", obj("name" to "n", "value" to "v"), "invalid_argument", "name one of: update, message, request")

        // The summary echoes the name as given; the field keeps it trimmed; the same slug is the same field.
        a.whole("set_card_field", obj("update_id" to u, "name" to "  PR Link  ", "value" to "  #12  "), "Noted \"  PR Link  \" on the card. Your owner can see it under Details.",
            obj("card" to card("update", u), "fields" to arr(field("PR Link", "#12")), "set" to true))
        a.whole("set_card_field", obj("update_id" to u, "name" to "PR Link", "value" to "#12"), "Noted \"PR Link\" on the card. Your owner can see it under Details.",
            obj("card" to card("update", u), "fields" to arr(field("PR Link", "#12")), "set" to true))
        beta.whole("set_card_field", obj("update_id" to u, "name" to "pr link", "value" to "#13"), "Noted \"pr link\" on the card. Your owner can see it under Details.",
            obj("card" to card("update", u), "fields" to arr(field("pr link", "#13", them)), "set" to true))
        a.ok("set_card_field", obj("update_id" to u, "name" to "Branch", "value" to "main"))
        val two = arr(field("pr link", "#13", them), field("Branch", "main"))
        a.whole("get_card_fields", obj("update_id" to u), "2 fields: pr link, Branch.", obj("card" to card("update", u), "fields" to two, "count" to 2))
        a.whole("set_card_field", obj("update_id" to u, "name" to "BRANCH", "value" to null), "Took \"BRANCH\" off the card.",
            obj("card" to card("update", u), "fields" to arr(field("pr link", "#13", them)), "set" to false))
        a.whole("get_card_fields", obj("update_id" to u), "1 field: pr link.", obj("card" to card("update", u), "fields" to arr(field("pr link", "#13", them)), "count" to 1))

        a.refused("get_card_fields", obj(), "invalid_argument", "name one of: update, message, request")
        a.refused("get_card_fields", obj("update_id" to u, "message_id" to nobody), "invalid_argument", "name one of: update, message, request — not 2 of them")
        for (kind in listOf("message", "request")) {
            a.whole("get_card_fields", obj("${kind}_id" to nobody), "Nothing noted on that card yet.", obj("card" to card(kind, nobody), "fields" to arr(), "count" to 0))
        }

        // Twenty at most; once the card is deleted its fields go with it, for reading and writing.
        for (i in 0 until 19) a.ok("set_card_field", obj("update_id" to u, "name" to "f$i", "value" to "v"))
        a.refused("set_card_field", obj("update_id" to u, "name" to "f19", "value" to "v"), "invalid_argument", "a card may carry at most 20 fields")
        owner.delete("/api/updates/$u")
        a.refused("set_card_field", obj("update_id" to u, "name" to "f1", "value" to "w"), "not_found", "no such update: $u")
        pendingFix(14, "a deleted card has no fields") {
            assertEquals(0, a.ok("get_card_fields", obj("update_id" to u))["count"].int)
        }
    }

    // ── messages ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `get_messages - blanks, cursors, scope, mentions, paging`() = parity {
        val a = Parity.mintAgent(uniq("edge-inbox"))
        val b = messageAgent
        val p = Fixtures.project(a, "Inbox Edge ${Parity.suffix()}")
        val other = Fixtures.project(a, "Inbox Other ${Parity.suffix()}")
        val u = Fixtures.update(p, a)["id"].s
        Fixtures.update(other, a)
        val empty = obj("messages" to arr(), "count" to 0, "cursor" to "0", "unread" to 0, "marked_read" to true)
        a.ok("get_messages")
        a.whole("get_messages", obj(), "No new messages. Carry on.", empty)
        a.whole("get_messages", obj("project" to "", "since" to ""), "No new messages. Carry on.", empty)
        a.refused("get_messages", obj("project" to "  "), "invalid_argument", "project is required")
        a.refused("get_messages", obj("project" to "nope-edge"), "not_found", "no such project: nope-edge")
        for (since in listOf("-1", " 5", "abc")) a.refused("get_messages", obj("since" to since), "invalid_argument", "since is not a cursor this server issued")

        val long = ownerMessage(u, "owner long " + "x".repeat(300) + "   ")
        val reply = b.ok("post_message", obj("body" to "b answers", "message_id" to long))["message"].o
        val bId = reply["author"].s
        val longView = obj("id" to long, "project_id" to p.id, "update_id" to u, "task_id" to null, "reply_to" to null, "author" to "human",
            "body" to "owner long " + "x".repeat(300), "created_at" to TS)
        val replyView = obj("id" to reply["id"], "project_id" to p.id, "update_id" to u, "task_id" to null, "reply_to" to null, "answers" to long,
            "author" to bId, "body" to "b answers", "created_at" to TS, "author_name" to b.name,
            "in_reply_to" to obj("id" to long, "author" to "human", "body" to "owner long " + "x".repeat(229) + "…", "truncated" to true))

        // Not marking read: the same two, twice, and both still unread.
        val peek = a.tool("get_messages", obj("mark_read" to false))
        val cursor = peek.structured["cursor"].s
        whole(peek, "2 new messages, oldest first. 2 still unread; call again for the rest.",
            obj("messages" to arr(longView, replyView), "count" to 2, "cursor" to cursor, "unread" to 2, "marked_read" to false))
        assertEquals(peek.structured, a.ok("get_messages", obj("since" to "0", "mark_read" to false)))
        // Another project's page is empty, but unread counts the agent's whole scope.
        a.whole("get_messages", obj("project" to other.slug), "No new messages. Carry on.", JsonObject(empty + obj("unread" to 2)))
        a.whole("get_messages", obj(), "2 new messages, oldest first.",
            obj("messages" to arr(longView, replyView), "count" to 2, "cursor" to cursor, "unread" to 0, "marked_read" to true))
        // A cursor reads again what is past it, read or not.
        assertEquals(1, a.ok("get_messages", obj("since" to (cursor.toLong() - 1).toString()))["count"].int)
        a.whole("get_messages", obj("since" to "999999999"), "No new messages. Carry on.", JsonObject(empty + obj("cursor" to "999999999")))

        val one = ownerMessage(u, "just one")
        val single = a.tool("get_messages")
        whole(single, "1 new message, oldest first.", obj(
            "messages" to arr(obj("id" to one, "project_id" to p.id, "update_id" to u, "task_id" to null, "reply_to" to null, "author" to "human", "body" to "just one", "created_at" to TS)),
            "count" to 1, "cursor" to single.structured["cursor"], "unread" to 0, "marked_read" to true,
        ))

        // A message that names another agent is theirs alone; the agent's own are never handed back.
        b.ok("post_message", obj("body" to "hey @${b.name}-nobody and @${b.name.uppercase()}", "update_id" to u))
        a.ok("post_message", obj("body" to "mine", "update_id" to u))
        a.whole("get_messages", obj(), "No new messages. Carry on.", empty)
        // Mentions: case-insensitive, not inside code, not glued to a word.
        b.ok("post_message", obj("body" to "ping @${a.name.uppercase()}. and `@${a.name}`", "project" to other.slug))
        b.ok("post_message", obj("body" to "email me@${a.name} ok", "update_id" to u))
        assertEquals(2, a.ok("get_messages")["count"].int)

        // Fifty to a page.
        repeat(51) { ownerMessage(u, "bulk $it") }
        val page = a.tool("get_messages")
        assertEquals("50 new messages, oldest first. 1 still unread; call again for the rest.", page.text.substringBefore('\n'))
        assertEquals((0 until 50).map { "bulk $it" }, page.structured["messages"].arr.map { it["body"].s })
        assertEquals("1 new message, oldest first.", a.summary("get_messages", obj()))
    }

    @Test
    fun `acknowledge - states, cards, one row per agent and card`() = parity {
        val a = messageAgent
        val p = Fixtures.project(a, "Ack Edge ${Parity.suffix()}")
        val u = Fixtures.update(p, a)["id"].s
        val m = ownerMessage(u, "ack me")
        val read = a.tool("acknowledge", obj("state" to "read", "message_id" to m))
        val ackId = read.structured["ack"]["id"]
        fun ack(state: String, kind: String = "message", id: String = m, ackId: JsonElement?, message: String? = m) =
            obj("ack" to obj("id" to ackId, "card_kind" to kind, "card_id" to id, "message_id" to message, "task_id" to null, "state" to state, "created_at" to TS, "updated_at" to TS))
        whole(read, "Marked read. Your owner sees that it reached you.", ack("read", ackId = ackId))
        a.whole("acknowledge", obj("state" to "thinking", "message_id" to m), "Acknowledged. Your owner sees that you are on it.", ack("thinking", ackId = ackId))
        a.whole("acknowledge", obj("state" to "done", "message_id" to m), "Marked done. Your owner sees a tick on it.", ack("done", ackId = ackId))
        a.whole("acknowledge", obj("state" to "done", "message_id" to m), "Marked done. Your owner sees a tick on it.", ack("done", ackId = ackId))
        val onUpdate = a.tool("acknowledge", obj("state" to "done", "update_id" to u))
        whole(onUpdate, "Marked done. Your owner sees a tick on it.", ack("done", "update", u, onUpdate.structured["ack"]["id"], message = null))
        // Another agent's ack on the same card is its own row.
        val theirs = beta.ok("acknowledge", obj("state" to "read", "message_id" to m))["ack"]["id"]
        assertTrue(theirs != ackId)

        a.refused("acknowledge", obj("state" to "done", "message_id" to nobody), "not_found", "no such message: $nobody")
        a.refused("acknowledge", obj("state" to "done", "request_id" to nobody), "not_found", "no such request: $nobody")
        a.refused("acknowledge", obj("state" to "done", "message_id" to " "), "invalid_argument", "name one of: update, message, request")
        a.refused("acknowledge", obj("state" to "done", "message_id" to m, "update_id" to u), "invalid_argument", "name one of: update, message, request — not 2 of them")
        a.refused("acknowledge", obj("state" to "done", "message_id" to m, "update_id" to u, "request_id" to nobody), "invalid_argument", "name one of: update, message, request — not 3 of them")
    }

    @Test
    fun `post_message - validation in order, threads flattened, deleted targets`() = parity {
        val a = messageAgent
        val p = Fixtures.project(a, "Post Edge ${Parity.suffix()}")
        val other = Fixtures.project(a, "Post Other ${Parity.suffix()}")
        val u = Fixtures.update(p, a)["id"].s
        val ou = Fixtures.update(other, a)["id"].s
        val author = "agent:" + Fixtures.update(p, a)["agent_id"].s
        val m = ownerMessage(u, "owner")
        val bReply = beta.ok("post_message", obj("body" to "b answers", "message_id" to m))["message"]["id"].s
        fun message(id: JsonElement?, body: String, update: String? = u, replyTo: String? = null, answers: String? = null, project: String? = p.id): JsonObject {
            val fields = LinkedHashMap<String, JsonElement>()
            fields["id"] = id.toJson()
            fields["project_id"] = project.toJson()
            fields["update_id"] = update.toJson()
            fields["task_id"] = null.toJson()
            fields["reply_to"] = replyTo.toJson()
            if (answers != null) fields["answers"] = JsonPrimitive(answers) // only when set
            fields["author"] = JsonPrimitive(author)
            fields["body"] = JsonPrimitive(body)
            fields["created_at"] = JsonPrimitive(TS)
            return obj("message" to JsonObject(fields))
        }
        val replied = "Replied. It is on your owner’s screen now."
        suspend fun posted(args: JsonObject, expected: (JsonElement?) -> JsonObject): String {
            val r = a.tool("post_message", args)
            val id = r.structured["message"]["id"]
            whole(r, replied, expected(id))
            return id.s
        }

        a.refused("post_message", obj("body" to "   "), "invalid_argument", "body is required")
        a.refused("post_message", obj("body" to "x".repeat(10_001)), "invalid_argument", "body must be at most 10000 characters")
        assertEquals(10_000, a.ok("post_message", obj("body" to "  " + "x".repeat(10_000) + "  ", "update_id" to u))["message"]["body"].s.length)
        a.refused("post_message", obj("body" to "x", "answers" to nobody), "not_found", "no such message: $nobody")
        a.refused("post_message", obj("body" to "x", "update_id" to u, "message_id" to m), "invalid_argument", "a reply answers a message, or hangs off an update, not both")
        a.refused("post_message", obj("body" to "x", "request_id" to nobody, "update_id" to u), "invalid_argument", "a message belongs to a request`s discussion, or to a card, not both")
        a.refused("post_message", obj("body" to "x", "request_id" to nobody), "not_found", "no such request: $nobody")
        a.refused("post_message", obj("body" to "x", "message_id" to nobody), "not_found", "no such message: $nobody")
        a.refused("post_message", obj("body" to "x", "project" to "nope-edge"), "not_found", "no such project: nope-edge")
        a.refused("post_message", obj("body" to "x", "project" to "  "), "invalid_argument", "project is required")
        a.refused("post_message", obj("body" to "x", "update_id" to nobody), "not_found", "no such update: $nobody")
        a.refused("post_message", obj("body" to "x", "update_id" to ou, "project" to p.slug), "invalid_argument", "that update, task or message belongs to a different project")
        a.refused("post_message", obj("body" to "x", "message_id" to m, "project" to other.slug), "invalid_argument", "that update, task or message belongs to a different project")
        a.refused("post_message", obj("body" to "x", "media_ids" to arr("nope")), "invalid_argument", "not a media id: \"nope\"")
        a.refused("post_message", obj("body" to "x", "media_ids" to arr(nobody)), "not_found", "no such media: $nobody")
        a.refused("post_message", obj("body" to "x", "embeds" to arr(obj("type" to "no-such-look", "data" to obj()))), "invalid_argument",
            "embeds[0]: no embed type \"no-such-look\". Call list_embed_types to see what is registered, or register_embed_type to add it.")
        a.refused("post_message", obj("body" to "x", "update_id" to ou, "answers" to m), "invalid_argument", "that message is in a different thread")
        // Blank update and message ids are no ids; a blank answers is looked up as given.
        a.refused("post_message", obj("body" to "x", "update_id" to "  ", "message_id" to "", "answers" to " "), "not_found", "no such message:  ")

        posted(obj("body" to " in project ", "project" to p.slug.uppercase())) { message(it, "in project", update = null) }
        // A reply to a reply answers it, and hangs off the card, not the reply.
        posted(obj("body" to "flatten", "message_id" to bReply)) { message(it, "flatten", answers = bReply) }
        posted(obj("body" to "answering", "update_id" to u, "answers" to m)) { message(it, "answering", answers = m) }
        val post = posted(obj("body" to "a feed post", "project" to p.slug)) { message(it, "a feed post", update = null) }
        posted(obj("body" to "on the post", "message_id" to post)) { message(it, "on the post", update = null, replyTo = post) }
        posted(obj("body" to "nowhere")) { message(it, "nowhere", update = null, project = null) }

        // A deleted message can still be replied to, not answered; a deleted update is gone.
        val doomed = ownerMessage(u, "to be deleted")
        owner.delete("/api/messages/$doomed")
        posted(obj("body" to "reply to gone", "message_id" to doomed)) { message(it, "reply to gone", answers = doomed) }
        a.refused("post_message", obj("body" to "x", "answers" to doomed), "not_found", "no such message: $doomed")
        val gone = Fixtures.update(p, a)["id"].s
        owner.delete("/api/updates/$gone")
        a.refused("post_message", obj("body" to "x", "update_id" to gone), "not_found", "no such update: $gone")
    }

    @Test
    fun `edit_message and delete_message - unknown, deleted, someone else's, cascades`() = parity {
        val a = messageAgent
        val p = Fixtures.project(a, "Unsend Edge ${Parity.suffix()}")
        val u = Fixtures.update(p, a)["id"].s
        val mine = Fixtures.message(a, obj("body" to "mine to edit", "update_id" to u))
        val id = mine["id"].s
        /** [of] as posted, with a new body and the deleted_at / edited_at keys that appear once set. */
        fun view(body: String, edited: Boolean = false, deleted: Boolean = false, of: JsonObject = mine): JsonObject {
            val fields = LinkedHashMap<String, JsonElement>(of)
            fields["body"] = JsonPrimitive(body)
            fields["created_at"] = JsonPrimitive(TS)
            if (deleted) fields["deleted_at"] = JsonPrimitive(TS)
            if (edited) fields["edited_at"] = JsonPrimitive(TS)
            return obj("message" to JsonObject(fields))
        }
        val edited = "Edited. Your owner sees the new text."
        val unsent = "Unsent. It is gone from your owner’s screen."

        a.refused("edit_message", obj("message_id" to nobody, "body" to "x"), "not_found", "no such message: $nobody")
        a.refused("edit_message", obj("message_id" to " ", "body" to "x"), "not_found", "no such message:  ")
        a.refused("edit_message", obj("message_id" to id, "body" to "  "), "invalid_argument", "body is required")
        a.whole("edit_message", obj("message_id" to id, "body" to "  edited  "), edited, view("edited", edited = true))
        a.whole("edit_message", obj("message_id" to id, "body" to "edited"), edited, view("edited", edited = true))
        // Authorship is checked before the body.
        beta.refused("edit_message", obj("message_id" to id, "body" to "b edits"), "invalid_argument", "that message was posted by somebody else")
        beta.refused("edit_message", obj("message_id" to id, "body" to " "), "invalid_argument", "that message was posted by somebody else")

        a.refused("delete_message", obj("message_id" to nobody), "not_found", "no such message: $nobody")
        beta.refused("delete_message", obj("message_id" to id), "invalid_argument", "that message was posted by somebody else")
        a.whole("delete_message", obj("message_id" to id), unsent, view("edited", edited = true, deleted = true))
        // Once deleted: its author may delete it again, nobody may edit it.
        a.whole("delete_message", obj("message_id" to id), unsent, view("edited", edited = true, deleted = true))
        a.refused("edit_message", obj("message_id" to id, "body" to "x"), "not_found", "no such message: $id")

        // Deleting a top-level post takes everyone's replies with it.
        val top = Fixtures.message(a, obj("body" to "top post", "project" to p.slug))
        val reply = Fixtures.message(beta, obj("body" to "reply one", "message_id" to top["id"].s))
        a.whole("delete_message", obj("message_id" to top["id"].s), unsent, view("top post", deleted = true, of = top))
        beta.refused("edit_message", obj("message_id" to reply["id"].s, "body" to "still?"), "not_found", "no such message: ${reply["id"].s}")
        beta.whole("delete_message", obj("message_id" to reply["id"].s), unsent, view("reply one", deleted = true, of = reply))
        // Somebody else gets no quiet success, and so no text, for a message already unsent.
        pendingFix(13, "only who could unsend a message is told it is unsent") {
            beta.refused("delete_message", obj("message_id" to id), "invalid_argument", "that message was posted by somebody else")
        }
    }

    @Test
    fun `react - shortcodes, toggles, idempotent on and off, odd emoji, cards`() = parity {
        val a = messageAgent
        val p = Fixtures.project(a, "React Edge ${Parity.suffix()}")
        val u = Fixtures.update(p, a)["id"].s
        val m = ownerMessage(u, "react to me")
        val me = "agent:" + Fixtures.update(p, a)["agent_id"].s
        val on = "Reacted. Your owner sees it on the card."
        val off = "Reaction removed. It is gone from the card."
        val tick = a.tool("react", obj("message_id" to m, "emoji" to ":tick:"))
        val tickView = obj("id" to tick.structured["reactions"][0]["id"], "card_kind" to "message", "card_id" to m, "message_id" to m, "actor" to me, "emoji" to "✅", "created_at" to TS)
        whole(tick, on, obj("reactions" to arr(tickView), "on" to true))
        a.whole("react", obj("message_id" to m, "emoji" to "tick", "on" to true), on, obj("reactions" to arr(tickView), "on" to true))
        a.whole("react", obj("message_id" to m, "emoji" to " CHECK ", "on" to false), off, obj("reactions" to arr(), "on" to false))
        a.whole("react", obj("message_id" to m, "emoji" to "✅", "on" to false), off, obj("reactions" to arr(), "on" to false))

        // On an update, by two agents, in order; aliases land on the same character.
        val mine = a.ok("react", obj("update_id" to u, "emoji" to "+1"))["reactions"].arr.single()
        val both = beta.ok("react", obj("update_id" to u, "emoji" to "thumbsup"))["reactions"].arr
        assertEquals(listOf(mine["id"], mine["emoji"]), listOf(both[0]["id"], both[1]["emoji"]))
        for ((given, stored) in listOf("ROCKET" to "🚀", ":HEART:" to "❤️", "👨‍👩‍👧‍👦" to "👨‍👩‍👧‍👦", "🇬🇧" to "🇬🇧", "1️⃣" to "1️⃣", "日" to "日", "\u001c👍" to "\u001c👍", "👍".repeat(12) to "👍".repeat(12))) {
            val r = a.ok("react", obj("update_id" to u, "emoji" to given))
            assertEquals(true, r["on"].bool, given)
            assertEquals(stored, r["reactions"].arr.last()["emoji"].s, given)
        }

        a.refused("react", obj("update_id" to u, "emoji" to "  "), "invalid_argument", "emoji is required")
        a.refused("react", obj("update_id" to u, "emoji" to "👍 👍"), "invalid_argument", "an emoji has no spaces in it")
        a.refused("react", obj("update_id" to u, "emoji" to "👍".repeat(13)), "invalid_argument", "an emoji is at most 24 characters")
        for (word in listOf("::", "smiley", "<3", "1")) {
            a.refused("react", obj("update_id" to u, "emoji" to word), "invalid_argument",
                "\"$word\" is not an emoji. Send the character itself, or one of: eyes, tick, check, white_check_mark, done, +1, thumbsup, -1")
        }
        a.refused("react", obj("update_id" to nobody, "emoji" to "eyes"), "not_found", "no such update: $nobody")
        a.refused("react", obj("emoji" to "eyes"), "invalid_argument", "name one of: update, message, request")
        owner.delete("/api/messages/$m")
        a.refused("react", obj("message_id" to m, "emoji" to "eyes"), "not_found", "no such message: $m")
    }

    // ── requests ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `request_input - every validation rule, answered at once, nothing held`() = parity {
        val a = feedAgent
        val p = Fixtures.project(a, "Ask Edge ${Parity.suffix()}")
        val other = Fixtures.project(a, "Ask Other ${Parity.suffix()}")
        val u = Fixtures.update(p, a)["id"].s
        val ou = Fixtures.update(other, a)["id"].s
        val m = ownerMessage(u, "owner")
        val x = { n: Int -> "x".repeat(n) }
        val started = System.nanoTime()
        suspend fun no(message: String, vararg args: Pair<String, Any?>, code: String = "invalid_argument") =
            a.refused("request_input", JsonObject(obj("project" to p.slug) + obj(*args)), code, message)
        no("question is required", "kind" to "text", "question" to "  ")
        no("question is required", "kind" to "text", "question" to "  ", "detail" to x(4001), "options" to arr("a"))
        no("question must be at most 500 characters", "kind" to "text", "question" to x(501))
        no("detail must be at most 4000 characters", "kind" to "text", "question" to "q", "detail" to x(4001))
        no("a text request takes no options", "kind" to "text", "question" to "q", "options" to arr("a"))
        no("options[1] is required", "kind" to "choice", "question" to "q", "options" to arr("a", "  "))
        no("options[1] must be at most 200 characters", "kind" to "choice", "question" to "q", "options" to arr("a", x(201)))
        no("options must be distinct", "kind" to "buttons", "question" to "q", "options" to arr("a", " a "))
        no("at most 25 options", "kind" to "choice", "question" to "q", "options" to (0 until 26).map { "o$it" })
        for (kind in listOf("buttons", "choice", "multi_choice", "form")) no("a $kind request needs at least one option", "kind" to kind, "question" to "q", "options" to arr())
        no("placeholder is for a text or form request", "kind" to "confirm", "question" to "q", "placeholder" to "p")
        no("multiline is for a text or form request", "kind" to "confirm", "question" to "q", "multiline" to true, "timeout_s" to 4)
        no("label names a form request’s field", "kind" to "choice", "question" to "q", "options" to arr("a"), "label" to "l")
        no("a multi_choice takes no default", "kind" to "multi_choice", "question" to "q", "options" to arr("a"), "default" to "a")
        no("the default for a confirm is \"true\" or \"false\"", "kind" to "confirm", "question" to "q", "default" to "yes")
        no("the default must be one of the options", "kind" to "choice", "question" to "q", "options" to arr("a"), "default" to "b")
        no("min and max are for a text, form or multi_choice request", "kind" to "confirm", "question" to "q", "min" to 1)
        no("min must not exceed max", "kind" to "text", "question" to "q", "min" to 5, "max" to 2)
        no("max must be at most 10000", "kind" to "text", "question" to "q", "max" to 10_001)
        no("max must be at most 2", "kind" to "multi_choice", "question" to "q", "options" to arr("a", "b"), "max" to 3)
        no("min must be a whole number", "kind" to "text", "question" to "q", "min" to -1)
        no("default must be at most 10000 characters", "kind" to "text", "question" to "q", "default" to x(10_001))
        no("placeholder must be at most 200 characters", "kind" to "text", "question" to "q", "placeholder" to x(201))
        no("no such project: nope-edge", "kind" to "confirm", "question" to "q", "project" to "nope-edge", code = "not_found")
        no("no such update: $nobody", "kind" to "confirm", "question" to "q", "update" to nobody, code = "not_found")
        no("the update named belongs to a different project", "kind" to "confirm", "question" to "q", "update" to ou)
        no("no such message: $nobody", "kind" to "confirm", "question" to "q", "message_id" to nobody, "update" to u, code = "not_found")
        no("the message named belongs to a different project", "kind" to "confirm", "question" to "q", "message_id" to m, "project" to other.slug)
        // The options are checked before the timeout.
        no("timeout_s must be a whole number of seconds between 5 and 86400", "kind" to "confirm", "question" to "q", "options" to arr(), "timeout_s" to 4)
        for (t in listOf(4, 86_401)) no("timeout_s must be a whole number of seconds between 5 and 86400", "kind" to "confirm", "question" to "q", "timeout_s" to t)
        no("not a media id: \"nope\"", "kind" to "confirm", "question" to "q", "media_ids" to arr("nope"))
        no("embeds[0]: no embed type \"no-such-look\". Call list_embed_types to see what is registered, or register_embed_type to add it.",
            "kind" to "confirm", "question" to "q", "embeds" to arr(obj("type" to "no-such-look", "data" to obj())))
        no("pictures on options are for a buttons, choice or multi_choice request", "kind" to "text", "question" to "q", "option_media" to obj("a" to nobody))
        no("b is not one of the options", "kind" to "choice", "question" to "q", "options" to arr("a"), "option_media" to obj("b" to nobody))
        no("each picture may be used once", "kind" to "choice", "question" to "q", "options" to arr("a", "b"), "option_media" to obj("a" to nobody, "b" to nobody))
        no("no such media: $nobody", "kind" to "choice", "question" to "q", "options" to arr("a"), "option_media" to obj("a" to nobody), code = "not_found")
        assertTrue(System.nanoTime() - started < 30_000_000_000L, "refusals are not held")
    }

    private val holdS: Int = System.getenv("PARITY_HOLD_S")?.toIntOrNull() ?: 55

    private suspend fun pendingRequest(question: String, agent: Agent, call: Deferred<*>): String =
        awaitCreated(agent, call, "request '$question' to be pending") {
            owner.get("/api/snapshot/requests").obj()["requests"].arr.firstOrNull { it["question"].s == question }?.get("id").str
        }

    private fun requestView(id: String, kind: String, question: String, projectId: String?, detail: String? = null, options: List<String>? = null, edited: Boolean = false) = JsonObject(
        LinkedHashMap<String, JsonElement>().apply {
            put("id", JsonPrimitive(id)); put("kind", JsonPrimitive(kind)); put("question", JsonPrimitive(question))
            put("detail", detail.toJson()); put("options", options.toJson()); put("project_id", projectId.toJson())
            put("update_id", null.toJson()); put("message_id", null.toJson()); put("expires_at", JsonPrimitive(TS))
            if (edited) put("edited_at", JsonPrimitive(TS))
        },
    )

    private fun dismissed(id: String, state: String, view: JsonObject) = obj("state" to state, "request_id" to id, "request" to view)
    private val dismissedSummary = "Your owner dismissed this request without answering. That is not permission: do not proceed as though you had an answer."
    private val timedOutSummary = "Nobody answered before this request timed out. That is not permission: do not proceed as though you had an answer."
    private fun pendingSummary(id: String) = "Nobody has answered yet. This is not a refusal — call await_request({request_id: \"$id\"}) and keep calling it while state is \"pending\"."

    @Test
    fun `long-poll - held for HOLD_S then pending, woken at once by an answer, a withdraw or a dismiss, timed out`() = parity(timeout = 3.minutes) {
        val a = Parity.mintAgent(uniq("edge-asker"))
        val p = Fixtures.project(a, "Hold Edge ${Parity.suffix()}")
        Fixtures.update(p, a)
        val sfx = Parity.suffix()
        coroutineScope {
            // 1. Nobody answers: request_input and a parallel await_request are both held about HOLD_S, then pending.
            val pickQ = "Pick one $sfx"
            val started = System.nanoTime()
            val asked = async { a.tool("request_input", obj("kind" to "choice", "question" to pickQ, "options" to arr("red", "green"), "default" to "red", "detail" to "d", "project" to p.slug, "timeout_s" to 3600)) }
            val pick = pendingRequest(pickQ, a, asked)
            val awaited = async { a.tool("await_request", obj("request_id" to pick)) }

            // 2. A request that expires in 5 s is held 5 s, then times out.
            val expireQ = "Expire? $sfx"
            val expiring = async {
                val t0 = System.nanoTime()
                a.tool("request_input", obj("kind" to "confirm", "question" to expireQ, "project" to p.slug, "timeout_s" to 5)) to (System.nanoTime() - t0) / 1_000_000
            }

            // While both are held: refusals, a pending approval, and edits (which wake nobody).
            pendingRequest(expireQ, a, expiring)
            val hb = a.ok("register_session")["session_id"].s
            assertEquals(2, a.ok("heartbeat", obj("session_id" to hb))["pending_approvals"].int)
            beta.refused("await_request", obj("request_id" to pick), "invalid_argument", "request belongs to another agent")
            beta.refused("edit_request", obj("request_id" to pick, "question" to "x"), "invalid_argument", "request belongs to another agent")
            beta.refused("withdraw_request", obj("request_id" to pick), "invalid_argument", "request belongs to another agent")
            for (tool in listOf("await_request", "withdraw_request")) a.refused(tool, obj("request_id" to nobody), "not_found", "no such request: $nobody")
            a.refused("edit_request", obj("request_id" to nobody, "question" to "x"), "not_found", "no such request: $nobody")
            a.refused("await_request", obj("request_id" to "  "), "not_found", "no such request:   ")
            a.refused("edit_request", obj("request_id" to pick, "question" to "  "), "invalid_argument", "question is required")
            a.refused("edit_request", obj("request_id" to pick, "options" to arr("blue", "green")), "invalid_argument", "the default must be one of the options")
            a.refused("edit_request", obj("request_id" to pick, "default" to "purple"), "invalid_argument", "the default must be one of the options")
            a.refused("edit_request", obj("request_id" to pick, "options" to arr(" ")), "invalid_argument", "options[0] is required")
            a.refused("edit_request", obj("request_id" to pick, "options" to arr()), "invalid_argument", "a choice request needs at least one option")
            a.refused("edit_request", obj("request_id" to pick), "invalid_argument", "an edit must change the question, the detail, the options or the default")
            val editedSummary = "Edited \"$pickQ\". Your owner sees the new question."
            a.whole("edit_request", obj("request_id" to pick, "question" to pickQ), editedSummary,
                obj("request" to requestView(pick, "choice", pickQ, p.id, "d", listOf("red", "green"), edited = true)))
            a.whole("edit_request", obj("request_id" to pick, "options" to arr("blue", "green"), "default" to "blue"), editedSummary,
                obj("request" to requestView(pick, "choice", pickQ, p.id, "d", listOf("blue", "green"), edited = true)))
            for (args in listOf(obj("detail" to null), obj("default" to null), obj("detail" to "  "))) {
                a.whole("edit_request", JsonObject(obj("request_id" to pick) + args), editedSummary,
                    obj("request" to requestView(pick, "choice", pickQ, p.id, null, listOf("blue", "green"), edited = true)))
            }

            val (expired, expireMs) = expiring.await()
            val expire = expired.structured["request_id"].s
            val expireView = requestView(expire, "confirm", expireQ, p.id)
            if (holdS > 5) {
                assertTrue(expireMs in 4_500..15_000, "held until it expired: $expireMs ms")
                // The hold's timer can fire a moment before the deadline on a slow machine; the
                // request is then still pending, and the await_request below times it out.
                if (expired.structured["state"].s != "pending") whole(expired, timedOutSummary, dismissed(expire, "timeout", expireView))
            }
            delay(1_000)
            a.whole("await_request", obj("request_id" to expire), timedOutSummary, dismissed(expire, "timeout", expireView))
            a.refused("edit_request", obj("request_id" to expire, "question" to "x"), "conflict", "request $expire timed out")
            a.refused("withdraw_request", obj("request_id" to expire), "conflict", "request $expire timed out")

            val pending = obj("state" to "pending", "request_id" to pick,
                "request" to requestView(pick, "choice", pickQ, p.id, null, listOf("blue", "green"), edited = true), "poll_after_ms" to 1000)
            for (held in listOf(asked, awaited)) {
                val result = held.await()
                val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
                assertTrue(elapsed >= holdS - 1 && elapsed <= holdS + 15, "held about $holdS s: $elapsed s")
                whole(result, pendingSummary(pick), pending)
            }

            // 3. Answered while an await_request is held: it returns at once, and again on every call after.
            val heldAgain = async { a.tool("await_request", obj("request_id" to pick)) }
            delay(700)
            val t0 = System.nanoTime()
            assertEquals(200, owner.post("/api/requests/$pick/answer", obj("value" to "green")).status.value)
            val answered = heldAgain.await()
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000, "woken by the answer")
            val answer = obj("state" to "answered", "request_id" to pick,
                "request" to requestView(pick, "choice", pickQ, p.id, null, listOf("blue", "green"), edited = true),
                "response" to obj("kind" to "choice", "value" to "green"), "answered_at" to TS)
            whole(answered, "Your owner answered: \"green\"", answer)
            a.whole("await_request", obj("request_id" to pick), "Your owner answered: \"green\"", answer)
            a.refused("edit_request", obj("request_id" to pick, "question" to "x"), "conflict", "request $pick has already been answered")
            a.refused("withdraw_request", obj("request_id" to pick), "conflict", "request $pick has already been answered")

            // 4. Withdrawn by the agent while held: the held call returns withdrawn at once.
            val nameQ = "Name it $sfx"
            val named = async { a.tool("request_input", obj("kind" to "text", "question" to nameQ, "placeholder" to "p", "multiline" to true, "min" to 1, "max" to 20, "project" to p.slug)) }
            val name = pendingRequest(nameQ, a, named)
            a.whole("withdraw_request", obj("request_id" to name), "Withdrew \"$nameQ\". It is off your owner's dashboard.", obj("request_id" to name, "state" to "withdrawn"))
            whole(named.await(), dismissedSummary, dismissed(name, "withdrawn", requestView(name, "text", nameQ, p.id)))
            a.refused("edit_request", obj("request_id" to name, "question" to "x"), "conflict", "request $name was withdrawn")
            a.refused("withdraw_request", obj("request_id" to name), "conflict", "request $name was withdrawn")

            // 5. Dismissed by the owner while held: cancelled.
            val someQ = "Pick some $sfx"
            val some = async { a.tool("request_input", obj("kind" to "multi_choice", "question" to someQ, "options" to arr("a", "b", "c"), "min" to 1, "project" to p.slug)) }
            val someId = pendingRequest(someQ, a, some)
            owner.delete("/api/requests/$someId")
            val someView = requestView(someId, "multi_choice", someQ, p.id, options = listOf("a", "b", "c"))
            whole(some.await(), dismissedSummary, dismissed(someId, "cancelled", someView))
            a.whole("await_request", obj("request_id" to someId), dismissedSummary, dismissed(someId, "cancelled", someView))
            a.refused("edit_request", obj("request_id" to someId, "question" to "x"), "conflict", "request $someId was dismissed")
            a.refused("withdraw_request", obj("request_id" to someId), "conflict", "request $someId was dismissed")
        }
    }

    @Test
    fun `request answers of every kind, and a blank project is no project`() = parity {
        val a = feedAgent
        val p = Fixtures.project(a, "Answers Edge ${Parity.suffix()}")
        val sfx = Parity.suffix()
        coroutineScope {
            val cases = listOf(
                Triple("text", obj(), "hello there") to "\"hello there\"",
                Triple("multi_choice", obj("options" to arr("a", "b")), arr("b", "a")) to "\"b\", \"a\"",
                Triple("multi_choice", obj("options" to arr("a", "b")), arr()) to "nothing.",
                Triple("buttons", obj("options" to arr("Ship", "Wait")), "Wait") to "\"Wait\"",
                Triple("confirm", obj(), false) to "no.",
                Triple("form", obj("options" to arr("Send"), "label" to "Why"), obj("action" to "Send", "text" to "abc")) to "\"Send\" on text of 3 characters.",
            )
            val held = cases.mapIndexed { i, (case, _) ->
                val (kind, extra, _) = case
                val question = "Kind $i $sfx"
                question to async { a.tool("request_input", JsonObject(obj("kind" to kind, "question" to question, "project" to p.slug) + extra)) }
            }
            for ((i, pair) in held.withIndex()) {
                val (question, call) = pair
                val (case, described) = cases[i]
                val (kind, extra, value) = case
                val id = pendingRequest(question, a, call)
                owner.post("/api/requests/$id/answer", obj("value" to value))
                val options = (extra["options"] as? JsonArray)?.map { it.s }
                whole(call.await(), "Your owner answered: $described", obj(
                    "state" to "answered", "request_id" to id, "request" to requestView(id, kind, question, p.id, options = options),
                    "response" to obj("kind" to kind, "value" to value), "answered_at" to TS,
                ))
            }

            // A blank project is taken as none: the request belongs to no project.
            val loose = "Loose $sfx"
            val call = async { a.tool("request_input", obj("kind" to "confirm", "question" to loose, "project" to "  ")) }
            val id = pendingRequest(loose, a, call)
            owner.post("/api/requests/$id/answer", obj("value" to true))
            assertTrue(call.await().structured["request"]["project_id"].isNull)
        }
    }

    // ── text ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `cut text keeps half an emoji as JSON's escape, and lone surrogates are stored as U+FFFD`() = parity {
        val a = cardAgent
        val p = Fixtures.project(a, "Cut Edge ${Parity.suffix()}")
        // 239 x then an emoji: the 240-character cut splits it.
        val u = Fixtures.update(p, a, body = "x".repeat(239) + "😀 tail")["id"].s
        a.ok("set_status", obj("update_id" to u, "status" to "todo"))
        val work = a.tool("list_work", obj("project" to p.slug))
        assertEquals("x".repeat(239) + "\ud83d…", work.structured["work"][0]["summary"].s)
        assertTrue(work.text.contains("\"summary\": \"" + "x".repeat(239) + "\\ud83d…\""), "the text block escapes it as JSON.stringify does")
        whole(work, "1 card: 1 todo.", stamped(work.structured) as JsonObject)

        // In a quoted parent, too.
        val reader = Parity.mintAgent(uniq("edge-cut"))
        val rp = Fixtures.project(reader, "Cut Reader ${Parity.suffix()}")
        val ru = Fixtures.update(rp, reader)["id"].s
        reader.ok("get_messages")
        val parent = ownerMessage(ru, "x".repeat(239) + "😀" + "x".repeat(20))
        beta.ok("post_message", obj("body" to "reply", "message_id" to parent))
        val quoted = reader.ok("get_messages")["messages"].arr.last()["in_reply_to"].o
        assertEquals("x".repeat(239) + "\ud83d…", quoted["body"].s)

        // A lone surrogate sent in is stored, and read back, as the replacement character. (Sent as
        // JSON escapes by hand: encoding it raw would make it "?" before it left.)
        val raw = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"post_update","arguments":""" +
            """{"project":"${p.slug}","body":"a\ud83d","title":"\udc00"}}}"""
        val response = call(HttpMethod.Post, "/mcp") {
            bearer(a.token)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBodyText(raw)
        }
        val odd = response.obj()["result"]["structuredContent"]["update"].o
        assertEquals("\ufffd", odd["title"].s)
        assertEquals(2, odd["body_chars"].int)
        // Characters JavaScript does not trim stay.
        val ws = Fixtures.update(p, a, body = "\u001c body \u001c")["id"].s
        a.ok("set_status", obj("update_id" to ws, "status" to "todo"))
        assertEquals("\u001c body \u001c", a.ok("list_work", obj("project" to p.slug, "limit" to 1))["work"][0]["summary"].s)
        assertEquals("\u001c v \u001c", a.ok("set_card_field", obj("update_id" to ws, "name" to "Ünïcode", "value" to "\u001c v \u001c"))["fields"][0]["value"].s)
    }

    private companion object {
        const val TS = "<ts>"
    }
}
