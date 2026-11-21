package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.Secrets
import dev.wildware.feed.server.domain.feed
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A secret an agent attaches to a post (migration 066): the post carries the name, never the value;
 * the owner (or an admin) opens it in place, and every opening is audited. Agents, other teams and
 * plain members can't.
 */
class SharedSecretsTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
        secretsKeys = Config.SecretsKeys("k".repeat(40)),
        teamDomain = "feed.test",
    )

    // Not a real key: a marker the test looks for everywhere it must not be.
    private val value = "sk-test-NOT-REAL-shared-91bc"

    private fun postgres(): Database {
        val url = System.getenv("PG_TEST_URL")
        org.junit.jupiter.api.Assumptions.assumeTrue(!url.isNullOrBlank(), "PG_TEST_URL not set")
        val schema = "t_" + java.util.UUID.randomUUID().toString().replace("-", "")
        Database.postgresConnection(url!!, null).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA \"$schema\"") } }
        return Database.openPostgres(url, schema = schema, onClose = {
            Database.postgresConnection(url, null).use { c -> c.createStatement().use { it.execute("DROP SCHEMA \"$schema\" CASCADE") } }
        })
    }

    private class Ctx(
        val app: App,
        val ownerKey: String,
        val adminKey: String,
        val memberKey: String,
        val otherTeamKey: String,
        val alpha: Agents.Agent,
        val alphaToken: String,
        val beta: Agents.Agent,
    )

    private fun withApp(db: Database, block: suspend ApplicationTestBuilder.(Ctx) -> Unit) {
        val app = App(config, db)
        try {
            Tenant.allTeams {
                for (t in listOf("alpha", "bravo")) app.db.update("INSERT INTO teams (id, slug, name, created_at) VALUES (?, ?, ?, 1)", t, t, t)
            }
            val ctx = Tenant.with("alpha") {
                val owner = app.people.createOwner("owner@alpha.test", "Olive", "a long password here")
                fun join(role: People.Role, name: String): String {
                    val (_, token) = app.people.invite(owner, role, null)
                    val person = app.people.accept(token, name, "$name@alpha.test", "a long password")
                    return app.devices.mint("$name's phone", person.id).second
                }
                app.feed.projects.create("Web", null, null)
                val (alpha, alphaToken) = app.agents.mint("alpha")
                val (beta, _) = app.agents.mint("beta")
                Ctx(
                    app, app.devices.mint("Olive's phone", owner.id).second, join(People.Role.ADMIN, "ada"), join(People.Role.MEMBER, "sam"),
                    "", alpha, alphaToken, beta,
                )
            }
            val otherKey = Tenant.with("bravo") {
                val owner = app.people.createOwner("owner@bravo.test", "Bea", "another long password")
                app.devices.mint("Bea's phone", owner.id).second
            }
            testApplication {
                application { feed(app) }
                block(Ctx(ctx.app, ctx.ownerKey, ctx.adminKey, ctx.memberKey, otherKey, ctx.alpha, ctx.alphaToken, ctx.beta))
            }
        } finally {
            app.close()
        }
    }

    private fun Ctx.tool(agent: Agents.Agent, name: String, args: JsonObject): JsonObject = Tenant.with("alpha") {
        kotlinx.coroutines.runBlocking { app.tools.call(app, agent, buildJsonObject { put("name", name); put("arguments", args) }).json }
    }

    private fun Ctx.secret(name: String, agents: List<Agents.Agent>): String = Tenant.with("alpha") {
        app.secrets.create(name, value.toByteArray(), null, false, agents.map { it.id }, Secrets.Actor.SERVER).id
    }

    private suspend fun ApplicationTestBuilder.call(team: String, key: String?, method: String, path: String, body: String? = null): HttpResponse {
        val setup: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header("Host", "$team.feed.test")
            if (key != null) header("Authorization", "Bearer $key")
            if (body != null) { header("Content-Type", "application/json"); setBody(body) }
        }
        return if (method == "GET") client.get(path, setup) else client.post(path, setup)
    }

    private fun String.json() = Wire.json.parseToJsonElement(this).jsonObject
    private fun JsonObject.data() = this["structuredContent"]!!.jsonObject
    private fun names(vararg n: String) = JsonArray(n.map(::JsonPrimitive))

    @Test
    fun `on SQLite an agent shares a secret in a thread and the owner opens it there`() = withApp(Database.memory()) { shares(it) }

    @Test
    fun `on Postgres an agent shares a secret in a thread and the owner opens it there`() = withApp(postgres()) { shares(it) }

    private suspend fun ApplicationTestBuilder.shares(c: Ctx) {
        val id = c.secret("STRIPE_TEST_KEY", listOf(c.alpha))
        c.secret("BETA_ONLY", listOf(c.beta))
        val transcript = StringBuilder()
        // beta works in the channel too, so it reads alpha's post.
        c.tool(c.beta, "subscribe", buildJsonObject { put("channel", "web") })

        // Attached by name; the result names it, and holds no value.
        val posted = c.tool(c.alpha, "post_message", buildJsonObject {
            put("channel", "web"); put("body", "Here is the test key."); put("secrets", names("STRIPE_TEST_KEY"))
        }).also { transcript.append(it) }
        assertTrue(posted["isError"]?.jsonPrimitive?.content != "true", posted.toString())
        val message = posted.data()["message"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(listOf("STRIPE_TEST_KEY"), posted.data()["secrets"]!!.jsonArray.map { it.jsonPrimitive.content })

        // Refused when the agent may not read it, when it doesn't exist, or when it's a value.
        for (bad in listOf("BETA_ONLY", "NO_SUCH_KEY", value)) {
            val refused = c.tool(c.alpha, "post_message", buildJsonObject { put("channel", "web"); put("body", "try $bad"); put("secrets", names(bad)) })
                .also { transcript.append(it) }
            assertEquals("true", refused["isError"]?.jsonPrimitive?.content, "sharing $bad is refused: $refused")
        }
        // Nothing of a refused post was stored.
        assertEquals(1, Tenant.with("alpha") { c.app.db.one("SELECT count(*) FROM messages WHERE team_id = 'alpha'") { it.getInt(1) } })

        // On an update too.
        val update = c.tool(c.alpha, "post_update", buildJsonObject {
            put("channel", "web"); put("body", "Deployed."); put("secrets", names("STRIPE_TEST_KEY"))
        }).also { transcript.append(it) }.data()["update"]!!.jsonObject["id"]!!.jsonPrimitive.content

        // The owner's app sees the name and that it can be opened; never the value.
        val thread = call("alpha", c.ownerKey, "GET", "/api/messages").bodyAsText()
        assertFalse(thread.contains(value))
        val chip = thread.json()["secrets"]!!.jsonObject[message]!!.jsonArray.single().jsonObject
        assertEquals("STRIPE_TEST_KEY", chip["name"]!!.jsonPrimitive.content)
        assertEquals(true, chip["available"]!!.jsonPrimitive.content.toBoolean())
        val snapshot = call("alpha", c.ownerKey, "GET", "/api/snapshot").bodyAsText()
        assertFalse(snapshot.contains(value))
        val card = snapshot.json()["updates"]!!.jsonObject["items"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == update }
        assertEquals("STRIPE_TEST_KEY", card["secrets"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)

        // Other agents read the name only.
        val read = c.tool(c.beta, "get_messages", buildJsonObject { put("channel", "web") }).also { transcript.append(it) }
        val seen = read.data()["messages"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.content == message }
        assertEquals(listOf("STRIPE_TEST_KEY"), seen["secrets"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(transcript.contains(value), "no tool result holds the value")

        val reveal = "/api/cards/message/$message/secrets/STRIPE_TEST_KEY/reveal"
        // The owner opens it, then copies it.
        val shown = call("alpha", c.ownerKey, "POST", reveal, """{"purpose":"reveal"}""")
        assertEquals(200, shown.status.value)
        assertEquals("no-store", shown.headers["Cache-Control"])
        assertEquals(value, shown.bodyAsText().json()["value"]!!.jsonPrimitive.content)
        assertEquals(200, call("alpha", c.adminKey, "POST", "/api/cards/update/$update/secrets/STRIPE_TEST_KEY/reveal", """{"purpose":"copy"}""").status.value)

        // Nobody else does: a plain member, an agent's token, no key, another team's owner here or at their own address.
        assertEquals(403, call("alpha", c.memberKey, "POST", reveal, "{}").status.value)
        assertEquals(401, call("alpha", c.alphaToken, "POST", reveal, "{}").status.value)
        assertEquals(401, call("alpha", null, "POST", reveal, "{}").status.value)
        assertEquals(401, call("alpha", c.otherTeamKey, "POST", reveal, "{}").status.value)
        val elsewhere = call("bravo", c.otherTeamKey, "POST", reveal, "{}")
        assertEquals(404, elsewhere.status.value)
        assertFalse(elsewhere.bodyAsText().contains(value))
        // A name that isn't on the card opens nothing, even one the owner holds.
        assertEquals(404, call("alpha", c.ownerKey, "POST", "/api/cards/message/$message/secrets/BETA_ONLY/reveal", "{}").status.value)

        // Every opening is in the audit, with who and on which device.
        val audit = call("alpha", c.ownerKey, "GET", "/api/secrets/audit?secret=$id").bodyAsText()
        assertFalse(audit.contains(value))
        val entries = audit.json()["entries"]!!.jsonArray.map { it.jsonObject }
        val actions = entries.map { it["action"]!!.jsonPrimitive.content }
        assertEquals(listOf("copied", "revealed", "shared", "shared", "created"), actions)
        val revealed = entries[1]
        assertEquals("Olive's phone", revealed["actor"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("in message $message, by Olive", revealed["detail"]!!.jsonPrimitive.content)
        assertEquals("ada's phone", entries[0]["actor"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        // Taken away from the agent that shared it, the chip says so and opens nothing.
        Tenant.with("alpha") { c.app.secrets.change(id, Secrets.Actor.SERVER, agentIds = listOf(c.beta.id)) }
        val after = call("alpha", c.ownerKey, "GET", "/api/messages").bodyAsText().json()["secrets"]!!.jsonObject[message]!!.jsonArray.single().jsonObject
        assertEquals(false, after["available"]!!.jsonPrimitive.content.toBoolean())
        val gone = call("alpha", c.ownerKey, "POST", reveal, "{}")
        assertEquals(410, gone.status.value)
        assertTrue(gone.bodyAsText().contains("no longer available"))

        // Deleted, the same.
        Tenant.with("alpha") { c.app.secrets.delete(id, Secrets.Actor.SERVER) }
        assertEquals(410, call("alpha", c.ownerKey, "POST", reveal, "{}").status.value)
        val deleted = call("alpha", c.ownerKey, "GET", "/api/messages").bodyAsText().json()["secrets"]!!.jsonObject[message]!!.jsonArray.single().jsonObject
        assertEquals(false, deleted["available"]!!.jsonPrimitive.content.toBoolean())
    }
}
