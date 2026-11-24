package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.events.AppEvent
import dev.wildware.feed.server.media.FfmpegTools
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Member profiles (migration 077, spec/13 "Profiles"): each person and each agent has a role line
 * and a description. A person sets their own; an agent its own with set_profile, and owners and
 * admins any agent's; nobody another person's. Everyone in the team reads them, and list_members
 * hands them to agents. Another team sees none of it.
 */
class MemberProfilesTest {
    private val config = Config(
        dataDir = java.nio.file.Files.createTempDirectory("feed-profiles").toString(), adminPasswordHash = Passwords.hash("server password"),
        sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://localhost:5173", maxImageBytes = 5_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://localhost:5173",
        teamDomain = "feed.test",
    )

    /** One team: an owner, an admin (Ada), a member (Sam), and two agents (dev, scout). */
    private class Team(val app: App, val id: String) {
        lateinit var owner: People.Person
        lateinit var ownerKey: String
        lateinit var ada: People.Person
        lateinit var adaKey: String
        lateinit var sam: People.Person
        lateinit var samKey: String
        lateinit var dev: Agents.Agent
        lateinit var devToken: String
        lateinit var scout: Agents.Agent
        lateinit var scoutToken: String

        fun seed() = Tenant.with(id) {
            owner = app.people.createOwner("owner@$id.test", "Owner $id", "password $id $id")
            ownerKey = app.devices.mint("Owner phone", owner.id).second
            ada = join(People.Role.ADMIN, "Ada")
            adaKey = app.devices.mint("Ada phone", ada.id).second
            sam = join(People.Role.MEMBER, "Sam")
            samKey = app.devices.mint("Sam phone", sam.id).second
            app.agents.mint("dev").let { (a, t) -> dev = a; devToken = t }
            app.agents.mint("scout").let { (a, t) -> scout = a; scoutToken = t }
        }

        private fun join(role: People.Role, name: String): People.Person {
            val email = "${name.lowercase()}@$id.test"
            val (_, token) = app.people.invite(owner, role, email)
            return app.people.accept(token, name, email, "password for $name")
        }
    }

    private fun withTeams(block: suspend (App, HttpClient, Team, Team, MutableList<AppEvent>) -> Unit) {
        val app = App(config, Database.memory())
        Tenant.allTeams {
            for (t in listOf("alpha", "bravo")) app.db.update("INSERT INTO teams (id, slug, name, created_at) VALUES (?, ?, ?, 1)", t, t, t)
        }
        val alpha = Team(app, "alpha").apply { seed() }
        val bravo = Team(app, "bravo").apply { seed() }
        val events = mutableListOf<AppEvent>()
        try {
            testApplication {
                application { feed(app) }
                val stop = Tenant.with("alpha") { app.bus.subscribe { synchronized(events) { events += it } } }
                try {
                    block(app, client, alpha, bravo, events)
                } finally {
                    stop()
                }
            }
        } finally {
            app.close()
        }
    }

    private suspend fun HttpClient.call(team: Team, key: String, method: String, path: String, body: String? = null): HttpResponse = request(path) {
        this.method = HttpMethod.parse(method)
        header("Host", "${team.id}.feed.test")
        header("Authorization", "Bearer $key")
        header("Accept", "application/json, text/event-stream")
        if (body != null) {
            header("Content-Type", "application/json")
            setBody(body)
        }
    }

    private suspend fun HttpClient.json(team: Team, key: String, method: String, path: String, body: String? = null, status: Int = 200): JsonObject {
        val r = call(team, key, method, path, body)
        val text = r.bodyAsText()
        assertEquals(status, r.status.value, "$method $path: $text")
        return Wire.json.parseToJsonElement(text).jsonObject
    }

    private suspend fun HttpClient.tool(team: Team, token: String, name: String, args: JsonObject = JsonObject(emptyMap())): JsonObject {
        val text = call(
            team, token, "POST", "/mcp",
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
                put("params", buildJsonObject { put("name", name); put("arguments", args) })
            }.toString(),
        ).bodyAsText()
        return Wire.json.parseToJsonElement(text).jsonObject["result"]!!.jsonObject
    }

    private fun JsonObject.data(): JsonObject = this["structuredContent"]!!.jsonObject
    private fun JsonObject.text(): String = this["content"]!!.jsonArray.joinToString(" ") { it.jsonObject["text"]!!.jsonPrimitive.content }
    private fun JsonObject.error(): String? = this["structuredContent"]?.jsonObject?.get("error")?.jsonPrimitive?.content
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private suspend fun HttpClient.person(team: Team, key: String, id: String): JsonObject =
        json(team, key, "GET", "/api/people")["people"]!!.jsonArray.map { it.jsonObject }.single { it.str("id") == id }

    private suspend fun HttpClient.agent(team: Team, key: String, id: String): JsonObject =
        json(team, key, "GET", "/api/agents")["agents"]!!.jsonArray.map { it.jsonObject }.single { it.str("id") == id }

    private suspend fun HttpClient.members(team: Team, token: String, channel: String? = null): List<JsonObject> {
        val result = tool(team, token, "list_members", buildJsonObject { if (channel != null) put("channel", channel) })
        assertNull(result.error(), result.toString())
        return result.data()["members"]!!.jsonArray.map { it.jsonObject }
    }

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { for ((k, v) in pairs) put(k, v) }

    @Test
    fun `a person sets their own profile and everyone in the team reads it`() = withTeams { _, client, a, _, events ->
        val sam = client.person(a, a.samKey, a.sam.id)
        assertNull(sam.str("profileRole"))
        assertNull(sam.str("description"))

        val saved = client.json(a, a.samKey, "PATCH", "/api/me", """{"profileRole":"  Design ","description":"I draw the app.\nAsk me about screens."}""")
        assertEquals("Design", saved["person"]!!.jsonObject.str("profileRole"))
        assertEquals("I draw the app.\nAsk me about screens.", saved["person"]!!.jsonObject.str("description"))
        assertTrue(synchronized(events) { events.any { it.type == "people.updated" } })

        // The owner and an agent read it.
        assertEquals("Design", client.person(a, a.ownerKey, a.sam.id).str("profileRole"))
        val seen = client.members(a, a.devToken).single { it.str("id") == a.sam.id }
        assertEquals("person", seen.str("kind"))
        assertEquals("@sam", seen.str("handle"))
        assertEquals("Design", seen.str("role"))
        assertEquals("I draw the app.\nAsk me about screens.", seen.str("description"))
        assertEquals("member", seen.str("team_role"))

        // Leaving a key out keeps it; null or blank clears it.
        client.json(a, a.samKey, "PATCH", "/api/me", """{"description":"Screens and icons."}""")
        assertEquals("Design", client.person(a, a.samKey, a.sam.id).str("profileRole"))
        client.json(a, a.samKey, "PATCH", "/api/me", """{"profileRole":null,"description":"   "}""")
        val cleared = client.person(a, a.samKey, a.sam.id)
        assertNull(cleared.str("profileRole"))
        assertNull(cleared.str("description"))
    }

    @Test
    fun `owners and admins edit an agent's profile and a member can't`() = withTeams { _, client, a, _, events ->
        val body = """{"profileRole":"Marketing","description":"Writes the launch posts. @mention me for copy."}"""
        client.json(a, a.samKey, "PATCH", "/api/agents/${a.scout.id}", body, status = 403)
        assertNull(client.agent(a, a.samKey, a.scout.id).str("description"))

        val saved = client.json(a, a.adaKey, "PATCH", "/api/agents/${a.scout.id}", body)["agent"]!!.jsonObject
        assertEquals("Marketing", saved.str("profileRole"))
        assertEquals("scout", saved.str("name"))
        assertTrue(synchronized(events) { events.any { it.type == "agent.profile" && it.payload.toString().contains(a.scout.id) } })
        // A member reads it.
        assertEquals("Writes the launch posts. @mention me for copy.", client.agent(a, a.samKey, a.scout.id).str("description"))

        client.json(a, a.ownerKey, "PATCH", "/api/agents/${a.scout.id}", """{"profileRole":"Growth"}""")
        val scout = client.agent(a, a.samKey, a.scout.id)
        assertEquals("Growth", scout.str("profileRole"))
        assertEquals("Writes the launch posts. @mention me for copy.", scout.str("description"))
        // A name and nothing else still renames, as before.
        client.json(a, a.ownerKey, "PATCH", "/api/agents/${a.scout.id}", """{"name":"herald"}""")
        assertEquals("herald", client.agent(a, a.samKey, a.scout.id).str("name"))
        client.json(a, a.ownerKey, "PATCH", "/api/agents/${a.scout.id}", """{"colour":"red"}""", status = 400)
    }

    @Test
    fun `nobody edits another person's profile`() = withTeams { _, client, a, _, _ ->
        client.json(a, a.samKey, "PATCH", "/api/me", """{"description":"Sam's own words."}""")
        // Not the owner, not an admin, not another member: the person route sets role alone.
        client.json(a, a.ownerKey, "PATCH", "/api/people/${a.sam.id}", """{"description":"Owner's words."}""", status = 400)
        client.json(a, a.ownerKey, "PATCH", "/api/people/${a.sam.id}", """{"profileRole":"Intern"}""", status = 400)
        client.json(a, a.adaKey, "PATCH", "/api/people/${a.sam.id}", """{"description":"Ada's words."}""", status = 403)
        client.json(a, a.samKey, "PATCH", "/api/people/${a.ada.id}", """{"description":"Sam's words."}""", status = 403)
        // An agent has no way to: set_profile is its own.
        client.tool(a, a.devToken, "set_profile", args("description" to "Dev's words."))
        assertEquals("Sam's own words.", client.person(a, a.ownerKey, a.sam.id).str("description"))
        assertNull(client.person(a, a.ownerKey, a.ada.id).str("description"))
    }

    @Test
    fun `an agent sets its own with set_profile and register_session asks for one until it has`() = withTeams { _, client, a, _, events ->
        val first = client.tool(a, a.devToken, "register_session")
        assertContains(first.text(), "set_profile")
        assertContains(first.text(), "check list_members")
        assertEquals(JsonNull, first.data()["profile"]!!.jsonObject["description"])

        val set = client.tool(a, a.devToken, "set_profile", args("role" to "Development", "description" to "I build the server and the apps. Contact me for bugs and features."))
        assertNull(set.error(), set.toString())
        assertEquals("Development", set.data().str("role"))
        assertTrue(synchronized(events) { events.any { it.type == "agent.profile" && it.payload.toString().contains(a.dev.id) } })

        // Owners see it in the app, and other agents in list_members.
        assertEquals("Development", client.agent(a, a.ownerKey, a.dev.id).str("profileRole"))
        val members = client.members(a, a.scoutToken)
        val dev = members.single { it.str("id") == a.dev.id }
        assertEquals("agent", dev.str("kind"))
        assertEquals("@dev", dev.str("handle"))
        assertEquals("I build the server and the apps. Contact me for bugs and features.", dev.str("description"))
        assertNull(dev["you"])
        assertEquals(true, members.single { it.str("id") == a.scout.id }["you"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(setOf(a.owner.id, a.ada.id, a.sam.id, a.dev.id, a.scout.id), members.map { it.str("id") }.toSet())

        val again = client.tool(a, a.devToken, "register_session")
        assertFalse("call set_profile" in again.text(), again.text())
        assertContains(again.text(), "check list_members")
        assertEquals("Development", again.data()["profile"]!!.jsonObject.str("role"))

        // One part at a time; empty clears; nothing at all is refused.
        client.tool(a, a.devToken, "set_profile", args("role" to ""))
        assertNull(client.agent(a, a.ownerKey, a.dev.id).str("profileRole"))
        assertEquals("I build the server and the apps. Contact me for bugs and features.", client.agent(a, a.ownerKey, a.dev.id).str("description"))
        assertEquals("invalid_argument", client.tool(a, a.devToken, "set_profile").error())
    }

    @Test
    fun `list_members with a channel lists only those who can read it`() = withTeams { _, client, a, _, _ ->
        client.tool(a, a.devToken, "create_project", args("name" to "Ops", "slug" to "ops"))
        client.json(a, a.ownerKey, "PUT", "/api/projects/ops/access", """{"private":true}""")
        client.json(a, a.ownerKey, "PUT", "/api/projects/ops/access/member/${a.sam.id}", """{"level":"read"}""")
        client.json(a, a.ownerKey, "PUT", "/api/projects/ops/access/agent/${a.dev.id}", """{"level":"post"}""")

        assertEquals(setOf(a.owner.id, a.sam.id, a.dev.id), client.members(a, a.devToken, "ops").map { it.str("id") }.toSet())
        assertEquals(5, client.members(a, a.devToken).size)
        // A channel the agent can't read doesn't exist for it.
        assertEquals("not_found", client.tool(a, a.scoutToken, "list_members", args("channel" to "ops")).error())
    }

    @Test
    fun `a role is at most 60 characters and a description at most 500`() = withTeams { _, client, a, _, _ ->
        client.json(a, a.samKey, "PATCH", "/api/me", """{"profileRole":"${"r".repeat(61)}"}""", status = 400)
        client.json(a, a.samKey, "PATCH", "/api/me", """{"description":"${"d".repeat(501)}"}""", status = 400)
        // A bad profile changes nothing else in the same patch.
        client.json(a, a.samKey, "PATCH", "/api/me", """{"name":"Samuel","description":"${"d".repeat(501)}"}""", status = 400)
        assertEquals("Sam", client.person(a, a.samKey, a.sam.id).str("name"))
        client.json(a, a.samKey, "PATCH", "/api/me", """{"profileRole":"Line\none"}""", status = 400)
        client.json(a, a.samKey, "PATCH", "/api/me", """{"profileRole":"${"r".repeat(60)}","description":"${"d".repeat(500)}"}""")

        client.json(a, a.ownerKey, "PATCH", "/api/agents/${a.dev.id}", """{"description":"${"d".repeat(501)}"}""", status = 400)
        client.json(a, a.ownerKey, "PATCH", "/api/agents/${a.dev.id}", """{"name":"renamed","profileRole":"${"r".repeat(61)}"}""", status = 400)
        assertEquals("dev", client.agent(a, a.ownerKey, a.dev.id).str("name"))
        val tooLong = client.tool(a, a.devToken, "set_profile", args("description" to "d".repeat(501)))
        assertEquals(true, tooLong["isError"]?.jsonPrimitive?.content?.toBoolean(), tooLong.toString())
        assertNull(client.agent(a, a.ownerKey, a.dev.id).str("description"))
    }

    @Test
    fun `another team sees none of it`() = withTeams { _, client, a, b, _ ->
        client.json(b, b.samKey, "PATCH", "/api/me", """{"profileRole":"Bravo role","description":"Bravo words."}""")
        client.tool(b, b.devToken, "set_profile", args("role" to "Bravo dev", "description" to "Bravo agent words."))

        val members = client.members(a, a.devToken)
        assertEquals(setOf(a.owner.id, a.ada.id, a.sam.id, a.dev.id, a.scout.id), members.map { it.str("id") }.toSet())
        for (m in members) assertFalse(m.toString().contains("bravo", ignoreCase = true), m.toString())
        assertFalse(client.json(a, a.ownerKey, "GET", "/api/people").toString().contains("Bravo"))
        assertFalse(client.json(a, a.ownerKey, "GET", "/api/agents").toString().contains("Bravo"))
        // Team A's admin can't reach team B's agent, and B's profile stays.
        client.json(a, a.ownerKey, "PATCH", "/api/agents/${b.dev.id}", """{"description":"Alpha words."}""", status = 404)
        assertEquals("Bravo agent words.", client.agent(b, b.ownerKey, b.dev.id).str("description"))
        // Same-named people in both teams keep their own.
        assertNull(client.person(a, a.ownerKey, a.sam.id).str("description"))
        assertEquals("Bravo words.", client.person(b, b.ownerKey, b.sam.id).str("description"))
    }

    // ---- pictures ---------------------------------------------------------------------------------

    /** A picture of [w] by [h] in random noise, so no two are the same bytes (an upload of the same bytes is the same media). */
    private fun picture(w: Int = 60, h: Int = 40): ByteArray {
        val random = java.util.Random()
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until w) for (y in 0 until h) image.setRGB(x, y, random.nextInt(0xFFFFFF))
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** A person's upload, as the app sends it (`POST /api/media`): its media id. */
    private suspend fun HttpClient.upload(team: Team, key: String, bytes: ByteArray = picture(), mime: String = "image/png"): String {
        val r = request("/api/media?filename=me.png") {
            method = HttpMethod.Post
            header("Host", "${team.id}.feed.test")
            header("Authorization", "Bearer $key")
            header("Content-Type", mime)
            setBody(bytes)
        }
        assertEquals(201, r.status.value, r.bodyAsText())
        return Wire.json.parseToJsonElement(r.bodyAsText()).jsonObject["media"]!!.jsonObject.str("id")!!
    }

    /** An agent's upload: create_upload, then the bytes to its upload_url. */
    private suspend fun HttpClient.agentUpload(team: Team, token: String, bytes: ByteArray = picture()): String {
        val grant = tool(team, token, "create_upload", buildJsonObject { put("filename", "me.png"); put("mime", "image/png"); put("bytes", bytes.size) }).data()
        val path = java.net.URI(grant.str("upload_url")!!).rawPath
        val r = request(path) {
            method = HttpMethod.Put
            header("Host", "${team.id}.feed.test")
            header("Content-Type", "image/png")
            setBody(bytes)
        }
        assertTrue(r.status.value in 200..299, r.bodyAsText())
        return grant.str("media_id")!!
    }

    private suspend fun HttpClient.avatar(team: Team, key: String, mediaId: String): HttpResponse = call(team, key, "GET", "/media/$mediaId/avatar")

    @Test
    fun `a person sets a picture that everyone sees as a 256 px square, and removing it resets it`() = withTeams { app, client, a, _, events ->
        val media = client.upload(a, a.samKey, picture(600, 400))
        val saved = client.json(a, a.samKey, "PATCH", "/api/me", """{"avatarMediaId":"$media"}""")["person"]!!.jsonObject
        assertEquals(media, saved.str("avatarMediaId"))
        assertTrue(synchronized(events) { events.any { it.type == "people.updated" } })
        assertEquals(media, client.person(a, a.ownerKey, a.sam.id).str("avatarMediaId"))
        assertEquals(true, client.members(a, a.devToken).single { it.str("id") == a.sam.id }["has_picture"]?.jsonPrimitive?.content?.toBoolean())

        // The owner sees it, as a 256 px square when ffmpeg is here, else the original.
        val shown = client.avatar(a, a.ownerKey, media)
        assertEquals(200, shown.status.value)
        if (FfmpegTools().available) {
            assertEquals("image/webp", shown.headers["Content-Type"])
            val square = Tenant.with("alpha") {
                app.db.one("SELECT width, height FROM derivatives WHERE team_id = :team AND media_id = ? AND width = 256", media) { it.getInt(1) to it.getInt(2) }
            }
            assertEquals(256 to 256, square)
        } else {
            assertEquals("image/png", shown.headers["Content-Type"])
        }

        // Kept while it is someone's picture, however old; the sweeper takes it once it is nobody's.
        Tenant.with("alpha") {
            app.db.update("UPDATE media SET created_at = 1 WHERE team_id = :team AND id = ?", media)
            app.uploads.sweep()
            assertEquals(1, app.db.one("SELECT count(*) FROM media WHERE team_id = :team AND id = ?", media) { it.getInt(1) })
        }
        client.json(a, a.samKey, "PATCH", "/api/me", """{"avatarMediaId":null}""")
        assertNull(client.person(a, a.ownerKey, a.sam.id).str("avatarMediaId"))
        Tenant.with("alpha") {
            app.uploads.sweep()
            assertEquals(0, app.db.one("SELECT count(*) FROM media WHERE team_id = :team AND id = ?", media) { it.getInt(1) })
        }
        assertEquals(404, client.avatar(a, a.ownerKey, media).status.value)
    }

    @Test
    fun `a picture must be an image of yours on no card, at most 2 MB, and nobody sets another person's`() = withTeams { _, client, a, _, _ ->
        // Not another person's: the person route takes role alone, and an admin can't set it there.
        val sams = client.upload(a, a.samKey)
        client.json(a, a.ownerKey, "PATCH", "/api/people/${a.sam.id}", """{"avatarMediaId":"$sams"}""", status = 400)
        client.json(a, a.adaKey, "PATCH", "/api/people/${a.sam.id}", """{"avatarMediaId":"$sams"}""", status = 403)
        assertNull(client.person(a, a.ownerKey, a.sam.id).str("avatarMediaId"))

        // An agent's upload is not a person's to use; nor is one on a card, a missing one, or a file.
        val devs = client.agentUpload(a, a.devToken)
        client.json(a, a.samKey, "PATCH", "/api/me", """{"avatarMediaId":"$devs"}""", status = 404)
        client.tool(a, a.devToken, "create_project", args("name" to "Web", "slug" to "web"))
        val posted = client.agentUpload(a, a.devToken)
        client.tool(a, a.devToken, "post_update", buildJsonObject {
            put("project", "web"); put("body", "a picture"); put("media_ids", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(posted))))
        })
        assertEquals("not_found", client.tool(a, a.devToken, "set_profile", args("avatar_media_id" to posted)).error())
        client.json(a, a.samKey, "PATCH", "/api/me", """{"avatarMediaId":"01JNOSUCHMEDIA000000000000"}""", status = 404)
        val text = client.upload(a, a.samKey, "notes".toByteArray(), "text/plain")
        client.json(a, a.samKey, "PATCH", "/api/me", """{"avatarMediaId":"$text"}""", status = 400)
        val big = client.upload(a, a.samKey, picture(1000, 800))
        assertTrue(big.isNotEmpty())
        client.json(a, a.samKey, "PATCH", "/api/me", """{"avatarMediaId":"$big"}""", status = 400)

        // One of Sam's own, at last.
        client.json(a, a.samKey, "PATCH", "/api/me", """{"avatarMediaId":"$sams"}""")
        assertEquals(sams, client.person(a, a.ownerKey, a.sam.id).str("avatarMediaId"))
    }

    @Test
    fun `an agent sets its own picture and owners and admins set an agent's from the app`() = withTeams { _, client, a, _, events ->
        val devs = client.agentUpload(a, a.devToken)
        val set = client.tool(a, a.devToken, "set_profile", args("avatar_media_id" to devs))
        assertNull(set.error(), set.toString())
        assertEquals(devs, set.data().str("avatar_media_id"))
        assertEquals(devs, client.agent(a, a.samKey, a.dev.id).str("avatarMediaId"))
        assertEquals(200, client.avatar(a, a.samKey, devs).status.value)
        // Another agent's upload is not its to use.
        assertEquals("not_found", client.tool(a, a.scoutToken, "set_profile", args("avatar_media_id" to devs)).error())

        // An admin picks one in the app for scout; a member can't.
        val chosen = client.upload(a, a.adaKey)
        client.json(a, a.samKey, "PATCH", "/api/agents/${a.scout.id}", """{"avatarMediaId":"$chosen"}""", status = 403)
        val agent = client.json(a, a.adaKey, "PATCH", "/api/agents/${a.scout.id}", """{"avatarMediaId":"$chosen"}""")["agent"]!!.jsonObject
        assertEquals(chosen, agent.str("avatarMediaId"))
        assertTrue(synchronized(events) { events.any { it.type == "agent.profile" && it.payload.toString().contains(a.scout.id) } })
        // Removing it resets it, from the app or by the agent.
        client.json(a, a.ownerKey, "PATCH", "/api/agents/${a.scout.id}", """{"avatarMediaId":null}""")
        assertNull(client.agent(a, a.samKey, a.scout.id).str("avatarMediaId"))
        client.tool(a, a.devToken, "set_profile", args("avatar_media_id" to ""))
        assertNull(client.agent(a, a.samKey, a.dev.id).str("avatarMediaId"))
    }

    @Test
    fun `another team can't use or see a picture`() = withTeams { _, client, a, b, _ ->
        val bravos = client.upload(b, b.samKey)
        client.json(b, b.samKey, "PATCH", "/api/me", """{"avatarMediaId":"$bravos"}""")
        assertEquals(200, client.avatar(b, b.ownerKey, bravos).status.value)

        assertEquals(404, client.avatar(a, a.ownerKey, bravos).status.value)
        client.json(a, a.samKey, "PATCH", "/api/me", """{"avatarMediaId":"$bravos"}""", status = 404)
        client.json(a, a.ownerKey, "PATCH", "/api/agents/${a.dev.id}", """{"avatarMediaId":"$bravos"}""", status = 404)
        assertEquals("not_found", client.tool(a, a.devToken, "set_profile", args("avatar_media_id" to bravos)).error())
        assertFalse(bravos in client.json(a, a.ownerKey, "GET", "/api/people").toString())
    }
}
