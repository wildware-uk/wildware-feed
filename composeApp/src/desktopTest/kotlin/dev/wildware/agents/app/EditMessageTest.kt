package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The owner changes the words of their own post or reply from its menu; nobody else's. */
@OptIn(ExperimentalTestApi::class)
class EditMessageTest {
    private val events = mutableListOf<FeedEvent>()
    private val fixTitle = "Reading messages in one session no longer hides them from another"

    private fun ComposeUiTest.feed(width: Int, startAt: String) = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.size(width.dp, 900.dp)) {
                FeedScreen(FakeFeed.state.copy(openThread = null), startAt = startAt, onEvent = { events += it })
            }
        }
    }

    private val inDialog = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog))

    @Test
    fun yourOwnPostCanBeEditedOnADesktop() = runDesktopComposeUiTest(1440, 900) {
        feed(1440, startAt = "u2")
        onNode(hasContentDescription("You, 8:57", substring = true)).performScrollTo().performMouseInput { rightClick(center) }
        onNodeWithText("Edit").performScrollTo().performClick()
        onNodeWithText("Edit message").assertExists()
        onNode(hasSetTextAction() and inDialog).performTextReplacement("Why does the Mac build take 9 minutes?")
        onNode(hasText("Save") and hasClickAction() and inDialog).performClick()
        waitForIdle()
        assertEquals(
            listOf<FeedEvent>(FeedEvent.SaveEdit("o1", "Why does the Mac build take 9 minutes?")),
            events.filterIsInstance<FeedEvent.SaveEdit>(),
        )
        onNodeWithText("Edit message").assertDoesNotExist()
    }

    @Test
    fun unchangedWordsSendNothing() = runDesktopComposeUiTest(1440, 900) {
        feed(1440, startAt = "u2")
        onNode(hasContentDescription("You, 8:57", substring = true)).performScrollTo().performMouseInput { rightClick(center) }
        onNodeWithText("Edit").performScrollTo().performClick()
        onNode(hasText("Save") and hasClickAction() and inDialog).performClick()
        waitForIdle()
        assertTrue(events.none { it is FeedEvent.SaveEdit }, "got $events")
    }

    @Test
    fun anAgentsUpdateHasNoEdit() = runDesktopComposeUiTest(1440, 900) {
        feed(1440, startAt = "u1")
        onNodeWithText(fixTitle).performScrollTo().performMouseInput { rightClick(center) }
        onNodeWithText("Delete").assertExists()
        onNodeWithText("Edit").assertDoesNotExist()
    }

    @Test
    fun yourOwnPostCanBeEditedOnAPhone() = runComposeUiTest {
        feed(390, startAt = "u2")
        onNode(hasContentDescription("You, 8:57", substring = true)).performTouchInput { longClick() }
        onNodeWithText("Edit").performScrollTo().performClick()
        onNodeWithText("Edit message").assertExists()
    }
}
