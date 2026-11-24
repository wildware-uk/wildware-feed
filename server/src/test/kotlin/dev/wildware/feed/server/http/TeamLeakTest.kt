package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Dialect
import dev.wildware.feed.server.db.TeamTables
import dev.wildware.feed.server.db.Tenant
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Two teams in one database, and team A trying every route and every MCP tool on team B's things
 * (docs/HOSTING.md §2, the two-team leak suite).
 *
 * Both teams are built alike, through the API: the same agent names, the same channel (on
 * Postgres, the same slug), cards, replies, a request, tags, fields, reactions, an upload, a
 * pairing, an invite, a secret, an OAuth client. Team B's text all says "bravo"; nothing A sends
 * does. Then A, with its own keys at its own address, calls every route the server has and every
 * tool, aimed at B's ids. Afterwards:
 *
 * - no answer A got names a B id it didn't send, or says "bravo";
 * - B's rows are exactly as they were;
 * - no row of either team points at the other's;
 * - B's keys, tokens and links open nothing at A's address.
 *
 * Each team is at its own address (`alpha.feed.test`), and the server is strict, as one with many
 * teams is: work with no team set fails instead of falling back to the home team.
 */
class TeamLeakTest {
    private val config = Config(
        dataDir = java.nio.file.Files.createTempDirectory("feed-leak").toString(), adminPasswordHash = Passwords.hash("server password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://localhost:5173", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://localhost:5173",
        secretsKeys = Config.SecretsKeys("k".repeat(32)),
        teamDomain = "feed.test",
    )


    private var strictBefore = false

    @BeforeTest fun strict() { strictBefore = Tenant.strict; Tenant.strict = true }

    @AfterTest fun relax() { Tenant.strict = strictBefore }

    /** One team's ways in and what it made. */
    class Team(val id: String, val marker: String) {
        lateinit var ownerKey: String
        lateinit var ownerId: String
        lateinit var agentToken: String
        lateinit var agentId: String
        val links = mutableListOf<String>()
        val tokens = mutableListOf<String>()
    }

    private val alpha = Team("alpha", "alpha")
    private val bravo = Team("bravo", "bravo")

    private var http: io.ktor.client.HttpClient? = null

    private suspend fun ApplicationTestBuilder.send(team: Team, key: String?, method: String, path: String, body: String? = null, contentType: String = "application/json"): HttpResponse =
        (http ?: createClient { followRedirects = false }.also { http = it }).request(path) {
            this.method = HttpMethod.parse(method)
            // Each team at its own address, as a server with many teams routes them.
            header("Host", "${team.id}.feed.test")
            header("Accept", "application/json, text/event-stream")
            if (key != null) header("Authorization", "Bearer $key")
            if (body != null) {
                header("Content-Type", contentType)
                setBody(body)
            }
        }

    private suspend fun ApplicationTestBuilder.tool(team: Team, token: String, name: String, args: JsonObject): String =
        send(
            team, token, "POST", "/mcp",
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
                put("params", buildJsonObject { put("name", name); put("arguments", args) })
            }.toString(),
        ).bodyAsText()

    private fun latest(app: App, team: Team, table: String, where: String = "1 = 1"): String =
        Tenant.allTeams {
            app.db.one("SELECT id FROM $table WHERE team_id = ? AND $where ORDER BY seq DESC LIMIT 1", team.id) { it.getString(1) }
        } ?: fail("team ${team.id} has no $table row")

    /** Build [team] alike through the API: [slug] names its channel. */
    private suspend fun ApplicationTestBuilder.seed(app: App, team: Team, slug: String, lookAlike: Boolean) {
        val m = team.marker
        Tenant.with(team.id) {
            val owner = app.people.createOwner("owner@$m.test", "Owner $m", "password $m $m")
            team.ownerId = owner.id
            team.ownerKey = app.devices.mint("Phone $m", owner.id).second
            val (agent, token) = app.agents.mint("scout")
            team.agentId = agent.id
            team.agentToken = token
        }
        val t = team.agentToken
        fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
            for ((k, v) in pairs) when (v) {
                is String -> put(k, v)
                is Int -> put(k, v)
                is Boolean -> put(k, v)
                is JsonArray -> put(k, v)
                is JsonObject -> put(k, v)
            }
        }
        tool(team, t, "create_project", args("name" to "Web $m", "slug" to slug))
        tool(team, t, "register_session", args("meta" to buildJsonObject { put("host", "box $m") }))
        // A profile (migration 077): what the agent says it does, which list_members hands out.
        tool(team, t, "set_profile", args("role" to "Role $m", "description" to "Description $m")).let { assertTrue("\"isError\":true" !in it, it) }
        tool(team, t, "post_update", args("project" to slug, "title" to "Title $m", "body" to "Update $m"))
        val update = latest(app, team, "updates")
        // A block on a card (migration 057): a checklist, the same block id in both teams.
        val blocks = Wire.json.parseToJsonElement("""[{"id":"ship","type":"checklist","title":"Ship $m","items":[{"id":"1","text":"Tests $m"}]}]""") as JsonArray
        tool(team, t, "post_update", args("project" to slug, "body" to "Blocks $m", "blocks" to blocks)).let { assertTrue("\"isError\":true" !in it, it) }
        tool(team, t, "post_message", args("project" to slug, "body" to "Post $m"))
        tool(team, t, "post_message", args("update_id" to update, "body" to "Reply $m"))
        val message = latest(app, team, "messages")
        tool(team, t, "request_input", args("kind" to "confirm", "question" to "Ship $m?", "project" to slug, "timeout_s" to 3600))
        val request = latest(app, team, "approvals")
        tool(team, t, "set_status", args("update_id" to update, "status" to "in_progress"))
        tool(team, t, "tag_card", args("update_id" to update, "tag" to "tag-$m", "create" to true))
        tool(team, t, "set_card_field", args("update_id" to update, "name" to "Field", "value" to "Value $m"))
        tool(team, t, "react", args("update_id" to update, "emoji" to "👍"))
        tool(team, t, "acknowledge", args("state" to "thinking", "message_id" to message))
        tool(team, t, "subscribe", args("project" to slug))
        val appUri = if (lookAlike && SAME_APP_URIS) "ui://board" else "ui://board-$m"
        tool(team, t, "register_app", args("uri" to appUri, "name" to "Board $m", "html" to "<p>$m</p>")).let { assertTrue("\"isError\":true" !in it, it) }
        val board = tool(team, t, "post_update", args(
            "project" to slug, "body" to "Board $m",
            "apps" to JsonArray(listOf(buildJsonObject { put("app", appUri); put("data", buildJsonObject { put("m", m) }); put("title", "Board $m") })),
        ))
        assertTrue("\"isError\":true" !in board, board)
        val reserved = tool(team, t, "create_upload", args("filename" to "notes-$m.txt", "mime" to "text/plain", "bytes" to 12))
        val uploadToken = Regex("/api/upload/([A-Za-z0-9_.~-]+)").find(reserved)?.groupValues?.get(1) ?: fail("no upload url: $reserved")
        team.tokens += uploadToken
        assertEquals(201, send(team, null, "PUT", "/api/upload/$uploadToken", "notes $m".padEnd(12, '.'), "text/plain").status.value)
        tool(team, t, "attach_media", args("update_id" to update, "media_ids" to JsonArray(listOf(JsonPrimitive(latest(app, team, "media"))))))
        val pairing = tool(team, t, "start_pairing", args("name" to "newbie"))
        Regex("\"poll_secret\":\"([A-Za-z0-9_-]+)\"").find(pairing.replace("\\\"", "\""))?.groupValues?.get(1)?.let { team.tokens += it }
        tool(team, t, "create_secret_upload", args("name" to "KEY_ONE", "note" to "Secret $m"))

        val k = team.ownerKey
        val invite = send(team, k, "POST", "/api/invites", """{"role":"member","email":"sam@$m.test"}""")
        assertEquals(201, invite.status.value, invite.bodyAsText())
        Wire.json.parseToJsonElement(invite.bodyAsText()).jsonObject.let { team.tokens += it["token"]!!.jsonPrimitive.content }
        send(team, k, "POST", "/api/groups", """{"name":"Apps $m","slug":"${if (lookAlike) "apps" else "apps-$m"}"}""")
        send(team, k, "PUT", "/api/settings/media-retention", """{"days":30}""")
        val secret = send(team, k, "POST", "/api/secrets", """{"name":"${if (lookAlike) "KEY_TWO" else "KEY_TWO_" + m.uppercase()}","value":"value $m","allAgents":true}""")
        assertEquals(201, secret.status.value, secret.bodyAsText())
        // A secret shared in a thread (migration 066): the same name on both teams' posts on Postgres.
        val shared = JsonArray(listOf(JsonPrimitive(if (lookAlike) "KEY_TWO" else "KEY_TWO_" + m.uppercase())))
        tool(team, t, "post_message", args("update_id" to update, "body" to "Key $m", "secrets" to shared)).let { assertTrue("\"isError\":true" !in it, it) }
        send(team, k, "POST", "/api/requests/$request/answer", """{"value":true}""")
        // Feedback (migration 067): a person's, sent, and an agent's, waiting on its owner.
        assertEquals(201, send(team, k, "POST", "/api/feedback", """{"kind":"feedback","area":"feed","title":"Feedback $m","body":"Words $m","card":{"kind":"update","id":"$update"}}""").status.value)
        tool(team, t, "send_feedback", args("kind" to "feature_request", "title" to "Idea $m", "body" to "Idea $m", "update_id" to update)).let { assertTrue("\"isError\":true" !in it, it) }
        tool(team, t, "request_input", args("kind" to "text", "question" to "Name $m?", "project" to slug, "timeout_s" to 3600))
        send(team, k, "POST", "/api/messages", """{"project":"$slug","body":"Owner post $m"}""")
        // The board (migration 070): a finished card taken off it, and the team's auto-remove age.
        val finished = latest(app, team, "messages")
        assertEquals(200, send(team, k, "POST", "/api/cards/message/$finished/status", """{"status":"done"}""").status.value)
        assertEquals(200, send(team, k, "POST", "/api/cards/message/$finished/board", """{"removed":true}""").status.value)
        // Not asserted, as media retention isn't: SQLite's server_settings keeps a key unique across teams.
        send(team, k, "PUT", "/api/settings/board", """{"autoRemoveDays":3}""")
        send(team, k, "PUT", "/api/cards/update/$update/follow", """{"follow":true}""")
        // Assignees (migration 074): the agent takes the update, and the owner assigns it a post too.
        tool(team, t, "assign_work", args("update_id" to update, "assignees" to JsonArray(listOf(JsonPrimitive("me"))))).let { assertTrue("\"isError\":true" !in it, it) }
        val agentName = Tenant.allTeams { app.db.one("SELECT name FROM agents WHERE team_id = ? ORDER BY seq LIMIT 1", team.id) { it.getString(1) } }!!
        assertEquals(200, send(team, k, "POST", "/api/cards/message/$finished/assignees", """{"assignees":["$agentName"],"mode":"set"}""").status.value)
        // Docs (migration 076): the same title on both teams when they look alike, saved twice, linked.
        val docTitle = if (lookAlike) "Plan" else "Plan $m"
        tool(team, t, "create_doc", args("project" to slug, "title" to docTitle, "body" to "Doc $m, see [[Notes]]")).let { assertTrue("\"isError\":true" !in it, it) }
        tool(team, t, "create_doc", args("project" to slug, "title" to "Notes", "body" to "Notes $m")).let { assertTrue("\"isError\":true" !in it, it) }
        val doc = latest(app, team, "docs")
        assertEquals(200, send(team, k, "PUT", "/api/docs/$doc", """{"baseVersion":1,"body":"Notes $m, saved again"}""").status.value)
        val client = send(team, null, "POST", "/oauth/register", """{"redirect_uris":["http://localhost:9/cb"],"client_name":"Client $m"}""")
        assertTrue(client.status.value in 200..299, client.bodyAsText())
    }

    /** Every row of [team], table by table, as text: the "before" and "after" of the sweep. */
    private fun rowsOf(app: App, team: Team): Map<String, List<String>> = Tenant.allTeams {
        TeamTables.ALL.associateWith { table ->
            app.db.query("SELECT * FROM \"$table\" WHERE team_id = ?", team.id) { rs ->
                (1..rs.metaData.columnCount).joinToString("|") { "${rs.metaData.getColumnName(it)}=${rs.getObject(it)}" }
            }.sorted()
        }
    }

    private val idShape = Regex("[0-9A-HJKMNP-TV-Z]{26}")

    /** Every id in [team]'s rows. */
    private fun idsOf(rows: Map<String, List<String>>): Set<String> =
        rows.values.flatten().flatMap { row -> idShape.findAll(row).map { it.value } }.toSet()

    /** The values to aim at B's things with: ids by what they are, then everything else. */
    private fun targets(app: App, team: Team): Map<String, List<String>> = Tenant.allTeams {
        fun ids(table: String, column: String = "id") = app.db.query("SELECT $column FROM $table WHERE team_id = ? ORDER BY seq", team.id) { it.getString(1) }
        mapOf(
            "update" to ids("updates"), "message" to ids("messages"), "request" to ids("approvals"),
            "project" to ids("projects"), "group" to ids("project_groups"), "tag" to ids("tags"), "device" to ids("owner_devices"),
            "agent" to ids("agents"), "invite" to ids("invites"), "person" to ids("memberships"), "pairing" to ids("agent_pairings"),
            "secret" to ids("secrets"), "media" to ids("media"), "view" to ids("app_views"), "session" to ids("sessions"),
            "feedback" to ids("feedback"), "doc" to ids("docs"),
            "token" to team.tokens,
        )
    }

    /** Which of B's ids fill a path's `{…}` slots. */
    private fun fills(pattern: String, b: Map<String, List<String>>): List<String> {
        val all = b.values.flatten().distinct()
        var paths = listOf(pattern)
        fun sub(slot: String, values: List<String>) {
            paths = paths.flatMap { p -> if ("{$slot}" in p) values.map { p.replace("{$slot}", it) } else listOf(p) }
        }
        val idKind = when {
            pattern.startsWith("/api/messages") -> "message"
            pattern.startsWith("/api/updates") -> "update"
            pattern.startsWith("/api/requests") -> "request"
            pattern.startsWith("/api/devices") -> "device"
            pattern.startsWith("/api/agents") -> "agent"
            pattern.startsWith("/api/invites") -> "invite"
            pattern.startsWith("/api/people") -> "person"
            pattern.startsWith("/api/pair") -> "pairing"
            pattern.startsWith("/api/secrets") -> "secret"
            pattern.startsWith("/api/tags") -> "tag"
            pattern.startsWith("/api/feedback") -> "feedback"
            pattern.startsWith("/api/docs") -> "doc"
            pattern.contains("media") -> "media"
            else -> null
        }
        if ("{kind}" in pattern) {
            paths = listOf("update", "message", "request").flatMap { kind ->
                b[kind].orEmpty().map { pattern.replace("{kind}", kind).replace("{id}", it) }
            }
        }
        sub("id", idKind?.let { b[it] } ?: all)
        sub("reference", b["project"].orEmpty() + b["group"].orEmpty())
        sub("view", b["view"].orEmpty())
        sub("variant", listOf("original", "thumb"))
        sub("connection", b["session"].orEmpty())
        sub("token", b["token"].orEmpty())
        sub("name", listOf("KEY_ONE", "KEY_TWO"))
        sub("version", listOf("1", "2"))
        return paths
    }

    /** A body naming B's things under every name a route or tool might read. */
    /** [a] with only one card named (update, message or request), as a card tool takes one. */
    private fun onlyCard(a: JsonObject, kind: String): JsonObject {
        val keys = mapOf(
            "update" to setOf("update_id", "updateId", "id"),
            "message" to setOf("message_id", "messageId", "answers"),
            "request" to setOf("request_id", "requestId"),
        )
        val drop = keys.filterKeys { it != kind }.values.flatten().toSet()
        return JsonObject(a.filterKeys { it !in drop })
    }

    private fun bodyAt(b: Map<String, List<String>>, i: Int): JsonObject {
        fun pick(kind: String) = b[kind].orEmpty().let { if (it.isEmpty()) "x" else it[i % it.size] }
        return buildJsonObject {
            put("id", pick("update")); put("update_id", pick("update")); put("updateId", pick("update"))
            put("message_id", pick("message")); put("messageId", pick("message")); put("answers", pick("message"))
            put("request_id", pick("request")); put("requestId", pick("request")); put("feedbackId", pick("feedback")); put("feedback_id", pick("feedback"))
            put("project", pick("project")); put("projectId", pick("project")); put("channel", pick("project")); put("group", pick("group"))
            put("view_id", pick("view")); put("session_id", pick("session")); put("pairing_id", pick("pairing")); put("poll_secret", pick("token"))
            put("tag", pick("tag")); put("tagId", pick("tag")); put("media_ids", JsonArray(listOf(JsonPrimitive(pick("media")))))
            put("mediaIds", JsonArray(listOf(JsonPrimitive(pick("media"))))); put("token", pick("token"))
            put("name", "KEY_ONE"); put("body", "hello"); put("emoji", "👍"); put("on", true); put("status", "done"); put("state", "done")
            put("removed", true); put("autoRemoveDays", 7)
            put("assignees", JsonArray(listOf(JsonPrimitive(pick("agent")), JsonPrimitive(pick("person")), JsonPrimitive("me"))))
            put("mode", "set"); put("assigned_to", pick("agent")); put("assigned", pick("person"))
            put("doc_id", pick("doc")); put("docId", pick("doc")); put("title", "Notes"); put("query", "notes")
            put("base_version", 1); put("baseVersion", 1); put("version", 1); put("new_title", "Renamed")
            put("edits", JsonArray(listOf(buildJsonObject { put("old_str", "Notes"); put("new_str", "Changed") })))
            put("value", true); put("kind", "confirm"); put("question", "q?"); put("level", "all"); put("follow", true); put("pinned", true)
            put("ids", JsonArray(listOf(JsonPrimitive(pick("update")))))
            put("cards", JsonArray(listOf(buildJsonObject { put("kind", "update"); put("id", pick("update")) })))
        }
    }

    private fun queryAt(b: Map<String, List<String>>, i: Int): String =
        bodyAt(b, i).entries.filter { it.value is JsonPrimitive }.joinToString("&") { (k, v) ->
            "$k=" + java.net.URLEncoder.encode((v as JsonPrimitive).content, Charsets.UTF_8)
        } + "&replyTo=" + b["message"]!!.first() + "&reply_to=" + b["message"]!!.first() + "&task_id=" + b["update"]!!.first() +
            "&update=" + b["update"]!!.first() + "&request=" + b["request"]!!.first()

    @Test
    fun `team A can't see, change or answer anything of team B's`() {
        val db = Database.memory()
        val lookAlike = db.dialect == Dialect.POSTGRES
        val app = App(config, db)
        Tenant.allTeams {
            for (t in listOf(alpha, bravo)) app.db.update("INSERT INTO teams (id, slug, name, created_at) VALUES (?, ?, ?, 1)", t.id, t.id, t.marker)
        }
        try {
            testApplication {
                application { feed(app) }
                seed(app, alpha, "web", lookAlike)
                seed(app, bravo, if (lookAlike) "web" else "web-b", lookAlike)

                val before = rowsOf(app, bravo)
                val bIds = idsOf(before)
                val b = targets(app, bravo)
                assertTrue(b.values.all { it.isNotEmpty() }, "team B has one of everything: $b")
                fun leaked(sent: String, answer: String): List<String> {
                    val named = bIds.filter { it in answer && it !in sent }
                    return named + listOfNotNull("bravo".takeIf { answer.contains("bravo", ignoreCase = true) })
                }

                // Every route the server has, aimed at B's things, with A's own key at A's address.
                val leaks = mutableListOf<String>()
                var calls = 0
                for (endpoint in registeredEndpoints.sorted()) {
                    val (method, pattern) = endpoint.split(" ", limit = 2)
                    // /mcp is swept tool by tool below; signing out would end A's own key.
                    // Streams never end: the bus test below covers them.
                    if (pattern == "/mcp" || pattern == "/api/devices/sign-out" || pattern.endsWith("/stream")) continue
                    for ((i, path) in fills(pattern, b).withIndex()) {
                        val read = method == "GET" || method == "HEAD"
                        val body = if (read) null else bodyAt(b, i).toString()
                        // Reads take B's ids as query parameters too, under every name a list reads.
                        val target = if (read) path + "?" + queryAt(b, i) else path
                        for (key in listOf(alpha.ownerKey, alpha.agentToken)) {
                            val answer = kotlinx.coroutines.withTimeoutOrNull(5_000) { send(alpha, key, method, target, body).bodyAsText() }
                                ?: fail("$method $path didn't answer")
                            calls++
                            leaked("$target $body", answer).takeIf { it.isNotEmpty() }?.let { leaks += "$method $path -> $it: ${answer.take(300)}" }
                        }
                    }
                }
                // Every MCP tool, aimed at B's things, with A's agent.
                val tools = Wire.json.parseToJsonElement(javaClass.getResource("/mcp/tools-list.json")!!.readText()) as JsonArray
                for (t in tools) {
                    val name = t.jsonObject["name"]!!.jsonPrimitive.content
                    // Every card at once (which a card tool refuses before anything else), each kind of
                    // card alone, and nothing at all (a list tool's "everything").
                    val tries = (0 until 3).flatMap { i -> listOf(bodyAt(b, i)) + listOf("update", "message", "request").map { onlyCard(bodyAt(b, i), it) } } +
                        listOf(JsonObject(emptyMap()))
                    for (args in tries) {
                        // Far more calls than an agent or a team may make in a minute: a refusal for that proves nothing.
                        app.mcpLimiter.reset(alpha.agentId)
                        app.teamMcpLimiter.reset(alpha.id)
                        val answer = kotlinx.coroutines.withTimeoutOrNull(5_000) { tool(alpha, alpha.agentToken, name, args) }
                            ?: fail("tool $name didn't answer")
                        assertTrue("rate_limited" !in answer, "tool $name was rate limited, so the sweep checked nothing")
                        calls++
                        leaked(args.toString(), answer).takeIf { it.isNotEmpty() }?.let { leaks += "tool $name -> $it: ${answer.take(300)}" }
                    }
                }
                assertTrue(calls > 300, "the sweep made $calls calls")
                assertEquals(emptyList(), leaks, "team A was told about team B's things")

                // B's keys, tokens and links open nothing at A's address.
                assertEquals(401, send(alpha, bravo.ownerKey, "GET", "/api/summary").status.value)
                assertEquals(401, send(alpha, bravo.agentToken, "POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").status.value)
                for (token in bravo.tokens) {
                    val answer = send(alpha, null, "POST", "/api/invite/peek", """{"token":"$token"}""")
                    assertTrue(answer.status.value >= 400, "B's token $token opened nothing at A's address: ${answer.bodyAsText()}")
                    assertTrue(send(alpha, null, "PUT", "/api/upload/$token", "x", "text/plain").status.value >= 400)
                }

                // B's rows are untouched, and neither team's rows point at the other's.
                assertEquals(before, rowsOf(app, bravo), "team B's rows changed")
                val aRows = rowsOf(app, alpha).values.flatten().joinToString("\n")
                val aIds = idsOf(rowsOf(app, alpha)) - bIds
                assertEquals(emptyList(), bIds.filter { it in aRows }, "team A's rows point at team B's")
                val bRows = before.values.flatten().joinToString("\n")
                assertEquals(emptyList(), aIds.filter { it in bRows }, "team B's rows point at team A's")
                assertTrue(!aRows.contains("bravo"), "no B text in A's rows")
            }
        } finally {
            app.close()
        }
    }

    @Test
    fun `an event reaches only its own team's listeners, with each team's own seq`() {
        val app = App(config, Database.memory())
        try {
            val heard = mutableListOf<String>()
            val stop = Tenant.with("alpha") { app.bus.subscribe { heard += "${it.team}:${it.seq}" } }
            val every = mutableListOf<String>()
            val stopEvery = app.bus.subscribeEveryTeam { every += "${it.team}:${Tenant.id}" }
            Tenant.with("bravo") { repeat(3) { app.bus.publish("x", JsonObject(emptyMap())) } }
            Tenant.with("alpha") { app.bus.publish("x", JsonObject(emptyMap())) }
            assertEquals(listOf("alpha:1"), heard)
            assertEquals(listOf("bravo:bravo", "bravo:bravo", "bravo:bravo", "alpha:alpha"), every)
            assertEquals(3L, Tenant.with("bravo") { app.bus.lastSeq })
            assertEquals(1L, Tenant.with("alpha") { app.bus.lastSeq })
            stop(); stopEvery()
        } finally {
            app.close()
        }
    }

    companion object {
        /** Whether two teams may register the same app address (migration 059 dropped the server-wide rule). */
        const val SAME_APP_URIS = true
    }
}
