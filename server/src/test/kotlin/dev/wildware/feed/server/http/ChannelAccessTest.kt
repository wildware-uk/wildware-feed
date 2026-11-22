package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Viewer
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.ChannelAccess
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per channel and per project permissions (migration 068, spec/14): the defaults are today's
 * access, a private channel is hidden from whoever isn't listed, Read lets you read but not post,
 * a project's setting reaches its channels unless a channel says otherwise, the team owner always
 * manages, every change is logged, and an agent with no access finds nothing.
 *
 * Runs on SQLite, and on Postgres when PG_TEST_URL names one ([Database.memory]).
 */
class ChannelAccessTest {
    private fun config(dir: String) = Config(
        dataDir = dir, adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    /** A team: the founder (owner), an admin, two members, two agents, and a key for each person. */
    private class Team(val app: App) {
        val owner: People.Person = app.people.find(People.FOUNDER)!!
        val ownerKey = app.devices.mint("Owner phone").second
        val admin = join(People.Role.ADMIN, "Ada")
        val adminKey = app.devices.mint("Ada phone", admin.id).second
        val sam = join(People.Role.MEMBER, "Sam")
        val samKey = app.devices.mint("Sam phone", sam.id).second
        val rin = join(People.Role.MEMBER, "Rin")
        val rinKey = app.devices.mint("Rin phone", rin.id).second
        val scout: Agents.Agent
        val scoutToken: String
        val other: Agents.Agent
        val otherToken: String

        init {
            app.agents.mint("scout").let { (a, t) -> scout = a; scoutToken = t }
            app.agents.mint("other").let { (a, t) -> other = a; otherToken = t }
        }

        private fun join(role: People.Role, name: String): People.Person {
            val (_, token) = app.people.invite(app.people.find(People.FOUNDER)!!, role, "${name.lowercase()}@example.test")
            return app.people.accept(token, name, "${name.lowercase()}@example.test", "password for $name")
        }
    }

    private fun withTeam(block: suspend Team.(HttpClient) -> Unit) {
        val dir = Files.createTempDirectory("channel-access").toFile()
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

    private fun slugs(snapshot: JsonObject): List<String> = snapshot["projects"]!!.jsonArray.map { it.jsonObject["slug"]!!.jsonPrimitive.content }

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { for ((k, v) in pairs) put(k, v) }

    /** Two channels, `web` and `secret`, each with a card and a post by scout. */
    private suspend fun Team.channels(client: HttpClient) {
        for (slug in listOf("web", "secret")) {
            client.tool(scoutToken, "create_project", args("name" to slug.replaceFirstChar { it.uppercase() }, "slug" to slug))
            client.tool(scoutToken, "post_update", args("project" to slug, "title" to "Card in $slug", "body" to "words in $slug"))
            client.json(ownerKey, "POST", "/api/messages", """{"project":"$slug","body":"owner says $slug"}""", status = 201)
        }
    }

    @Test
    fun `with nothing set everyone keeps today's access`() = withTeam { client ->
        channels(client)
        // Every person reads every channel; members post; only admins and owners change a channel.
        for (key in listOf(ownerKey, adminKey, samKey)) {
            assertEquals(listOf("secret", "web"), slugs(client.json(key, "GET", "/api/snapshot")).sorted())
            client.json(key, "POST", "/api/messages", """{"project":"secret","body":"hello"}""", status = 201)
        }
        assertEquals(403, client.call(samKey, "PATCH", "/api/projects/web", """{"name":"Webby"}""").status.value)
        client.json(adminKey, "PATCH", "/api/projects/web", """{"name":"Webby"}""")
        // Agents post everywhere, as before.
        assertNull(client.tool(otherToken, "post_message", args("project" to "secret", "body" to "agent hello")).error())

        val panel = client.json(samKey, "GET", "/api/projects/web/access")
        val levels = panel["entries"]!!.jsonArray.associate { e -> e.jsonObject["name"]!!.jsonPrimitive.content to e.jsonObject["level"]!!.jsonPrimitive.content }
        assertEquals("manage", levels["Ada"])
        assertEquals("post", levels["Sam"])
        assertEquals("post", levels["scout"])
        assertEquals(false, panel["private"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("post", panel["yourLevel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a private channel is hidden from a member without Read until they are given it`() = withTeam { client ->
        channels(client)
        val secret = app.feed.projects.resolve("secret").id
        client.json(ownerKey, "PUT", "/api/projects/secret/access", """{"private":true}""")

        // Sam sees nothing of it, anywhere he looks.
        assertEquals(listOf("web"), slugs(client.json(samKey, "GET", "/api/snapshot")))
        val summary = client.call(samKey, "GET", "/api/summary").bodyAsText()
        assertFalse("secret" in summary.lowercase(), summary)
        val messages = client.call(samKey, "GET", "/api/messages").bodyAsText()
        assertFalse("owner says secret" in messages, messages)
        assertEquals(404, client.call(samKey, "GET", "/api/projects/secret/tags").status.value)
        assertEquals(404, client.call(samKey, "GET", "/api/projects/$secret/access").status.value)
        assertEquals(404, client.call(samKey, "POST", "/api/messages", """{"project":"secret","body":"let me in"}""").status.value)
        val update = app.db.one("SELECT id FROM updates WHERE team_id = :team AND project_id = ?", secret) { it.getString(1) }!!
        assertEquals(404, client.call(samKey, "GET", "/api/cards/update/$update/blocks").status.value)
        assertEquals(404, client.call(samKey, "POST", "/api/cards/update/$update/reactions", """{"emoji":"👍"}""").status.value)
        assertEquals(404, client.call(samKey, "PUT", "/api/cards/update/$update/follow", """{"following":true}""").status.value)
        // Its live events don't reach him; his phone and bell are not told about it.
        val access = ChannelAccess(app.db, app.clock)
        val event = buildJsonObject { put("projectId", secret); put("updateId", update) }
        assertFalse(Viewer.with(Viewer.member(sam.id)) { access.eventVisible(event) })
        assertTrue(Viewer.with(Viewer.member(owner.id)) { access.eventVisible(event) })
        assertFalse(access.canRead(Viewer.MEMBER + sam.id, secret))
        val apns = dev.wildware.feed.server.push.Apns(app, Config.Apns("KEY123", "TEAM456", "/unused.p8", "dev.example.feed")) { dev.wildware.feed.server.push.ApnsResponse(200, null) }
        assertNull(apns.alertFor(AppEvent("update.created", 1, "", buildJsonObject { put("updateId", update) }), sam.id), "no phone alert for a channel Sam can't read")
        assertNotNull(apns.alertFor(AppEvent("update.created", 1, "", buildJsonObject { put("updateId", update) }), owner.id), "the owner's phone still hears it")
        // The owner and the admin who did nothing still see it? The owner does; an admin who isn't listed doesn't.
        assertTrue("secret" in slugs(client.json(ownerKey, "GET", "/api/snapshot")))
        assertFalse("secret" in slugs(client.json(adminKey, "GET", "/api/snapshot")))

        // Read: Sam sees it now, and still can't post in it.
        client.json(ownerKey, "PUT", "/api/projects/secret/access/member/${sam.id}", """{"level":"read"}""")
        assertTrue("secret" in slugs(client.json(samKey, "GET", "/api/snapshot")))
        assertTrue("owner says secret" in client.call(samKey, "GET", "/api/messages").bodyAsText())
        val refused = client.call(samKey, "POST", "/api/messages", """{"project":"secret","body":"may I?"}""")
        assertEquals(403, refused.status.value, refused.bodyAsText())
        assertEquals(403, client.call(samKey, "POST", "/api/cards/update/$update/reactions", """{"emoji":"👍"}""").status.value)
        // Rin, not listed, still sees nothing.
        assertEquals(listOf("web"), slugs(client.json(rinKey, "GET", "/api/snapshot")))
    }

    @Test
    fun `an agent with no access gets not_found for messages, posts and files, and forbidden when it may only read`() = withTeam { client ->
        channels(client)
        val secret = app.feed.projects.resolve("secret").id
        client.json(ownerKey, "PUT", "/api/projects/secret/access/agent/${other.id}", """{"level":"none"}""")
        assertEquals("not_found", client.tool(otherToken, "get_messages", args("channel" to "secret")).error())
        assertEquals("not_found", client.tool(otherToken, "post_message", args("channel" to "secret", "body" to "hi")).error())
        assertEquals("not_found", client.tool(otherToken, "post_update", args("project" to "secret", "body" to "hi")).error())
        assertEquals("not_found", client.tool(otherToken, "subscribe", args("channel" to "secret")).error())
        val listed = client.tool(otherToken, "list_projects", JsonObject(emptyMap())).toString()
        assertFalse("secret" in listed.lowercase(), listed)
        // An @mention in the channel no longer reaches it either.
        client.json(ownerKey, "POST", "/api/messages", """{"project":"secret","body":"@other are you there"}""", status = 201)
        assertTrue(app.work.unread(other.id, 50).none { it.projectId == secret })

        // A file in the channel answers as any other miss.
        val media = upload(client, ownerKey)
        client.json(ownerKey, "POST", "/api/messages", """{"project":"secret","body":"a picture","mediaIds":["$media"]}""", status = 201)
        assertEquals(404, client.call(otherToken, "GET", "/api/agent/media/$media").status.value)
        client.tool(scoutToken, "subscribe", args("channel" to "secret"))
        assertEquals(200, client.call(scoutToken, "GET", "/api/agent/media/$media").status.value, "an agent that hears the channel fetches it")

        // Read only: it reads, and may not post.
        client.json(ownerKey, "PUT", "/api/projects/secret/access/agent/${other.id}", """{"level":"read"}""")
        assertNull(client.tool(otherToken, "subscribe", args("channel" to "secret")).error())
        assertNull(client.tool(otherToken, "get_messages", args("channel" to "secret")).error())
        assertEquals("forbidden", client.tool(otherToken, "post_message", args("channel" to "secret", "body" to "hi")).error())
        assertEquals("forbidden", client.tool(otherToken, "post_update", args("project" to "secret", "body" to "hi")).error())
        // Agents never change permissions: their tokens don't open owner routes.
        assertEquals(401, client.call(otherToken, "PUT", "/api/projects/secret/access/agent/${other.id}", """{"level":"post"}""").status.value)
    }

    @Test
    fun `a project's setting reaches its channels and a channel's own setting wins`() = withTeam { client ->
        channels(client)
        val group = client.json(ownerKey, "POST", "/api/groups", """{"name":"Apps","slug":"apps"}""", status = 201)["group"]!!.jsonObject["id"]!!.jsonPrimitive.content
        client.json(ownerKey, "PATCH", "/api/projects/web", """{"groupId":"$group"}""")
        client.json(ownerKey, "PATCH", "/api/projects/secret", """{"groupId":"$group"}""")
        client.json(ownerKey, "PUT", "/api/groups/apps/access", """{"private":true}""")
        assertEquals(emptyList(), slugs(client.json(samKey, "GET", "/api/snapshot")), "a private project hides every channel in it")
        assertEquals(404, client.call(samKey, "GET", "/api/groups/apps/access").status.value)

        client.json(ownerKey, "PUT", "/api/groups/apps/access/member/${sam.id}", """{"level":"read"}""")
        assertEquals(listOf("secret", "web"), slugs(client.json(samKey, "GET", "/api/snapshot")).sorted(), "read on the project reads every channel")
        assertEquals(403, client.call(samKey, "POST", "/api/messages", """{"project":"web","body":"hi"}""").status.value)

        // The channel's own row wins over the project's.
        client.json(ownerKey, "PUT", "/api/projects/web/access/member/${sam.id}", """{"level":"post"}""")
        client.json(ownerKey, "PUT", "/api/projects/secret/access/member/${sam.id}", """{"level":"none"}""")
        assertEquals(listOf("web"), slugs(client.json(samKey, "GET", "/api/snapshot")))
        client.json(samKey, "POST", "/api/messages", """{"project":"web","body":"hi"}""", status = 201)
        val panel = client.json(ownerKey, "GET", "/api/projects/web/access")
        val samEntry = panel["entries"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "Sam" }
        assertEquals("channel", samEntry["via"]!!.jsonPrimitive.content)
        assertEquals("read", samEntry["inherited"]!!.jsonPrimitive.content)
        assertEquals(true, panel["groupPrivate"]!!.jsonPrimitive.content.toBoolean())

        // Clearing the channel's row inherits the project's again; moving the channel out keeps it hidden.
        client.json(ownerKey, "PUT", "/api/projects/secret/access/member/${sam.id}", """{"level":null}""")
        assertTrue("secret" in slugs(client.json(samKey, "GET", "/api/snapshot")))
        client.json(ownerKey, "PUT", "/api/projects/secret/access/member/${sam.id}", """{"level":"none"}""")
        client.json(ownerKey, "PATCH", "/api/projects/secret", """{"groupId":null}""")
        assertFalse("secret" in slugs(client.json(rinKey, "GET", "/api/snapshot")), "leaving a private project keeps a channel private")
    }

    @Test
    fun `the team owner always manages and can't be locked out`() = withTeam { client ->
        channels(client)
        client.json(adminKey, "PUT", "/api/projects/secret/access", """{"private":true}""")
        // The admin who made it private is listed as managing it; the owner manages it without a row.
        assertTrue("secret" in slugs(client.json(adminKey, "GET", "/api/snapshot")))
        assertTrue("secret" in slugs(client.json(ownerKey, "GET", "/api/snapshot")))
        val refused = client.call(adminKey, "PUT", "/api/projects/secret/access/member/${owner.id}", """{"level":"none"}""")
        assertEquals(409, refused.status.value, refused.bodyAsText())
        // A member who only posts can't change who has access.
        client.json(ownerKey, "PUT", "/api/projects/secret/access/member/${sam.id}", """{"level":"post"}""")
        assertEquals(403, client.call(samKey, "PUT", "/api/projects/secret/access/member/${rin.id}", """{"level":"read"}""").status.value)
        assertEquals(403, client.call(samKey, "PUT", "/api/projects/secret/access", """{"private":false}""").status.value)
        // Given Manage, a member can, and can rename it.
        client.json(ownerKey, "PUT", "/api/projects/secret/access/member/${sam.id}", """{"level":"manage"}""")
        client.json(samKey, "PUT", "/api/projects/secret/access/member/${rin.id}", """{"level":"read"}""")
        client.json(samKey, "PATCH", "/api/projects/secret", """{"name":"Secrets"}""")
        assertEquals("manage", ChannelAccess(app.db, app.clock).levelFor(Viewer.MEMBER + owner.id, app.feed.projects.resolve("secret").id).wire)
    }

    @Test
    fun `every change is logged and the log is for owners and admins`() = withTeam { client ->
        channels(client)
        client.json(ownerKey, "PUT", "/api/projects/secret/access", """{"private":true}""")
        client.json(ownerKey, "PUT", "/api/projects/secret/access/member/${sam.id}", """{"level":"read"}""")
        client.json(ownerKey, "PUT", "/api/projects/secret/access/agent/${scout.id}", """{"level":"none"}""")
        val log = client.json(adminKey, "GET", "/api/access/audit")["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, log.size, log.toString())
        val first = log.last()
        assertEquals("private", first["change"]!!.jsonPrimitive.content)
        assertEquals("false", first["from"]!!.jsonPrimitive.content)
        assertEquals("true", first["to"]!!.jsonPrimitive.content)
        assertEquals("member:${owner.id}", first["actor"]!!.jsonPrimitive.content)
        assertEquals("Secret", first["targetName"]!!.jsonPrimitive.content)
        val agentRow = log.first()
        assertEquals("agent:${scout.id}", agentRow["principal"]!!.jsonPrimitive.content)
        assertEquals("scout", agentRow["principalName"]!!.jsonPrimitive.content)
        assertEquals("none", agentRow["to"]!!.jsonPrimitive.content)
        assertEquals(403, client.call(samKey, "GET", "/api/access/audit").status.value)
    }

    @Test
    fun `the member and agent pages say what each can reach`() = withTeam { client ->
        channels(client)
        client.json(ownerKey, "PUT", "/api/projects/secret/access", """{"private":true}""")
        client.json(ownerKey, "PUT", "/api/projects/secret/access/member/${sam.id}", """{"level":"read"}""")
        val mine = client.json(samKey, "GET", "/api/people/${sam.id}/access")["channels"]!!.jsonArray.map { it.jsonObject }
        assertEquals(mapOf("secret" to "read", "web" to "post"), mine.associate { it["slug"]!!.jsonPrimitive.content to it["level"]!!.jsonPrimitive.content })
        assertEquals(403, client.call(samKey, "GET", "/api/people/${rin.id}/access").status.value, "a member sees only their own")
        // Rin's page, seen by the owner: no access to secret.
        val rins = client.json(ownerKey, "GET", "/api/people/${rin.id}/access")["channels"]!!.jsonArray.map { it.jsonObject }
        assertEquals("none", rins.first { it["slug"]!!.jsonPrimitive.content == "secret" }["level"]!!.jsonPrimitive.content)
        // An agent's page, seen by Rin, leaves out what Rin can't see.
        val scouts = client.json(rinKey, "GET", "/api/agents/${scout.id}/access")["channels"]!!.jsonArray.map { it.jsonObject["slug"]!!.jsonPrimitive.content }
        assertEquals(listOf("web"), scouts)
    }

    @Test
    fun `an agent's file download follows the message it is on (issue 304)`() = withTeam { client ->
        channels(client)
        client.tool(scoutToken, "subscribe", args("channel" to "web"))
        // A file on a line @addressed only to another agent is that agent's.
        val forOther = upload(client, ownerKey)
        client.json(ownerKey, "POST", "/api/messages", """{"project":"web","body":"@other this one is yours","mediaIds":["$forOther"]}""", status = 201)
        assertEquals(404, client.call(scoutToken, "GET", "/api/agent/media/$forOther").status.value)
        assertEquals(200, client.call(otherToken, "GET", "/api/agent/media/$forOther").status.value)

        // A file posted where scout doesn't listen: refused, until a person @mentions scout in its thread.
        client.json(ownerKey, "POST", "/api/projects", """{"name":"Quiet","slug":"quiet"}""", status = 201)
        val later = upload(client, ownerKey)
        val post = client.json(ownerKey, "POST", "/api/messages", """{"project":"quiet","body":"see this","mediaIds":["$later"]}""", status = 201)
        val postId = post["message"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(404, client.call(scoutToken, "GET", "/api/agent/media/$later").status.value)
        client.json(ownerKey, "POST", "/api/messages", """{"replyTo":"$postId","body":"@scout can you look"}""", status = 201)
        assertEquals(200, client.call(scoutToken, "GET", "/api/agent/media/$later").status.value)
    }

    @Test
    fun `slugs stay unique when the channel holding one is hidden`() = withTeam { client ->
        channels(client)
        client.json(ownerKey, "PUT", "/api/projects/secret/access/agent/${other.id}", """{"level":"none"}""")
        val again = client.tool(otherToken, "create_project", args("name" to "Secret", "slug" to "secret"))
        assertEquals("conflict", again.error(), again.toString())
        assertFalse("words in secret" in again.toString())
    }

    private suspend fun upload(client: HttpClient, key: String): String {
        val image = BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB).apply { setRGB(1, 1, (Math.random() * 0xFFFFFF).toInt()) }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val r = client.request("/api/media?filename=p.png") {
            method = HttpMethod.Post
            header("Authorization", "Bearer $key")
            header("Content-Type", "image/png")
            setBody(bytes)
        }
        assertEquals(201, r.status.value, r.bodyAsText())
        return Wire.json.parseToJsonElement(r.bodyAsText()).jsonObject["media"]!!.jsonObject["id"]!!.jsonPrimitive.content
    }
}
