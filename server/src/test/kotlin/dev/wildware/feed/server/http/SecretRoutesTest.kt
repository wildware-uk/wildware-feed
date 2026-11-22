package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRoutesTest {
    private fun config(key: String?) = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "https://feed.example.com", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "https://feed.example.com",
        secretsKeys = key?.let { Config.SecretsKeys(it) },
    )

    private val value = "sk-test-NOT-REAL-route-77aa"

    private class Ctx(val app: App, val key: String, val agent: Agents.Agent)

    private fun withApp(secretsKey: String? = "k".repeat(40), block: suspend ApplicationTestBuilder.(Ctx) -> Unit) {
        val app = App(config(secretsKey), Database.memory())
        val (_, key) = app.devices.mint("Phone")
        val (agent, _) = app.agents.mint("alpha")
        testApplication {
            application { feed(app) }
            block(Ctx(app, key, agent))
        }
        app.close()
    }

    private fun String.json() = Wire.json.parseToJsonElement(this).jsonObject
    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.owner(method: String, path: String, key: String?, body: String? = null): HttpResponse {
        val setup: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            if (key != null) header("Authorization", "Bearer $key")
            if (body != null) { header("Content-Type", "application/json"); setBody(body) }
        }
        return when (method) {
            "GET" -> client.get(path, setup)
            "POST" -> client.post(path, setup)
            "PATCH" -> client.patch(path, setup)
            "DELETE" -> client.delete(path, setup)
            else -> error(method)
        }
    }

    /** A tool call's whole result, as the agent's transcript would hold it. */
    private suspend fun Ctx.tool(name: String, args: JsonObject = JsonObject(emptyMap())): JsonObject =
        app.tools.call(app, agent, buildJsonObject { put("name", name); put("arguments", args) }).json

    private fun JsonObject.data() = this["structuredContent"]!!.jsonObject

    @Test
    fun `every owner route needs the owner`() = withApp { c ->
        val id = c.app.secrets.create("K", value.toByteArray(), null, true, emptyList(), dev.wildware.feed.server.domain.Secrets.Actor.SERVER).id
        val routes = listOf(
            "GET" to "/api/secrets", "POST" to "/api/secrets", "GET" to "/api/secrets/audit",
            "PATCH" to "/api/secrets/$id", "DELETE" to "/api/secrets/$id", "POST" to "/api/secrets/$id/reveal",
            "POST" to "/api/requests/X/secret",
        )
        for ((method, path) in routes) {
            assertEquals(401, owner(method, path, null, "{}").status.value, "$method $path")
            // An agent's token is not the owner's key.
            val agentToken = c.app.agents.mint("other").second
            assertEquals(401, owner(method, path, agentToken, "{}").status.value, "$method $path with an agent token")
        }
        assertTrue(c.app.secrets.find(id) != null)
    }

    @Test
    fun `the owner adds a secret, the agent fetches it once by URL, and no tool result or log line holds it`() = withApp { c ->
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val (oldOut, oldErr) = System.out to System.err
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        val transcript = StringBuilder()
        try {
            val created = owner("POST", "/api/secrets", c.key, """{"name":"OPENAI_API_KEY","value":"$value","note":"tests","agentIds":["${c.agent.id}"]}""")
            assertEquals(201, created.status.value)
            val createdText = created.bodyAsText()
            assertFalse(createdText.contains(value))
            val id = createdText.json()["secret"]!!.jsonObject.s("id")

            val listed = owner("GET", "/api/secrets", c.key).bodyAsText()
            assertFalse(listed.contains(value))
            assertEquals("true", listed.json().s("enabled"))

            val list = c.tool("list_secrets").also { transcript.append(it) }
            assertEquals("OPENAI_API_KEY", list.data()["secrets"]!!.jsonArray.single().jsonObject.s("name"))

            val url = c.tool("secret_url", buildJsonObject { put("name", "OPENAI_API_KEY") }).also { transcript.append(it) }.data().s("url")
            val path = url.removePrefix("https://feed.example.com")

            // HEAD never spends it.
            assertEquals(405, client.head(path).status.value)
            val got = client.get(path)
            assertEquals(200, got.status.value)
            assertEquals("no-store", got.headers["Cache-Control"])
            assertEquals("application/octet-stream", got.headers["Content-Type"])
            assertContentEquals(value.toByteArray(), got.readRawBytes())
            assertEquals(403, client.get(path).status.value)
            assertEquals(403, client.get("/api/agent/secret/${"x".repeat(43)}").status.value)

            val revealed = owner("POST", "/api/secrets/$id/reveal", c.key, """{"purpose":"copy"}""").bodyAsText().json()
            assertEquals(value, revealed.s("value"))
            assertEquals("utf8", revealed.s("encoding"))

            val audit = owner("GET", "/api/secrets/audit?secret=$id", c.key).bodyAsText()
            assertFalse(audit.contains(value))
            val entries = audit.json()["entries"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("copied", "refused", "downloaded", "url_issued", "created"), entries.map { it.s("action") })
            assertEquals("Phone", entries.first()["actor"]!!.jsonObject.s("name"))
            assertEquals("alpha", entries[2]["actor"]!!.jsonObject.s("name"))
            assertTrue(entries[2].s("detail").startsWith("from "), "the audit says where it was fetched from")
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        assertFalse(transcript.contains(value), "a tool result carried the value")
        assertFalse(out.toString().contains(value) || err.toString().contains(value), "a log line carried the value")
    }

    @Test
    fun `an agent hands a secret over with curl -T, and the owner sees it`() = withApp { c ->
        val transcript = StringBuilder()
        val grant = c.tool("create_secret_upload", buildJsonObject { put("name", "DEPLOY_TOKEN"); put("note", "from deploy") })
            .also { transcript.append(it) }.data()
        val path = grant.s("upload_url").removePrefix("https://feed.example.com")
        assertEquals("65536", grant.s("max_bytes"))

        // Empty and too large leave it unspent; no Content-Type is fine (curl -T sends none).
        assertEquals(400, client.put(path).status.value)
        assertEquals(413, client.put(path) { setBody(ByteArray(65537)) }.status.value)
        val stored = client.put(path) { setBody(value.toByteArray()) }
        assertEquals(201, stored.status.value)
        val reply = stored.bodyAsText().also { transcript.append(it) }.json()
        assertEquals(listOf("DEPLOY_TOKEN", "true"), listOf(reply.s("name"), reply.s("saved")))
        assertEquals(403, client.put(path) { setBody(value.toByteArray()) }.status.value)

        // POST works too (curl --data-binary @file).
        val again = c.tool("create_secret_upload", buildJsonObject { put("name", "DEPLOY_TOKEN") }).data().s("upload_url")
        assertEquals(201, client.post(again.removePrefix("https://feed.example.com")) {
            header("Content-Type", "application/x-www-form-urlencoded")
            setBody("second")
        }.status.value)

        val row = owner("GET", "/api/secrets", c.key).bodyAsText().json()["secrets"]!!.jsonArray.single().jsonObject
        assertEquals("agent:${c.agent.id}", row.s("createdBy"))
        assertEquals("false", row.s("allAgents"))
        val revealed = owner("POST", "/api/secrets/${row.s("id")}/reveal", c.key, "{}").bodyAsText().json()
        assertEquals("second", revealed.s("value"))
        assertFalse(transcript.contains(value))
    }

    @Test
    fun `request_secret is answered in the masked route and the agent only learns the name`() = withApp { c ->
        val transcript = StringBuilder()
        val asked = c.tool("request_secret", buildJsonObject {
            put("name", "STRIPE_TEST_KEY"); put("question", "The Stripe test key, please"); put("timeout_s", 60)
        }).also { transcript.append(it) }.data()
        assertEquals("pending", asked.s("state"))
        val requestId = asked.s("request_id")

        assertEquals(400, owner("POST", "/api/requests/$requestId/answer", c.key, """{"value":"$value"}""").status.value)
        val answered = owner("POST", "/api/requests/$requestId/secret", c.key, """{"value":"$value"}""")
        assertEquals(200, answered.status.value)
        assertFalse(answered.bodyAsText().contains(value))

        val done = c.tool("await_request", buildJsonObject { put("request_id", requestId) }).also { transcript.append(it) }
        assertEquals("answered", done.data().s("state"))
        assertEquals("STRIPE_TEST_KEY", done.data()["response"]!!.jsonObject.s("value"))
        assertTrue(transcript.contains("secret_url"))

        val url = c.tool("secret_url", buildJsonObject { put("name", "STRIPE_TEST_KEY") }).also { transcript.append(it) }.data().s("url")
        assertEquals(value, client.get(url.removePrefix("https://feed.example.com")).bodyAsText())
        assertFalse(transcript.contains(value))

        // request_input cannot ask for kind "secret": it is not in its schema.
        val sneaky = c.tool("request_input", buildJsonObject { put("kind", "secret"); put("question", "?") })
        assertEquals("true", sneaky.s("isError"))
    }

    @Test
    fun `request_secret asks for several secrets in one card and the agent hears which names were saved`() = withApp { c ->
        val transcript = StringBuilder()
        val first = "AKIA-NOT-REAL-route-0001"
        val second = "not-real-route-secret-0002"
        fun field(name: String, optional: Boolean = false) = buildJsonObject { put("name", name); put("label", "The $name"); if (optional) put("optional", true) }
        val asked = c.tool("request_secret", buildJsonObject {
            put("question", "The AWS keys for the deploy, please")
            put("fields", kotlinx.serialization.json.JsonArray(listOf(field("AWS_ACCESS_KEY_ID"), field("AWS_SECRET_ACCESS_KEY"), field("AWS_SESSION_TOKEN", optional = true))))
            put("timeout_s", 60)
        }).also { transcript.append(it) }.data()
        assertEquals("pending", asked.s("state"))
        val requestId = asked.s("request_id")
        val config = c.app.requests.find(requestId)!!.config!!
        assertEquals("AWS_ACCESS_KEY_ID", config.s("secretName"))
        assertEquals(3, config["secretFields"]!!.jsonArray.size)

        // One value can't answer a card with several boxes, and a missing required one saves nothing.
        assertEquals(400, owner("POST", "/api/requests/$requestId/secret", c.key, """{"value":"$first"}""").status.value)
        assertEquals(400, owner("POST", "/api/requests/$requestId/secret", c.key, """{"values":{"AWS_ACCESS_KEY_ID":"$first"}}""").status.value)
        assertTrue(c.app.secrets.list().isEmpty())

        val answered = owner("POST", "/api/requests/$requestId/secret", c.key, """{"values":{"AWS_ACCESS_KEY_ID":"$first","AWS_SECRET_ACCESS_KEY":"$second","AWS_SESSION_TOKEN":""}}""")
        assertEquals(200, answered.status.value, answered.bodyAsText())
        val body = answered.bodyAsText()
        assertFalse(body.contains(first) || body.contains(second))
        assertEquals(listOf("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY"), body.json()["secrets"]!!.jsonArray.map { it.jsonObject.s("name") })

        val done = c.tool("await_request", buildJsonObject { put("request_id", requestId) }).also { transcript.append(it) }
        assertEquals("answered", done.data().s("state"))
        val response = done.data()["response"]!!.jsonObject
        assertEquals(listOf("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY"), response["value"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("AWS_SESSION_TOKEN"), response["skipped"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(transcript.contains("saved 2 secret(s)"), transcript.toString())

        val url = c.tool("secret_url", buildJsonObject { put("name", "AWS_SECRET_ACCESS_KEY") }).also { transcript.append(it) }.data().s("url")
        assertEquals(second, client.get(url.removePrefix("https://feed.example.com")).bodyAsText())
        assertFalse(transcript.contains(first) || transcript.contains(second))
    }

    @Test
    fun `request_secret takes name or fields and checks the fields`() = withApp { c ->
        fun list(vararg names: String) = kotlinx.serialization.json.JsonArray(names.map { n -> buildJsonObject { put("name", n) } })
        suspend fun failed(args: JsonObject) = c.tool("request_secret", args)["isError"]?.jsonPrimitive?.content == "true"
        assertTrue(failed(buildJsonObject { put("question", "?") }), "neither")
        assertTrue(failed(buildJsonObject { put("question", "?"); put("name", "A"); put("fields", list("B")) }), "both")
        assertTrue(failed(buildJsonObject { put("question", "?"); put("fields", list()) }), "none listed")
        assertTrue(failed(buildJsonObject { put("question", "?"); put("fields", list(*Array(11) { "K$it" })) }), "eleven")
        assertTrue(failed(buildJsonObject { put("question", "?"); put("fields", list("A", "A")) }), "twice")
        assertTrue(failed(buildJsonObject { put("question", "?"); put("fields", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject { put("label", "no name") }))) }), "no name")
        assertFalse(failed(buildJsonObject { put("question", "?"); put("fields", list(*Array(10) { "K$it" })) }), "ten")
        assertFalse(failed(buildJsonObject { put("question", "?"); put("name", "OLD_STYLE") }), "the old single name")
    }

    @Test
    fun `a download link can't upload, an upload link can't download, and links are rate limited`() = withApp { c ->
        c.app.secrets.create("K", value.toByteArray(), null, true, emptyList(), dev.wildware.feed.server.domain.Secrets.Actor.SERVER)
        val read = c.tool("secret_url", buildJsonObject { put("name", "K") }).data().s("url").removePrefix("https://feed.example.com")
        val write = c.tool("create_secret_upload", buildJsonObject { put("name", "NEW") }).data().s("upload_url").removePrefix("https://feed.example.com")
        assertEquals(403, client.put(read) { setBody("poison") }.status.value)
        assertEquals(403, client.get(write).status.value)
        // Neither attempt spent the other kind.
        assertEquals(value, client.get(read).bodyAsText())
        assertEquals(201, client.put(write) { setBody("fresh") }.status.value)

        var last = 0
        repeat(70) { last = client.get("/api/agent/secret/${"x".repeat(43)}").status.value }
        assertEquals(429, last)
    }

    @Test
    fun `a reveal from the web app's cookie names its device in the audit`() = withApp { c ->
        val id = owner("POST", "/api/secrets", c.key, """{"name":"K","value":"$value","allAgents":true}""").bodyAsText().json()["secret"]!!.jsonObject.s("id")
        val revealed = client.post("/api/secrets/$id/reveal") {
            header("Cookie", "${OwnerCookie.name}=${c.key}")
            header("Sec-Fetch-Site", "same-origin")
            header("Content-Type", "application/json")
            setBody("""{"purpose":"copy"}""")
        }
        assertEquals(200, revealed.status.value)
        val entries = owner("GET", "/api/secrets/audit?secret=$id", c.key).bodyAsText().json()["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals("copied", entries.first().s("action"))
        assertEquals("Phone", entries.first()["actor"]!!.jsonObject.s("name"))
    }

    @Test
    fun `the owner pauses, rescopes and deletes`() = withApp { c ->
        val id = owner("POST", "/api/secrets", c.key, """{"name":"K","value":"$value","allAgents":true}""").bodyAsText().json()["secret"]!!.jsonObject.s("id")
        assertEquals(200, owner("PATCH", "/api/secrets/$id", c.key, """{"paused":true}""").status.value)
        assertEquals("conflict", c.tool("secret_url", buildJsonObject { put("name", "K") }).data().s("error"))
        assertEquals(200, owner("PATCH", "/api/secrets/$id", c.key, """{"paused":false,"allAgents":false,"agentIds":[]}""").status.value)
        assertEquals("not_found", c.tool("secret_url", buildJsonObject { put("name", "K") }).data().s("error"))
        assertEquals(400, owner("PATCH", "/api/secrets/$id", c.key, "{}").status.value)
        assertEquals(200, owner("DELETE", "/api/secrets/$id", c.key).status.value)
        assertEquals(404, owner("DELETE", "/api/secrets/$id", c.key).status.value)
        assertEquals(404, owner("POST", "/api/secrets/$id/reveal", c.key, "{}").status.value)
    }

    @Test
    fun `without SECRETS_KEY the routes and tools say it is off`() = withApp(secretsKey = null) { c ->
        assertEquals("false", owner("GET", "/api/secrets", c.key).bodyAsText().json().s("enabled"))
        val refused = owner("POST", "/api/secrets", c.key, """{"name":"K","value":"$value"}""")
        assertEquals(503, refused.status.value)
        assertEquals("secrets_disabled", refused.bodyAsText().json().s("error"))
        assertEquals("secrets_disabled", c.tool("secret_url", buildJsonObject { put("name", "K") }).data().s("error"))
        assertEquals("secrets_disabled", c.tool("request_secret", buildJsonObject { put("name", "K"); put("question", "?") }).data().s("error"))
        assertEquals("false", c.tool("list_secrets").data().s("enabled"))
    }
}
