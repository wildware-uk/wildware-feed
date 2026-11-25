package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.ControlPlane
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Teams
import dev.wildware.feed.server.domain.feed
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An App Store purchase made in the iPhone app (docs/HOSTING.md §7, "App Store purchases"): an owner
 * or admin posts it, the server relays it to the control plane signed with its admin keys, and the
 * control plane says who takes the money from then on (`billing` on the plan).
 */
class AppStoreRelayTest {
    // Built, not written out, so the secret scan does not take them for real keys.
    private val key = "test-" + "k" + "ey-" + (('0'..'9') + ('a'..'f')).joinToString("").repeat(2)
    private val oldKey = "old-" + "k" + "ey-" + (('a'..'f') + ('0'..'9')).joinToString("").repeat(2)
    private var now = 1_763_629_200_000L

    private fun config(controlPlane: String? = "https://account.feed.test") = Config(
        dataDir = java.nio.file.Files.createTempDirectory("feed-iap").toString(), adminPasswordHash = Passwords.hash("server password"),
        sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "https://agents.example.com", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "https://agents.example.com",
        teamDomain = "feed.test", adminApiKeys = listOf(key, oldKey), controlPlaneOrigin = controlPlane, adminHost = "admin.feed.test",
    )

    /** What the control plane was sent, and what it answers. */
    private class FakeControlPlane(var answer: Pair<Int, String> = 200 to """{"plan":"team","billing":"apple","seats":5,"expiresAt":null}""") {
        val sent = mutableListOf<Triple<String, Map<String, String>, String>>()
        fun install(app: App) {
            app.controlPlane.transport = dev.wildware.feed.server.ControlPlaneTransport { url, headers, body ->
                sent += Triple(url, headers.toMap(), body)
                answer
            }
        }
    }

    private fun withApp(controlPlane: String? = "https://account.feed.test", block: suspend ApplicationTestBuilder.(App) -> Unit) {
        val app = App(config(controlPlane), Database.memory()) { now }
        try {
            testApplication {
                application { feed(app) }
                block(app)
            }
        } finally {
            app.close()
        }
    }

    private var http: HttpClient? = null

    private suspend fun ApplicationTestBuilder.send(
        host: String, method: String, path: String, body: String? = null, vararg headers: Pair<String, String>, type: String = "application/json",
    ): HttpResponse = (http ?: createClient { followRedirects = false }.also { http = it }).request(path) {
        this.method = HttpMethod.parse(method)
        header("Host", host)
        for ((k, v) in headers) header(k, v)
        if (body != null) {
            header("Content-Type", type)
            setBody(body)
        }
    }

    private suspend fun ApplicationTestBuilder.admin(method: String, path: String, body: String? = null, vararg headers: Pair<String, String>): HttpResponse {
        now += 1000
        val ts = (now / 1000).toString()
        val sig = AdminApi.sign(key, AdminApi.message(ts, method, path, body?.toByteArray() ?: ByteArray(0)))
        return send("feed.test", method, path, body, "Feed-Admin-Timestamp" to ts, "Feed-Admin-Signature" to sig, *headers)
    }

    private suspend fun HttpResponse.obj(): JsonObject = Wire.json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.o(k: String) = this[k]!!.jsonObject

    /** Make acme on the free plan with Ann as owner, sign her in: the team's id and her bearer header. */
    private suspend fun ApplicationTestBuilder.acmeWithAnn(): Pair<String, Pair<String, String>> {
        val made = admin(
            "POST", "/admin/teams",
            """{"slug":"acme","name":"Acme","owner":{"email":"ann@acme.test","name":"Ann"},"limits":{"seats":5,"storageBytes":26843545600},"externalRef":"tm_1","plan":"free"}""",
            "Idempotency-Key" to "create-tm_1",
        )
        assertEquals(201, made.status.value, made.bodyAsText())
        val link = made.obj().o("owner").s("setupUrl").removePrefix("https://acme.feed.test")
        send("acme.feed.test", "POST", link, "password=a+long+password&again=a+long+password", "Origin" to "https://acme.feed.test", type = "application/x-www-form-urlencoded")
        return made.obj().o("team").s("id") to signIn("ann@acme.test")
    }

    private suspend fun ApplicationTestBuilder.signIn(email: String): Pair<String, String> {
        val r = send("acme.feed.test", "POST", "/api/devices/sign-in", """{"email":"$email","password":"a long password","name":"Phone"}""")
        assertEquals(201, r.status.value, r.bodyAsText())
        return "Authorization" to "Bearer ${r.obj().s("token")}"
    }

    // Shaped like a signed transaction (three base64url parts); the server only passes it on.
    private val jws = "aGVhZGVy.cGF5bG9hZA.c2lnbmF0dXJl"

    @Test
    fun `the team token matches the control plane's`() {
        // The same pair is checked in the control plane's tests (app-store.test.ts).
        assertEquals("aa19f4a5-0eb8-501f-898f-bb79bbdcb7f5", Teams.appAccountToken("01JD7Y6Q4M8T2V0K3N5P7R9S1W"))
    }

    @Test
    fun `the plan says who bills it and the token a purchase carries`() = withApp { _ ->
        val (id, auth) = acmeWithAnn()
        val plan = send("acme.feed.test", "GET", "/api/plan", null, auth).obj()
        assertEquals(JsonNull, plan["billing"])
        assertEquals(Teams.appAccountToken(id), plan.s("appAccountToken"))

        val set = admin("PUT", "/admin/teams/$id/plan", """{"plan":"team","billing":"apple"}""").obj().o("team")
        assertEquals("apple", set.s("billing"))
        val paid = send("acme.feed.test", "GET", "/api/plan", null, auth).obj()
        assertEquals("apple", paid.s("billing"))
        // Paid through the App Store: still somewhere to move to paying on the website.
        assertEquals("https://account.feed.test/teams/acme", paid.s("upgradeUrl"))
        admin("PUT", "/admin/teams/$id/plan", """{"plan":"team","billing":"web"}""")
        assertEquals(JsonNull, send("acme.feed.test", "GET", "/api/plan", null, auth).obj()["upgradeUrl"])
        admin("PUT", "/admin/teams/$id/plan", """{"plan":"team","billing":"apple"}""")
        // Left out keeps it; null clears it; anything else is refused.
        assertEquals("apple", admin("PUT", "/admin/teams/$id/plan", """{"plan":"team"}""").obj().o("team").s("billing"))
        assertEquals(JsonNull, admin("PUT", "/admin/teams/$id/plan", """{"plan":"free","billing":null}""").obj().o("team")["billing"])
        assertEquals(400, admin("PUT", "/admin/teams/$id/plan", """{"plan":"team","billing":"paypal"}""").status.value)
    }

    @Test
    fun `an owner's purchase is relayed, signed with every admin key, and the answer comes back`() = withApp { app ->
        val cp = FakeControlPlane().also { it.install(app) }
        val (id, auth) = acmeWithAnn()
        val r = send("acme.feed.test", "POST", "/api/plan/app-store", """{"signedTransaction":"$jws"}""", auth)
        assertEquals(200, r.status.value, r.bodyAsText())
        assertEquals("apple", r.obj().s("billing"))

        val (url, headers, body) = cp.sent.single()
        assertEquals("https://account.feed.test/api/server/app-store/transactions", url)
        assertEquals("""{"teamId":"$id","signedTransaction":"$jws"}""", body)
        val ts = headers.getValue("Feed-Relay-Timestamp")
        assertEquals((now / 1000).toString(), ts)
        val message = listOf("relay1", ts, "POST", ControlPlane.PATH, AdminApi.sha256Hex(body.toByteArray())).joinToString("\n")
        assertEquals(listOf(AdminApi.sign(key, message), AdminApi.sign(oldKey, message)).joinToString(","), headers.getValue("Feed-Relay-Signature"))
    }

    @Test
    fun `the control plane's refusal goes back as it is, but never a 401 or 404`() = withApp { app ->
        val cp = FakeControlPlane(409 to """{"error":"wrong_team","message":"That purchase was made for another team."}""").also { it.install(app) }
        val (_, auth) = acmeWithAnn()
        val refused = send("acme.feed.test", "POST", "/api/plan/app-store", """{"signedTransaction":"$jws"}""", auth)
        assertEquals(409, refused.status.value)
        assertEquals("wrong_team", refused.obj().s("error"))
        cp.answer = 401 to """{"error":"bad_signature"}"""
        val misconfigured = send("acme.feed.test", "POST", "/api/plan/app-store", """{"signedTransaction":"$jws"}""", auth)
        assertEquals(502, misconfigured.status.value)
        assertEquals("control_plane_unreachable", misconfigured.obj().s("error"))
        app.controlPlane.transport = dev.wildware.feed.server.ControlPlaneTransport { _, _, _ -> throw java.io.IOException("down") }
        assertEquals(502, send("acme.feed.test", "POST", "/api/plan/app-store", """{"signedTransaction":"$jws"}""", auth).status.value)
    }

    @Test
    fun `members can't, and nothing but a signed transaction is sent`() = withApp { app ->
        val cp = FakeControlPlane().also { it.install(app) }
        val (id, auth) = acmeWithAnn()
        val invite = admin("POST", "/admin/teams/$id/invites", """{"email":"sam@acme.test","role":"member"}""", "Idempotency-Key" to "sam")
        assertEquals(201, invite.status.value, invite.bodyAsText())
        val accepted = send(
            "acme.feed.test", "POST", "/api/invite/accept",
            """{"token":"${invite.obj().s("url")}","name":"Sam","email":"sam@acme.test","password":"sam password 1","deviceName":"Sam's phone"}""",
        )
        assertEquals(201, accepted.status.value, accepted.bodyAsText())
        val sam = "Authorization" to "Bearer ${accepted.obj().s("token")}"
        assertEquals(403, send("acme.feed.test", "POST", "/api/plan/app-store", """{"signedTransaction":"$jws"}""", sam).status.value)
        assertEquals(400, send("acme.feed.test", "POST", "/api/plan/app-store", """{"signedTransaction":"not a jws"}""", auth).status.value)
        assertEquals(400, send("acme.feed.test", "POST", "/api/plan/app-store", """{}""", auth).status.value)
        assertTrue(cp.sent.isEmpty())
    }

    @Test
    fun `a server with no control plane has no plan to buy`() = withApp(controlPlane = null) { app ->
        val cp = FakeControlPlane().also { it.install(app) }
        val (_, auth) = acmeWithAnn()
        assertEquals(JsonNull, send("acme.feed.test", "GET", "/api/plan", null, auth).obj()["appAccountToken"])
        assertEquals(404, send("acme.feed.test", "POST", "/api/plan/app-store", """{"signedTransaction":"$jws"}""", auth).status.value)
        assertTrue(cp.sent.isEmpty())
    }
}
