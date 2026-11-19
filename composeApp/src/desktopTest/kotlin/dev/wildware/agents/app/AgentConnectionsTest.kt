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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.AgentRecord
import dev.wildware.agents.app.engine.connectionPlace
import dev.wildware.agents.app.engine.withOffline
import dev.wildware.agents.app.feed.Agent
import dev.wildware.agents.app.feed.Presence
import dev.wildware.agents.app.feed.ConnectionList
import dev.wildware.agents.app.feed.ConnectionRow
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

/** An agent's dialog lists the apps signed in as it over OAuth, and signs one out after asking. */
@OptIn(ExperimentalTestApi::class)
class AgentConnectionsTest {
    private val events = mutableListOf<FeedEvent>()
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val forgeApps = ConnectionList(
        "a2",
        listOf(
            ConnectionRow("g1", "Cursor", "cursor: links", "Last used 4m ago", "Added 18 November 2026"),
            ConnectionRow("g2", "Claude Code", "An app on its own computer", "Last used 2h ago", "Added 12 November 2026"),
        ),
        loaded = true,
    )

    /** What the dashboard says back to a removal: null for done, or why not. */
    private var removeAnswer: String? = null

    private fun ComposeUiTest.feed(width: Int, connections: ConnectionList = forgeApps, preview: ScreenPreview = ScreenPreview()) = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.size(width.dp, 900.dp)) {
                FeedScreen(FakeFeed.state.copy(connections = connections), preview = preview, onEvent = { event ->
                    events += event
                    if (event is FeedEvent.RemoveAgent) event.done(removeAnswer)
                })
            }
        }
    }

    @Test
    fun removingAnAgentAsksFirstThenRemovesItAndCloses() = runComposeUiTest {
        feed(1440)
        openForge()
        onNode(hasText("Remove agent") and hasClickAction()).performScrollTo().performClick()
        onNode(hasText("Remove forge?", substring = true)).assertExists()
        assertTrue(events.none { it is FeedEvent.RemoveAgent }, "nothing is removed before the owner says so")
        onAllNodes(hasText("Keep it") and hasClickAction()).onLast().performClick()
        onNode(hasText("Remove forge?", substring = true)).assertDoesNotExist()
        onNode(hasText("Remove agent") and hasClickAction()).performScrollTo().performClick()
        onAllNodes(hasText("Remove agent") and hasClickAction()).onLast().performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf("a2"), events.filterIsInstance<FeedEvent.RemoveAgent>().map { it.agentId })
        onNode(hasText("Rename")).assertDoesNotExist()
    }

    @Test
    fun aRefusedRemovalSaysWhyAndStaysOpen() = runComposeUiTest {
        removeAnswer = "Couldn't reach the dashboard. Check the connection and try again."
        feed(1440)
        openForge()
        onNode(hasText("Remove agent") and hasClickAction()).performScrollTo().performClick()
        onAllNodes(hasText("Remove agent") and hasClickAction()).onLast().performScrollTo().performClick()
        waitForIdle()
        onNode(hasText("Couldn't reach the dashboard. Check the connection and try again.")).assertExists()
        onNode(hasText("Rename")).assertExists()
    }

    @Test
    fun theListAddsOfflineAgentsAndLeavesRemovedOnesOut() {
        val live = listOf(Agent("a1", "forge", "Agent", Presence.Online, 0), Agent("a8", "gone-bot", "Agent", Presence.Online, 1))
        val known = listOf(
            AgentRecord("a1", "forge", "2026-11-01T10:00:00Z"),
            AgentRecord("a9", "old-bot", "2026-11-01T10:00:00Z", lastSeenAt = "2026-11-02T10:00:00Z"),
            AgentRecord("a7", "older-bot", "2026-11-01T10:00:00Z", lastSeenAt = "2026-11-01T11:00:00Z"),
            AgentRecord("a8", "gone-bot", "2026-11-01T10:00:00Z", revokedAt = "2026-11-18T10:00:00Z"),
        )
        val list = withOffline(live, known)
        assertEquals(listOf("forge", "old-bot", "older-bot"), list.map { it.name })
        assertEquals(listOf(Presence.Online, Presence.Offline, Presence.Offline), list.map { it.presence })
        assertEquals(live, withOffline(live, emptyList()), "an older dashboard: live agents only, as before")
    }

    private fun ComposeUiTest.openForge() =
        onNode(hasClickAction() and hasContentDescription("forge, online", substring = true)).performScrollTo().performClick()

    @Test
    fun openingAnAgentReadsAndListsItsConnectedApps() = runComposeUiTest {
        feed(1440)
        openForge()
        waitForIdle()
        assertTrue(FeedEvent.ReadConnections("a2") in events)
        onNode(hasText("Connected apps")).assertExists()
        onNode(hasText("Cursor")).assertExists()
        onNode(hasText("An app on its own computer")).assertExists()
        onNode(hasText("Last used 2h ago")).assertExists()
    }

    @Test
    fun revokingAsksFirstThenSignsThatAppOut() = runComposeUiTest {
        feed(1440)
        openForge()
        onAllNodes(hasText("Revoke") and hasClickAction()).onFirst().performClick()
        onNode(hasText("Sign Cursor out? It can't act as this agent any more until you allow it again.")).assertExists()
        assertTrue(events.none { it is FeedEvent.RevokeConnection }, "nothing is revoked before the owner says so")
        onNode(hasText("Keep it")).performClick()
        onNode(hasText("Sign Cursor out", substring = true)).assertDoesNotExist()
        onAllNodes(hasText("Revoke") and hasClickAction()).onFirst().performClick()
        // Cursor's row, then the confirm under it, then Claude Code's row.
        onAllNodes(hasText("Revoke") and hasClickAction())[1].performClick()
        waitForIdle()
        assertEquals(listOf(FeedEvent.RevokeConnection("a2", "g1")), events.filterIsInstance<FeedEvent.RevokeConnection>())
    }

    @Test
    fun nothingShowsForAnAgentWithNoneOrForAnotherAgentsList() = runComposeUiTest {
        feed(1440, ConnectionList("a2", emptyList(), loaded = true))
        openForge()
        onNode(hasText("Connected apps")).assertDoesNotExist()
    }

    @Test
    fun anotherAgentsConnectionsAreNotShown() = runComposeUiTest {
        feed(1440, forgeApps.copy(agentId = "a1"))
        openForge()
        onNode(hasText("Connected apps")).assertDoesNotExist()
    }

    @Test
    fun placesAreSaidInWords() {
        assertEquals("An app on its own computer", connectionPlace("http://127.0.0.1:33418"))
        assertEquals("An app on its own computer", connectionPlace("http://[::1]:5000"))
        assertEquals("claude.ai", connectionPlace("https://claude.ai"))
        assertEquals("cursor: links", connectionPlace("cursor:"))
    }

    // --- Through the real engine --------------------------------------------------------------

    private val server = FakeServer()

    @AfterTest
    fun closeStreams() = server.close()

    private fun ComposeUiTest.waitForNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        try {
            waitUntil(timeoutMillis = 20_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            throw AssertionError("never saw ${matcher.description}; the screen had:\n" + onRoot().printToString(), timeout)
        }
        return onAllNodes(matcher).onFirst()
    }

    @Test
    fun theDashboardIsAskedAndTheListReadAgainAfterARevoke() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device", deviceId = "d1")))
        setContent { Box(Modifier.size(1440.dp, 900.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) } }
        waitForNode(hasClickAction() and hasContentDescription("forge", substring = true)).performScrollTo().performClick()
        waitForNode(hasText("Cursor"))
        val reads = server.count("/api/agents/a1/connections")
        server.connections = """{"connections":[]}"""
        onAllNodes(hasText("Revoke") and hasClickAction()).onFirst().performClick()
        onAllNodes(hasText("Revoke") and hasClickAction()).onLast().performClick()
        val until = System.currentTimeMillis() + 20_000
        while (server.wrote("DELETE", "/api/agents/a1/connections/g1") == null && System.currentTimeMillis() < until) Thread.sleep(25)
        assertTrue(server.wrote("DELETE", "/api/agents/a1/connections/g1") != null, "writes were ${server.writes}")
        waitUntil(timeoutMillis = 20_000) { server.count("/api/agents/a1/connections") > reads }
        waitUntil(timeoutMillis = 20_000) { onAllNodes(hasText("Connected apps")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun anOfflineAgentCanBeOpenedAndRemoved() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device", deviceId = "d1")))
        setContent { Box(Modifier.size(1440.dp, 900.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) } }
        waitForNode(hasClickAction() and hasContentDescription("old-bot", substring = true)).performScrollTo().performClick()
        assertTrue(onAllNodes(hasContentDescription("gone-bot", substring = true)).fetchSemanticsNodes().isEmpty(), "a removed agent is not listed")
        waitForNode(hasText("Remove agent") and hasClickAction()).performScrollTo().performClick()
        onAllNodes(hasText("Remove agent") and hasClickAction()).onLast().performScrollTo().performClick()
        val until = System.currentTimeMillis() + 20_000
        while (server.wrote("DELETE", "/api/agents/a9") == null && System.currentTimeMillis() < until) Thread.sleep(25)
        assertTrue(server.wrote("DELETE", "/api/agents/a9") != null, "writes were ${server.writes}")
        waitUntil(timeoutMillis = 20_000) { onAllNodes(hasContentDescription("old-bot", substring = true)).fetchSemanticsNodes().isEmpty() }
    }

    private fun ComposeUiTest.shoot(name: String, scale: Float, dark: Boolean, safeArea: SafeArea, preview: ScreenPreview) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                AgentTheme(dark = dark) {
                    Box(Modifier.fillMaxSize()) { FeedScreen(FakeFeed.state.copy(connections = forgeApps), safeArea, preview = preview) }
                }
            }
        }
        onAllNodes(hasText("Revoke") and hasClickAction()).onFirst().performClick()
        waitForIdle()
        val file = File(outDir, "$name-${if (dark) "dark" else "light"}-agent-connections.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
        onAllNodes(hasText("Keep it") and hasClickAction()).onFirst().performClick()
        onNode(hasText("Remove agent") and hasClickAction()).performScrollTo().performClick()
        onAllNodes(hasText("Remove agent") and hasClickAction()).onLast().performScrollTo()
        waitForIdle()
        val removing = File(outDir, "$name-${if (dark) "dark" else "light"}-agent-remove.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", removing)
        println("screenshot: ${removing.absolutePath}")
    }

    @Test
    fun screenshots() {
        for (dark in listOf(true, false)) {
            runDesktopComposeUiTest(2880, 1800) { shoot("desktop", 2f, dark, SafeArea(), ScreenPreview(agentOpen = "a2")) }
            runDesktopComposeUiTest(1170, 2532) { shoot("phone", 3f, dark, SafeArea(top = 54.dp, bottom = 34.dp), ScreenPreview(agentOpen = "a2")) }
        }
    }
}
