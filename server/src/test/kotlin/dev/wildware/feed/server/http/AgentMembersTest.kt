package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.Projects
import dev.wildware.feed.server.domain.Updates
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.domain.agentMembers
import dev.wildware.feed.server.domain.groups
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The owner adds agents to channels and projects, and removes them (migration 060): a removal
 * sticks, @mentions still reach, it takes effect at once, and the agent is told.
 */
class AgentMembersTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = Passwords.hash("owner password"), sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    private fun tool(app: App, agent: Agents.Agent, name: String, vararg args: Pair<String, String>): JsonObject = runBlocking {
        val arguments = buildJsonObject { for ((k, v) in args) put(k, JsonPrimitive(v)) }
        app.tools.call(app, agent, buildJsonObject { put("name", JsonPrimitive(name)); put("arguments", arguments) }).json["structuredContent"]!!.jsonObject
    }

    private fun JsonObject.str(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    /** What reaches [agent] now, then marks it read, as its bridge and get_messages would. */
    private fun heard(app: App, agent: Agents.Agent): List<String> {
        val lines = app.work.unread(agent.id, 50).map { it.body }
        tool(app, agent, "get_messages")
        return lines
    }

    private fun say(app: App, body: String, project: String) =
        app.feed.messages.post(Messages.Post(author = Messages.HUMAN, body = body, project = project))

    private fun owner(app: App) = app.people.find(People.FOUNDER)!!.id

    private fun projectOf(app: App, slug: String) = app.feed.projects.resolve(slug)

    /** A fresh Postgres schema, when PG_TEST_URL names a server; the test is skipped otherwise. */
    private fun postgres(): Database {
        val url = System.getenv("PG_TEST_URL")
        org.junit.jupiter.api.Assumptions.assumeTrue(!url.isNullOrBlank(), "PG_TEST_URL not set")
        val schema = "t_" + java.util.UUID.randomUUID().toString().replace("-", "")
        Database.postgresConnection(url!!, null).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA \"$schema\"") } }
        return Database.openPostgres(url, schema = schema, onClose = {
            Database.postgresConnection(url, null).use { c -> c.createStatement().use { it.execute("DROP SCHEMA \"$schema\" CASCADE") } }
        })
    }

    @Test
    fun `a removed agent stops hearing the channel, posting there does not bring it back, and an @mention still reaches it`() =
        removalSticks(App(config, Database.memory()))

    @Test
    fun `on Postgres too a removal sticks and an @mention still reaches`() {
        for (check in listOf(::removalSticks, ::groupRemoval)) {
            val db = postgres()
            try {
                check(App(config, db))
            } finally {
                db.close()
            }
        }
    }

    private fun removalSticks(app: App) {
        val (alpha, _) = app.agents.mint("alpha")
        app.feed.projects.create("Web", null, null)
        app.feed.projects.create("Api", null, null)
        app.feed.updates.post(Updates.Post(project = "web", agentId = alpha.id, body = "alpha works in web"))
        app.feed.updates.post(Updates.Post(project = "api", agentId = alpha.id, body = "and in api"))
        heard(app, alpha)
        say(app, "hello web", "web")
        assertEquals(listOf("hello web"), heard(app, alpha))

        val member = app.feed.agentMembers.setChannel(projectOf(app, "web"), alpha.id, hears = false, by = owner(app))
        assertEquals(false, member.hears)
        assertEquals("channel", member.removed)
        val notice = heard(app, alpha).single()
        assertTrue(notice.startsWith("@alpha I removed you from #web."), notice)

        say(app, "not for alpha", "web")
        say(app, "api still reaches", "api")
        assertEquals(listOf("api still reaches"), heard(app, alpha))

        // Posting there follows the thread it posts in, but not the channel.
        tool(app, alpha, "post_message", "channel" to "web", "body" to "alpha posts in web anyway")
        say(app, "still not for alpha", "web")
        assertEquals(emptyList(), heard(app, alpha))

        val refused = tool(app, alpha, "subscribe", "channel" to "web")
        assertEquals("forbidden", refused.str("error"))
        assertTrue(refused.str("message")!!.contains("Your owner removed you from channel web"), refused.toString())
        assertEquals(listOf("web"), tool(app, alpha, "list_subscriptions")["removed_by_owner"]!!.jsonArray.map { it.jsonPrimitive.content })

        say(app, "@alpha a word, please", "web")
        assertEquals(listOf("@alpha a word, please"), heard(app, alpha))

        // The owner adds it back: it hears the channel, and may subscribe itself again.
        assertEquals(true, app.feed.agentMembers.setChannel(projectOf(app, "web"), alpha.id, hears = true, by = owner(app)).hears)
        assertTrue(heard(app, alpha).single().startsWith("@alpha I added you to #web."))
        say(app, "welcome back", "web")
        assertEquals(listOf("welcome back"), heard(app, alpha))
        assertNull(tool(app, alpha, "subscribe", "channel" to "web").str("error"))
    }

    @Test
    fun `an agent that hears every channel keeps hearing the rest when removed from one`() {
        val app = App(config, Database.memory())
        val (alpha, _) = app.agents.mint("alpha")
        app.feed.projects.create("Web", null, null)
        app.feed.projects.create("Api", null, null)
        assertEquals(true, tool(app, alpha, "list_subscriptions")["all_projects"]!!.jsonPrimitive.content.toBoolean())

        app.feed.agentMembers.setChannel(projectOf(app, "web"), alpha.id, hears = false, by = owner(app))
        heard(app, alpha)
        say(app, "web is quiet for alpha", "web")
        say(app, "api is not", "api")
        app.feed.projects.create("Later", null, null)
        say(app, "nor a channel made later", "later")
        assertEquals(listOf("api is not", "nor a channel made later"), heard(app, alpha))
        assertEquals(true, tool(app, alpha, "list_subscriptions")["all_projects"]!!.jsonPrimitive.content.toBoolean(), "still hears every other channel")

        val list = app.feed.agentMembers.ofChannel(projectOf(app, "api").id).single()
        assertEquals("all", list.via)
    }

    @Test
    fun `removing an agent from a project quiets every channel in it, and adding it back brings them all`() =
        groupRemoval(App(config, Database.memory()))

    private fun groupRemoval(app: App) {
        val (alpha, _) = app.agents.mint("alpha")
        val group = app.feed.groups.create("Launch", "launch").first
        for (name in listOf("Web", "Api")) {
            app.feed.projects.create(name, null, null)
            app.feed.projects.update(name.lowercase(), Projects.Patch(group = dev.wildware.feed.server.domain.Given(group.id)))
        }
        app.feed.projects.create("Elsewhere", null, null)
        tool(app, alpha, "subscribe", "channel" to "web")
        tool(app, alpha, "subscribe", "channel" to "elsewhere")
        heard(app, alpha)

        app.feed.agentMembers.setGroup(group, alpha.id, hears = false, by = owner(app))
        assertTrue(heard(app, alpha).single().startsWith("@alpha I removed you from project Launch"))
        say(app, "web quiet", "web")
        say(app, "api quiet", "api")
        say(app, "elsewhere heard", "elsewhere")
        assertEquals(listOf("elsewhere heard"), heard(app, alpha))
        assertEquals("forbidden", tool(app, alpha, "subscribe", "channel" to "api").str("error"))
        assertEquals("forbidden", tool(app, alpha, "subscribe", "group" to "launch").str("error"))

        app.feed.agentMembers.setGroup(group, alpha.id, hears = true, by = owner(app))
        heard(app, alpha)
        say(app, "web again", "web")
        say(app, "api again", "api")
        assertEquals(listOf("web again", "api again"), heard(app, alpha))
    }

    @Test
    fun `the stream hears a removal at once, ignores a project list, and still brings the @mention`() {
        val app = App(config, Database.memory())
        val server = FeedServer(app).start()
        try {
            val (alpha, token) = app.agents.mint("alpha")
            app.feed.projects.create("Web", null, null)
            app.feed.updates.post(Updates.Post(project = "web", agentId = alpha.id, body = "alpha works in web"))
            val bodies = LinkedBlockingQueue<String>()
            val lines = HttpClient.newHttpClient().sendAsync(
                // An older bridge names its channels: the server decides on its own now.
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}/api/agent/stream?project=web")).header("Authorization", "Bearer $token").build(),
                java.net.http.HttpResponse.BodyHandlers.ofLines(),
            ).get(5, TimeUnit.SECONDS).body().iterator()
            Thread {
                var event: String? = null
                runCatching {
                    while (lines.hasNext()) {
                        val line = lines.next()
                        when {
                            line.startsWith("event: ") -> event = line.removePrefix("event: ")
                            line.startsWith("data: ") && event == "message" ->
                                Wire.json.parseToJsonElement(line.removePrefix("data: ")).jsonObject["messages"]!!.jsonArray
                                    .forEach { bodies += it.jsonObject["body"]!!.jsonPrimitive.content }
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
            Thread.sleep(300)
            // A list naming no channel at all, or every one, is accepted and ignored too.
            for (query in listOf("?project=nope", "?project=*,web", "?channel=nope")) {
                val other = HttpClient.newHttpClient().sendAsync(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}/api/agent/stream$query")).header("Authorization", "Bearer $token").build(),
                    java.net.http.HttpResponse.BodyHandlers.ofLines(),
                ).get(5, TimeUnit.SECONDS)
                assertEquals(200, other.statusCode(), query)
                other.body().close()
            }

            say(app, "before", "web")
            assertEquals("before", bodies.poll(5, TimeUnit.SECONDS))
            app.feed.agentMembers.setChannel(projectOf(app, "web"), alpha.id, hears = false, by = owner(app))
            assertTrue(bodies.poll(5, TimeUnit.SECONDS)!!.startsWith("@alpha I removed you from #web."), "the agent is told")
            say(app, "after", "web")
            assertNull(bodies.poll(700, TimeUnit.MILLISECONDS), "a removal takes effect without reconnecting")
            say(app, "@alpha only this", "web")
            assertEquals("@alpha only this", bodies.poll(5, TimeUnit.SECONDS))

            // The agent's own unsubscribe is live too, whatever list the bridge sent.
            app.feed.agentMembers.setChannel(projectOf(app, "web"), alpha.id, hears = true, by = owner(app))
            assertTrue(bodies.poll(5, TimeUnit.SECONDS)!!.startsWith("@alpha I added you"))
            tool(app, alpha, "unsubscribe", "channel" to "web")
            say(app, "after leaving", "web")
            assertNull(bodies.poll(700, TimeUnit.MILLISECONDS))
        } finally {
            server.stop()
        }
    }

    // ---- the owner API ----------------------------------------------------------------------------

    private class Team(val app: App, val owner: String, val admin: String, val member: String, val agent: Agents.Agent, val agentToken: String)

    private fun withTeam(block: suspend ApplicationTestBuilder.(Team) -> Unit) {
        val app = App(config, Database.memory())
        app.feed.projects.create("Web", null, null)
        app.feed.groups.create("Launch", "launch")
        val founder = app.people.find(People.FOUNDER)!!
        fun join(role: People.Role, name: String): String {
            val (_, token) = app.people.invite(founder, role, null)
            val person = app.people.accept(token, name, "$name@example.com", "a long password")
            return app.devices.mint("$name's phone", person.id).second
        }
        val ownerKey = app.devices.mint("Phone").second
        val adminKey = join(People.Role.ADMIN, "ada")
        val memberKey = join(People.Role.MEMBER, "sam")
        val (agent, agentToken) = app.agents.mint("alpha")
        testApplication {
            application { feed(app) }
            block(Team(app, ownerKey, adminKey, memberKey, agent, agentToken))
        }
        app.close()
    }

    private suspend fun ApplicationTestBuilder.get(key: String, path: String): HttpResponse = client.get(path) { header("Authorization", "Bearer $key") }

    private suspend fun ApplicationTestBuilder.put(key: String, path: String, body: String): HttpResponse = client.put(path) {
        header("Authorization", "Bearer $key")
        header("Content-Type", "application/json")
        setBody(body)
    }

    private suspend fun HttpResponse.json() = Wire.json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `the owner lists removes and adds agents and a member only reads and an agent cannot`() = withTeam { t ->
        val list = get(t.member, "/api/projects/web/agents")
        assertEquals(200, list.status.value)
        val row = list.json()["agents"]!!.jsonArray.single().jsonObject
        assertEquals("""{"agentId":"${t.agent.id}","name":"alpha","hears":true,"via":"all","removed":null}""", Wire.encode(row))

        assertEquals(403, put(t.member, "/api/projects/web/agents/${t.agent.id}", """{"hears":false}""").status.value)
        assertEquals(403, put(t.member, "/api/groups/launch/agents/${t.agent.id}", """{"hears":false}""").status.value)
        val asAgent = put(t.agentToken, "/api/projects/web/agents/${t.agent.id}", """{"hears":false}""")
        assertTrue(asAgent.status.value in setOf(401, 403), "an agent's token is not a person's: ${asAgent.status}")
        assertEquals(true, t.app.feed.agentMembers.ofChannel(t.app.feed.projects.resolve("web").id).single().hears, "nothing changed")

        val removed = put(t.admin, "/api/projects/web/agents/${t.agent.id}", """{"hears":false}""")
        assertEquals(200, removed.status.value, removed.bodyAsText())
        assertEquals("channel", removed.json()["agent"]!!.jsonObject.str("removed"))
        assertEquals(400, put(t.owner, "/api/projects/web/agents/${t.agent.id}", """{"hears":"no"}""").status.value)
        assertEquals(404, put(t.owner, "/api/projects/web/agents/nobody", """{"hears":true}""").status.value)

        val back = put(t.owner, "/api/projects/web/agents/${t.agent.id}", """{"hears":true}""").json()["agent"]!!.jsonObject
        assertEquals("true", back.str("hears"))
        assertNull(back.str("removed"))

        val group = put(t.owner, "/api/groups/launch/agents/${t.agent.id}", """{"hears":false}""").json()["agent"]!!.jsonObject
        assertEquals("group", group.str("removed"))
        assertEquals("false", get(t.owner, "/api/groups/launch/agents").json()["agents"]!!.jsonArray.single().jsonObject.str("hears"))
    }

    @Test
    fun `the people list says who used the app in the last five minutes`() = withTeam { t ->
        get(t.admin, "/api/me")
        val people = get(t.member, "/api/people").json()["people"]!!.jsonArray.associate { it.jsonObject.str("name") to it.jsonObject }
        assertEquals("true", people.getValue("ada").str("online"), "ada just used the app")
        assertEquals("true", people.getValue("sam").str("online"), "so did sam, asking")
        val founder = people.values.single { it.str("founder") == "true" }
        assertEquals("false", founder.str("online"), "the founder's key has not been used")
        assertNull(founder.str("lastSeenAt"))
    }
}
