package dev.wildware.feed.parity

import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import java.util.Base64
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec: server/spec/01-config-and-auth.md. The login rate limiter is in [RateLimitTest], which runs last. */
class AuthTest : ParityTest() {
    private val cookiePattern =
        Regex("^ad_session=[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}; Max-Age=2592000; Path=/; HttpOnly; Secure; SameSite=Lax$")

    @Test
    @Tag("tsOnly")
    fun `pages redirect to login and the API answers 401 without a session`() = parity {
        val root = call(HttpMethod.Get, "/")
        assertEquals(303, root.status.value)
        assertEquals("/login", root.headers[HttpHeaders.Location])

        val deep = call(HttpMethod.Get, "/projects/demo?x=1")
        assertEquals(303, deep.status.value)
        assertEquals("/login?redirectTo=%2Fprojects%2Fdemo%3Fx%3D1", deep.headers[HttpHeaders.Location])

        val api = call(HttpMethod.Get, "/api/summary")
        assertEquals(401, api.status.value)
        assertTrue(api.headers[HttpHeaders.ContentType]!!.startsWith("application/json"))
        assertEquals("""{"error":"unauthenticated"}""", api.bodyAsText())

        assertNotEquals(200, call(HttpMethod.Get, "/api/summary/").status.value, "a trailing slash is no bypass")
        assertEquals(200, call(HttpMethod.Get, "/login").status.value, "the login page is the health check")
    }

    @Test
    fun `the API answers 401 to a stranger, trailing slash or not`() = parity {
        val api = call(HttpMethod.Get, "/api/summary")
        assertEquals(401, api.status.value)
        assertTrue(api.headers[HttpHeaders.ContentType]!!.startsWith("application/json"))
        assertEquals("""{"error":"unauthenticated"}""", api.bodyAsText())
        assertNotEquals(200, call(HttpMethod.Get, "/api/summary/").status.value, "a trailing slash is no bypass")
        assertEquals(401, call(HttpMethod.Get, "/api/summary") { bearer("A".repeat(43)) }.status.value, "an unknown key")
        assertEquals(401, call(HttpMethod.Get, "/api/summary") { bearer(alpha.token) }.status.value, "an agent token is not a device key")
    }

    @Test
    fun `an unknown API path answers JSON, never the login page`() = parity {
        val anonymous = call(HttpMethod.Get, "/api/does-not-exist")
        assertEquals(401, anonymous.status.value)
        assertEquals("""{"error":"unauthenticated"}""", anonymous.bodyAsText())
        val signedIn = owner.get("/api/does-not-exist")
        assertEquals(404, signedIn.status.value)
        assertEquals("not_found", signedIn.obj()["error"].s)
        assertEquals("no such API route: /api/does-not-exist", signedIn.obj()["message"].s)
    }

    @Test
    @Tag("tsOnly")
    fun `a wrong password is refused and a right one sets the session cookie`() = parity {
        val wrongHtml = Owner.loginForm("wrong-password")
        assertEquals(400, wrongHtml.status.value)
        assertContains(wrongHtml.bodyAsText(), "That password is not right.")

        val wrongJson = Owner.loginForm("wrong-password", accept = "application/json")
        assertEquals(200, wrongJson.status.value)
        assertEquals(
            """{"type":"failure","status":400,"data":"[{\"error\":1},\"That password is not right.\"]"}""",
            wrongJson.bodyAsText(),
        )

        val right = Owner.loginForm(Parity.adminPassword)
        assertEquals(303, right.status.value)
        assertEquals("/", right.headers[HttpHeaders.Location])
        val setCookie = right.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("ad_session=") }
        assertTrue(cookiePattern.matches(setCookie), "cookie shape: $setCookie")

        // The payload is base64url JSON with issuedAt/expiresAt in ms, exactly 30 days apart.
        val payload = setCookie.removePrefix("ad_session=").substringBefore('.')
        val decoded = Parity.json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload)))
        assertEquals(setOf("issuedAt", "expiresAt"), decoded.o.keys)
        assertEquals(2_592_000_000L, decoded["expiresAt"].long - decoded["issuedAt"].long)

        val signedIn = Owner(setCookie.substringBefore(';'))
        assertEquals(200, signedIn.get("/api/summary").status.value)
        val loginAgain = signedIn.get("/login")
        assertEquals(303, loginAgain.status.value)
        assertEquals("/", loginAgain.headers[HttpHeaders.Location])
    }

    @Test
    @Tag("tsOnly")
    fun `login only redirects to safe local paths`() = parity {
        suspend fun landing(redirectTo: String): String? {
            val response = call(HttpMethod.Post, "/login?redirectTo=" + java.net.URLEncoder.encode(redirectTo, "UTF-8")) {
                header(HttpHeaders.Origin, Parity.baseUrl)
                header(HttpHeaders.Accept, "text/html")
                setFormBody("password" to Parity.adminPassword)
            }
            assertEquals(303, response.status.value)
            return response.headers[HttpHeaders.Location]
        }
        assertEquals("/", landing("//evil.com"))
        assertEquals("/projects/a", landing("/projects/a"))
        assertEquals("/", landing("/mcp"))
        assertEquals("/", landing("/logout"))
    }

    @Test
    @Tag("tsOnly")
    fun `a form post without a matching Origin is refused before anything runs`() = parity {
        val html = call(HttpMethod.Post, "/login") {
            header(HttpHeaders.Accept, "text/html")
            setFormBody("password" to "x")
        }
        assertEquals(403, html.status.value)
        assertContains(html.bodyAsText(), "Cross-site POST form submissions are forbidden")

        val json = call(HttpMethod.Post, "/login") {
            header(HttpHeaders.Accept, "application/json")
            setFormBody("password" to "x")
        }
        assertEquals(403, json.status.value)
        assertEquals("""{"message":"Cross-site POST form submissions are forbidden"}""", json.bodyAsText())
    }

    @Test
    @Tag("tsOnly")
    fun `a tampered or expired cookie is not a session`() = parity {
        val expired = Owner("ad_session=eyJpc3N1ZWRBdCI6MTcwMDAwMDAwMDAwMCwiZXhwaXJlc0F0IjoxNzAyNTkyMDAwMDAwfQ.VaTNC-sXCNyk_rloaqTvm5YrV47-gvu8B6NCkd4aGj4")
        assertEquals(401, expired.get("/api/summary").status.value)

        val good = Owner.signInWithCookie().cookie!!
        val last = good.last()
        val flipped = Owner(good.dropLast(1) + (if (last == 'A') 'B' else 'A'))
        assertEquals(401, flipped.get("/api/summary").status.value)
    }

    @Test
    @Tag("tsOnly")
    fun `logout clears the cookie`() = parity {
        val session = Owner.signIn()
        val out = session.send(HttpMethod.Post, "/logout") {
            header(HttpHeaders.Accept, "text/html")
            setFormBody()
        }
        assertEquals(303, out.status.value)
        assertEquals("/login", out.headers[HttpHeaders.Location])
        assertContains(
            out.headers.getAll(HttpHeaders.SetCookie).orEmpty(),
            "ad_session=; Max-Age=0; Path=/; HttpOnly; Secure; SameSite=Lax",
        )
        val get = call(HttpMethod.Get, "/logout")
        assertEquals(303, get.status.value)
        assertEquals("/login", get.headers[HttpHeaders.Location])
    }

    @Test
    @Tag("tsOnly")
    fun `a bearer header decides on its own, even beside a good cookie`() = parity {
        val fake = "A".repeat(43)
        val withBoth = owner.get("/api/summary") { bearer(fake) }
        assertEquals(401, withBoth.status.value)

        val basic = owner.get("/api/summary") { header(HttpHeaders.Authorization, "Basic Zm9v") }
        assertEquals(200, basic.status.value)

        // An agent token is not a device key.
        val agentAsDevice = call(HttpMethod.Get, "/api/summary") { bearer(alpha.token) }
        assertEquals(401, agentAsDevice.status.value)
    }

    @Test
    fun `device sign-in issues a key that works on the API but not on pages`() = parity {
        val response = call(HttpMethod.Post, "/api/devices/sign-in", obj("name" to "  Phone  ", "password" to Parity.adminPassword))
        assertEquals(201, response.status.value)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("application/json; charset=utf-8", response.headers[HttpHeaders.ContentType])
        val body = response.obj()
        assertEquals("Phone", body["device"]["name"].s)
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(body["token"].s))
        assertTrue(Regex("\\.\\d{3}Z$").containsMatchIn(body["device"]["createdAt"].s))

        val device = Device(body["device"]["id"].s, body["token"].s)
        assertEquals(200, device.get("/api/summary").status.value)
        if (Parity.web) {
            val page = device.get("/")
            assertEquals(303, page.status.value)
            assertEquals("/login", page.headers[HttpHeaders.Location])
        }

        val removed = owner.delete("/api/devices/${device.id}")
        assertEquals(200, removed.status.value)
        assertEquals("""{"removed":true}""", removed.bodyAsText())
        assertEquals("""{"removed":false}""", owner.delete("/api/devices/${device.id}").bodyAsText())

        val after = device.get("/api/summary")
        assertEquals(401, after.status.value)
        assertEquals("""{"error":"unauthenticated"}""", after.bodyAsText())
    }

    @Test
    fun `device sign-in validates its body`() = parity {
        val notJson = call(HttpMethod.Post, "/api/devices/sign-in") {
            setBodyText("nope")
        }
        assertEquals(400, notJson.status.value)
        assertEquals("""{"error":"invalid_argument","message":"body must be JSON"}""", notJson.bodyAsText())

        val noName = call(HttpMethod.Post, "/api/devices/sign-in", obj("password" to "x"))
        assertEquals(400, noName.status.value)
        assertEquals("name must be a string", noName.obj()["message"].s)

        val emptyName = call(HttpMethod.Post, "/api/devices/sign-in", obj("name" to "", "password" to "x"))
        assertEquals(400, emptyName.status.value)
        assertEquals("name is required", emptyName.obj()["message"].s)
    }

    @Test
    @Tag("tsOnly")
    fun `device-only routes say so to a cookie`() = parity {
        val response = owner.put("/api/devices/current/apns", obj("token" to "abc"))
        assertEquals(400, response.status.value)
        assertEquals(
            """{"error":"invalid_argument","message":"this needs a device key: send Authorization: Bearer <device token>"}""",
            response.bodyAsText(),
        )
    }

    @Test
    fun `the MCP endpoint explains every kind of bad token`() = parity {
        suspend fun mcp(authorization: String?) = call(HttpMethod.Post, "/mcp", obj("jsonrpc" to "2.0", "id" to 1, "method" to "tools/list")) {
            if (authorization != null) header(HttpHeaders.Authorization, authorization)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
        }

        val missing = mcp(null)
        assertEquals(401, missing.status.value)
        // The Kotlin server also points MCP clients at its OAuth metadata (spec/11-oauth.md).
        val discovery = if (Parity.kotlin) "resource_metadata=\"${Parity.baseUrl}/.well-known/oauth-protected-resource/mcp\", scope=\"agent\"" else null
        assertEquals(listOfNotNull("Bearer", discovery).joinToString(" "), missing.headers[HttpHeaders.WWWAuthenticate])
        assertEquals(
            """{"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":"missing Authorization header: send Authorization: Bearer <agent token>","data":{"error":"missing_token"}}}""",
            missing.bodyAsText(),
        )

        val scheme = mcp("Token abc").obj()
        assertEquals("unsupported_scheme", scheme["error"]["data"]["error"].s)
        assertEquals("""unsupported Authorization scheme "Token": this server accepts Bearer tokens only""", scheme["error"]["message"].s)

        val empty = mcp("Bearer")
        assertEquals(401, empty.status.value)
        assertEquals("missing_token", empty.obj()["error"]["data"]["error"].s)

        val malformed = mcp("Bearer short")
        assertEquals(401, malformed.status.value)
        assertEquals("malformed_token", malformed.obj()["error"]["data"]["error"].s)
        assertEquals(
            listOfNotNull("""Bearer error="invalid_token", error_description="malformed bearer token: not a token this server issued"""", discovery).joinToString(", "),
            malformed.headers[HttpHeaders.WWWAuthenticate],
        )

        val lower = mcp("bearer ${alpha.token}")
        assertEquals(200, lower.status.value, "the scheme is case-insensitive")

        val stream = call(HttpMethod.Get, "/api/agent/stream")
        assertEquals(401, stream.status.value)
        assertNull(stream.headers[HttpHeaders.WWWAuthenticate])
        assertEquals(
            """{"error":"missing_token","message":"missing Authorization header: send Authorization: Bearer <agent token>"}""",
            stream.bodyAsText(),
        )
    }
}
