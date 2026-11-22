package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.FeedbackReports
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.People
import dev.wildware.feed.server.domain.feed
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feedback and feature requests (migration 067): a person's is sent at once; an agent's waits for
 * an owner or an admin, who sends it (maybe edited) or discards it. Sent means handed to Sentry's
 * user feedback, here a list; with no SENTRY_DSN it is only saved. Nothing crosses teams.
 */
class FeedbackTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
        teamDomain = "feed.test", feedbackRecipient = "Wildware",
    )

    /** What would have gone to Sentry: the server's sender, swapped for a list. */
    private val outbox = mutableListOf<FeedbackReports.Outgoing>()

    private fun sending() {
        FeedbackReports.sender = { outbox += it; "event-${outbox.size}" }
    }

    @AfterTest
    fun noSender() {
        FeedbackReports.sender = null
    }

    private fun postgres(): Database {
        val url = System.getenv("PG_TEST_URL")
        org.junit.jupiter.api.Assumptions.assumeTrue(!url.isNullOrBlank(), "PG_TEST_URL not set")
        val schema = "t_" + java.util.UUID.randomUUID().toString().replace("-", "")
        Database.postgresConnection(url!!, null).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA \"$schema\"") } }
        return Database.openPostgres(url, schema = schema, onClose = {
            Database.postgresConnection(url, null).use { c -> c.createStatement().use { it.execute("DROP SCHEMA \"$schema\" CASCADE") } }
        })
    }

    private class Ctx(val app: App, val ownerKey: String, val adminKey: String, val memberKey: String, val bravoKey: String, val agent: Agents.Agent, val bravoAgent: Agents.Agent)

    private fun withApp(db: Database, block: suspend ApplicationTestBuilder.(Ctx) -> Unit) {
        val app = App(config, db)
        try {
            Tenant.allTeams {
                for (t in listOf("alpha", "bravo")) app.db.update("INSERT INTO teams (id, slug, name, created_at) VALUES (?, ?, ?, 1)", t, t, "Team $t")
            }
            val alpha = Tenant.with("alpha") {
                val owner = app.people.createOwner("owner@alpha.test", "Olive", "a long password here")
                fun join(role: People.Role, name: String): String {
                    val (_, token) = app.people.invite(owner, role, null)
                    val person = app.people.accept(token, name, "$name@alpha.test", "a long password")
                    return app.devices.mint("$name's phone", person.id).second
                }
                app.feed.projects.create("Web", "web", null)
                listOf(app.devices.mint("Olive's phone", owner.id).second, join(People.Role.ADMIN, "Ada"), join(People.Role.MEMBER, "Sam")) to app.agents.mint("scout").first
            }
            val bravo = Tenant.with("bravo") {
                val owner = app.people.createOwner("owner@bravo.test", "Bea", "another long password")
                app.devices.mint("Bea's phone", owner.id).second to app.agents.mint("ranger").first
            }
            testApplication {
                application { feed(app) }
                block(Ctx(app, alpha.first[0], alpha.first[1], alpha.first[2], bravo.first, alpha.second, bravo.second))
            }
        } finally {
            app.close()
        }
    }

    private suspend fun ApplicationTestBuilder.call(team: String, key: String?, method: String, path: String, body: String? = null, vararg headers: Pair<String, String>): HttpResponse =
        client.request(path) {
            this.method = HttpMethod.parse(method)
            header("Host", if (team.isEmpty()) "feed.test" else "$team.feed.test")
            if (key != null) header("Authorization", "Bearer $key")
            for ((k, v) in headers) header(k, v)
            if (body != null) { header("Content-Type", "application/json"); setBody(body) }
        }

    private fun Ctx.tool(team: String, agent: Agents.Agent, name: String, args: JsonObject): JsonObject = Tenant.with(team) {
        kotlinx.coroutines.runBlocking { app.tools.call(app, agent, buildJsonObject { put("name", name); put("arguments", args) }).json }
    }

    /** A made-up key, put together here so secret scanners don't take the test for a leak. */
    private fun fakeKey(head: String, tail: String) = head + tail

    private fun String.json() = Wire.json.parseToJsonElement(this).jsonObject
    private fun JsonObject.data() = this["structuredContent"]!!.jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private suspend fun HttpResponse.obj() = bodyAsText().json()

    @Test
    fun `on SQLite feedback goes from person and agent to Sentry`() = withApp(Database.memory()) { sending(); flows(it) }

    @Test
    fun `on Postgres feedback goes from person and agent to Sentry`() = withApp(postgres()) { sending(); flows(it) }

    private suspend fun ApplicationTestBuilder.flows(c: Ctx) {
        // A person's goes at once, with their name, and their email only when they say so.
        val sent = call("alpha", c.memberKey, "POST", "/api/feedback", """{"kind":"feature_request","area":"voice","title":"Voice notes in threads","body":"I'd like to **reply** by voice.","client":"wildware-feed@1.0.19"}""")
        assertEquals(201, sent.status.value, sent.bodyAsText())
        val person = sent.obj()["feedback"]!!.jsonObject
        assertEquals("sent", person.s("status"))
        assertEquals("Sam", person["author"]!!.jsonObject.s("name"))
        val first = outbox.single()
        assertEquals(listOf("feature_request", "voice", "Voice notes in threads", "I'd like to **reply** by voice.", "Sam", "person", "wildware-feed@1.0.19"),
            listOf(first.kind, first.area, first.title, first.body, first.name, first.source, first.client))
        assertNull(first.email, "no email unless the person ticks it")
        assertEquals(16, first.team.length)
        assertTrue("alpha" !in first.team && "Team" !in first.team, "the team goes as a hash")
        assertEquals(Tenant.with("alpha") { c.app.feedback.find(person.s("id"))!!.sentryEventId }, "event-1")
        assertEquals(201, call("alpha", c.ownerKey, "POST", "/api/feedback", """{"kind":"feedback","title":"Reach me","body":"Write back.","includeEmail":true}""").status.value)
        assertEquals("owner@alpha.test", outbox.last().email)
        assertEquals(first.team, outbox.last().team, "one team, one hash")
        val sentByPeople = outbox.size

        // An agent's waits for an owner or an admin, as a question with the text to send. Nothing goes yet.
        val proposed = c.tool("alpha", c.agent, "send_feedback", buildJsonObject {
            put("kind", "feedback"); put("area", "agents"); put("title", "get_messages is slow to page"); put("body", "Paging takes three calls."); put("channel", "web")
        })
        assertTrue(proposed["isError"] == null, proposed.toString())
        val requestId = proposed.data().s("request_id")
        val feedbackId = proposed.data()["feedback"]!!.jsonObject.s("id")
        assertEquals("pending", proposed.data()["feedback"]!!.jsonObject.s("status"))
        assertEquals(sentByPeople, outbox.size, "a pending item is never sent")
        val request = Tenant.with("alpha") { c.app.requests.find(requestId)!! }
        assertEquals("form", request.kind)
        assertEquals(listOf("Send to Wildware", "Discard"), request.options)
        assertEquals("Paging takes three calls.", request.config!!["default"]!!.jsonPrimitive.content)

        // The same idea twice is refused.
        val again = c.tool("alpha", c.agent, "send_feedback", buildJsonObject { put("kind", "feedback"); put("title", "get_messages is slow to page"); put("body", "Again.") })
        assertEquals("conflict", again.data().s("error"))

        // The agent can't swap the buttons round.
        val swapped = c.tool("alpha", c.agent, "edit_request", buildJsonObject {
            put("request_id", requestId); put("options", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("Discard"), kotlinx.serialization.json.JsonPrimitive("Send to Wildware"))))
        })
        assertEquals("invalid_argument", swapped.data().s("error"))

        // A member can't send or dismiss it; an admin sends it, edited.
        val send = """{"value":{"action":"Send to Wildware","text":"Paging takes three calls; one would do."}}"""
        assertEquals(403, call("alpha", c.memberKey, "POST", "/api/requests/$requestId/answer", send).status.value)
        assertEquals(403, call("alpha", c.memberKey, "DELETE", "/api/requests/$requestId").status.value)
        val leaky = """{"value":{"action":"Send to Wildware","text":"my key is ${fakeKey("sk-", "live-abcdefghijklmnopqrstuvwxyz123456")}"}}"""
        assertEquals(400, call("alpha", c.adminKey, "POST", "/api/requests/$requestId/answer", leaky).status.value)
        val answered = call("alpha", c.adminKey, "POST", "/api/requests/$requestId/answer", send)
        assertEquals(200, answered.status.value, answered.bodyAsText())
        val agentItem = Tenant.with("alpha") { c.app.feedback.find(feedbackId)!! }
        assertEquals("sent", agentItem.status)
        assertEquals("Paging takes three calls; one would do.", agentItem.body)
        assertTrue(agentItem.approvedBy != null)
        val fromAgent = outbox.last()
        assertEquals(listOf("agent", "scout", "Paging takes three calls; one would do.", "agents"), listOf(fromAgent.source, fromAgent.name, fromAgent.body, fromAgent.area))
        assertNull(fromAgent.email)

        // Another, discarded on its card, and a third dismissed: neither is ever sent.
        val before = outbox.size
        val second = c.tool("alpha", c.agent, "send_feedback", buildJsonObject { put("kind", "feature_request"); put("title", "Dark mode for embeds"); put("body", "Embeds stay light.") })
        val secondId = second.data()["feedback"]!!.jsonObject.s("id")
        assertEquals(200, call("alpha", c.ownerKey, "POST", "/api/requests/${second.data().s("request_id")}/answer", """{"value":{"action":"Discard","text":"Embeds stay light."}}""").status.value)
        val third = c.tool("alpha", c.agent, "send_feedback", buildJsonObject { put("kind", "feedback"); put("title", "Too many tools"); put("body", "Hard to choose.") })
        val thirdId = third.data()["feedback"]!!.jsonObject.s("id")
        assertEquals(200, call("alpha", c.ownerKey, "DELETE", "/api/requests/${third.data().s("request_id")}").status.value)
        Tenant.with("alpha") {
            assertEquals("discarded", c.app.feedback.find(secondId)!!.status)
            assertEquals("discarded", c.app.feedback.find(thirdId)!!.status)
        }
        assertEquals(before, outbox.size, "a discarded item is never sent")

        // The team sees its own, with their status; the agent too.
        val listed = call("alpha", c.memberKey, "GET", "/api/feedback").obj()
        assertEquals("Wildware", listed.s("recipient"))
        assertEquals("true", listed.s("sends"))
        assertEquals(listOf(thirdId, secondId, feedbackId), listed["feedback"]!!.jsonArray.take(3).map { it.jsonObject.s("id") })
        val mine = c.tool("alpha", c.agent, "list_feedback", buildJsonObject { put("mine", true); put("status", "sent") }).data()["feedback"]!!.jsonArray
        assertEquals(listOf(feedbackId), mine.map { it.jsonObject.s("id") })

        // Bravo sees none of alpha's, and its agent can't approve or read them.
        assertEquals(0, call("bravo", c.bravoKey, "GET", "/api/feedback").obj()["feedback"]!!.jsonArray.size)
        assertEquals(0, (c.tool("bravo", c.bravoAgent, "list_feedback", buildJsonObject {}).data()["feedback"] as JsonArray).size)
        assertTrue(call("bravo", c.bravoKey, "POST", "/api/requests/$requestId/answer", send).status.value in setOf(401, 404, 409))
        // Nothing of alpha's names or text went in bravo's feed, nor any card about it in alpha's.
        assertTrue(outbox.none { "bravo" in it.toString() })
    }

    @Test
    fun `with no SENTRY_DSN nothing leaves the server and feedback is saved`() = withApp(Database.memory()) { c ->
        val saved = call("alpha", c.ownerKey, "POST", "/api/feedback", """{"kind":"feedback","title":"Kept here","body":"Words."}""")
        assertEquals(201, saved.status.value, saved.bodyAsText())
        assertEquals("saved", saved.obj()["feedback"]!!.jsonObject.s("status"))
        val listed = call("alpha", c.ownerKey, "GET", "/api/feedback").obj()
        assertEquals("false", listed.s("sends"))

        // An agent's approval card says "Save it", and saving keeps it here.
        val proposed = c.tool("alpha", c.agent, "send_feedback", buildJsonObject { put("kind", "feedback"); put("title", "Slow"); put("body", "It is slow.") })
        val requestId = proposed.data().s("request_id")
        assertEquals(listOf("Save it", "Discard"), Tenant.with("alpha") { c.app.requests.find(requestId)!!.options })
        assertEquals(200, call("alpha", c.ownerKey, "POST", "/api/requests/$requestId/answer", """{"value":{"action":"Save it","text":"It is slow."}}""").status.value)
        assertEquals("saved", Tenant.with("alpha") { c.app.feedback.find(proposed.data()["feedback"]!!.jsonObject.s("id"))!!.status })
        assertTrue(outbox.isEmpty())
    }

    @Test
    fun `feedback is checked and limited to twenty a day`() = withApp(Database.memory()) { c ->
        fun body(title: String, text: String = "Some words.") = """{"kind":"feedback","title":"$title","body":"$text"}"""
        assertEquals(400, call("alpha", c.ownerKey, "POST", "/api/feedback", body("x".repeat(121))).status.value)
        assertEquals(400, call("alpha", c.ownerKey, "POST", "/api/feedback", body("Big", "y".repeat(5001))).status.value)
        assertEquals(400, call("alpha", c.ownerKey, "POST", "/api/feedback", """{"kind":"praise","title":"Hi","body":"Hi"}""").status.value)
        assertEquals(400, call("alpha", c.ownerKey, "POST", "/api/feedback", """{"kind":"feedback","area":"moon","title":"Hi","body":"Hi"}""").status.value)
        assertEquals(400, call("alpha", c.ownerKey, "POST", "/api/feedback", body("Key", fakeKey("gh", "p_abcdefghijklmnopqrstuvwxyz0123456789"))).status.value)
        assertEquals(400, call("alpha", c.ownerKey, "POST", "/api/feedback", body("Token", fakeKey("Ab3dEf6hIj9lMn2p", "Qr5tUv8xYz1bCd4fGh7"))).status.value)
        assertEquals(404, call("alpha", c.ownerKey, "POST", "/api/feedback", """{"kind":"feedback","title":"Hi","body":"Hi","card":{"kind":"update","id":"01JAAAAAAAAAAAAAAAAAAAAAAA"}}""").status.value)
        val secret = c.tool("alpha", c.agent, "send_feedback", buildJsonObject { put("kind", "feedback"); put("title", "Key"); put("body", fakeKey("-----BEGIN RSA ", "PRIVATE KEY-----")) })
        assertEquals("invalid_argument", secret.data().s("error"))

        for (i in 1..20) assertEquals(201, call("alpha", c.ownerKey, "POST", "/api/feedback", body("Idea $i")).status.value)
        val over = call("alpha", c.ownerKey, "POST", "/api/feedback", body("Idea 21"))
        assertEquals(429, over.status.value, over.bodyAsText())
        // Someone else on the team still can; so can the agent, until its own twenty.
        assertEquals(201, call("alpha", c.memberKey, "POST", "/api/feedback", body("Idea from Sam")).status.value)
        for (i in 1..20) {
            val r = c.tool("alpha", c.agent, "send_feedback", buildJsonObject { put("kind", "feedback"); put("title", "Agent idea $i"); put("body", "Words.") })
            assertTrue(r["isError"] == null, r.toString())
        }
        assertEquals("rate_limited", c.tool("alpha", c.agent, "send_feedback", buildJsonObject { put("kind", "feedback"); put("title", "One more"); put("body", "Words.") }).data().s("error"))
    }
}
