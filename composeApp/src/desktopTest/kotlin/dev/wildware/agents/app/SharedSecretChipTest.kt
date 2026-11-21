package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.DeviceAuth
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedSection
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.LocalDeviceAuth
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.SharedSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A secret an agent attached to a card: a locked chip, opened in place. Every value here is made up. */
@OptIn(ExperimentalTestApi::class)
class SharedSecretChipTest {
    private val events = mutableListOf<FeedEvent>()
    private val value = "sk-test-NOT-REAL-chip-5e2d"

    private class FakeClipboard : ClipboardManager {
        var held: AnnotatedString? = null
        override fun getText(): AnnotatedString? = held
        override fun setText(annotatedString: AnnotatedString) { held = annotatedString }
    }

    /** The feed, with the first update carrying [secrets] and moved to the top. */
    private fun state(): FeedState {
        val update = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().first()
        val withSecrets = update.copy(
            title = "Stripe is set up",
            secrets = listOf(
                SharedSecret("update", update.id, "STRIPE_TEST_KEY"),
                SharedSecret("update", update.id, "OLD_DEPLOY_TOKEN", available = false),
            ),
        )
        return FakeFeed.state.copy(openThread = null, sections = listOf(FeedSection("Today", listOf(withSecrets))))
    }

    private fun ComposeUiTest.screen(auth: DeviceAuth, clipboard: ClipboardManager) = setContent {
        CompositionLocalProvider(LocalDeviceAuth provides auth, LocalClipboardManager provides clipboard) {
            AgentTheme(dark = true) {
                Box(Modifier.fillMaxSize()) {
                    FeedScreen(state(), SafeArea(), onEvent = { events += it })
                }
            }
        }
    }

    private fun ComposeUiTest.valueOnScreen(): Boolean = onAllNodes(SemanticsMatcher("shows the value") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(value) } == true
    }).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theChipShowsTheNameMaskedAndSaysWhenOneIsGone() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen(DeviceAuth { true }, FakeClipboard())
        onNodeWithText("STRIPE_TEST_KEY").assertExists()
        onNodeWithText("••••••••").assertExists()
        onNodeWithText("OLD_DEPLOY_TOKEN").assertExists()
        onNodeWithText("No longer available").assertExists()
        // One Reveal and one Copy: the gone one has neither.
        assertEquals(1, onAllNodes(hasText("Reveal") and hasClickAction()).fetchSemanticsNodes().size)
        assertEquals(1, onAllNodes(hasText("Copy") and hasClickAction()).fetchSemanticsNodes().size)
        assertTrue(events.none { it is FeedEvent.RevealSharedSecret }, "nothing is fetched until asked")
    }

    @Test
    fun revealAsksTheDeviceFirstThenShowsTheValueInPlace() = runDesktopComposeUiTest(width = 1440, height = 900) {
        var allow = false
        screen(DeviceAuth { allow }, FakeClipboard())
        onAllNodes(hasText("Reveal") and hasClickAction()).onFirst().performClick()
        waitForIdle()
        onNodeWithText("Not confirmed, so it stays hidden.").assertExists()
        assertTrue(events.none { it is FeedEvent.RevealSharedSecret }, "nothing is fetched until the owner is confirmed")

        allow = true
        onAllNodes(hasText("Reveal") and hasClickAction()).onFirst().performClick()
        waitForIdle()
        val reveal = events.filterIsInstance<FeedEvent.RevealSharedSecret>().single()
        assertEquals(Triple("update", "STRIPE_TEST_KEY", false), Triple(reveal.secret.cardKind, reveal.secret.name, reveal.copy))
        assertTrue(!valueOnScreen(), "still masked until the value arrives")
        reveal.result(value, null, false)
        waitForIdle()
        assertTrue(valueOnScreen(), "shown in place")
        onNodeWithText("Hide").performClick()
        waitForIdle()
        assertTrue(!valueOnScreen(), "masked again")
        assertTrue(value !in reveal.toString(), "the event never prints the value")
    }

    @Test
    fun copyPutsTheValueOnTheClipboardWithoutShowingIt() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val clipboard = FakeClipboard()
        screen(DeviceAuth { true }, clipboard)
        onAllNodes(hasText("Copy") and hasClickAction()).onFirst().performClick()
        waitForIdle()
        val copy = events.filterIsInstance<FeedEvent.RevealSharedSecret>().single()
        assertTrue(copy.copy)
        assertNull(clipboard.held)
        copy.result(value, null, false)
        waitForIdle()
        assertEquals(value, clipboard.held?.text)
        onNodeWithText("Copied. The clipboard is cleared in a minute.").assertExists()
        assertTrue(!valueOnScreen(), "copying never shows it")
    }

    @Test
    fun aSecretThatIsGoneWhenOpenedSaysSo() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen(DeviceAuth { true }, FakeClipboard())
        onAllNodes(hasText("Reveal") and hasClickAction()).onFirst().performClick()
        waitForIdle()
        events.filterIsInstance<FeedEvent.RevealSharedSecret>().single().result(null, "No longer available.", true)
        waitForIdle()
        assertEquals(2, onAllNodes(hasText("No longer available")).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodes(hasText("Reveal") and hasClickAction()).fetchSemanticsNodes().size)
    }
}
