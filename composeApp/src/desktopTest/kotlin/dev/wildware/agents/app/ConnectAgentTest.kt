package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.ConnectPrompt
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.LocalDashboardAddress
import dev.wildware.agents.app.feed.LocalMediaAuth
import dev.wildware.agents.app.feed.MediaAuth
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * "Connect an agent": a prompt to paste into any agent so it installs the plugin and asks to join
 * the dashboard the owner is signed in to. It carries that dashboard's address and nothing secret.
 */
@OptIn(ExperimentalTestApi::class)
class ConnectAgentTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val address = "https://team.example.com/"
    private val ownerKey = "owner-key-do-not-share"

    @Test
    fun thePromptInstallsThePluginAndPairsWithThisDashboard() {
        val prompt = ConnectPrompt.text(address)
        assertTrue("claude plugin marketplace add wildware-uk/wildware-feed-agents" in prompt)
        assertTrue("claude plugin install wildware-feed@wildware-feed --config dashboard_url=https://team.example.com\n" in prompt)
        assertTrue("pair start --url https://team.example.com --name" in prompt)
        assertTrue("pair wait" in prompt)
        assertTrue("/reload-plugins" in prompt)
        assertTrue("https://team.example.com/mcp" in prompt, "other agents get the MCP address")
        assertTrue("https://team.example.com/api/pair" in prompt, "and the way to pair without the plugin")
        assertFalse("example.com//" in prompt, "the address is used without its trailing slash")
        assertFalse("subscribe" in prompt, "no channel unless one is open")
        assertFalse(Regex("(?i)bearer|authorization|token=|password=").containsMatchIn(prompt), "nothing secret is in it:\n$prompt")
    }

    @Test
    fun anOpenChannelIsWhereTheAgentIsToldToPost() {
        val prompt = ConnectPrompt.text(address, channel = "web-app")
        assertTrue("subscribe({channel: \"web-app\"})" in prompt)
    }

    private fun ComposeUiTest.sheet(clipboard: FakeClipboard = FakeClipboard(), state: FeedState = FakeFeed.state, preview: ScreenPreview = ScreenPreview(connectOpen = true), scale: Float = 1f, dark: Boolean = true) = setContent {
        CompositionLocalProvider(
            LocalDensity provides Density(scale),
            LocalClipboard provides clipboard,
            LocalClipboardManager provides clipboard,
            LocalDashboardAddress provides address,
            LocalMediaAuth provides MediaAuth(address, "Bearer $ownerKey"),
        ) {
            AgentTheme(dark = dark) {
                Box(Modifier.fillMaxSize()) {
                    FeedScreen(state, SafeArea(), preview = preview, onEvent = {})
                }
            }
        }
    }

    @Test
    fun copyPutsThePromptForThisDashboardOnTheClipboard() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val clipboard = FakeClipboard()
        sheet(clipboard)
        onNodeWithText("Copy prompt").performClick()
        waitForIdle()
        val copied = assertNotNull(clipboard.text)
        assertEquals(ConnectPrompt.text(address, FakeFeed.state.current.slug), copied)
        assertTrue("dashboard_url=https://team.example.com" in copied)
        assertFalse(ownerKey in copied, "the owner's own key never goes in the prompt")
        onNodeWithText("Copied", substring = true).assertExists()
    }

    @Test
    fun anEmptyDashboardOffersToConnectTheFirstAgent() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val empty = FakeFeed.state.copy(sections = emptyList(), agents = emptyList(), openThread = null)
        sheet(state = empty, preview = ScreenPreview())
        onNodeWithText("Nothing here yet").assertExists()
        onAllNodes(hasText("Connect an agent") and hasClickAction()).onFirst().performClick()
        waitForIdle()
        onNodeWithText("Copy prompt").assertExists()
    }

    // --- Through the real app ------------------------------------------------------------------

    private val server = FakeServer()

    @AfterTest
    fun closeStreams() = server.close()

    private fun ComposeUiTest.waitForNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        try {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            throw AssertionError("never saw ${matcher.description}; the screen had:\n" + onRoot().printToString(), timeout)
        }
        return onAllNodes(matcher).onFirst()
    }

    @Test
    fun theSignedInAppPutsItsOwnAddressInThePromptAndNotItsKey() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device", deviceId = "d1")))
        setContent { Box(Modifier.size(1440.dp, 900.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) } }
        waitForNode(hasText("Connect an agent") and hasClickAction()).performClick()
        waitForNode(hasText("Copy prompt"))
        val shown = onNodeWithTag("connect-prompt").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }.orEmpty()
        assertTrue("dashboard_url=https://dash.test\n" in shown, "the prompt names the dashboard this app is signed in to:\n$shown")
        assertFalse("key-1" in shown, "and never the device's key")
    }

    @Test
    fun screenshots() {
        for (dark in listOf(true, false)) {
            runDesktopComposeUiTest(2880, 1800) { shoot("desktop", 2f, dark) }
            runDesktopComposeUiTest(1170, 2532) { shoot("phone", 3f, dark) }
        }
    }

    private fun ComposeUiTest.shoot(name: String, scale: Float, dark: Boolean) {
        sheet(scale = scale, dark = dark)
        onNodeWithText("Copy prompt").performClick()
        waitForIdle()
        val file = File(outDir, "$name-${if (dark) "dark" else "light"}-connect-agent.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
    }
}
