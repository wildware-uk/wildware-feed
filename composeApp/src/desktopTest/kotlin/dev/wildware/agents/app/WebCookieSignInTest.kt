package dev.wildware.agents.app

import dev.wildware.agents.MemoryOutboxStorage
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.SignIn
import dev.wildware.agents.app.engine.AppModel
import dev.wildware.agents.app.engine.AppScreen
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The account site's "Open" signs a browser in to its team with a ticket, which sets the web app's
 * cookie and nothing the page keeps. With nothing saved, the web app asks the server whether that
 * cookie is good before it shows the sign-in form.
 */
class WebCookieSignInTest {
    private val server = FakeServer()
    private val sessions = MemorySessionStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tidy() {
        server.close()
        scope.cancel()
    }

    private fun model(home: String?) = AppModel(
        AppPlatform(sessions, MemoryTextStore(), MemoryOutboxStorage(), { server.engine() }, "Chrome on Mac", imageCacheDir = null, homeServer = home),
        scope,
        accountService = "",
    )

    /** `GET /api/me` answers 401, as it does to a browser with no cookie or a revoked one. */
    private fun noCookie() {
        server.otherHost = { request ->
            if (request.url.encodedPath == "/api/me") {
                server.calls += "/api/me"
                respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized, headersOf("Content-Type", "application/json"))
            } else {
                null
            }
        }
    }

    @Test
    fun `a browser the server already signed in opens its feed`() = runBlocking {
        server.me = """{"person":{"id":"founder","name":"Ann","handle":"ann","role":"owner","founder":true},"team":false}"""
        val model = model("https://acme.feed.test")
        withTimeout(10_000) { model.screen.first { it is AppScreen.Signed } }
        val saved = sessions.load()!!
        assertEquals("https://acme.feed.test", saved.server)
        assertEquals(SignIn.CookieSession, saved.token, "the cookie is the key: the page keeps none")
    }

    @Test
    fun `with no good cookie, the sign-in form stays`() = runBlocking {
        noCookie()
        val model = model("https://acme.feed.test")
        withTimeout(10_000) { while (server.count("/api/me") == 0) kotlinx.coroutines.delay(20) }
        kotlinx.coroutines.delay(200)
        assertIs<AppScreen.SigningIn>(model.screen.value)
        assertNull(sessions.load())
    }

    @Test
    fun `the apps, which keep their own key, don't ask`() = runBlocking {
        server.me = """{"person":{"id":"founder","name":"Ann","handle":"ann","role":"owner","founder":true},"team":false}"""
        val model = model(null)
        kotlinx.coroutines.delay(300)
        assertIs<AppScreen.SigningIn>(model.screen.value)
        assertEquals(0, server.count("/api/me"))
    }
}
