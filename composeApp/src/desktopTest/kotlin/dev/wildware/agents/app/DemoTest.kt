package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * "Try a demo": the whole app on built-in sample data, for App Review and anyone curious. Every
 * test here runs with a network that fails the test if anything reaches it.
 */
@OptIn(ExperimentalTestApi::class)
class DemoTest {
    /** How many times the app asked for a real network. The demo must never. */
    private var networkAsked = 0
    private val sessions = MemorySessionStore()

    private fun ComposeUiTest.app(width: Int = 390) {
        val platform = AppPlatform.inMemory(
            engine = {
                networkAsked++
                error("the demo must not use the network")
            },
            sessions = sessions,
        )
        setContent {
            Box(Modifier.size(width.dp, 844.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) }
        }
    }

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher) = try {
        waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(matcher).onFirst()
    } catch (timeout: Throwable) {
        throw AssertionError("never saw ${matcher.description}; the screen had:\n" + onRoot().printToString(), timeout)
    }

    private fun ComposeUiTest.waitGone(matcher: SemanticsMatcher) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }

    /** Scrolls the feed until [text] is on screen, waiting for it to load first. */
    private fun ComposeUiTest.seeInFeed(text: String) {
        try {
            waitUntil(timeoutMillis = 10_000) {
                runCatching {
                    onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text, substring = true))
                }.isSuccess
            }
        } catch (timeout: Throwable) {
            throw AssertionError("never saw \"$text\"; the screen had:\n" + onRoot().printToString(), timeout)
        }
    }

    private fun ComposeUiTest.enterDemo() {
        onNodeWithText("Try a demo").performClick()
        waitFor(hasText("Demo"))
    }

    @Test
    fun theDemoOpensOnSampleDataWithoutANetwork() = runComposeUiTest {
        app()
        enterDemo()
        seeInFeed("Seedlings now sprout after a rain shower")
        assertEquals(0, networkAsked)
        // Nothing is saved: the demo is not a sign-in.
        assertNull(sessions.load())
    }

    @Test
    fun aReplyShowsAtOnce() = runComposeUiTest {
        app()
        enterDemo()
        seeInFeed("Seedlings now sprout after a rain shower")
        waitFor(hasContentDescription("Message")).performTextInput("Looks great, ship it")
        onNodeWithContentDescription("Send").performClick()
        seeInFeed("Looks great, ship it")
        assertEquals(0, networkAsked)
    }

    @Test
    fun aQuestionCanBeAnswered() = runComposeUiTest {
        app()
        enterDemo()
        val question = hasText("Use the new watering-can sound for everyone?")
        waitFor(question)
        onNode(hasText("Ship it") and hasClickAction()).performClick()
        waitGone(question)
        assertEquals(0, networkAsked)
    }

    @Test
    fun leavingTheDemoGoesBackToSignInAndForgetsIt() = runComposeUiTest {
        app()
        enterDemo()
        seeInFeed("Seedlings now sprout after a rain shower")
        waitFor(hasContentDescription("Message")).performTextInput("Remember me?")
        onNodeWithContentDescription("Send").performClick()
        seeInFeed("Remember me?")

        // The top bar scrolls with the feed: back up to it, then open the drawer.
        onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasContentDescription("Projects", substring = true))
        waitFor(hasContentDescription("Projects", substring = true)).performClick()
        waitFor(hasText("Leave demo")).performClick()
        waitFor(hasText("Try a demo"))
        assertNull(sessions.load())

        // A fresh demo: what was written last time is gone.
        enterDemo()
        seeInFeed("Seedlings now sprout after a rain shower")
        onAllNodes(hasText("Remember me?")).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        assertEquals(0, networkAsked)
    }
}
