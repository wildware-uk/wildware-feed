package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.Given
import dev.wildware.feed.server.domain.Projects
import dev.wildware.feed.server.domain.Subscriptions
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.groups
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Channels and projects over MCP and the owner API (migration 053, spec/12). */
class ChannelsRoutesTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    private fun tool(app: App, agent: Agents.Agent, name: String, vararg args: Pair<String, String>): JsonObject = runBlocking {
        val arguments = buildJsonObject { for ((k, v) in args) put(k, JsonPrimitive(v)) }
        app.tools.call(app, agent, buildJsonObject { put("name", JsonPrimitive(name)); put("arguments", arguments) }).json
    }

    private fun JsonObject.structured() = this["structuredContent"]!!.jsonObject
    private fun JsonObject.error() = structured()["error"]?.jsonPrimitive?.content
    private fun JsonObject.message() = structured()["message"]?.jsonPrimitive?.content
    private fun JsonObject.str(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    @Test
    fun `tools take channel, and project still works as its older name`() {
        val app = App(config, Database.memory())
        val (alpha, _) = app.agents.mint("alpha")
        app.feed.projects.create("Web", null, null)
        app.feed.projects.create("Api", null, null)

        val viaChannel = tool(app, alpha, "post_update", "channel" to "web", "body" to "by channel")
        assertEquals(null, viaChannel.error(), viaChannel.toString())
        val viaProject = tool(app, alpha, "post_update", "project" to "web", "body" to "by project")
        assertEquals(null, viaProject.error())
        assertEquals(null, tool(app, alpha, "post_update", "channel" to "web", "project" to "WEB", "body" to "both, the same").error())

        val both = tool(app, alpha, "post_update", "channel" to "web", "project" to "api", "body" to "x")
        assertEquals("invalid_argument", both.error())
        val neither = tool(app, alpha, "post_update", "body" to "x")
        assertEquals("channel is required", neither.message())

        assertEquals(null, tool(app, alpha, "set_project_theme", "channel" to "web", "accent" to "#fff").error())
        assertEquals(null, tool(app, alpha, "post_message", "channel" to "api", "body" to "a note").error())
        assertEquals(null, tool(app, alpha, "get_messages", "channel" to "api").error())
        assertEquals(null, tool(app, alpha, "list_tags", "channel" to "api").error())
        assertEquals(null, tool(app, alpha, "list_work", "channel" to "api").error())
    }

    @Test
    fun `a group's slug where a channel goes says it is a project`() {
        val app = App(config, Database.memory())
        val (alpha, _) = app.agents.mint("alpha")
        app.feed.groups.create("Launch", "launch")
        val wrong = tool(app, alpha, "post_update", "project" to "launch", "body" to "x")
        assertEquals("not_found", wrong.error())
        assertTrue(wrong.message()!!.startsWith("no such project: launch (that is a project, a group of channels"), wrong.message())
        assertEquals("no such project: nope", tool(app, alpha, "post_update", "project" to "nope", "body" to "x").message())
    }

    @Test
    fun `list_projects says which project each channel is in`() {
        val app = App(config, Database.memory())
        val (alpha, _) = app.agents.mint("alpha")
        app.feed.projects.create("Web", null, null)
        app.feed.projects.create("Api", null, null)
        val group = app.feed.groups.create("Launch", null).first
        app.feed.projects.update("web", Projects.Patch(group = Given("launch")))

        val listed = tool(app, alpha, "list_projects").structured()
        val web = listed["projects"]!!.jsonArray.map { it.jsonObject }.first { it.str("slug") == "web" }
        assertEquals(buildJsonObject { put("id", JsonPrimitive(group.id)); put("slug", JsonPrimitive("launch")); put("name", JsonPrimitive("Launch")) }, web["group"])
        assertEquals(JsonNull, listed["projects"]!!.jsonArray.map { it.jsonObject }.first { it.str("slug") == "api" }["group"])
        val g = listed["groups"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("web"), g["channels"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `subscribe takes a group, a channel (or project), or a thread, and project never means the group`() {
        val app = App(config, Database.memory())
        val (alpha, _) = app.agents.mint("alpha")
        app.feed.projects.create("Web", null, null)
        app.feed.projects.create("Api", null, null)
        app.feed.groups.create("Launch", "launch")
        app.feed.projects.update("web", Projects.Patch(group = Given("launch")))
        app.feed.projects.update("api", Projects.Patch(group = Given("launch")))
        // Alpha has worked in api, so follows it from posting there.
        val card = app.feed.updates.post(dev.wildware.feed.server.domain.Updates.Post(project = "api", agentId = alpha.id, body = "work"))

        val group = tool(app, alpha, "subscribe", "group" to "launch").structured()
        assertEquals("group", group["subscribed"]!!.jsonObject.str("kind"))
        assertEquals("launch", group["subscribed"]!!.jsonObject.str("group"))
        assertEquals(false, group["already"]!!.jsonPrimitive.content.toBoolean())

        val list = tool(app, alpha, "list_subscriptions").structured()
        assertEquals(listOf("launch"), list["groups"]!!.jsonArray.map { it.jsonObject.str("slug") })
        assertEquals<Map<String?, String?>>(mapOf("api" to "group", "web" to "group"), list["channels"]!!.jsonArray.associate { it.jsonObject.str("slug") to it.jsonObject.str("via") })

        // `project` is the channel's older name here too, never the group.
        assertEquals("not_found", tool(app, alpha, "subscribe", "project" to "launch").error())
        val channel = tool(app, alpha, "unsubscribe", "project" to "web").structured()
        assertEquals("channel", channel["unsubscribed"]!!.jsonObject.str("kind"))
        assertEquals(true, channel["was_subscribed"]!!.jsonPrimitive.content.toBoolean(), "web was heard through its group")
        assertEquals(listOf(app.feed.projects.resolve("api").id), Subscriptions(app.db).followedProjects(alpha.id))

        val left = tool(app, alpha, "unsubscribe", "group" to "launch").structured()
        assertEquals(true, left["was_subscribed"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(emptyList<String>(), Subscriptions(app.db).followedProjects(alpha.id), "api, only posted in, gives way to the group alpha left")

        val again = tool(app, alpha, "subscribe", "channel" to "api").structured()
        assertEquals(false, again["already"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf(app.feed.projects.resolve("api").id), Subscriptions(app.db).followedProjects(alpha.id))

        assertEquals("invalid_argument", tool(app, alpha, "subscribe", "group" to "launch", "channel" to "api").error())
        assertEquals("update", tool(app, alpha, "subscribe", "update_id" to card.id).structured()["subscribed"]!!.jsonObject.str("kind"))
    }

    @Test
    fun `unsubscribe(group) silences channels the agent only posted in, but a channel it chose survives`() {
        val app = App(config, Database.memory())
        val (alpha, _) = app.agents.mint("alpha")
        for (name in listOf("Web", "Api")) app.feed.projects.create(name, null, null)
        app.feed.groups.create("Launch", "launch")
        for (slug in listOf("web", "api")) app.feed.projects.update(slug, Projects.Patch(group = Given("launch")))
        // Alpha posts in both, so follows both automatically; then chooses web on purpose.
        for (slug in listOf("web", "api")) assertEquals(null, tool(app, alpha, "post_update", "channel" to slug, "body" to "work in $slug").error())
        assertEquals(null, tool(app, alpha, "subscribe", "channel" to "web").error())
        tool(app, alpha, "get_messages")

        assertEquals(true, tool(app, alpha, "unsubscribe", "group" to "launch").structured()["was_subscribed"]!!.jsonPrimitive.content.toBoolean())
        for (slug in listOf("web", "api")) {
            app.feed.messages.post(dev.wildware.feed.server.domain.Messages.Post(author = "human", body = "owner in $slug", project = slug))
        }
        val heard = tool(app, alpha, "get_messages").structured()["messages"]!!.jsonArray.map { it.jsonObject.str("body") }
        assertEquals(listOf("owner in web"), heard, "api, only posted in, is quiet; web, chosen, still reaches alpha")
        val via = tool(app, alpha, "list_subscriptions").structured()["channels"]!!.jsonArray.associate { it.jsonObject.str("slug") to it.jsonObject.str("via") }
        assertEquals<Map<String?, String?>>(mapOf("web" to "chosen"), via)
    }

    @Test
    fun `the owner makes, fills, follows and deletes a group over the API`() {
        val app = App(config, Database.memory())
        val (_, key) = app.devices.mint("Phone")
        val server = FeedServer(app).start()
        try {
            app.feed.projects.create("Web", null, null)
            val client = HttpClient.newHttpClient()
            fun send(method: String, path: String, json: String = "", status: Int = 200): JsonObject {
                val r = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}$path")).header("Authorization", "Bearer $key")
                        .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                return Wire.json.parseToJsonElement(r.body()).jsonObject.also { assertEquals(status, r.statusCode(), r.body()) }
            }

            val made = send("POST", "/api/groups", """{"name":"Launch"}""", status = 201)["group"]!!.jsonObject
            val id = made.str("id")!!
            assertEquals(listOf("seq", "id", "slug", "name", "status", "createdAt", "updatedAt", "theme"), made.keys.toList())
            assertEquals(false, send("POST", "/api/groups", """{"name":"Launch"}""")["created"]!!.jsonPrimitive.content.toBoolean())

            val moved = send("PATCH", "/api/projects/web", """{"groupId":"launch"}""")["project"]!!.jsonObject
            assertEquals(id, moved.str("groupId"))
            val snapshot = send("GET", "/api/snapshot")
            assertEquals(listOf("launch"), snapshot["groups"]!!.jsonArray.map { it.jsonObject.str("slug") })
            assertEquals(id, snapshot["projects"]!!.jsonArray.single().jsonObject.str("groupId"))

            val styled = send("PATCH", "/api/groups/launch", """{"name":"Go","theme":{"accent":"#123"},"status":"archived"}""")["group"]!!.jsonObject
            assertEquals(listOf("Go", "archived"), listOf(styled.str("name"), styled.str("status")))
            assertEquals("#112233", styled["theme"]!!.jsonObject.str("accent"))

            assertEquals("""{"groupId":"$id","level":"muted"}""", Wire.encode(send("PUT", "/api/groups/launch/subscription", """{"level":"muted"}""")))
            assertEquals("muted", send("PUT", "/api/projects/web/subscription", """{"level":null}""").str("level"), "inherited from the group")
            assertEquals(true, send("PUT", "/api/projects/web/subscription", """{"level":null}""")["inherited"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("""[{"groupId":"$id","level":"muted"}]""", Wire.encode(send("GET", "/api/subscriptions")["groups"]!!))
            send("PUT", "/api/groups/launch/subscription", """{"level":"loud"}""", status = 400)

            assertEquals(true, send("DELETE", "/api/groups/launch")["deleted"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(JsonNull, send("GET", "/api/snapshot")["projects"]!!.jsonArray.single().jsonObject["groupId"])
            assertEquals(0, send("GET", "/api/groups")["groups"]!!.jsonArray.size)
            send("PATCH", "/api/projects/web", """{"groupId":"launch"}""", status = 404)
        } finally {
            server.stop()
        }
    }
}
