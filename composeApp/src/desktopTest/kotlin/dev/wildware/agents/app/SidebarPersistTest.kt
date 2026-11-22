package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.GroupView
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A drag in the sidebar, through the real engine to [FakeServer] and back: the dashboard is told,
 * and a fresh start of the app finds the channel where it was dropped.
 */
@OptIn(ExperimentalTestApi::class)
class SidebarPersistTest {
    private val server = FakeServer().apply {
        groups = listOf(GroupView(id = "g1", slug = "launch", name = "Launch"))
        me = """{"person":{"id":"me","name":"Sam","handle":"sam","role":"owner"}}"""
    }
    private val signedIn = SavedSession("https://dash.test", "key-1", "Test device")

    @AfterTest
    fun closeStreams() = server.close()

    private fun ComposeUiTest.app() {
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = MemorySessionStore(signedIn))
        setContent { Box(Modifier.size(1280.dp, 900.dp)) { AgentsApp(platform, dark = false, accessibility = AccessibilityPrefs()) } }
    }

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun ComposeUiTest.bounds(name: String): Rect = onNode(hasText(name) and isTab).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.waitForRow(name: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(name) and isTab).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForWrite(method: String, path: String): String {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            server.wrote(method, path)?.let { return it }
            Thread.sleep(25)
        }
        error("no $method $path; writes were ${server.writes}")
    }

    /** Other sits under Launch's heading, above the CHANNELS label. */
    private fun ComposeUiTest.otherIsInLaunch(): Boolean {
        val channels = onNode(hasText("CHANNELS") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNode().boundsInRoot
        return bounds("Other").top > bounds("Launch").top && bounds("Other").top < channels.top
    }

    @Test
    fun `a channel dragged into a project is still there after a restart`() {
        runDesktopComposeUiTest(1280, 900) {
            app()
            waitForRow("Other")
            waitForRow("Launch")
            val from = bounds("Other").center
            onRoot().performMouseInput {
                moveTo(from)
                press()
                moveTo(from + Offset(0f, 24f))
                moveTo(bounds("Launch").center)
            }
            waitForIdle()
            onRoot().performMouseInput { release() }
            waitForIdle()
            val body = waitForWrite("PUT", "/api/projects/order")
            assertTrue(""""groupId":"g1"""" in body && """"ids":["other"]""" in body, body)
            waitUntil(timeoutMillis = 10_000) { otherIsInLaunch() }
        }
        // A fresh start reads the order from the dashboard alone.
        runDesktopComposeUiTest(1280, 900) {
            app()
            waitForRow("Other")
            waitForRow("Launch")
            waitUntil(timeoutMillis = 10_000) { otherIsInLaunch() }
        }
    }
}
