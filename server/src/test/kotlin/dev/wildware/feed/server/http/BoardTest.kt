package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.feed
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The board (migration 070): cards with a work state, a column per state, in one channel, a project
 * or everything the viewer can read; finished cards removed by hand or by age, and put back.
 *
 * Runs on SQLite, and on Postgres when PG_TEST_URL names one ([Database.memory]).
 */
class BoardTest {
    private fun config(dir: String) = Config(
        dataDir = dir, adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    /** The clock every test turns by hand. */
    private var now = 1_800_000_000_000L

    private class Team(val app: App) {
        val ownerKey = app.devices.mint("Owner phone").second
        val sam: People.Person = join(People.Role.MEMBER, "Sam")
        val samKey = app.devices.mint("Sam phone", sam.id).second

        private fun join(role: People.Role, name: String): People.Person {
            val (_, token) = app.people.invite(app.people.find(People.FOUNDER)!!, role, "${name.lowercase()}@example.test")
            return app.people.accept(token, name, "${name.lowercase()}@example.test", "password for $name")
        }
    }

    private fun withTeam(block: suspend Team.(HttpClient) -> Unit) {
        val dir = Files.createTempDirectory("board").toFile()
        val app = App(config(dir.path), Database.memory()) { now }
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
        header("Accept", "application/json")
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

    /** A post by the owner in [channel], marked [status] (null: not work). Returns its id. */
    private suspend fun Team.post(client: HttpClient, channel: String, body: String, status: String?): String {
        client.json(ownerKey, "POST", "/api/messages", """{"project":"$channel","body":"$body"}""", status = 201)
        val id = app.db.one("SELECT id FROM messages WHERE team_id = :team ORDER BY seq DESC LIMIT 1") { it.getString(1) }!!
        if (status != null) client.json(ownerKey, "POST", "/api/cards/message/$id/status", """{"status":"$status"}""")
        return id
    }

    private suspend fun Team.channels(client: HttpClient) {
        for (slug in listOf("web", "api", "ops")) client.json(ownerKey, "POST", "/api/projects", """{"name":"${slug.uppercase()}","slug":"$slug"}""", status = 201)
        client.json(ownerKey, "POST", "/api/groups", """{"name":"Apps","slug":"apps"}""", status = 201)
        val group = app.db.one("SELECT id FROM project_groups WHERE team_id = :team AND slug = 'apps'") { it.getString(1) }!!
        for (slug in listOf("web", "api")) client.json(ownerKey, "PATCH", "/api/projects/$slug", """{"groupId":"$group"}""")
    }

    /** Card titles by column, for [query]. */
    private suspend fun Team.board(client: HttpClient, query: String = "", key: String = ownerKey): Map<String, List<String>> =
        client.json(key, "GET", "/api/board$query")["columns"]!!.jsonArray.associate { col ->
            col.jsonObject["status"]!!.jsonPrimitive.content to col.jsonObject["cards"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }
        }

    private suspend fun Team.card(client: HttpClient, title: String, query: String = "", key: String = ownerKey): JsonObject =
        client.json(key, "GET", "/api/board$query")["columns"]!!.jsonArray.flatMap { it.jsonObject["cards"]!!.jsonArray }
            .map { it.jsonObject }.single { it["title"]!!.jsonPrimitive.content == title }

    @Test
    fun `the columns are the work states in order and only cards marked as work are on them`() = withTeam { client ->
        channels(client)
        post(client, "web", "Fix the login", "todo")
        post(client, "web", "Ship the release", "in_progress")
        post(client, "web", "Waiting on design", "blocked")
        post(client, "web", "Wrote the docs", "done")
        post(client, "web", "Dropped idea", "cancelled")
        post(client, "web", "Just chatting", null)
        val board = board(client)
        assertEquals(listOf("todo", "in_progress", "blocked", "done"), board.keys.toList())
        assertEquals(listOf("Fix the login"), board["todo"])
        assertEquals(listOf("Ship the release"), board["in_progress"])
        assertEquals(listOf("Waiting on design"), board["blocked"])
        assertEquals(setOf("Wrote the docs", "Dropped idea"), board["done"]!!.toSet())
        assertFalse(board.values.flatten().contains("Just chatting"))
        val card = card(client, "Fix the login")
        assertEquals("web", card["channel"]!!.jsonObject["slug"]!!.jsonPrimitive.content)
        assertEquals("person", card["on"]!!.jsonArray.single().jsonObject["kind"]!!.jsonPrimitive.content)
        assertTrue(card["canMove"]!!.jsonPrimitive.boolean)
        assertEquals(7, client.json(ownerKey, "GET", "/api/board")["autoRemoveDays"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a channel shows its own work a project shows its channels and everything shows all`() = withTeam { client ->
        channels(client)
        post(client, "web", "Web work", "todo")
        post(client, "api", "Api work", "todo")
        post(client, "ops", "Ops work", "todo")
        assertEquals(listOf("Web work"), board(client, "?channel=web")["todo"])
        assertEquals(setOf("Web work", "Api work"), board(client, "?group=apps")["todo"]!!.toSet())
        assertEquals(setOf("Web work", "Api work", "Ops work"), board(client)["todo"]!!.toSet())
        assertEquals(404, client.call(ownerKey, "GET", "/api/board?channel=nowhere").status.value)
        assertEquals(400, client.call(ownerKey, "GET", "/api/board?column=sideways").status.value)
    }

    @Test
    fun `a hidden channel never reaches the board and a read only viewer can't move its cards`() = withTeam { client ->
        channels(client)
        post(client, "web", "Open work", "todo")
        val secret = post(client, "ops", "Secret work", "todo")
        client.json(ownerKey, "PUT", "/api/projects/ops/access", """{"private":true}""")
        assertEquals(listOf("Open work"), board(client, key = samKey)["todo"])
        assertEquals(404, client.call(samKey, "GET", "/api/board?channel=ops").status.value)
        assertFalse("Secret work" in client.call(samKey, "GET", "/api/board?group=apps").bodyAsText())
        assertEquals(404, client.call(samKey, "POST", "/api/cards/message/$secret/board", """{"removed":true}""").status.value)
        assertEquals(setOf("Open work", "Secret work"), board(client)["todo"]!!.toSet())

        // Read only on web: Sam sees its card, marked as not his to move, and moving it is refused.
        client.json(ownerKey, "PUT", "/api/projects/web/access/member/${sam.id}", """{"level":"read"}""")
        val card = card(client, "Open work", key = samKey)
        assertFalse(card["canMove"]!!.jsonPrimitive.boolean)
        val id = card["card"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(403, client.call(samKey, "POST", "/api/cards/message/$id/status", """{"status":"done"}""").status.value)
        assertEquals("todo", card(client, "Open work")["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a finished card can be removed from the board and put back without being deleted`() = withTeam { client ->
        channels(client)
        val done = post(client, "web", "Finished", "done")
        val open = post(client, "web", "Still going", "in_progress")
        assertEquals(400, client.call(ownerKey, "POST", "/api/cards/message/$open/board", """{"removed":true}""").status.value)
        client.json(ownerKey, "POST", "/api/cards/message/$done/board", """{"removed":true}""")
        assertEquals(emptyList(), board(client)["done"])
        // Not deleted: still in the feed, and its state is unchanged.
        assertTrue("Finished" in client.call(ownerKey, "GET", "/api/snapshot").bodyAsText())
        // Gone for everyone in the team, not just the one who removed it.
        assertEquals(emptyList(), board(client, key = samKey)["done"])
        val shown = card(client, "Finished", "?removed=1")
        assertTrue(shown["removed"]!!.jsonPrimitive.boolean)
        assertEquals("someone", shown["removedBy"]!!.jsonPrimitive.content)

        client.json(ownerKey, "POST", "/api/cards/message/$done/board", """{"removed":false}""")
        assertEquals(listOf("Finished"), board(client)["done"])
        assertFalse(card(client, "Finished")["removed"]!!.jsonPrimitive.boolean)

        // Removed, then given a new state: back on the board in its new column.
        client.json(ownerKey, "POST", "/api/cards/message/$done/board", """{"removed":true}""")
        client.json(ownerKey, "POST", "/api/cards/message/$done/status", """{"status":"todo"}""")
        assertEquals(listOf("Finished"), board(client)["todo"])
    }

    @Test
    fun `finished cards leave the board once older than the team's auto remove age`() = withTeam { client ->
        channels(client)
        val day = 24L * 60 * 60 * 1000
        val old = post(client, "web", "Old news", "done")
        now += 2 * day
        post(client, "web", "Fresh", "done")
        post(client, "web", "Long todo", "todo")

        // Seven days by default: the first is six days old at six days on, then gone at eight.
        now += 4 * day
        assertEquals(setOf("Old news", "Fresh"), board(client)["done"]!!.toSet())
        now += 2 * day
        assertEquals(listOf("Fresh"), board(client)["done"])
        assertEquals(listOf("Long todo"), board(client)["todo"], "only finished cards leave by age")
        assertEquals("age", card(client, "Old news", "?removed=1")["removedBy"]!!.jsonPrimitive.content)

        // Thirty days keeps both; never keeps both; one day keeps neither.
        client.json(ownerKey, "PUT", "/api/settings/board", """{"autoRemoveDays":30}""")
        assertEquals(setOf("Old news", "Fresh"), board(client)["done"]!!.toSet())
        client.json(ownerKey, "PUT", "/api/settings/board", """{"autoRemoveDays":null}""")
        assertEquals(setOf("Old news", "Fresh"), board(client)["done"]!!.toSet())
        assertEquals(null, client.json(ownerKey, "GET", "/api/settings/board")["autoRemoveDays"]!!.jsonPrimitive.content.toIntOrNull())
        client.json(ownerKey, "PUT", "/api/settings/board", """{"autoRemoveDays":1}""")
        assertEquals(emptyList(), board(client)["done"])

        // Put back, an old card stays for the team's age again, counted from then.
        client.json(ownerKey, "POST", "/api/cards/message/$old/board", """{"removed":false}""")
        assertEquals(listOf("Old news"), board(client)["done"])
        now += 2 * day
        assertEquals(emptyList(), board(client)["done"])
    }

    @Test
    fun `the auto remove age is one of the choices and only an admin sets it`() = withTeam { client ->
        assertEquals(400, client.call(ownerKey, "PUT", "/api/settings/board", """{"autoRemoveDays":5}""").status.value)
        assertEquals(400, client.call(ownerKey, "PUT", "/api/settings/board", """{}""").status.value)
        assertEquals(403, client.call(samKey, "PUT", "/api/settings/board", """{"autoRemoveDays":30}""").status.value)
        client.json(ownerKey, "PUT", "/api/settings/board", """{"autoRemoveDays":3}""")
        assertEquals(3, client.json(samKey, "GET", "/api/settings/board")["autoRemoveDays"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `each column pages on its own newest move first`() = withTeam { client ->
        channels(client)
        for (i in 1..3) {
            post(client, "web", "Task $i", "todo")
            now += 1000
        }
        post(client, "web", "Doing", "in_progress")
        val first = client.json(ownerKey, "GET", "/api/board?limit=2")["columns"]!!.jsonArray.map { it.jsonObject }
        val todo = first.single { it["status"]!!.jsonPrimitive.content == "todo" }
        assertEquals(listOf("Task 3", "Task 2"), todo["cards"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content })
        assertTrue(todo["more"]!!.jsonPrimitive.boolean)
        val after = todo["after"]!!.jsonPrimitive.content
        val next = client.json(ownerKey, "GET", "/api/board?limit=2&column=todo&after=$after")["columns"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("Task 1"), next["cards"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content })
        assertFalse(next["more"]!!.jsonPrimitive.boolean)
    }
}
