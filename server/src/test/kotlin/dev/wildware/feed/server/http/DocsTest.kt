package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.DocLinks
import dev.wildware.feed.server.domain.Docs
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.events.AppEvent
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Docs (migration 076, spec/17): create, read, save and delete; who may (Read reads, Post edits,
 * no access is not_found); a save on a stale version refused with both texts; edit_doc's
 * find-and-replace rules; [[links]], backlinks and title lookup; versions and restore.
 *
 * Runs on SQLite, and on Postgres when PG_TEST_URL names one ([Database.memory]).
 */
class DocsTest {
    private fun config(dir: String) = Config(
        dataDir = dir, adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    /** The founder, a member (Sam), and two agents: rover, and scout. */
    private class Team(val app: App) {
        val owner: People.Person = app.people.find(People.FOUNDER)!!
        val ownerKey = app.devices.mint("Owner phone").second
        val sam: People.Person
        val samKey: String
        val rover: Agents.Agent
        val roverToken: String
        val scout: Agents.Agent
        val scoutToken: String

        init {
            val (_, token) = app.people.invite(owner, People.Role.MEMBER, "sam@example.test")
            sam = app.people.accept(token, "Sam", "sam@example.test", "password for Sam")
            samKey = app.devices.mint("Sam phone", sam.id).second
            app.agents.mint("rover").let { (a, t) -> rover = a; roverToken = t }
            app.agents.mint("scout").let { (a, t) -> scout = a; scoutToken = t }
        }
    }

    private fun withTeam(block: suspend Team.(HttpClient) -> Unit) {
        val dir = Files.createTempDirectory("docs").toFile()
        val app = App(config(dir.path), Database.memory())
        try {
            testApplication {
                application { feed(app) }
                Team(app).block(client)
            }
        } finally {
            app.close()
            dir.deleteRecursively()
        }
    }

    private suspend fun HttpClient.call(key: String, method: String, path: String, body: String? = null): HttpResponse = request(path) {
        this.method = HttpMethod.parse(method)
        header("Authorization", "Bearer $key")
        header("Accept", "application/json, text/event-stream")
        if (body != null) {
            header("Content-Type", "application/json")
            setBody(body)
        }
    }

    private suspend fun HttpClient.json(key: String, method: String, path: String, body: String? = null, status: Int = 200): JsonObject {
        val r = call(key, method, path, body)
        val text = r.bodyAsText()
        assertEquals(status, r.status.value, "$method $path: $text")
        return Wire.json.parseToJsonElement(text).jsonObject
    }

    private suspend fun HttpClient.tool(token: String, name: String, args: JsonObject): JsonObject {
        val r = call(
            token, "POST", "/mcp",
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
                put("params", buildJsonObject { put("name", name); put("arguments", args) })
            }.toString(),
        )
        return Wire.json.parseToJsonElement(r.bodyAsText()).jsonObject["result"]!!.jsonObject
    }

    private fun JsonObject.error(): String? = this["structuredContent"]?.jsonObject?.get("error")?.jsonPrimitive?.content
    private fun JsonObject.message(): String = this["structuredContent"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: ""
    private fun JsonObject.data(): JsonObject = this["structuredContent"]!!.jsonObject

    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            is String -> put(k, v)
            is Int -> put(k, v)
            is Long -> put(k, v)
            is JsonElement -> put(k, v)
        }
    }

    private fun edits(vararg pairs: Pair<String, String>) =
        JsonArray(pairs.map { (o, n) -> buildJsonObject { put("old_str", o); put("new_str", n) } })

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.doc() = this["doc"]!!.jsonObject

    /** Channel `ops`, made by the owner. */
    private suspend fun Team.ops(client: HttpClient) {
        client.json(ownerKey, "POST", "/api/projects", """{"name":"Ops","slug":"ops"}""", status = 201)
    }

    @Test
    fun `a person creates, reads, lists and saves a doc, and every save is a version with who saved it`() = withTeam { client ->
        ops(client)
        val events = mutableListOf<AppEvent>()
        val stop = app.bus.subscribe { events += it }
        val made = client.json(ownerKey, "POST", "/api/projects/ops/docs", """{"title":"Runbook","body":"# Runbook\nStep one."}""", status = 201)
        val id = made.doc().text("id")
        assertEquals(1L, made.doc()["version"]!!.jsonPrimitive.long)
        assertEquals(owner.name, made.doc()["updatedBy"]!!.jsonObject.text("name"), "names the saver")
        assertEquals("person", made.doc()["createdBy"]!!.jsonObject.text("kind"))
        assertTrue(made["canEdit"]!!.jsonPrimitive.content.toBoolean())

        val list = client.json(ownerKey, "GET", "/api/projects/ops/docs")["docs"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Runbook"), list.map { it.text("title") })
        assertFalse("body" in list.single(), "lists leave the text out")

        // Sam saves it, based on version 1.
        val saved = client.json(samKey, "PUT", "/api/docs/$id", """{"baseVersion":1,"body":"# Runbook\nStep one.\nStep two."}""")
        assertEquals(2L, saved.doc()["version"]!!.jsonPrimitive.long)
        assertEquals("Sam", saved.doc()["updatedBy"]!!.jsonObject.text("name"))
        // Saving the same text again keeps the version.
        val same = client.json(samKey, "PUT", "/api/docs/$id", """{"baseVersion":2,"body":"# Runbook\nStep one.\nStep two."}""")
        assertEquals(2L, same.doc()["version"]!!.jsonPrimitive.long)

        val versions = client.json(ownerKey, "GET", "/api/docs/$id/versions")["versions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(2L, 1L), versions.map { it["version"]!!.jsonPrimitive.long })
        assertEquals("Sam", versions.first()["savedBy"]!!.jsonObject.text("name"))
        val first = client.json(ownerKey, "GET", "/api/docs/$id/versions/1")["version"]!!.jsonObject
        assertEquals("# Runbook\nStep one.", first.text("body"))
        stop()
        val changes = events.filter { it.type == "doc.updated" }.map { it.payload.text("change") to it.payload.text("projectId") }
        assertEquals(listOf("created", "saved"), changes.map { it.first })
        assertTrue(changes.all { it.second == app.feed.projects.resolve("ops").id }, "each event names its channel, so the stream can filter it")
    }

    @Test
    fun `a save based on an old version is refused with the doc as it is now, never overwriting`() = withTeam { client ->
        ops(client)
        val id = client.json(ownerKey, "POST", "/api/projects/ops/docs", """{"title":"Plan","body":"one"}""", status = 201).doc().text("id")
        client.json(samKey, "PUT", "/api/docs/$id", """{"baseVersion":1,"body":"Sam's text"}""")
        val refused = client.json(ownerKey, "PUT", "/api/docs/$id", """{"baseVersion":1,"body":"the owner's text"}""", status = 409)
        assertEquals("conflict", refused.text("error"))
        assertEquals(1L, refused["baseVersion"]!!.jsonPrimitive.long)
        assertEquals("Sam's text", refused.doc().text("body"), "the conflict hands back the text now, to merge with")
        assertEquals(2L, refused.doc()["version"]!!.jsonPrimitive.long)
        assertEquals("Sam's text", client.json(ownerKey, "GET", "/api/docs/$id").doc().text("body"))
        // A restore on a stale version is refused the same way.
        assertEquals(409, client.call(ownerKey, "POST", "/api/docs/$id/restore", """{"version":1,"baseVersion":1}""").status.value)
        // An agent on a stale version gets a conflict too, and nothing is saved.
        val stale = client.tool(roverToken, "edit_doc", args("doc_id" to id, "base_version" to 1, "body" to "rover's"))
        assertEquals("conflict", stale.error())
        assertContains(stale.message(), "version 2")
        assertContains(stale.message(), "Sam")
        assertEquals("Sam's text", client.json(ownerKey, "GET", "/api/docs/$id").doc().text("body"))
    }

    @Test
    fun `read access reads docs, post edits them, and no access finds nothing`() = withTeam { client ->
        ops(client)
        val id = client.json(ownerKey, "POST", "/api/projects/ops/docs", """{"title":"Secret plan","body":"zebra"}""", status = 201).doc().text("id")
        client.json(ownerKey, "PUT", "/api/projects/ops/access/agent/${rover.id}", """{"level":"read"}""")
        client.json(ownerKey, "PUT", "/api/projects/ops/access/member/${sam.id}", """{"level":"read"}""")

        // Read: rover reads, can't edit or create.
        val read = client.tool(roverToken, "read_doc", args("doc_id" to id))
        assertEquals(null, read.error(), read.toString())
        assertEquals("zebra", read.data().doc().text("body"))
        assertEquals("false", read.data()["can_edit"]!!.jsonPrimitive.content)
        assertEquals("forbidden", client.tool(roverToken, "edit_doc", args("doc_id" to id, "base_version" to 1, "body" to "x")).error())
        assertEquals("forbidden", client.tool(roverToken, "create_doc", args("channel" to "ops", "title" to "Mine")).error())
        // Sam with Read: reads, can't save, delete or create.
        assertFalse(client.json(samKey, "GET", "/api/docs/$id")["canEdit"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(403, client.call(samKey, "PUT", "/api/docs/$id", """{"baseVersion":1,"body":"x"}""").status.value)
        assertEquals(403, client.call(samKey, "DELETE", "/api/docs/$id").status.value)
        assertEquals(403, client.call(samKey, "POST", "/api/projects/ops/docs", """{"title":"Mine"}""").status.value)

        // Private, and scout not listed: nothing is there for it, by id, title or search.
        client.json(ownerKey, "PUT", "/api/projects/ops/access", """{"private":true}""")
        assertEquals("not_found", client.tool(scoutToken, "read_doc", args("doc_id" to id)).error())
        assertEquals("not_found", client.tool(scoutToken, "read_doc", args("channel" to "ops", "title" to "Secret plan")).error())
        assertEquals("not_found", client.tool(scoutToken, "list_docs", args("channel" to "ops")).error())
        assertEquals("not_found", client.tool(scoutToken, "edit_doc", args("doc_id" to id, "base_version" to 1, "body" to "x")).error())
        val search = client.tool(scoutToken, "search_docs", args("query" to "zebra"))
        assertEquals(0, search.data()["docs"]!!.jsonArray.size, search.toString())
        // Sam was listed with Read, so still reads it; a person with no row would not.
        assertEquals(200, client.call(samKey, "GET", "/api/docs/$id").status.value)
        client.json(ownerKey, "PUT", "/api/projects/ops/access/member/${sam.id}", """{"level":"none"}""")
        assertEquals(404, client.call(samKey, "GET", "/api/docs/$id").status.value)
        assertEquals(404, client.call(samKey, "GET", "/api/docs/$id/versions").status.value)
        assertEquals(404, client.call(samKey, "GET", "/api/projects/ops/docs").status.value)
    }

    @Test
    fun `edit_doc replaces text found exactly once, all or nothing, or the whole body`() = withTeam { client ->
        ops(client)
        val made = client.tool(roverToken, "create_doc", args("channel" to "ops", "title" to "Notes", "body" to "alpha beta\ngamma beta\ndelta"))
        assertEquals(null, made.error(), made.toString())
        val id = made.data().doc().text("id")
        fun body() = app.db.one("SELECT body FROM docs WHERE team_id = :team AND id = ?", id) { it.getString(1) }!!

        // Found twice: refused, nothing changed.
        val twice = client.tool(roverToken, "edit_doc", args("doc_id" to id, "base_version" to 1, "edits" to edits("beta" to "B")))
        assertEquals("invalid_argument", twice.error())
        assertContains(twice.message(), "more than once")
        // Not found, after a good first edit: neither is made.
        val missing = client.tool(roverToken, "edit_doc", args("doc_id" to id, "base_version" to 1, "edits" to edits("alpha" to "A", "omega" to "O")))
        assertEquals("invalid_argument", missing.error())
        assertContains(missing.message(), "edit 2")
        assertEquals("alpha beta\ngamma beta\ndelta", body())
        // Edits apply in order, each to the text the one before left.
        val ok = client.tool(roverToken, "edit_doc", args("doc_id" to id, "base_version" to 1, "edits" to edits("alpha beta" to "alpha B", "gamma beta" to "gamma C", "alpha B" to "A B")))
        assertEquals(null, ok.error(), ok.toString())
        assertEquals(2L, ok.data().doc()["version"]!!.jsonPrimitive.long)
        assertEquals("A B\ngamma C\ndelta", body())
        // An empty old_str is refused (by the schema, before the tool runs); edits and body together too.
        assertEquals("true", client.tool(roverToken, "edit_doc", args("doc_id" to id, "base_version" to 2, "edits" to edits("" to "x")))["isError"]?.jsonPrimitive?.content)
        assertEquals("invalid_argument", client.tool(roverToken, "edit_doc", args("doc_id" to id, "base_version" to 2, "edits" to edits("A" to "a"), "body" to "x")).error())
        // The whole body, by title, with a rename.
        val whole = client.tool(roverToken, "edit_doc", args("channel" to "ops", "title" to "notes", "base_version" to 2, "body" to "all new", "new_title" to "Field notes"))
        assertEquals(null, whole.error(), whole.toString())
        assertEquals("Field notes", whole.data().doc().text("title"))
        assertEquals("all new", body())
        // Agent saves name the agent.
        val versions = client.json(ownerKey, "GET", "/api/docs/$id/versions")["versions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("rover", "rover", "rover"), versions.map { it["savedBy"]!!.jsonObject.text("name") })
        assertEquals("agent", versions.first()["savedBy"]!!.jsonObject.text("kind"))
    }

    @Test
    fun `links find docs by title in any case, and each doc lists the docs that link to it`() = withTeam { client ->
        ops(client)
        client.json(ownerKey, "POST", "/api/projects", """{"name":"Web","slug":"web"}""", status = 201)
        val plan = client.json(ownerKey, "POST", "/api/projects/ops/docs", """{"title":"Release Plan","body":"the plan"}""", status = 201).doc().text("id")
        client.json(ownerKey, "POST", "/api/projects/ops/docs", """{"title":"Checklist","body":"see [[release plan|the plan]] and [[Missing doc]]; `[[Not a link]]`"}""", status = 201)
        client.json(ownerKey, "POST", "/api/projects/ops/docs", """{"title":"Notes","body":"[[RELEASE PLAN]]"}""", status = 201)
        // A doc of the same title in another channel is not linked from ops.
        client.json(ownerKey, "POST", "/api/projects/web/docs", """{"title":"Elsewhere","body":"[[Release Plan]]"}""", status = 201)

        val page = client.json(ownerKey, "GET", "/api/docs/$plan")
        assertEquals(listOf("Checklist", "Notes"), page["backlinks"]!!.jsonArray.map { it.jsonObject.text("title") })

        // Titles are found in any case; a missing one is a 404 the app turns into "create it".
        val found = client.json(ownerKey, "GET", "/api/projects/ops/docs/by-title?title=release%20PLAN")
        assertEquals(plan, found.doc().text("id"))
        assertEquals(404, client.call(ownerKey, "GET", "/api/projects/ops/docs/by-title?title=Missing%20doc").status.value)
        // Two docs can't share a title in a channel, whatever the case; another channel is fine.
        assertEquals(409, client.call(ownerKey, "POST", "/api/projects/ops/docs", """{"title":"release plan"}""").status.value)
        client.json(ownerKey, "POST", "/api/projects/web/docs", """{"title":"Release plan"}""", status = 201)
        // A title can't hold what links are made of.
        assertEquals(400, client.call(ownerKey, "POST", "/api/projects/ops/docs", """{"title":"a|b"}""").status.value)

        // read_doc says which links exist.
        val read = client.tool(roverToken, "read_doc", args("channel" to "ops", "title" to "checklist")).data()
        val links = read["links"]!!.jsonArray.map { it.jsonObject.text("title") to it.jsonObject.text("exists") }
        assertEquals(listOf("Release Plan" to "true", "Missing doc" to "false"), links)

        // Removing the link takes it off "Linked from"; deleting a doc frees its title.
        val notes = client.json(ownerKey, "GET", "/api/projects/ops/docs/by-title?title=Notes").doc().text("id")
        client.json(ownerKey, "PUT", "/api/docs/$notes", """{"baseVersion":1,"body":"no links"}""")
        assertEquals(listOf("Checklist"), client.json(ownerKey, "GET", "/api/docs/$plan")["backlinks"]!!.jsonArray.map { it.jsonObject.text("title") })
        client.json(ownerKey, "DELETE", "/api/docs/$notes")
        assertEquals(404, client.call(ownerKey, "GET", "/api/docs/$notes").status.value)
        client.json(ownerKey, "POST", "/api/projects/ops/docs", """{"title":"Notes"}""", status = 201)
    }

    @Test
    fun `an old version is restored as a new save, and only the newest versions are kept`() = withTeam { client ->
        ops(client)
        val id = client.json(ownerKey, "POST", "/api/projects/ops/docs", """{"title":"Spec","body":"v1"}""", status = 201).doc().text("id")
        client.json(ownerKey, "PUT", "/api/docs/$id", """{"baseVersion":1,"body":"v2","title":"Spec two"}""")
        val restored = client.json(samKey, "POST", "/api/docs/$id/restore", """{"version":1,"baseVersion":2}""")
        assertEquals(3L, restored.doc()["version"]!!.jsonPrimitive.long)
        assertEquals("v1", restored.doc().text("body"))
        assertEquals("Spec", restored.doc().text("title"))
        val top = client.json(ownerKey, "GET", "/api/docs/$id/versions")["versions"]!!.jsonArray.first().jsonObject
        assertEquals(1L, top["restoredFrom"]!!.jsonPrimitive.long)
        assertEquals("Sam", top["savedBy"]!!.jsonObject.text("name"))

        // Past the cap, the oldest go.
        var v = 3L
        repeat(Docs.KEEP_VERSIONS) { i -> v = client.json(ownerKey, "PUT", "/api/docs/$id", """{"baseVersion":$v,"body":"save $i"}""").doc()["version"]!!.jsonPrimitive.long }
        val versions = client.json(ownerKey, "GET", "/api/docs/$id/versions")["versions"]!!.jsonArray
        assertEquals(Docs.KEEP_VERSIONS, versions.size)
        assertEquals(404, client.call(ownerKey, "GET", "/api/docs/$id/versions/1").status.value)
    }

    @Test
    fun `a doc is at most 1 MB, and agents list and search docs`() = withTeam { client ->
        ops(client)
        val big = "x".repeat(Docs.MAX_BYTES + 1)
        assertEquals(400, client.call(ownerKey, "POST", "/api/projects/ops/docs", buildJsonObject { put("title", "Big"); put("body", big) }.toString()).status.value)
        client.json(ownerKey, "POST", "/api/projects/ops/docs", buildJsonObject { put("title", "Fits"); put("body", "x".repeat(Docs.MAX_BYTES)) }.toString(), status = 201)
        client.json(ownerKey, "POST", "/api/projects/ops/docs", """{"title":"Deploy steps","body":"Run the migration, then restart."}""", status = 201)
        client.json(ownerKey, "POST", "/api/projects/ops/docs", """{"title":"Migration notes","body":"none yet"}""", status = 201)

        val list = client.tool(roverToken, "list_docs", args("channel" to "ops")).data()["docs"]!!.jsonArray.map { it.jsonObject.text("title") }
        assertEquals(setOf("Fits", "Deploy steps", "Migration notes"), list.toSet())
        val found = client.tool(roverToken, "search_docs", args("query" to "migration")).data()["docs"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Migration notes", "Deploy steps"), found.map { it.text("title") }, "title matches first")
        assertContains(found[1].text("snippet"), "Run the migration")
        assertEquals("ops", found[0].text("channel"))
    }

    @Test
    fun `links are read outside code only`() {
        val links = DocLinks.parse("[[A]] [[B|shown]]\n```\n[[C]]\n```\n`[[D]]` [[ E ]] [[]] [[F|]]")
        assertEquals(listOf("A" to null, "B" to "shown", "E" to null, "F" to null), links.map { it.target to it.shown })
    }
}
