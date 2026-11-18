package dev.wildware.feed.parity

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The limiters. Runs last: the login lockout lasts 15 minutes for this client's address, so after
 * this class nothing can sign in. Set PARITY_SKIP_LOCKOUT=1 to leave the login limiter alone when
 * the server is shared. Spec: server/spec/01-config-and-auth.md (rate limits), 07 §4.
 */
@Order(Int.MAX_VALUE)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RateLimitTest : ParityTest() {
    @Test
    @Order(1)
    fun `an agent token gets 120 MCP requests a minute`() = parity {
        val agent = Parity.mintAgent(uniq("flood"))
        repeat(120) { i ->
            assertEquals(200, agent.rpc("ping", honourRateLimit = false).status.value, "request ${i + 1}")
        }
        val limited = agent.rpc("ping", honourRateLimit = false)
        assertEquals(429, limited.status.value)
        val retry = limited.headers[HttpHeaders.RetryAfter]!!.toInt()
        assertTrue(retry in 1..60, "retry-after $retry")
        val body = limited.obj()
        assertEquals("rate_limited", body["error"]["data"]["error"].s)
        assertTrue(body["error"]["message"].s.startsWith("rate limit exceeded for this token: retry after "))

        // The agent stream has a bucket of its own.
        withStream("/api/agent/stream?project=*", { bearer(agent.token) }) { stream ->
            assertEquals(200, stream.response.status.value)
        }
    }

    @Test
    @Order(2)
    @org.junit.jupiter.api.Tag("media")
    fun `an upload token gets six attempts a minute`() = parity {
        val reserved = Media.reserve(alpha, "r.png", "image/png", 10)
        repeat(6) { Media.put(reserved["upload_url"].s, null, null) }
        val limited = Media.put(reserved["upload_url"].s, null, null)
        assertEquals(429, limited.status.value)
        assertEquals("""{"error":"rate_limited","message":"too many upload attempts for this token"}""", limited.bodyAsText())
        assertTrue(limited.headers[HttpHeaders.RetryAfter]!!.toInt() >= 1)
    }

    @Test
    @Order(3)
    fun `five wrong passwords lock the login, form and device sign-in alike`() = parity {
        assumeTrue(System.getenv("PARITY_SKIP_LOCKOUT") != "1", "PARITY_SKIP_LOCKOUT=1")
        owner // make sure the shared session exists before the door shuts
        Device.signIn() // a success resets the count
        repeat(5) { i ->
            assertEquals(400, Owner.loginForm("wrong-$i").status.value, "attempt ${i + 1}")
        }
        val locked = Owner.loginForm(Parity.adminPassword)
        assertEquals(429, locked.status.value)
        assertContains(locked.bodyAsText(), "Too many attempts. Try again later.")
        assertNull(locked.headers[HttpHeaders.RetryAfter], "the form's 429 has no Retry-After")

        val empty = Owner.loginForm("")
        assertEquals(400, empty.status.value, "an empty password is answered before the limiter")

        val device = call(HttpMethod.Post, "/api/devices/sign-in", obj("name" to "x", "password" to Parity.adminPassword))
        assertEquals(429, device.status.value)
        assertEquals("""{"error":"rate_limited","message":"Too many attempts. Try again later."}""", device.bodyAsText())
        val retry = device.headers[HttpHeaders.RetryAfter]!!.toInt()
        assertTrue(retry in 800..900, "retry-after about 15 minutes: $retry")

        // A session from before the lockout still works.
        assertEquals(200, owner.get("/api/summary").status.value)
    }
}
