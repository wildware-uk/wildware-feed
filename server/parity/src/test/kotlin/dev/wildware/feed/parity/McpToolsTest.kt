package dev.wildware.feed.parity

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** At least one end-to-end check per MCP tool. Spec: server/spec/05-mcp.md §7 and its parity table. */
class McpToolsTest : ParityTest() {
    private suspend fun Agent.refused(tool: String, args: JsonObject, code: String, message: String) {
        val result = tool(tool, args)
        if (!result.isError) fail("$tool should have failed: ${result.raw}")
        assertEquals(code, result.structured["error"].s, result.raw.toString())
        assertEquals(message, result.structured["message"].s)
        assertEquals("$code: $message", result.text)
    }

    private suspend fun Agent.sdkRefused(tool: String, args: JsonObject, contains: String) {
        val result = tool(tool, args)
        assertTrue(result.isError)
        assertTrue(result.text.startsWith("MCP error -32602: "), result.text)
        assertContains(result.text, contains)
    }

    @Test
    fun `create_project is idempotent and keeps the first description`() = parity {
        val name = "Demo Project! ${Parity.suffix()}"
        val first = alpha.tool("create_project", obj("name" to name, "description" to "d"))
        val project = first.structured["project"].o
        assertEquals(true, first.structured["created"].bool)
        assertTrue(project["theme"].isNull)
        assertEquals(listOf("id", "slug", "name", "description", "status", "pinned", "created_at", "updated_at", "theme"), project.keys.toList())
        assertTrue(first.text.startsWith("Created project \"$name\" (slug ${project["slug"].s})."))

        val again = alpha.tool("create_project", obj("name" to name, "description" to "other"))
        assertEquals(false, again.structured["created"].bool)
        assertEquals("d", again.structured["project"]["description"].s)
        assertTrue(again.text.startsWith("Project \"$name\" already exists (slug ${project["slug"].s}); returning it unchanged."), again.text)

        alpha.refused("create_project", obj("name" to "   "), "invalid_argument", "name is required")
    }

    @Test
    fun `list_projects lists and filters`() = parity {
        val project = Fixtures.project()
        val listed = alpha.ok("list_projects")
        assertTrue(listed["projects"].arr.any { it["slug"].s == project.slug })
        assertEquals(listed["projects"].arr.size, listed["count"].int)
        alpha.sdkRefused("list_projects", obj("status" to "x"), "Invalid option: expected one of \"active\"|\"archived\" at status")
    }

    @Test
    fun `set_project_theme normalises colours and checks the logo`() = parity {
        val project = Fixtures.project()
        val themed = alpha.ok("set_project_theme", obj("project" to project.slug, "background" to "#101820", "accent" to "#f80"))
        assertEquals(obj("background" to "#101820", "accent" to "#ff8800"), themed["project"]["theme"])
        alpha.refused("set_project_theme", obj("project" to project.slug, "background" to "red"), "invalid_argument", "background must be a hex colour like #1a2b3c")
        alpha.refused("set_project_theme", obj("project" to "nope-x1", "accent" to "#fff"), "not_found", "no such project: nope-x1")
        alpha.refused("set_project_theme", obj("project" to project.slug, "logo_replaces_name" to true), "invalid_argument", "logoReplacesName needs a logo to show instead of the name")
    }

    @Test
    fun `post_update and edit_update`() = parity {
        val project = Fixtures.project()
        val session = alpha.ok("register_session", obj("meta" to obj("host" to "box")))["session_id"].s
        val posted = alpha.tool("post_update", obj(
            "project" to project.slug, "title" to "T", "body" to "hello **world**",
            "level" to "success", "priority" to "high", "session_id" to session,
        ))
        val update = posted.structured["update"].o
        assertEquals("success", update["level"].s)
        assertEquals(15, update["body_chars"].int)
        assertEquals(session, update["session_id"].s)
        assertEquals(
            listOf("id", "project_id", "agent_id", "session_id", "title", "level", "pinned", "body_chars", "created_at", "edited_at", "status"),
            update.keys.toList(),
        )
        assertTrue(posted.text.startsWith("Posted a success update to project ${project.slug} as agent \"${alpha.name}\"."), posted.text)

        alpha.refused("post_update", obj("project" to project.slug, "body" to ""), "invalid_argument", "body is required")
        alpha.refused("post_update", obj("project" to project.slug, "body" to "x", "session_id" to "bogus"), "not_found", "no such session: bogus")

        val id = update["id"].s
        val edited = alpha.ok("edit_update", obj("update_id" to id, "title" to null, "body" to "edited"))["update"].o
        assertTrue(edited["title"].isNull)
        assertEquals(6, edited["body_chars"].int)
        assertNotNull(edited["edited_at"].str)
        beta.refused("edit_update", obj("update_id" to id, "body" to "mine"), "invalid_argument", "that update was posted by another agent")
        alpha.refused("edit_update", obj("update_id" to id), "invalid_argument", "an edit must change the title, the body, the level or the priority")
    }

    @Test
    @Tag("media")
    fun `create_upload and attach_media`() = parity {
        val reserved = alpha.ok("create_upload", obj("filename" to "shot.png", "mime" to "image/png", "bytes" to 1234))
        assertTrue(Regex("^${Regex.escape(Parity.baseUrl)}/api/upload/[0-9A-Z]{26}\\.[A-Za-z0-9_-]{43}$").matches(reserved["upload_url"].s), reserved["upload_url"].s)
        assertEquals(1234, reserved["max_bytes"].int)
        val expires = java.time.Instant.parse(reserved["expires_at"].s).toEpochMilli() - System.currentTimeMillis()
        assertTrue(expires in 14 * 60_000L..15 * 60_000L + 5_000, "expires in about 15 minutes: $expires")

        alpha.refused("create_upload", obj("filename" to "big.png", "mime" to "image/png", "bytes" to 10_000_000_000L),
            "invalid_argument", "an image may be at most 10485760 bytes; this one declares 10000000000")
        assertTrue(!alpha.tool("create_upload", obj("filename" to "x.exe", "mime" to "application/x-msdownload", "bytes" to 10)).isError)

        val update = Fixtures.update(Fixtures.project())["id"].s
        alpha.refused("attach_media", obj("update_id" to update, "media_ids" to arr("nope")), "invalid_argument", "not a media id: \"nope\"")
        val unknown = alpha.tool("attach_media", obj("update_id" to update, "media_ids" to arr("01ZZZZZZZZZZZZZZZZZZZZZZZZ")))
        assertEquals(arr(), unknown.structured["attached"])
        assertEquals(arr("01ZZZZZZZZZZZZZZZZZZZZZZZZ"), unknown.structured["skipped"])
        assertContains(unknown.text, "skipped 1 that were not available to you.")

        val media = Media.uploadPng(alpha)
        val attached = alpha.ok("attach_media", obj("update_id" to update, "media_ids" to arr(media)))
        assertEquals(arr(media), attached["attached"])
    }

    @Test
    fun `embed types - the built-ins and registering one`() = parity {
        val types = alpha.ok("list_embed_types")
        val builtIn = types["types"].arr.filter { it["built_in"].bool }.map { it["slug"].s }
        assertEquals(listOf("confluence-page", "github-comment", "jira-issue", "linked-page", "slack-message"), builtIn)

        alpha.refused("register_embed_type", obj("slug" to "bad", "name" to "Bad", "spec" to obj()), "invalid_argument", "spec.blocks must be a list of blocks")
        alpha.refused("register_embed_type", obj("slug" to "mini", "name" to "Mini", "spec" to obj("blocks" to arr(obj("type" to "text", "text" to "{{text}}")))),
            "invalid_argument", "blocks[0].value is required")
        val clash = alpha.tool("register_embed_type", obj("slug" to "slack-message", "name" to "Mine", "spec" to obj("blocks" to arr(obj("type" to "text", "value" to "{{text}}")))))
        assertEquals("conflict", clash.structured["error"].s)
        assertTrue(clash.structured["message"].s.startsWith("\"slack-message\" is one of the looks this dashboard ships"))

        val slug = "mini-${Parity.suffix()}"
        val made = alpha.tool("register_embed_type", obj("slug" to slug, "name" to "Mini", "spec" to obj("blocks" to arr(obj("type" to "text", "value" to "{{text}}")))))
        assertTrue(!made.isError, made.raw.toString())
        assertTrue(alpha.ok("list_embed_types")["types"].arr.any { it["slug"].s == slug && !it["built_in"].bool })

        // An update can carry an embed of that type, and the timeline shows it.
        val project = Fixtures.project()
        val update = Fixtures.update(project, extra = obj("embeds" to arr(obj("type" to slug, "data" to obj("text" to "hi")))))["id"].s
        val item = owner.get("/api/snapshot/updates?project=${project.slug}").obj()["updates"]["items"].arr.single { it["id"].s == update }
        assertEquals(slug, item["embeds"][0]["type"].s)
        assertEquals(obj("text" to "hi"), item["embeds"][0]["data"])
    }

    @Test
    fun `apps - register, list, put on an update, set data`() = parity {
        val uri = "ui://parity-${Parity.suffix()}/board"
        assertEquals(
            obj("uri" to uri, "name" to "Board", "url" to null, "bytes" to 9, "csp" to obj("connect" to arr(), "resource" to arr(), "frame" to arr())),
            alpha.ok("register_app", obj("uri" to uri, "name" to "Board", "html" to "<p>hi</p>")),
        )
        alpha.refused("register_app", obj("uri" to "http://x", "name" to "X", "html" to "<p/>"), "invalid_argument", "uri must start with ui://, e.g. \"ui://review-board\"")

        val app = alpha.ok("list_apps")["apps"].arr.single { it["uri"].s == uri }
        assertEquals(true, app["mine"].bool)
        assertTrue(app["updated_at"].str!!.all { it.isDigit() }, "spec surprise: updated_at is a number here")

        val project = Fixtures.project()
        val posted = alpha.ok("post_update", obj("project" to project.slug, "body" to "board", "apps" to arr(obj("app" to uri, "data" to obj("a" to 1), "title" to "B"))))
        val view = posted["apps"][0].o
        assertEquals(uri, view["uri"].s)
        assertEquals("B", view["title"].s)
        val viewId = view["view_id"].s

        val set = alpha.ok("set_app_data", obj("view_id" to viewId, "data" to obj("a" to 2)))
        assertEquals(listOf("view_id", "uri", "title", "updated_at"), set.keys.toList())
        assertEquals(viewId, set["view_id"].s)
        alpha.refused("set_app_data", obj("view_id" to "nope", "data" to obj()), "not_found", "no app view \"nope\"")

        // The owner can open the view, and keep state for it.
        val page = owner.get("/apps/$viewId")
        assertEquals(200, page.status.value)
        assertTrue(page.headers["content-security-policy"]!!.startsWith("default-src 'none'"))
        assertEquals("""{"view_id":"$viewId","state":{"tab":2}}""", owner.post("/api/apps/$viewId/state", obj("state" to obj("tab" to 2))).bodyAsText())
        val bad = owner.post("/api/apps/$viewId/state", obj())
        assertEquals(400, bad.status.value)
        assertEquals("state must be an object, or null to forget it", bad.obj()["message"].s)
    }

    @Test
    fun `sessions - register, heartbeat, end`() = parity {
        val agent = Parity.mintAgent(uniq("sessions"))
        val session = agent.ok("register_session", obj("meta" to obj("host" to "box", "cwd" to "/w", "model" to "m")))
        assertEquals(listOf("session_id", "heartbeat_interval_s"), session.keys.toList())
        assertEquals(30, session["heartbeat_interval_s"].int)
        val id = session["session_id"].s

        // A brand-new agent has worked nowhere, so it hears about every project (spec 04 surprises);
        // once it has a project of its own, the counts are about that project.
        val project = Fixtures.project(agent)
        val update = Fixtures.update(project, agent)["id"].s
        agent.ok("get_messages")
        val beat = agent.tool("heartbeat", obj("session_id" to id))
        assertEquals(obj("ok" to true, "unread_messages" to 0, "open_tasks" to 0, "pending_approvals" to 0), beat.structured)
        assertEquals("Heartbeat recorded. Nothing is waiting for you; carry on.", beat.text.lines().first())
        alpha.refused("heartbeat", obj("session_id" to id), "invalid_argument", "session belongs to another agent")

        // Something waiting: two unread messages on its card and the card in progress.
        agent.ok("set_status", obj("update_id" to update, "status" to "in_progress"))
        owner.post("/api/messages", obj("update" to update, "body" to "from the owner"))
        beta.ok("post_message", obj("update_id" to update, "body" to "from beta"))
        val busy = agent.tool("heartbeat", obj("session_id" to id))
        assertEquals(2, busy.structured["unread_messages"].int)
        assertEquals(1, busy.structured["open_tasks"].int)
        assertEquals("Heartbeat recorded. Waiting for you: 2 unread messages, 1 open task.", busy.text.lines().first())

        // The owner sees it online with its meta.
        val online = owner.get("/api/snapshot/agents").obj()["agents"].arr.single { it["sessionId"].str == id }
        assertEquals("box", online["host"].s)
        assertEquals("/w", online["cwd"].s)

        assertEquals(true, agent.ok("end_session", obj("session_id" to id))["ended"].bool)
        val again = agent.tool("end_session", obj("session_id" to id))
        assertEquals(false, again.structured["ended"].bool)
        assertEquals("Session $id was already ended; nothing to do.", again.text.lines().first())
        agent.refused("heartbeat", obj("session_id" to id), "conflict", "session $id has ended; call register_session to start a new one")
    }

    @Test
    fun `set_status and list_work`() = parity {
        val agent = Parity.mintAgent(uniq("work"))
        val project = Fixtures.project(agent)
        val update = Fixtures.update(project, agent, body = "the work")["id"].s
        val set = agent.tool("set_status", obj("update_id" to update, "status" to "in_progress"))
        assertEquals(obj("card" to obj("kind" to "update", "id" to update), "status" to "in_progress"), set.structured)
        assertTrue(set.text.startsWith("Marked in progress."), set.text)
        agent.refused("set_status", obj("status" to "todo"), "invalid_argument", "name one of: update, message, request")
        agent.refused("set_status", obj("update_id" to update, "message_id" to update, "status" to "todo"), "invalid_argument", "name one of: update, message, request — not 2 of them")

        val work = agent.tool("list_work", obj("project" to project.slug))
        val item = work.structured["work"].arr.single().o
        assertEquals("update", item["kind"].s)
        assertEquals("the work", item["summary"].s)
        assertEquals("in_progress", item["status"].s)
        assertTrue(item["author"].s.startsWith("agent:"))
        assertEquals("1 card: 1 in progress.", work.text.lines().first())
        agent.sdkRefused("list_work", obj("limit" to 0), "Too small: expected number to be >0 at limit")
    }

    @Test
    fun `tags - list, tag, toggle`() = parity {
        val project = Fixtures.project()
        val empty = alpha.tool("list_tags", obj("project" to project.slug))
        assertEquals(0, empty.structured["count"].int)
        assertEquals("No tags yet. tag_card with create: true decides the first one.", empty.text.lines().first())

        val update = Fixtures.update(project)["id"].s
        alpha.refused("tag_card", obj("update_id" to update, "tag" to "urgent"), "not_found", "this project has no tag called \"urgent\"")
        assertEquals(true, alpha.ok("tag_card", obj("update_id" to update, "tag" to "urgent", "create" to true))["on"].bool)
        val off = alpha.tool("tag_card", obj("update_id" to update, "tag" to "urgent"))
        assertEquals(false, off.structured["on"].bool)
        assertEquals("Took \"urgent\" off.", off.text.lines().first())
        assertEquals(1, alpha.ok("list_tags", obj("project" to project.slug))["count"].int)

        val loose = Fixtures.message(args = obj("body" to "nowhere"))["id"].s
        alpha.refused("tag_card", obj("message_id" to loose, "tag" to "x", "create" to true), "invalid_argument", "this card does not belong to a project, so it cannot be tagged")
    }

    @Test
    fun `card fields - set, read, clear`() = parity {
        val update = Fixtures.update(Fixtures.project())["id"].s
        val set = alpha.ok("set_card_field", obj("update_id" to update, "name" to "PR", "value" to "#12"))
        assertEquals(true, set["set"].bool)
        assertEquals("#12", set["fields"][0]["value"].s)
        val read = alpha.ok("get_card_fields", obj("update_id" to update))
        assertEquals(1, read["count"].int)
        assertEquals("PR", read["fields"][0]["name"].s)
        val cleared = alpha.ok("set_card_field", obj("update_id" to update, "name" to "PR", "value" to null))
        assertEquals(false, cleared["set"].bool)
        assertEquals(arr(), cleared["fields"])
        assertEquals(
            obj("card" to obj("kind" to "update", "id" to "01ZZZZZZZZZZZZZZZZZZZZZZZZ"), "fields" to arr(), "count" to 0),
            alpha.ok("get_card_fields", obj("update_id" to "01ZZZZZZZZZZZZZZZZZZZZZZZZ")),
        )
    }

    @Test
    fun `messages - get, acknowledge, post, edit, delete, react`() = parity {
        val agent = Parity.mintAgent(uniq("messages"))
        val project = Fixtures.project(agent)
        val update = Fixtures.update(project, agent)["id"].s
        agent.ok("get_messages") // start from a clean cursor

        val m = owner.post("/api/messages", obj("update" to update, "body" to "owner says")).obj()["message"]["id"].s
        beta.ok("post_message", obj("update_id" to update, "body" to "beta says"))

        val got = agent.ok("get_messages")
        val messages = got["messages"].arr
        assertEquals(listOf("owner says", "beta says"), messages.map { it["body"].s })
        assertEquals(beta.name, messages[1]["author_name"].s)
        assertTrue(messages[0]["author_name"] == null, "the owner's message has no author_name")
        assertEquals(0, got["unread"].int)
        assertEquals(true, got["marked_read"].bool)
        assertEquals(obj("messages" to arr(), "count" to 0, "cursor" to "0", "unread" to 0, "marked_read" to true), agent.ok("get_messages"))
        agent.refused("get_messages", obj("since" to "abc"), "invalid_argument", "since is not a cursor this server issued")

        val thinking = agent.ok("acknowledge", obj("state" to "thinking", "message_id" to m))["ack"].o
        val done = agent.ok("acknowledge", obj("state" to "done", "message_id" to m))["ack"].o
        assertEquals(thinking["id"], done["id"])
        assertEquals("done", done["state"].s)

        val reply = agent.ok("post_message", obj("body" to "reply from agent", "message_id" to m))["message"].o
        assertEquals(update, reply["update_id"].s)
        assertTrue(reply["reply_to"].isNull)
        assertEquals(m, reply["answers"].s)
        assertTrue(agent.ok("post_message", obj("body" to "nowhere"))["message"]["project_id"].isNull)
        agent.refused("post_message", obj("body" to "x", "update_id" to update, "message_id" to m), "invalid_argument", "a reply answers a message, or hangs off an update, not both")

        assertNotNull(agent.ok("edit_message", obj("message_id" to reply["id"].s, "body" to "edited"))["message"]["edited_at"].str)
        agent.refused("edit_message", obj("message_id" to m, "body" to "mine now"), "invalid_argument", "that message was posted by somebody else")

        val on = agent.ok("react", obj("message_id" to m, "emoji" to "👍"))
        assertEquals(true, on["on"].bool)
        assertEquals(1, on["reactions"].arr.size)
        assertEquals(obj("reactions" to arr(), "on" to false), agent.ok("react", obj("message_id" to m, "emoji" to "👍")))
        agent.refused("react", obj("message_id" to m, "emoji" to "nope"), "invalid_argument",
            "\"nope\" is not an emoji. Send the character itself, or one of: eyes, tick, check, white_check_mark, done, +1, thumbsup, -1")

        assertNotNull(agent.ok("delete_message", obj("message_id" to reply["id"].s))["message"]["deleted_at"].str)
        assertNotNull(agent.ok("delete_message", obj("message_id" to reply["id"].s))["message"]["deleted_at"].str)
        agent.refused("delete_message", obj("message_id" to m), "invalid_argument", "that message was posted by somebody else")
    }

    @Test
    fun `request tools - input, await, edit, withdraw`() = parity {
        val project = Fixtures.project()
        alpha.refused("request_input", obj("kind" to "choice", "question" to "Pick", "project" to project.slug), "invalid_argument", "a choice request needs at least one option")
        alpha.refused("request_input", obj("kind" to "confirm", "question" to "x", "project" to project.slug, "timeout_s" to 1), "invalid_argument", "timeout_s must be a whole number of seconds between 5 and 86400")

        coroutineScope {
            val question = "Ship it? ${Parity.suffix()}"
            val held = async { alpha.tool("request_input", obj("kind" to "confirm", "question" to question, "project" to project.slug)) }
            val id = eventually { owner.get("/api/snapshot/requests").obj()["requests"].arr.firstOrNull { it["question"].s == question }?.get("id").str }
            owner.post("/api/requests/$id/answer", obj("value" to true))
            val answered = held.await()
            assertEquals("answered", answered.structured["state"].s)
            assertEquals(obj("kind" to "confirm", "value" to true), answered.structured["response"])
            assertEquals("Your owner answered: yes.", answered.text.lines().first())
        }

        // A request that nobody answers: edit it, await it, withdraw it. It must expire well after the hold.
        val pending = alpha.tool("request_input", obj("kind" to "confirm", "question" to "Later? ${Parity.suffix()}", "project" to project.slug, "timeout_s" to 3600))
        // (held for HOLD_S, 55 s by default, then pending)
        assertEquals("pending", pending.structured["state"].s)
        assertEquals(1000, pending.structured["poll_after_ms"].int)
        val id = pending.structured["request_id"].s

        beta.refused("await_request", obj("request_id" to id), "invalid_argument", "request belongs to another agent")
        alpha.refused("await_request", obj("request_id" to "01ZZZZZZZZZZZZZZZZZZZZZZZZ"), "not_found", "no such request: 01ZZZZZZZZZZZZZZZZZZZZZZZZ")

        val edited = alpha.tool("edit_request", obj("request_id" to id, "question" to "Ship it now?"))
        assertNotNull(edited.structured["request"]["edited_at"].str)
        assertEquals("Edited \"Ship it now?\". Your owner sees the new question.", edited.text.lines().first())
        alpha.refused("edit_request", obj("request_id" to id), "invalid_argument", "an edit must change the question, the detail, the options or the default")

        assertEquals(obj("request_id" to id, "state" to "withdrawn"), alpha.ok("withdraw_request", obj("request_id" to id)))
        alpha.refused("withdraw_request", obj("request_id" to id), "conflict", "request $id was withdrawn")
        val awaited = alpha.tool("await_request", obj("request_id" to id))
        assertEquals("withdrawn", awaited.structured["state"].s)
        assertTrue(awaited.text.startsWith("Your owner dismissed this request without answering."), "spec surprise: says dismissed")
    }
}
