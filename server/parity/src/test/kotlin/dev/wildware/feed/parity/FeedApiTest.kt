package dev.wildware.feed.parity

import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The owner's feed API. Spec: server/spec/02-owner-api-feed.md (its parity notes are numbered; so are these). */
class FeedApiTest : ParityTest() {
    private suspend fun HttpResponse.expectError(status: Int, error: String, message: String) {
        val text = bodyAsText()
        assertEquals(status, this.status.value, text)
        assertEquals("""{"error":"$error","message":${obj("m" to message)["m"]}}""", text)
    }

    private suspend fun newProject(name: String = uniq("Feed")) =
        owner.post("/api/projects", obj("name" to name)).obj()["project"].o

    private suspend fun post(vararg fields: Pair<String, Any?>) = owner.post("/api/messages", obj(*fields))

    @Test
    fun `1 the API wants a session`() = parity {
        call(HttpMethod.Post, "/api/messages", obj("body" to "x")).expectError401()
        // A bad key beside a good cookie is still refused (only where there are cookies).
        if (owner.cookie != null) owner.send(HttpMethod.Post, "/api/messages", obj("body" to "x")) { bearer("B".repeat(43)) }.expectError401()
        call(HttpMethod.Post, "/api/messages", obj("body" to "x")) { bearer("B".repeat(43)) }.expectError401()
    }

    private suspend fun HttpResponse.expectError401() {
        assertEquals(401, status.value)
        assertEquals("""{"error":"unauthenticated"}""", bodyAsText())
    }

    @Test
    fun `2 creating a project is idempotent on its slug`() = parity {
        val name = "Café Déjà Vu! ${Parity.suffix()}"
        val first = owner.post("/api/projects", obj("name" to name))
        assertEquals(201, first.status.value)
        val body = first.obj()
        val project = body["project"].o
        assertTrue(project["slug"].s.startsWith("cafe-deja-vu-"), project["slug"].s)
        assertEquals(true, body["created"].bool)
        assertEquals(
            listOf("seq", "id", "slug", "name", "description", "status", "pinned", "createdAt", "updatedAt", "theme", "board"),
            project.keys.toList(),
        )

        val again = owner.post("/api/projects", obj("name" to name))
        assertEquals(200, again.status.value)
        val second = again.obj()
        assertEquals(false, second["created"].bool)
        assertEquals(project["id"], second["project"]["id"])
        assertEquals(project["updatedAt"], second["project"]["updatedAt"])
    }

    @Test
    fun `3 and 4 project bodies are checked strictly`() = parity {
        owner.post("/api/projects", obj("name" to "x", "slug" to null)).expectError(400, "invalid_argument", "slug must be a string")
        owner.send(HttpMethod.Post, "/api/projects") { setBodyText("[1]") }.expectError(400, "invalid_argument", "body must be a JSON object")
        owner.send(HttpMethod.Post, "/api/projects") { setBodyText("nope") }.expectError(400, "invalid_argument", "body must be JSON")
    }

    @Test
    fun `5 a theme merges, normalises colours and refuses a logo flag without a logo`() = parity {
        val slug = newProject()["slug"].s
        val set = owner.patch("/api/projects/$slug", obj("theme" to obj("accent" to "#ABC")))
        assertEquals(200, set.status.value)
        assertEquals(obj("accent" to "#aabbcc"), set.obj()["project"]["theme"])

        val cleared = owner.patch("/api/projects/$slug", obj("theme" to obj("accent" to null))).obj()
        assertTrue(cleared["project"]["theme"].isNull)

        owner.patch("/api/projects/$slug", obj("theme" to obj("logoReplacesName" to true)))
            .expectError(400, "invalid_argument", "logoReplacesName needs a logo to show instead of the name")
    }

    @Test
    fun `6 and 7 a patch must change something, and a slug cannot be taken twice`() = parity {
        val a = newProject()["slug"].s
        val b = newProject()["slug"].s
        owner.patch("/api/projects/$a", obj()).expectError(400, "invalid_argument", "update requires at least one field to change")
        owner.patch("/api/projects/nope-${Parity.suffix()}", obj()).let {
            assertEquals(404, it.status.value)
            assertTrue(it.obj()["message"].s.startsWith("no such project: nope-"))
        }
        owner.patch("/api/projects/$b", obj("slug" to a)).expectError(409, "conflict", "slug already in use: $a")
    }

    @Test
    fun `6b a project can be renamed, pinned and archived`() = parity {
        val slug = newProject()["slug"].s
        val patched = owner.patch("/api/projects/$slug", obj("name" to "Renamed", "pinned" to true)).obj()["project"].o
        assertEquals("Renamed", patched["name"].s)
        assertEquals(true, patched["pinned"].bool)
        val archived = owner.patch("/api/projects/$slug", obj("status" to "archived")).obj()["project"].o
        assertEquals("archived", archived["status"].s)
    }

    @Test
    fun `8 a work state sits in one board column only`() = parity {
        val slug = newProject()["slug"].s
        val board = obj("columns" to arr(obj("title" to "A", "states" to arr("todo")), obj("title" to "B", "states" to arr("todo"))))
        owner.patch("/api/projects/$slug", obj("board" to board)).expectError(400, "invalid_argument", "todo is already in another column")
    }

    @Test
    fun `9 the owner posts as human, trimmed, with no anchors`() = parity {
        val slug = newProject()["slug"].s
        val response = post("project" to slug, "body" to "  hi @nobody  ")
        assertEquals(201, response.status.value)
        val message = response.obj()["message"].o
        assertEquals("hi @nobody", message["body"].s)
        assertEquals("human", message["author"].s)
        for (key in listOf("updateId", "taskId", "replyTo", "answers", "requestId", "deletedAt", "editedAt", "status", "statusAt")) {
            assertTrue(message[key].isNull, "$key should be null")
        }
        assertEquals(
            listOf("seq", "id", "projectId", "updateId", "taskId", "author", "body", "createdAt", "replyTo", "deletedAt", "answers", "requestId", "editedAt", "pinned", "status", "statusAt"),
            message.keys.toList(),
        )
    }

    @Test
    fun `10 a present key may not be null`() = parity {
        val slug = newProject()["slug"].s
        post("project" to slug, "body" to "x", "update" to null).expectError(400, "invalid_argument", "update must be a string")
        post("project" to slug, "body" to "   ").expectError(400, "invalid_argument", "body is required")
        post("project" to slug, "body" to "x".repeat(10_001)).expectError(400, "invalid_argument", "body must be at most 10000 characters")
    }

    @Test
    fun `11 to 14 replies flatten to the post and anchors cannot be mixed`() = parity {
        val project = Fixtures.project()
        val other = Fixtures.project()
        val update = Fixtures.update(project)["id"].s
        val p = post("project" to project.slug, "body" to "post").obj()["message"]["id"].s
        val r1 = post("replyTo" to p, "body" to "r1").obj()["message"].o
        assertEquals(p, r1["replyTo"].s)
        val r2 = post("replyTo" to r1["id"].s, "body" to "r2").obj()["message"].o
        assertEquals(p, r2["replyTo"].s, "a reply to a reply hangs off the post")
        assertTrue(r2["answers"].isNull)

        post("replyTo" to p, "update" to update, "body" to "x")
            .expectError(400, "invalid_argument", "a reply answers a message, or hangs off an update, not both")
        post("update" to update, "project" to other.slug, "body" to "x")
            .expectError(400, "invalid_argument", "that update, task or message belongs to a different project")

        // 15: the thread, its envelope and its ordering.
        val thread = owner.get("/api/messages?replyTo=$p")
        assertEquals(200, thread.status.value)
        val body = thread.obj()
        assertEquals(listOf("seq", "at", "messages", "acks", "media", "embeds", "deliveries", "reactions"), body.keys.toList())
        assertEquals(listOf(p, r1["id"].s, r2["id"].s), body["messages"].arr.map { it["id"].s })
        assertEquals(obj(), body["media"])
        assertEquals(3, owner.get("/api/messages?replyTo=$p&limit=1").obj()["messages"].arr.size, "spec surprise: limit ignored for a thread")
        owner.get("/api/messages?replyTo=$p&limit=0").expectError(400, "invalid_argument", "limit must be a positive integer")
        owner.get("/api/messages?replyTo=$p&limit=-1").expectError(400, "invalid_argument", "limit must be a positive integer")

        // 16: deleting a post deletes its replies, once.
        val deleted = owner.delete("/api/messages/$p")
        assertEquals(200, deleted.status.value)
        assertNotNull(deleted.obj()["message"]["deletedAt"].str)
        assertEquals(0, owner.get("/api/messages?replyTo=$p").obj()["messages"].arr.size)
        assertEquals(200, owner.delete("/api/messages/$p").status.value)
    }

    @Test
    fun `13 a request discussion is not a card thread`() = parity {
        val project = Fixtures.project()
        val update = Fixtures.update(project)["id"].s
        val request = alpha.ok("request_input", obj("kind" to "confirm", "question" to "ok?", "project" to project.slug, "timeout_s" to 5))["request_id"].s
        post("request" to request, "update" to update, "body" to "x")
            .expectError(400, "invalid_argument", "a message belongs to a request`s discussion, or to a card, not both")
        val inDiscussion = post("request" to request, "body" to "about this").obj()["message"].o
        assertEquals(request, inDiscussion["requestId"].s)
        assertEquals(project.id, inDiscussion["projectId"].s)
    }

    @Test
    fun `messages can be listed by update and by project`() = parity {
        val project = Fixtures.project()
        val update = Fixtures.update(project)["id"].s
        val onCard = post("update" to update, "body" to "on the card").obj()["message"]["id"].s
        val loose = post("project" to project.slug, "body" to "loose").obj()["message"]["id"].s
        assertEquals(listOf(onCard), owner.get("/api/messages?update=$update").obj()["messages"].arr.map { it["id"].s })
        assertEquals(setOf(onCard, loose), owner.get("/api/messages?project=${project.slug}").obj()["messages"].arr.map { it["id"].s }.toSet())
        assertEquals(404, owner.get("/api/messages?project=nope-${Parity.suffix()}").status.value)
    }

    @Test
    fun `17 and 18 reactions toggle, accept shortcodes and refuse non-emoji`() = parity {
        val slug = newProject()["slug"].s
        val p2 = post("project" to slug, "body" to "react to me").obj()["message"]["id"].s
        val on = owner.post("/api/cards/message/$p2/reactions", obj("emoji" to ":tick:"))
        assertEquals(200, on.status.value)
        val reaction = on.obj()
        assertEquals(true, reaction["on"].bool)
        assertEquals("✅", reaction["reactions"][0]["emoji"].s)
        assertEquals("human", reaction["reactions"][0]["actor"].s)
        assertEquals("""{"reactions":[],"on":false}""", owner.post("/api/cards/message/$p2/reactions", obj("emoji" to ":tick:")).bodyAsText())

        owner.post("/api/messages/$p2/reactions", obj("emoji" to "✅", "on" to true))
        val twice = owner.post("/api/messages/$p2/reactions", obj("emoji" to "✅", "on" to true)).obj()
        assertEquals(true, twice["on"].bool)
        assertEquals(1, twice["reactions"].arr.size)

        owner.post("/api/cards/message/$p2/reactions", obj("emoji" to "ok")).expectError(
            400, "invalid_argument",
            "\"ok\" is not an emoji. Send the character itself, or one of: eyes, tick, check, white_check_mark, done, +1, thumbsup, -1",
        )
        owner.post("/api/cards/message/$p2/reactions", obj("emoji" to "👍 👍")).expectError(400, "invalid_argument", "an emoji has no spaces in it")
        owner.post("/api/cards/message/$p2/reactions", obj()).expectError(400, "invalid_argument", "emoji must be a string")
    }

    @Test
    fun `19 to 21 card kinds, work states and pins`() = parity {
        val project = Fixtures.project()
        val update = Fixtures.update(project)["id"].s
        val p2 = post("project" to project.slug, "body" to "work").obj()["message"]["id"].s
        owner.post("/api/cards/task/X/pin", obj("pinned" to true)).expectError(400, "invalid_argument", "no such kind of card: task")
        val request = alpha.ok("request_input", obj("kind" to "confirm", "question" to "ok?", "project" to project.slug, "timeout_s" to 5))["request_id"].s
        owner.post("/api/cards/request/$request/status", obj("status" to "todo")).expectError(400, "invalid_argument", "a request cannot be marked as work")

        assertEquals(
            """{"card":{"kind":"message","id":"$p2"},"status":"todo"}""",
            owner.post("/api/cards/message/$p2/status", obj("status" to "todo")).bodyAsText(),
        )
        owner.post("/api/cards/message/$p2/status", obj()).expectError(400, "invalid_argument", "status must be one of the five work states, or null")
        assertEquals(
            """{"card":{"kind":"message","id":"$p2"},"status":null}""",
            owner.post("/api/cards/message/$p2/status", obj("status" to null)).bodyAsText(),
        )

        owner.send(HttpMethod.Post, "/api/cards/update/$update/pin") { setBodyText("nope") }
            .expectError(400, "invalid_argument", "pinned must be true or false")
        assertEquals(
            """{"card":{"kind":"update","id":"$update"},"pinned":true}""",
            owner.post("/api/cards/update/$update/pin", obj("pinned" to true)).bodyAsText(),
        )
        owner.post("/api/cards/update/01ZZZZZZZZZZZZZZZZZZZZZZZZ/pin", obj("pinned" to true))
            .expectError(404, "not_found", "no such update: 01ZZZZZZZZZZZZZZZZZZZZZZZZ")
    }

    @Test
    fun `22 an update patch only pins, and delete is soft and repeatable`() = parity {
        val project = Fixtures.project()
        val update = Fixtures.update(project)["id"].s
        owner.patch("/api/updates/$update", obj("pinned" to true, "x" to 1)).expectError(400, "invalid_argument", "an update patch changes pinned and nothing else")
        val pinned = owner.patch("/api/updates/$update", obj("pinned" to true))
        assertEquals(200, pinned.status.value)
        assertEquals(true, pinned.obj()["update"]["pinned"].bool)
        assertEquals(200, owner.delete("/api/updates/$update").status.value)
        assertEquals(200, owner.delete("/api/updates/$update").status.value)
        owner.patch("/api/updates/$update", obj("pinned" to false)).expectError(404, "not_found", "no such update: $update")
    }

    @Test
    fun `23 project tags`() = parity {
        val slug = newProject()["slug"].s
        val made = owner.post("/api/projects/$slug/tags", obj("name" to "High Priority", "colour" to "rose"))
        assertEquals(200, made.status.value)
        val tag = made.obj()["tag"].o
        assertEquals("high-priority", tag["slug"].s)
        val again = owner.post("/api/projects/$slug/tags", obj("name" to "High Priority", "colour" to "blue")).obj()["tag"].o
        assertEquals(tag["id"], again["id"])
        assertEquals("rose", again["colour"].s)
        owner.post("/api/projects/$slug/tags", obj("name" to "!!!")).expectError(400, "invalid_argument", "\"!!!\" has no letters or digits in it")

        owner.post("/api/projects/$slug/tags", obj("name" to "bug"))
        owner.patch("/api/tags/${tag["id"].s}", obj("name" to "bug")).expectError(400, "invalid_argument", "this project already has a tag called \"bug\"")

        val list = owner.get("/api/projects/$slug/tags")
        assertEquals(200, list.status.value)
        assertEquals(setOf("high-priority", "bug"), list.obj()["tags"].arr.map { it["slug"].s }.toSet())

        val gone = owner.delete("/api/tags/${tag["id"].s}").obj()
        assertEquals(tag["id"], gone["tag"]["id"])
        assertTrue(gone["tag"].o.containsKey("projectId"))
        owner.delete("/api/tags/${tag["id"].s}").expectError(404, "not_found", "no such tag: ${tag["id"].s}")
    }

    @Test
    fun `24 tagging a card`() = parity {
        val slug = newProject()["slug"].s
        val p2 = post("project" to slug, "body" to "tag me").obj()["message"]["id"].s
        owner.post("/api/cards/message/$p2/tags", obj("tag" to "wontfix")).expectError(404, "not_found", "this project has no tag called \"wontfix\"")
        val on = owner.post("/api/cards/message/$p2/tags", obj("tag" to "wontfix", "create" to true)).obj()
        assertEquals(true, on["on"].bool)
        assertEquals("wontfix", on["tag"]["slug"].s)

        val loose = post("body" to "nowhere").obj()["message"]["id"].s
        owner.post("/api/cards/message/$loose/tags", obj("tag" to "x", "create" to true))
            .expectError(400, "invalid_argument", "this card does not belong to a project, so it cannot be tagged")

        for (i in 2..8) owner.post("/api/cards/message/$p2/tags", obj("tag" to "t$i", "create" to true))
        owner.post("/api/cards/message/$p2/tags", obj("tag" to "t9", "create" to true))
            .expectError(400, "invalid_argument", "a card may carry at most 8 tags")
    }

    @Test
    fun `25 card fields`() = parity {
        val project = Fixtures.project()
        val update = Fixtures.update(project)["id"].s
        val first = owner.post("/api/cards/update/$update/fields", obj("name" to " Branch ", "value" to "main")).obj()
        val field = first["fields"][0].o
        assertEquals("Branch", field["name"].s)
        assertEquals("branch", field["slug"].s)
        assertEquals("main", field["value"].s)
        assertEquals("human", field["author"].s)
        assertEquals(true, first["set"].bool)

        val renamed = owner.post("/api/cards/update/$update/fields", obj("name" to "branch", "value" to "dev")).obj()["fields"][0].o
        assertEquals(field["id"], renamed["id"])
        assertEquals(field["createdAt"], renamed["createdAt"])
        assertEquals("branch", renamed["name"].s)
        assertEquals("dev", renamed["value"].s)

        owner.post("/api/cards/update/$update/fields", obj("name" to "nope", "value" to null))
            .expectError(404, "not_found", "this card has no field called \"nope\"")
        owner.post("/api/cards/update/$update/fields", obj("name" to "x"))
            .expectError(400, "invalid_argument", "value must be a string, or null to remove the field")
        val removed = owner.post("/api/cards/update/$update/fields", obj("name" to "Branch", "value" to null)).obj()
        assertEquals(false, removed["set"].bool)
        assertEquals(0, removed["fields"].arr.size)
    }

    @Test
    fun `26 agents can be renamed by the owner`() = parity {
        val agent = Parity.mintAgent(uniq("rename-me"))
        val id = agent.ok("post_update", obj("project" to Fixtures.project(agent).slug, "body" to "hi"))["update"]["agent_id"].s
        assertEquals("""{"agent":{"id":"$id","name":"Scout"}}""", owner.patch("/api/agents/$id", obj("name" to " Scout ")).bodyAsText())
        owner.patch("/api/agents/$id", obj("name" to "x", "tokenHash" to "y")).expectError(400, "invalid_argument", "an agent patch changes name and nothing else")
    }

    @Test
    fun `27 and 28 wrong method and cross-site text bodies`() = parity {
        val get = owner.get("/api/projects")
        assertEquals(405, get.status.value)
        assertEquals("GET method not allowed", get.bodyAsText())
        assertEquals("POST", get.headers[HttpHeaders.Allow])

        // The browser's CSRF rule belongs to the website, which the Kotlin server does not have.
        if (Parity.web) {
            val crossSite = call(HttpMethod.Post, "/api/messages") {
                header(HttpHeaders.Origin, "https://evil.example")
                header(HttpHeaders.Cookie, owner.cookie ?: "")
                setBodyText("""{"body":"x"}""", ContentType.Text.Plain)
            }
            assertEquals(403, crossSite.status.value)
            assertContains(crossSite.bodyAsText(), "Cross-site POST form submissions are forbidden")
        }
    }
}
