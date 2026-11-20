package dev.wildware.agents.app

import dev.wildware.agents.MemoryOutboxStorage
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.app.engine.AppModel
import dev.wildware.agents.app.engine.AppScreen
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Signing out in the app ends the device on the dashboard too, as the web app's sign-out does. */
class SignOutTest {
    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tidy() {
        server.close()
        scope.cancel()
    }

    private fun signedIn(): Pair<AppModel, MemorySessionStore> {
        val sessions = MemorySessionStore(SavedSession("https://feed.test", "key-1", "Test device"))
        val model = AppModel(
            AppPlatform(sessions, MemoryTextStore(), MemoryOutboxStorage(), { server.engine() }, "Test device", imageCacheDir = null),
            scope,
            accountService = "",
        )
        return model to sessions
    }

    @Test
    fun `signing out tells the dashboard with this device's key`() = runBlocking {
        val (model, sessions) = signedIn()
        withTimeout(10_000) { model.screen.first { it is AppScreen.Signed } }
        model.signOut()
        withTimeout(10_000) { while (server.wrote("POST", "/api/devices/sign-out") == null) delay(20) }
        assertNull(sessions.load(), "and the key is forgotten here")
        assertEquals("Bearer key-1", server.authorizations.last(), "sent with this device's own key")
    }

    @Test
    fun `a sign-out the dashboard made sends nothing back`() = runBlocking {
        val (model, _) = signedIn()
        withTimeout(10_000) { model.screen.first { it is AppScreen.Signed } }
        model.signOut("This device was signed out on the dashboard.")
        delay(500)
        assertNull(server.wrote("POST", "/api/devices/sign-out"))
    }
}
