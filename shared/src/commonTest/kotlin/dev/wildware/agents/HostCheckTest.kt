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
import kotlin.test.assertNull

class HostCheckTest {
    private val seen = mutableListOf<HttpRequestData>()

    private fun engine(reply: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = MockEngine { request ->
        seen += request
        reply(request)
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private suspend fun check(address: String = "https://dash.test", reply: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HostCheck.check(address, engine(reply))

    @Test
    fun `a Feed server says how to sign in`() = runTest {
        val found = check("dashboard.example.com/") {
            json("""{"product":"wildware-feed","version":"1.4.0","signIn":["password"],"team":false}""")
        }

        assertEquals(HostCheck.Feed("https://dashboard.example.com", listOf(SignInMethod.Password)), found)
        val request = seen.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("https://dashboard.example.com/api/server", request.url.toString())
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `ways this app doesn't know yet are left out`() = runTest {
        val found = check { json("""{"product":"wildware-feed","signIn":["email-password","passkey","password"]}""") }
        assertEquals(listOf(SignInMethod.EmailPassword, SignInMethod.Password), (found as HostCheck.Feed).signIn)
    }

    @Test
    fun `an older Feed server with no server info signs in with a password`() = runTest {
        val found = check { request ->
            when (request.url.encodedPath) {
                "/api/server" -> json("""{"error":"not_found"}""", HttpStatusCode.NotFound)
                "/api/summary" -> json("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized)
                else -> error("unexpected ${request.url}")
            }
        }
        assertEquals(HostCheck.Feed("https://dash.test", listOf(SignInMethod.Password)), found)
        assertEquals(listOf("/api/server", "/api/summary"), seen.map { it.url.encodedPath })
    }

    @Test
    fun `an older server whose site answers every path with a page is still found`() = runTest {
        val found = check { request ->
            if (request.url.encodedPath == "/api/summary") json("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized)
            else respond("<!doctype html><title>Feed</title>", HttpStatusCode.OK)
        }
        assertEquals(HostCheck.Feed("https://dash.test", listOf(SignInMethod.Password)), found)
    }

    @Test
    fun `some other site is not a Feed server`() = runTest {
        assertEquals(HostCheck.NotFeed("https://dash.test"), check { respond("<html>hi</html>", HttpStatusCode.OK) })
        assertEquals(HostCheck.NotFeed("https://dash.test"), check { respond("Not found", HttpStatusCode.NotFound) })
        assertEquals(HostCheck.NotFeed("https://dash.test"), check { json("""{"product":"something-else"}""") })
        // A 401 that isn't JSON is a login wall of some other kind.
        assertEquals(
            HostCheck.NotFeed("https://dash.test"),
            check { request ->
                if (request.url.encodedPath == "/api/summary") respond("Unauthorized", HttpStatusCode.Unauthorized)
                else respond("", HttpStatusCode.NotFound)
            },
        )
    }

    @Test
    fun `an address that doesn't answer is unreachable`() = runTest {
        assertEquals(HostCheck.Unreachable("https://dash.test"), check { throw IllegalStateException("Connection refused") })
    }

    @Test
    fun `a server that is failing is treated as unreachable`() = runTest {
        assertEquals(HostCheck.Unreachable("https://dash.test"), check { respond("Bad gateway", HttpStatusCode.BadGateway) })
    }
}
