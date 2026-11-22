package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.ScreenPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cards pin and unpin from their menu, on desktop, phones and screen readers. */
@OptIn(ExperimentalTestApi::class)
class PinTest {
    private val events = mutableListOf<FeedEvent>()
    private val fixTitle = "Reading messages in one session no longer hides them from another"
    private val diskTitle = "Build box disk is 86% full"

    /** The feed with the disk update already pinned. */
    private val withPinned: FeedState = FakeFeed.state.copy(
        openThread = null,
        sections = FakeFeed.state.sections.map { s ->
            s.copy(items = s.items.map { if (it is FeedItem.Update && it.id == "u3") it.copy(pinned = true) else it })
        },
    )

    private fun ComposeUiTest.feed(width: Int, startAt: String? = "u1", state: FeedState = withPinned, preview: ScreenPreview = ScreenPreview()) = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.size(width.dp, 900.dp)) { FeedScreen(state, startAt = startAt, preview = preview, onEvent = { events += it }) }
        }
    }

    @Test
    fun pinningFromTheDesktopMenuPinsAndTheMenuThenOffersUnpin() = runDesktopComposeUiTest(1440, 900) {
        feed(1440)
        onNodeWithText(fixTitle).performScrollTo().performMouseInput { rightClick(center) }
        onNodeWithText("Pin").performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.Pin("u1", pinned = true)), events)
        // Shown as pinned straight away, before the server answers.
        onNode(hasContentDescription("forge", substring = true) and hasContentDescription("pinned", substring = true)).assertExists()
        onNodeWithText(fixTitle).performMouseInput { rightClick(center) }
        onNodeWithText("Unpin").assertExists()
    }

    @Test
    fun aPinnedCardUnpinsFromAPhone() = runComposeUiTest {
        feed(390, startAt = "u3")
        onNodeWithText(diskTitle).performScrollTo().performTouchInput { longClick() }
        onNodeWithText("Unpin").performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.Pin("u3", pinned = false)), events)
    }

    @Test
    fun pinIsAScreenReaderActionToo() = runComposeUiTest {
        feed(390)
        val node = onAllNodes(SemanticsMatcher("has action 'Pin'") { n ->
            n.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Pin" } == true
        }).onFirst().fetchSemanticsNode()
        runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == "Pin" }.action() }
        assertTrue(FeedEvent.Pin("u1", pinned = true) in events, "got $events")
    }

    @Test
    fun aReplyInAThreadHasNoPin() = runComposeUiTest {
        feed(390, startAt = null, state = FakeFeed.state, preview = ScreenPreview(threadOpen = true))
        // Held on the reply's name: holding its text selects the text instead.
        onNode(hasContentDescription("forge, answering you, 9:54")).performScrollTo().performTouchInput { longClick() }
        onNodeWithText("Reply").assertExists()
        onNodeWithText("Pin").assertDoesNotExist()
    }
}
