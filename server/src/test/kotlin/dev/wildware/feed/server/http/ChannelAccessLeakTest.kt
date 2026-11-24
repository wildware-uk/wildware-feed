package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.TeamTables
import dev.wildware.feed.server.db.Viewer
import dev.wildware.feed.server.domain.ChannelAccess
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.events.AppEvent
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * One team, one private channel (`vault`, in a private project), and a member and an agent with no
 * access to it trying every route the server has and every MCP tool, aimed at its things
 * (spec/15, the TeamLeakTest sweep for channels).
 *
 * Everything in the vault says "zebra"; nothing anywhere else does. Afterwards:
 *
 * - no answer either of them got says "zebra", or names a vault id it wasn't sent;
 * - the vault's rows are as they were, and nothing new points at it;
 * - no live event about the vault passes the member's stream filter;
 * - nothing from the vault reaches the agent's stream or its counts.
 */
class ChannelAccessLeakTest {
    private val config = Config(
        dataDir = java.nio.file.Files.createTempDirectory("feed-access-leak").toString(), adminPasswordHash = Passwords.hash("server password"),
        sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://localhost:5173", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://localhost:5173",
        secretsKeys = Config.SecretsKeys("k".repeat(32)),
    )

    private var http: io.ktor.client.HttpClient? = null

    private suspend fun ApplicationTestBuilder.send(key: String?, method: String, path: String, body: String? = null, contentType: String = "application/json"): HttpResponse =
        (http ?: createClient { followRedirects = false }.also { http = it }).request(path) {
            this.method = HttpMethod.parse(method)
            header("Accept", "application/json, text/event-stream")
            if (key != null) header("Authorization", "Bearer $key")
            if (body != null) {
                header("Content-Type", contentType)
                setBody(body)
            }
        }

    private suspend fun ApplicationTestBuilder.tool(token: String, name: String, args: JsonObject): String =
        send(
            token, "POST", "/mcp",
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
                put("params", buildJsonObject { put("name", name); put("arguments", args) })
            }.toString(),
        ).bodyAsText()

    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            is String -> put(k, v)
            is Int -> put(k, v)
            is Boolean -> put(k, v)
            is JsonArray -> put(k, v)
            is JsonObject -> put(k, v)
        }
    }

    private fun latest(app: App, table: String, where: String = "1 = 1"): String =
        app.db.one("SELECT id FROM $table WHERE team_id = :team AND $where ORDER BY seq DESC LIMIT 1") { it.getString(1) } ?: fail("no $table row")

    /** The vault's things, through the API, by the owner and scout (who may), all saying zebra. */
    private suspend fun ApplicationTestBuilder.seed(app: App, ownerKey: String, scout: String) {
        tool(scout, "create_project", args("name" to "Zebra vault", "slug" to "vault"))
        tool(scout, "create_project", args("name" to "Web", "slug" to "web"))
        val group = Wire.json.parseToJsonElement(send(ownerKey, "POST", "/api/groups", """{"name":"Zebra lockers","slug":"lockers"}""").bodyAsText())
            .jsonObject["group"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val vault = app.feed.projects.resolve("vault").id
        assertEquals(200, send(ownerKey, "PATCH", "/api/projects/$vault", """{"groupId":"$group"}""").status.value)
        tool(scout, "post_update", args("project" to "vault", "title" to "Zebra plan", "body" to "zebra update"))
        val update = latest(app, "updates", "project_id = '$vault'")
        val blocks = Wire.json.parseToJsonElement("""[{"id":"ship","type":"checklist","title":"Zebra ship","items":[{"id":"1","text":"Zebra tests"}]}]""") as JsonArray
        tool(scout, "post_update", args("project" to "vault", "body" to "zebra blocks", "blocks" to blocks))
        tool(scout, "post_message", args("project" to "vault", "body" to "zebra post"))
        tool(scout, "post_message", args("update_id" to update, "body" to "zebra reply"))
        val message = latest(app, "messages", "project_id = '$vault'")
        tool(scout, "request_input", args("kind" to "confirm", "question" to "Zebra ship?", "project" to "vault", "timeout_s" to 3600))
        tool(scout, "set_status", args("update_id" to update, "status" to "in_progress"))
        tool(scout, "tag_card", args("update_id" to update, "tag" to "zebra-tag", "create" to true))
        tool(scout, "set_card_field", args("update_id" to update, "name" to "Field", "value" to "zebra value"))
        tool(scout, "react", args("update_id" to update, "emoji" to "👍"))
        tool(scout, "acknowledge", args("state" to "thinking", "message_id" to message))
        // An app's registration is the team's, like an embed type: only its showing on a card is the vault's.
        tool(scout, "register_app", args("uri" to "ui://board", "name" to "Board", "html" to "<p>a board</p>"))
        tool(scout, "post_update", args(
            "project" to "vault", "body" to "zebra board",
            "apps" to JsonArray(listOf(buildJsonObject { put("app", "ui://board"); put("data", buildJsonObject { put("m", "zebra") }); put("title", "Zebra board") })),
        ))
        val reserved = tool(scout, "create_upload", args("filename" to "zebra.txt", "mime" to "text/plain", "bytes" to 12))
        val uploadToken = Regex("/api/upload/([A-Za-z0-9_.~-]+)").find(reserved)?.groupValues?.get(1) ?: fail("no upload url: $reserved")
        assertEquals(201, send(null, "PUT", "/api/upload/$uploadToken", "zebra notes.", "text/plain").status.value)
        tool(scout, "attach_media", args("update_id" to update, "media_ids" to JsonArray(listOf(JsonPrimitive(latest(app, "media"))))))
        send(ownerKey, "POST", "/api/messages", """{"project":"vault","body":"zebra owner post"}""")
        // A finished card taken off the board (migration 070).
        val finished = latest(app, "messages", "project_id = '$vault'")
        assertEquals(200, send(ownerKey, "POST", "/api/cards/message/$finished/status", """{"status":"done"}""").status.value)
        assertEquals(200, send(ownerKey, "POST", "/api/cards/message/$finished/board", """{"removed":true}""").status.value)
        assertEquals(200, send(ownerKey, "PUT", "/api/cards/update/$update/follow", """{"following":true}""").status.value)
        // Assignees (migration 074): scout takes the zebra plan, and the owner assigns it the finished post.
        assertTrue("\"isError\":true" !in tool(scout, "assign_work", args("update_id" to update, "assignees" to JsonArray(listOf(JsonPrimitive("me"))))))
        assertEquals(200, send(ownerKey, "POST", "/api/cards/message/$finished/assignees", """{"assignees":["scout"]}""").status.value)
        send(ownerKey, "POST", "/api/messages", """{"project":"vault","body":"@other zebra for you"}""")
        // Docs (migration 076): scout writes one that links to another, and the owner saves it again.
        assertTrue("\"isError\":true" !in tool(scout, "create_doc", args("project" to "vault", "title" to "Zebra notes", "body" to "zebra doc, see [[Zebra plan doc]]")))
        assertTrue("\"isError\":true" !in tool(scout, "create_doc", args("project" to "vault", "title" to "Zebra plan doc", "body" to "zebra plan text")))
        val doc = latest(app, "docs", "project_id = '$vault'")
        assertEquals(200, send(ownerKey, "PUT", "/api/docs/$doc", """{"baseVersion":1,"body":"zebra doc, saved again"}""").status.value)
        // And the rest of the team's ordinary life, in web, where everyone is.
        tool(scout, "post_update", args("project" to "web", "title" to "Web plan", "body" to "an ordinary update"))
        send(ownerKey, "POST", "/api/messages", """{"project":"web","body":"an ordinary post"}""")
        // Then lock it: the project and the channel both private, scout listed.
        assertEquals(200, send(ownerKey, "PUT", "/api/groups/lockers/access", """{"private":true}""").status.value)
        assertEquals(200, send(ownerKey, "PUT", "/api/projects/vault/access", """{"private":true}""").status.value)
        val scoutId = app.db.one("SELECT id FROM agents WHERE team_id = :team AND name = 'scout'") { it.getString(1) }!!
        assertEquals(200, send(ownerKey, "PUT", "/api/projects/vault/access/agent/$scoutId", """{"level":"post"}""").status.value)
        // Feedback about a vault card, by the owner and (pending approval) by scout (review F3).
        assertEquals(201, send(ownerKey, "POST", "/api/feedback", """{"kind":"feedback","title":"Zebra feedback","body":"zebra words","card":{"kind":"update","id":"$update"}}""").status.value)
        assertTrue("error" !in tool(scout, "send_feedback", args("kind" to "feedback", "title" to "Zebra idea", "body" to "zebra idea body", "update_id" to update, "project" to "vault")))
        // Taking scout out of the private project and back in says so inside it, not in no channel (F4).
        assertEquals(200, send(ownerKey, "PUT", "/api/groups/lockers/agents/$scoutId", """{"hears":false}""").status.value)
        assertEquals(200, send(ownerKey, "PUT", "/api/groups/lockers/agents/$scoutId", """{"hears":true}""").status.value)
    }

    /** [a] with only one card named (update, message or request), as a tool takes one: every other card key dropped. */
    private fun onlyCard(a: JsonObject, kind: String): JsonObject {
        val keys = mapOf(
            "update" to setOf("update_id", "updateId", "update", "id"),
            "message" to setOf("message_id", "messageId", "answers", "replyTo"),
            "request" to setOf("request_id", "requestId"),
        )
        val drop = keys.filterKeys { it != kind }.values.flatten().toSet()
        return JsonObject(a.filterKeys { it !in drop })
    }

    /** Ids of the vault's things, by kind. */
    private fun targets(app: App): Map<String, List<String>> {
        val vault = app.feed.projects.resolve("vault").id
        fun ids(table: String, where: String) = app.db.query("SELECT id FROM $table WHERE team_id = :team AND $where ORDER BY seq") { it.getString(1) }
        val updates = ids("updates", "project_id = '$vault'")
        val messages = ids("messages", "project_id = '$vault'")
        val requests = ids("approvals", "project_id = '$vault'")
        val cardIn = (updates + messages + requests).joinToString(",") { "'$it'" }
        return mapOf(
            "update" to updates, "message" to messages, "request" to requests,
            "project" to listOf(vault), "group" to ids("project_groups", "slug = 'lockers'"),
            "tag" to ids("tags", "project_id = '$vault'"),
            "doc" to ids("docs", "project_id = '$vault'"),
            "media" to ids("media", "update_id IN ($cardIn) OR message_id IN ($cardIn) OR request_id IN ($cardIn)"),
            "view" to ids("app_views", "update_id IN ($cardIn) OR message_id IN ($cardIn) OR request_id IN ($cardIn)"),
        )
    }

    private fun fills(pattern: String, v: Map<String, List<String>>): List<String> {
        var paths = listOf(pattern)
        fun sub(slot: String, values: List<String>) {
            paths = paths.flatMap { p -> if ("{$slot}" in p) values.map { p.replace("{$slot}", it) } else listOf(p) }
        }
        if ("{kind}" in pattern && "/access/" !in pattern) {
            paths = listOf("update", "message", "request").flatMap { kind -> v[kind].orEmpty().map { pattern.replace("{kind}", kind).replace("{id}", it) } }
        }
        if ("/access/{kind}/{id}" in pattern) sub("kind", listOf("member", "agent"))
        // A thread read takes a card's id or a reply's: try every update and message of the vault.
        if (pattern.startsWith("/api/threads")) sub("id", v["update"].orEmpty() + v["message"].orEmpty())
        val idKind = when {
            pattern.startsWith("/api/messages") -> "message"
            pattern.startsWith("/api/updates") -> "update"
            pattern.startsWith("/api/requests") -> "request"
            pattern.startsWith("/api/tags") -> "tag"
            pattern.startsWith("/api/docs") -> "doc"
            pattern.contains("media") -> "media"
            else -> null
        }
        sub("id", idKind?.let { v[it] } ?: listOf("x"))
        sub("reference", v["project"].orEmpty() + v["group"].orEmpty() + listOf("vault", "lockers"))
        sub("view", v["view"].orEmpty())
        sub("variant", listOf("original", "thumb"))
        sub("agentId", listOf("x"))
        sub("block", listOf("ship"))
        sub("version", listOf("1", "2"))
        for (slot in listOf("connection", "token", "name")) sub(slot, listOf("x"))
        return paths
    }

    private fun bodyAt(v: Map<String, List<String>>, i: Int): JsonObject {
        fun pick(kind: String) = v[kind].orEmpty().let { if (it.isEmpty()) "x" else it[i % it.size] }
        return buildJsonObject {
            put("id", pick("update")); put("update_id", pick("update")); put("updateId", pick("update")); put("update", pick("update"))
            put("message_id", pick("message")); put("messageId", pick("message")); put("answers", pick("message")); put("replyTo", pick("message"))
            put("request_id", pick("request")); put("requestId", pick("request"))
            put("project", pick("project")); put("projectId", pick("project")); put("channel", pick("project")); put("group", pick("group"))
            put("groupId", pick("group")); put("view_id", pick("view"))
            put("tag", pick("tag")); put("tagId", pick("tag")); put("media_ids", JsonArray(listOf(JsonPrimitive(pick("media")))))
            put("mediaIds", JsonArray(listOf(JsonPrimitive(pick("media"))))); put("projects", JsonArray(listOf(JsonPrimitive("vault"))))
            put("name", "Field"); put("body", "hello"); put("emoji", "👍"); put("on", true); put("status", "done"); put("state", "done")
            put("removed", true); put("autoRemoveDays", 7)
            put("assignees", JsonArray(listOf(JsonPrimitive("scout"), JsonPrimitive("Sam"), JsonPrimitive("me")))); put("mode", "set")
            put("assigned_to", "scout"); put("assigned", "scout")
            put("doc_id", pick("doc")); put("docId", pick("doc")); put("title", "Zebra notes"); put("query", "zebra")
            put("base_version", 1); put("baseVersion", 1); put("version", 1); put("new_title", "Renamed")
            put("edits", JsonArray(listOf(buildJsonObject { put("old_str", "zebra"); put("new_str", "horse") })))
            put("value", true); put("kind", "confirm"); put("question", "q?"); put("level", "read"); put("follow", true); put("following", true); put("pinned", true)
            put("private", false); put("item", "1"); put("done", true); put("options", JsonArray(listOf(JsonPrimitive("a"))))
            put("ids", JsonArray(listOf(JsonPrimitive(pick("update")))))
            put("cards", JsonArray(listOf(JsonPrimitive(pick("update")))))
        }
    }

    private fun queryAt(v: Map<String, List<String>>, i: Int): String =
        bodyAt(v, i).entries.filter { it.value is JsonPrimitive }.joinToString("&") { (k, value) ->
            "$k=" + java.net.URLEncoder.encode((value as JsonPrimitive).content, Charsets.UTF_8)
        } + "&reply_to=" + v["message"]!!.first() + "&task_id=" + v["update"]!!.first() + "&request=" + v["request"]!!.first()

    /** Every row that names one of the vault's ids, table by table. */
    private fun vaultRows(app: App, ids: Set<String>): Map<String, List<String>> = TeamTables.ALL.associateWith { table ->
        app.db.query("SELECT * FROM \"$table\" WHERE team_id = :team") { rs ->
            (1..rs.metaData.columnCount).joinToString("|") { "${rs.metaData.getColumnName(it)}=${rs.getObject(it)}" }
        }.filter { row -> ids.any { it in row } }
            // What the server keeps for its own bookkeeping about who looked, not the vault's content.
            .filter { table !in setOf("read_cursors", "message_deliveries", "sessions", "agents") }
            .sorted()
    }

    @Test
    fun `a member and an agent with no access can't see, change or hear anything in a private channel`() {
        val app = App(config, Database.memory())
        val events = mutableListOf<AppEvent>()
        val stopEvents = app.bus.subscribe { events += it }
        try {
            testApplication {
                application { feed(app) }
                val ownerKey = app.devices.mint("Owner phone").second
                val (_, invite) = app.people.invite(app.people.find(People.FOUNDER)!!, People.Role.MEMBER, "sam@example.test")
                val sam = app.people.accept(invite, "Sam", "sam@example.test", "password for sam")
                val samKey = app.devices.mint("Sam phone", sam.id).second
                // An admin who isn't listed on the private channel or project: hidden from them too (spec/15).
                val (_, adminInvite) = app.people.invite(app.people.find(People.FOUNDER)!!, People.Role.ADMIN, "ada@example.test")
                val ada = app.people.accept(adminInvite, "Ada", "ada@example.test", "password for ada")
                val adaKey = app.devices.mint("Ada phone", ada.id).second
                val scout = app.agents.mint("scout").second
                val (other, otherToken) = app.agents.mint("other")
                seed(app, ownerKey, scout)
                // Another agent with no access can't be put in the private project (F4).
                assertEquals(409, send(ownerKey, "PUT", "/api/groups/lockers/agents/${other.id}", """{"hears":true}""").status.value)
                tool(otherToken, "register_session", args("meta" to buildJsonObject { put("host", "box") }))

                val v = targets(app)
                assertTrue(v.values.all { it.isNotEmpty() }, "the vault has one of everything: $v")
                val vaultIds = v.values.flatten().toSet()
                // The owner sees it all: the sweep below proves only that the others don't.
                assertTrue("zebra" in send(ownerKey, "GET", "/api/snapshot").bodyAsText().lowercase())
                val before = vaultRows(app, vaultIds)

                fun leaked(sent: String, answer: String): List<String> =
                    vaultIds.filter { it in answer && it !in sent } + listOfNotNull("zebra".takeIf { answer.contains("zebra", ignoreCase = true) && !sent.contains("zebra", ignoreCase = true) })

                val leaks = mutableListOf<String>()
                var calls = 0
                for (endpoint in registeredEndpoints.sorted()) {
                    val (method, pattern) = endpoint.split(" ", limit = 2)
                    if (pattern == "/mcp" || pattern == "/api/devices/sign-out" || pattern.endsWith("/stream") || pattern.startsWith("/admin")) continue
                    for ((i, path) in fills(pattern, v).withIndex()) {
                        val read = method == "GET" || method == "HEAD"
                        val body = if (read) null else bodyAt(v, i).toString()
                        // A read with every query parameter, and with none (a bad one, like status=done, refuses the lot).
                        val targets = if (read) listOf("$path?" + queryAt(v, i), path) else listOf(path)
                        for (target in targets) for (key in listOf(samKey, adaKey, otherToken)) {
                            val answer = kotlinx.coroutines.withTimeoutOrNull(5_000) { send(key, method, target, body).bodyAsText() }
                                ?: fail("$method $path didn't answer")
                            calls++
                            leaked("$target $body", answer).takeIf { it.isNotEmpty() }?.let { leaks += "$method $path -> $it: ${answer.take(300)}" }
                        }
                    }
                }
                val tools = Wire.json.parseToJsonElement(javaClass.getResource("/mcp/tools-list.json")!!.readText()) as JsonArray
                for (t in tools) {
                    val name = t.jsonObject["name"]!!.jsonPrimitive.content
                    // Every card at once (which a card tool refuses before any access check), each kind of
                    // card alone, and nothing at all (a list tool's "every channel").
                    val tries = (0 until 3).flatMap { i -> listOf(bodyAt(v, i)) + listOf("update", "message", "request").map { onlyCard(bodyAt(v, i), it) } } +
                        listOf(JsonObject(emptyMap()))
                    for (a in tries) {
                        // The sweep makes far more calls than an agent may in a minute: a refusal for that proves nothing.
                        app.mcpLimiter.reset(other.id)
                        val answer = kotlinx.coroutines.withTimeoutOrNull(5_000) { tool(otherToken, name, a) } ?: fail("tool $name didn't answer")
                        assertTrue("rate_limited" !in answer, "tool $name was rate limited, so the sweep checked nothing")
                        calls++
                        leaked(a.toString(), answer).takeIf { it.isNotEmpty() }?.let { leaks += "tool $name -> $it: ${answer.take(300)}" }
                    }
                }
                assertTrue(calls > 300, "the sweep made $calls calls")
                assertEquals(emptyList(), leaks, "someone without access was told about the vault")

                // The vault's rows are as they were, and nothing new names it.
                val after = vaultRows(app, vaultIds)
                val changed = before.keys.filter { before[it] != after[it] }.associateWith { t -> (after[t].orEmpty() - before[t].orEmpty().toSet()) to (before[t].orEmpty() - after[t].orEmpty().toSet()) }
                assertEquals(emptyMap(), changed, "the vault's rows changed (new to gone, by table)")

                // No live event about the vault passes Sam's stream; the owner's passes them.
                val access = ChannelAccess(app.db, app.clock)
                val aboutVault = events.filter { e -> vaultIds.any { it in e.payload.toString() } }
                assertTrue(aboutVault.isNotEmpty())
                val passed = aboutVault.filter { e -> Viewer.with(Viewer.member(sam.id)) { access.eventVisible(e.payload) } }
                assertEquals(emptyList(), passed.map { "${it.type} ${it.payload}" }, "vault events in Sam's stream")
                assertTrue(aboutVault.all { e -> Viewer.with(Viewer.member(People.FOUNDER)) { access.eventVisible(e.payload) } })

                // Nothing from the vault reaches the agent: not its stream, not its counts, not its mention.
                val unread = Viewer.with(Viewer.agent(other.id)) { app.work.unread(other.id, 50) }
                assertTrue(unread.none { it.projectId == v["project"]!!.single() }, unread.toString())
                val bell = send(samKey, "GET", "/api/notifications").bodyAsText()
                assertTrue("zebra" !in bell.lowercase(), bell)
            }
        } finally {
            stopEvents()
            app.close()
        }
    }
}
