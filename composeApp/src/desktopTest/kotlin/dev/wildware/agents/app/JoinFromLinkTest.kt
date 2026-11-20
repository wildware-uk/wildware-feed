package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.feed.AppNavigator
import dev.wildware.agents.app.feed.InviteLink
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An invite link (`agents://invite`, `/join/<code>`, or the web app's `#invite=`) opens the join screen, filled in. */
@OptIn(ExperimentalTestApi::class)
class JoinFromLinkTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val server = FakeServer()
    private val code = "q4Xk2Vb7Nn0sLwR9tYp3Hc8uJm1ZaEe6Gd5Fo2Ii7Kx"
    private val link = "agents://invite?server=https%3A%2F%2Ffeed.example.com&token=$code"

    @AfterTest
    fun stop() = server.close()

    private fun ComposeUiTest.app(store: MemorySessionStore, navigator: AppNavigator, width: Int = 390, height: Int = 844, dark: Boolean = true, scale: Float = 1f) {
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = store, deviceName = "Priya's phone")
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                Box(Modifier.size((width / scale).dp, (height / scale).dp)) {
                    AgentsApp(platform, dark = dark, accessibility = AccessibilityPrefs(), safeArea = if (width < 600 * scale) SafeArea(top = 54.dp, bottom = 34.dp) else SafeArea(), navigator = navigator)
                }
            }
        }
    }

    @Test
    fun theLinksAnInviteComesAsAreRead() {
        assertEquals("https://feed.example.com", InviteLink.of(link)!!.server)
        assertEquals("https://feed.example.com/join/$code", InviteLink.of(link)!!.link)
        assertEquals("https://feed.example.com", InviteLink.of("https://feed.example.com/join/$code")!!.server)
        assertNull(InviteLink.of(code)!!.server)
        assertNull(InviteLink.of("agents://open?path=/"))
        assertNull(InviteLink.of("agents://invite?server=x"))
        assertNull(InviteLink.of("agents://invite?server=x&token=short"))
        assertNull(InviteLink.of("https://feed.example.com/projects/web"))
        assertEquals(code, InviteLink.inFragment("#invite=$code"))
        assertNull(InviteLink.inFragment("#invite=<script>"))
        assertNull(InviteLink.inFragment(""))
    }

    @Test
    fun signedOutTheLinkOpensTheJoinScreenFilledIn() = runDesktopComposeUiTest(390, 844) {
        val navigator = AppNavigator()
        app(MemorySessionStore(), navigator)
        onNodeWithText("Sign in").assertExists()
        assertTrue(navigator.openInvite(link))
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Join a team").fetchSemanticsNodes().isNotEmpty() }
        // The link is in its box, and the host it names is shown, so only the account is left to type.
        onAllNodes(hasSetTextAction() and hasText("https://feed.example.com/join/$code")).onFirst().assertExists()
        onNodeWithText("feed.example.com").assertExists()
        onNodeWithText("Join the team").assertExists()
    }

    @Test
    fun signedInItAsksBeforeSigningOut() = runDesktopComposeUiTest(390, 844) {
        val store = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device", deviceId = "d1"))
        val navigator = AppNavigator()
        app(store, navigator)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Ship the new sounds?").fetchSemanticsNodes().isNotEmpty() }
        navigator.openInvite(link)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Join a team?").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("This device is signed in to dash.test", substring = true).assertExists()
        // Staying signed in changes nothing.
        onNodeWithText("Stay signed in").performClick()
        waitForIdle()
        assertTrue(onAllNodesWithText("Join a team?").fetchSemanticsNodes().isEmpty())
        assertEquals("key-1", store.load()?.token)

        navigator.openInvite(link)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Sign out and join").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Sign out and join").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Join a team").fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasSetTextAction() and hasText("https://feed.example.com/join/$code")).onFirst().assertExists()
        assertNull(store.load(), "this device signed out to join as the new person")
    }

    @Test
    fun screenshots() {
        for (dark in listOf(true, false)) {
            val theme = if (dark) "dark" else "light"
            runDesktopComposeUiTest(1170, 2532) { shootJoin(1170, 2532, 3f, dark); save("phone-$theme-people-join-link") }
            runDesktopComposeUiTest(2880, 1800) { shootJoin(2880, 1800, 2f, dark); save("desktop-$theme-people-join-link") }
            runDesktopComposeUiTest(1170, 2532) { shootAsk(1170, 2532, 3f, dark); save("phone-$theme-people-join-link-signed-in") }
        }
    }

    private fun ComposeUiTest.shootJoin(width: Int, height: Int, scale: Float, dark: Boolean) {
        val navigator = AppNavigator()
        navigator.openInvite(link)
        app(MemorySessionStore(), navigator, width, height, dark, scale)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Join a team").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.shootAsk(width: Int, height: Int, scale: Float, dark: Boolean) {
        val navigator = AppNavigator()
        app(MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device", deviceId = "d1")), navigator, width, height, dark, scale)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Ship the new sounds?").fetchSemanticsNodes().isNotEmpty() }
        navigator.openInvite(link)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Join a team?").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.save(name: String) {
        waitForIdle()
        val file = File(outDir, "$name.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }
}
