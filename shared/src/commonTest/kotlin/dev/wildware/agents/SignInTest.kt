package dev.wildware.agents

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SignInTest {
    private val seen = mutableListOf<Pair<HttpRequestData, String>>()

    private fun engine(reply: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = MockEngine { request ->
        seen += request to request.textOf()
        reply(request)
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode, vararg extra: Pair<String, String>) =
        respond(body, status, headersOf(*(listOf(HttpHeaders.ContentType to listOf("application/json")) + extra.map { it.first to listOf(it.second) }).toTypedArray()))

    private suspend fun failure(reply: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SignInException =
        assertFailsWith { SignIn.signIn("https://dash.test", "pw", "Phone", engine(reply)) }

    @Test
    fun `a good password becomes a device key in one request`() = runTest {
        val signedIn = SignIn.signIn("dash.example.com/", "hunter2", "Alex's Mac", engine {
            json("""{"device":{"id":"d1","name":"Alex's Mac","createdAt":"2026-11-18T10:00:00.000Z"},"token":"device-key"}""", HttpStatusCode.Created)
        })

        assertEquals("https://dash.example.com", signedIn.server)
        assertEquals("device-key", signedIn.token)
        assertEquals("d1", signedIn.device.id)

        val (request, body) = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://dash.example.com/api/devices/sign-in", request.url.toString())
        assertEquals("""{"password":"hunter2","name":"Alex's Mac"}""", body)
        assertNull(request.headers[HttpHeaders.Cookie])
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a wrong password says so in the server's words`() = runTest {
        val failure = failure { json("""{"error":"wrong_password","message":"That password is not right."}""", HttpStatusCode.BadRequest) }
        assertEquals(SignInFailure.WrongPassword, failure.failure)
        assertEquals("That password is not right.", failure.message)
    }

    @Test
    fun `too many tries says how long to wait`() = runTest {
        val failure = failure {
            json("""{"error":"rate_limited","message":"Too many attempts. Try again later."}""", HttpStatusCode.TooManyRequests, HttpHeaders.RetryAfter to "840")
        }
        assertEquals(SignInFailure.TooManyAttempts, failure.failure)
        assertEquals(840, failure.retryAfterSeconds)
    }

    @Test
    fun `a dashboard with no password set up says so`() = runTest {
        val failure = failure { json("""{"error":"misconfigured"}""", HttpStatusCode.ServiceUnavailable) }
        assertEquals(SignInFailure.NotSetUp, failure.failure)
    }

    @Test
    fun `a name the server won't take is refused with its reason`() = runTest {
        val failure = failure { json("""{"error":"invalid_argument","message":"name must be at most 100 characters"}""", HttpStatusCode.BadRequest) }
        assertEquals(SignInFailure.Refused, failure.failure)
        assertEquals("name must be at most 100 characters", failure.message)
    }

    @Test
    fun `an address that doesn't answer is unreachable`() = runTest {
        val failure = failure { throw IllegalStateException("Connection refused") }
        assertEquals(SignInFailure.Unreachable, failure.failure)
    }

    @Test
    fun `a site with no sign-in route is not a dashboard`() = runTest {
        assertEquals(SignInFailure.NotADashboard, failure { respond("Not found", HttpStatusCode.NotFound) }.failure)
        assertEquals(SignInFailure.NotADashboard, failure { respond("<html>hi</html>", HttpStatusCode.OK) }.failure)
    }

    @Test
    fun `a server error is worth trying again`() = runTest {
        assertEquals(SignInFailure.Unreachable, failure { respond("oops", HttpStatusCode.BadGateway) }.failure)
    }

    // ---- people (migration 055) ------------------------------------------------------------------

    @Test
    fun `a person signs in with their email and the old form is unchanged`() = runTest {
        SignIn.signInWithEmail("https://dash.test", " sam@example.com ", "hunter2", "Phone", engine {
            json("""{"device":{"id":"d1","name":"Phone","createdAt":"2026-11-18T10:00:00.000Z"},"token":"k"}""", HttpStatusCode.Created)
        })
        assertEquals("""{"email":"sam@example.com","password":"hunter2","name":"Phone"}""", seen.single().second)
    }

    @Test
    fun `joining with an invite sends the code and a new account in one request`() = runTest {
        val signed = SignIn.acceptInvite("https://dash.test", "https://dash.test/join/abc", "Priya", "priya@example.com", "a long password", "Laptop", engine {
            json("""{"device":{"id":"d2","name":"Laptop","createdAt":"2026-11-18T10:00:00.000Z"},"token":"k2","person":{"id":"p2","name":"Priya"}}""", HttpStatusCode.Created)
        }, cookie = false)
        assertEquals("k2", signed.token)
        val (request, body) = seen.single()
        assertEquals("https://dash.test/api/invite/accept", request.url.toString())
        assertEquals("""{"token":"https://dash.test/join/abc","name":"Priya","email":"priya@example.com","password":"a long password","deviceName":"Laptop"}""", body)
    }

    @Test
    fun `a used or wrong invite says so`() = runTest {
        val failure = assertFailsWith<SignInException> {
            SignIn.acceptInvite("https://dash.test", "nope", "P", "p@example.com", "a long password", "Laptop", engine {
                json("""{"error":"invalid_invite","message":"That invite has expired."}""", HttpStatusCode.BadRequest)
            }, cookie = false)
        }
        assertEquals(SignInFailure.BadInvite, failure.failure)
    }

    @Test
    fun `an invite link names its server and a bare code does not`() {
        val code = "A".repeat(43)
        assertEquals("https://feed.example.com", SignIn.serverOfInvite("https://feed.example.com/join/$code"))
        assertEquals("http://192.0.2.10:4317", SignIn.serverOfInvite("http://192.0.2.10:4317/join/$code"))
        assertEquals("https://feed.example.com", SignIn.serverOfInvite("agents://invite?server=https%3A%2F%2Ffeed.example.com&token=$code"))
        assertNull(SignIn.serverOfInvite(code))
    }
}
