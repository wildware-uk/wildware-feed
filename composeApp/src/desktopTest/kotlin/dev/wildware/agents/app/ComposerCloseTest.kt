package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.BackSwipe
import dev.wildware.agents.app.feed.Composer
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedUi
import dev.wildware.agents.app.feed.LocalFeedUi
import dev.wildware.agents.app.feed.LocalSystemBack
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.SystemBack
import kotlin.test.Test

/**
 * Once the message box is open (the cursor in it, the keyboard up) it closes without sending: its
 * close button, a swipe down on it, a tap on the feed, back, or Escape. What was written stays.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerCloseTest {
    private class FakeSystemBack : SystemBack {
        override val ownsLeftEdge = true
        var back: () -> Unit = {}

        @Composable
        override fun Handler(enabled: Boolean, onProgress: (BackSwipe) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
            back = onBack
        }
    }

    private val system = FakeSystemBack()

    private fun ComposeUiTest.phone() = setContent {
        CompositionLocalProvider(LocalSystemBack provides system) {
            AgentTheme(dark = false) {
                Box(Modifier.size(390.dp, 844.dp)) { FeedScreen(FakeFeed.state) }
            }
        }
    }

    private val box get() = hasSetTextAction() and androidx.compose.ui.test.hasContentDescription("Message")

    private fun ComposeUiTest.openAndWrite() {
        onNode(box).performClick()
        onNode(box).performTextInput("half a thought")
        onNode(box).assertIsFocused()
    }

    private fun ComposeUiTest.closedWithDraftKept() {
        onNode(box).assertIsNotFocused()
        onNodeWithText("half a thought").assertExists()
        onNodeWithContentDescription("Close the message box").assertDoesNotExist()
    }

    @Test
    fun theOpenBoxHasACloseButton() = runComposeUiTest {
        phone()
        onNodeWithContentDescription("Close the message box").assertDoesNotExist()
        openAndWrite()
        onNodeWithContentDescription("Close the message box").performClick()
        closedWithDraftKept()
    }

    @Test
    fun backClosesTheOpenBoxBeforeAnythingElse() = runComposeUiTest {
        phone()
        openAndWrite()
        runOnIdle { system.back() }
        closedWithDraftKept()
        // The drawer did not open: back only closed the box.
        onNodeWithText("Hold to reorder").assertDoesNotExist()
    }

    @Test
    fun tappingTheFeedClosesTheOpenBox() = runComposeUiTest {
        phone()
        openAndWrite()
        onRoot().performTouchInput { click(Offset(195.dp.toPx(), 400.dp.toPx())) }
        closedWithDraftKept()
    }

    @Test
    fun swipingTheBoxDownClosesIt() = runComposeUiTest {
        phone()
        openAndWrite()
        onNode(box).performTouchInput { swipeDown(startY = top, endY = top + 120.dp.toPx()) }
        closedWithDraftKept()
    }

    @Test
    fun escapeClosesTheBoxOnADesktop() = runComposeUiTest {
        setContent {
            AgentTheme(dark = false) {
                Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(FakeFeed.state) }
            }
        }
        val feedBox = hasSetTextAction() and androidx.compose.ui.test.hasContentDescription("Message")
        onNode(feedBox).performClick()
        onNode(feedBox).performTextInput("half a thought")
        onNode(feedBox).performKeyInput { pressKey(Key.Escape) }
        onNode(feedBox).assertIsNotFocused()
        onNodeWithText("half a thought").assertExists()
    }

    @Test
    fun aBoxKeepsItsDraftWhenItLeavesTheScreenAndComesBack() = runComposeUiTest {
        val ui = FeedUi(ScreenPreview(), mutableStateOf(FakeFeed.state)) {}
        var shown by mutableStateOf(true)
        setContent {
            AgentTheme(dark = false) {
                CompositionLocalProvider(LocalFeedUi provides ui) {
                    if (shown) Composer(placeholder = "Reply to the thread…", onSend = { _, _ -> }, label = "Message", draftKey = "thread:t1")
                }
            }
        }
        onNode(box).performTextInput("half a thought")
        shown = false
        waitForIdle()
        onNode(box).assertDoesNotExist()
        shown = true
        waitForIdle()
        onNodeWithText("half a thought").assertExists()
    }
}
