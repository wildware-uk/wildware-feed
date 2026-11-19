package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.DeviceList
import dev.wildware.agents.app.feed.DeviceRow
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Devices sheet: every device signed in as the owner, and revoking a lost one. */
@OptIn(ExperimentalTestApi::class)
class DevicesTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val events = mutableListOf<FeedEvent>()
    private val list = DeviceList(
        listOf(
            DeviceRow("d1", "Sam's iPhone", "Last used now", "Added 3 August 2026", current = true),
            DeviceRow("d2", "Work laptop", "Last used 2h ago", "Added 18 September 2026"),
            DeviceRow("d3", "Old iPad", "Last used 41d ago", "Added 2 March 2026"),
        ),
        loaded = true,
    )

    private fun ComposeUiTest.sheet(scale: Float = 1f, dark: Boolean = true, devices: DeviceList = list) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(scale)) {
            AgentTheme(dark = dark) {
                Box(Modifier.fillMaxSize()) {
                    FeedScreen(FakeFeed.state.copy(devices = devices), SafeArea(), preview = ScreenPreview(devicesOpen = true), onEvent = { events += it })
                }
            }
        }
    }

    @Test
    fun itListsTheDevicesAndReadsThemAgainWhenOpened() = runDesktopComposeUiTest(width = 1440, height = 900) {
        sheet()
        onNodeWithText("Sam's iPhone").assertExists()
        onNodeWithText("This device", substring = true).assertExists()
        onNodeWithText("Last used 41d ago").assertExists()
        onNodeWithText("Added 2 March 2026").assertExists()
        assertTrue(FeedEvent.ReadDevices in events)
    }

    @Test
    fun revokingAsksFirstThenRevokes() = runDesktopComposeUiTest(width = 1440, height = 900) {
        sheet()
        onAllNodes(hasText("Revoke") and hasClickAction())[2].performClick()
        onNodeWithText("Revoke Old iPad? It is signed out at once and can't read or answer anything more.").assertExists()
        assertTrue(events.none { it is FeedEvent.RevokeDevice }, "nothing is revoked before the owner says so")
        onNodeWithText("Keep it").performClick()
        onAllNodesWithText("Revoke Old iPad", substring = true).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        onAllNodes(hasText("Revoke") and hasClickAction())[2].performClick()
        onAllNodes(hasText("Revoke") and hasClickAction()).onLast().performClick()
        waitForIdle()
        assertEquals(listOf(FeedEvent.RevokeDevice("d3")), events.filterIsInstance<FeedEvent.RevokeDevice>())
    }

    @Test
    fun revokingThisDeviceSaysItSignsOut() = runDesktopComposeUiTest(width = 1440, height = 900) {
        sheet()
        onAllNodes(hasText("Revoke") and hasClickAction()).onFirst().performClick()
        onNodeWithText("Revoke and sign out").assertExists()
    }

    // --- Through the real engine --------------------------------------------------------------

    private val server = FakeServer()

    @AfterTest
    fun closeStreams() = server.close()

    private fun ComposeUiTest.app(store: MemorySessionStore) {
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = store)
        setContent { Box(Modifier.size(1440.dp, 900.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) } }
    }

    private fun ComposeUiTest.waitForNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        try {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            throw AssertionError("never saw ${matcher.description}; the screen had:\n" + onRoot().printToString(), timeout)
        }
        return onAllNodes(matcher).onFirst()
    }

    private fun waitForWrite(method: String, path: String): String {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            server.wrote(method, path)?.let { return it }
            Thread.sleep(25)
        }
        error("no $method $path; writes were ${server.writes}")
    }

    private fun ComposeUiTest.openDevices() {
        waitForNode(hasContentDescription("Alerts on this device")).performClick()
        waitForNode(hasText("Devices") and hasClickAction()).performClick()
    }

    @Test
    fun anotherDeviceIsRevokedAndTheListReadAgain() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app(MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device", deviceId = "d1")))
        openDevices()
        waitForNode(hasText("Old phone"))
        val reads = server.count("/api/devices")
        onAllNodes(hasText("Revoke") and hasClickAction())[1].performClick()
        onAllNodes(hasText("Revoke") and hasClickAction()).onLast().performClick()
        waitForWrite("DELETE", "/api/devices/d2")
        waitUntil(timeoutMillis = 10_000) { server.count("/api/devices") > reads }
    }

    @Test
    fun revokingThisDeviceSignsItOut() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val store = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device", deviceId = "d1"))
        app(store)
        openDevices()
        waitForNode(hasText("This device", substring = true))
        onAllNodes(hasText("Revoke") and hasClickAction()).onFirst().performClick()
        waitForNode(hasText("Revoke and sign out")).performClick()
        waitForWrite("DELETE", "/api/devices/d1")
        waitUntil(timeoutMillis = 10_000) { store.load() == null }
    }

    @Test
    fun screenshots() {
        for (dark in listOf(true, false)) {
            runDesktopComposeUiTest(2880, 1800) { shoot("desktop", 2f, dark) }
            runDesktopComposeUiTest(1170, 2532) { shoot("phone", 3f, dark) }
        }
    }

    private fun ComposeUiTest.shoot(name: String, scale: Float, dark: Boolean) {
        sheet(scale, dark)
        onAllNodes(hasText("Revoke") and hasClickAction())[2].performClick()
        waitForIdle()
        val file = File(outDir, "$name-${if (dark) "dark" else "light"}-devices.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }
}
