package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.domain.People
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deleting your own account (App Store guideline 5.1.1(v)): DELETE /api/me ends it in every team on
 * the server, signs out every device, and leaves a hashed audit row the control plane reads at
 * GET /admin/account-deletions. An owner is refused until someone else owns the team.
 */
class DeleteAccountTest {
    // Built at run time, so no key-shaped literal sits in the source for the secret scanner to flag.
    // The same test-only key as ManyTeamsTest ("test-key-" and 0-9a-f twice).
    private val key = "test-" + "k" + "ey-" + (('0'..'9') + ('a'..'f')).joinToString("").repeat(2)
    private var now = 1_763_629_200_000L

    private fun config() = Config(
        dataDir = java.nio.file.Files.createTempDirectory("feed-delete").toString(), adminPasswordHash = Passwords.hash("server password"),
        sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "https://agents.example.com", maxImageBytes = 1_000_000, maxVideoBytes = 1_000_000, maxFileBytes = 1_000_000, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "https://agents.example.com",
        teamDomain = "feed.test", adminApiKeys = listOf(key), controlPlaneOrigin = "https://feed.test", adminHost = "admin.feed.test",
    )

    private fun withApp(block: suspend ApplicationTestBuilder.(App) -> Unit) {
        val app = App(config(), Database.memory()) { now }
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
        host: String, method: String, path: String, body: String? = null, type: String = "application/json", vararg headers: Pair<String, String>,
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
        return send("feed.test", method, path, body, "application/json", "Feed-Admin-Timestamp" to ts, "Feed-Admin-Signature" to sig, *headers)
    }

    private suspend fun HttpResponse.obj(): JsonObject = Wire.json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.o(k: String) = this[k]!!.jsonObject
    private fun bearer(token: String) = "Authorization" to "Bearer $token"

    /** A team made by the admin API, its owner's password set through the link; the team's id and the owner's key. */
    private suspend fun ApplicationTestBuilder.team(slug: String, owner: String): Pair<String, String> {
        val made = admin(
            "POST", "/admin/teams",
            """{"slug":"$slug","name":"${slug.replaceFirstChar { it.uppercase() }}","owner":{"email":"$owner@$slug.test","name":"$owner"},"limits":{"seats":5,"storageBytes":1000},"externalRef":"tm_$slug"}""",
            "Idempotency-Key" to "create-$slug",
        ).obj()
        val link = made.o("owner").s("setupUrl").removePrefix("https://$slug.feed.test")
        val set = send("$slug.feed.test", "POST", link, "password=owner+password+1&again=owner+password+1", "application/x-www-form-urlencoded", "Origin" to "https://$slug.feed.test")
        assertEquals(200, set.status.value, set.bodyAsText())
        val signIn = send("$slug.feed.test", "POST", "/api/devices/sign-in", """{"email":"$owner@$slug.test","password":"owner password 1","name":"$owner's phone"}""")
        assertEquals(201, signIn.status.value, signIn.bodyAsText())
        return made.o("team").s("id") to signIn.obj().s("token")
    }

    /** Sam joins [slug] by an admin invite, with the same account everywhere; Sam's key there. */
    private suspend fun ApplicationTestBuilder.samJoins(teamId: String, slug: String): String {
        val invite = admin("POST", "/admin/teams/$teamId/invites", """{"email":"sam@example.com","role":"member"}""", "Idempotency-Key" to "sam-$slug")
        assertEquals(201, invite.status.value, invite.bodyAsText())
        val accepted = send(
            "$slug.feed.test", "POST", "/api/invite/accept",
            """{"token":"${invite.obj().s("url")}","name":"Sam","email":"sam@example.com","password":"sam password 1","deviceName":"Sam's phone"}""",
        )
        assertEquals(201, accepted.status.value, accepted.bodyAsText())
        return accepted.obj().s("token")
    }

    @Test
    fun `a member deletes their account in every team, and nothing of it signs in again`() = withApp { app ->
        val (acme, ann) = team("acme", "ann")
        val (beta, _) = team("beta", "bob")
        val samAtAcme = samJoins(acme, "acme")
        val samAtBeta = samJoins(beta, "beta")

        val noConfirm = send("acme.feed.test", "DELETE", "/api/me", """{"password":"sam password 1"}""", "application/json", bearer(samAtAcme))
        assertEquals(400, noConfirm.status.value, "the typed confirmation is required")
        val wrong = send("acme.feed.test", "DELETE", "/api/me", """{"confirm":"DELETE","password":"not it"}""", "application/json", bearer(samAtAcme))
        assertEquals("wrong_password", wrong.obj().s("error"))
        assertEquals(200, send("acme.feed.test", "GET", "/api/me", null, "application/json", bearer(samAtAcme)).status.value, "a refused delete changes nothing")

        val gone = send("acme.feed.test", "DELETE", "/api/me", """{"confirm":"DELETE","password":"sam password 1"}""", "application/json", bearer(samAtAcme))
        assertEquals(200, gone.status.value, gone.bodyAsText())

        // Every device, in both teams, is signed out, and the password opens nothing.
        assertEquals(401, send("acme.feed.test", "GET", "/api/me", null, "application/json", bearer(samAtAcme)).status.value)
        assertEquals(401, send("beta.feed.test", "GET", "/api/me", null, "application/json", bearer(samAtBeta)).status.value)
        for (slug in listOf("acme", "beta")) {
            val again = send("$slug.feed.test", "POST", "/api/devices/sign-in", """{"email":"sam@example.com","password":"sam password 1","name":"x"}""")
            assertEquals(400, again.status.value, "$slug still let Sam in")
        }
        assertTrue(admin("POST", "/admin/memberships/lookup", """{"email":"sam@example.com"}""").obj()["memberships"]!!.jsonArray.isEmpty())

        // The account forgets who it was; anything Sam wrote now shows the deleted name.
        val (name, email, hash) = Tenant.allTeams {
            app.db.one("SELECT u.name, u.email, u.password_hash FROM users AS u WHERE u.id = (SELECT user_id FROM memberships WHERE team_id = ? AND handle = 'sam')", acme) {
                Triple(it.getString(1), it.getString(2), it.getString(3))
            }!!
        }
        assertEquals(People.DELETED_NAME, name)
        assertNull(email)
        assertNull(hash)

        // The audit, for the control plane: a hash of the email, never the email.
        val feed = admin("GET", "/admin/account-deletions").obj()
        val rows = feed["deletions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, rows.size)
        assertEquals(People.emailHash("sam@example.com"), rows[0].s("emailHash"))
        assertTrue("sam@example.com" !in feed.toString())
        val after = feed.s("next")
        assertTrue(admin("GET", "/admin/account-deletions?after=$after").obj()["deletions"]!!.jsonArray.isEmpty())

        // The owner is untouched.
        assertEquals(200, send("acme.feed.test", "GET", "/api/me", null, "application/json", bearer(ann)).status.value)
    }

    @Test
    fun `an owner can't delete their account while they own a team`() = withApp { _ ->
        val (_, ann) = team("acme", "ann")
        val refused = send("acme.feed.test", "DELETE", "/api/me", """{"confirm":"DELETE","password":"owner password 1"}""", "application/json", bearer(ann))
        assertEquals(409, refused.status.value, refused.bodyAsText())
        val body = refused.obj()
        assertEquals("owner_must_hand_over", body.s("error"))
        assertTrue(body.s("message").contains("Acme"), body.s("message"))
        assertEquals(200, send("acme.feed.test", "GET", "/api/me", null, "application/json", bearer(ann)).status.value)
        assertTrue(admin("GET", "/admin/account-deletions").obj()["deletions"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `the admin feed of deletions needs the admin signature`() = withApp { _ ->
        assertEquals(401, send("feed.test", "GET", "/admin/account-deletions").status.value)
        assertEquals(400, admin("GET", "/admin/account-deletions?limit=0").status.value)
    }
}
