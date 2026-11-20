package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * On a desktop a thread's reply box sits inside the feed, and a tap on the feed closes an open
 * box. A click in the reply box itself is not a tap on the feed: it keeps the cursor, every time.
 */
@OptIn(ExperimentalTestApi::class)
class ReplyBoxClickTest {
    private val fix = FakeFeed.state.openThread as FeedItem.Update

    @Test
    fun clicksInsideTheOpenReplyBoxKeepItOpen() = runDesktopComposeUiTest(1440, 1000) {
        setContent { AgentTheme(dark = false) { Box(Modifier.size(1440.dp, 1000.dp)) { FeedScreen(FakeFeed.state.copy(openThread = null), startAt = fix.id) } } }
        onAllNodes(hasText("Reply") and hasClickAction()).onFirst().performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        val box = onAllNodes(hasSetTextAction() and hasContentDescription("Reply")).onFirst()
        box.performMouseInput { moveTo(center); click() }
        box.performTextInput("one ")
        repeat(3) { round ->
            // A second apart, as a person clicks: not a double or triple click, which selects words.
            // Checked while the button is still down: on the desktop the release puts the cursor
            // back, which hid the bug; in a browser the close landed after it and won.
            box.performMouseInput { advanceEventTime(1_000); press() }
            waitForIdle()
            box.assertIsFocused()
            box.performMouseInput { release() }
            waitForIdle()
            box.assertIsFocused()
            box.performTextInput("w$round ")
        }
        assertEquals("one w0 w1 w2 ", box.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun mentionAnAgentPutsTheCursorBackInTheBoxAndOpensTheList() = runDesktopComposeUiTest(1440, 900) {
        setContent { AgentTheme(dark = false) { Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(FakeFeed.state) } } }
        val box = onNode(hasSetTextAction() and hasContentDescription("Message"))
        box.performClick()
        box.performTextInput("hi")
        onNode(hasContentDescription("Mention an agent") and hasClickAction()).performClick()
        waitForIdle()
        box.assertIsFocused()
        assertEquals("hi @", box.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        // The list of who can be mentioned is up, and typing goes on in the box after the "@".
        onAllNodes(hasContentDescription("to mention", substring = true)).onFirst().assertExists()
        box.performTextInput("a")
        waitForIdle()
        assertEquals("hi @a", box.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
}
