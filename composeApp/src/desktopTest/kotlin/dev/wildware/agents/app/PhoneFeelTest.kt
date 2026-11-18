package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedUi
import dev.wildware.agents.app.feed.LocalFeedUi
import dev.wildware.agents.app.feed.PhoneThreadScreen
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.ScreenPreview
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The phone layout at iPhone size (390 by 844), counting what redraws while something moves: the
 * keyboard sliding, a thread sliding in, the drawer opening, a key being typed. Counted, not timed,
 * so it holds on a busy machine. Anything that redraws per frame here costs frames on a phone.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
class PhoneFeelTest {
    private val calls = HashMap<String, Int>()

    private fun count() {
        calls.clear()
        Composer.setTracer(object : CompositionTracer {
            override fun isTraceInProgress() = true
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                val name = info.substringBefore(" (").substringAfterLast('.')
                calls[name] = (calls[name] ?: 0) + 1
            }
            override fun traceEventEnd() {}
        })
    }

    @AfterTest
    fun stopCounting() = Composer.setTracer(null)

    private fun ComposeUiTest.phone(content: @androidx.compose.runtime.Composable () -> Unit) = setContent {
        AgentTheme(dark = true) { Box(Modifier.size(390.dp, 844.dp)) { content() } }
    }

    /** The keyboard's slide, as iOS reports it: the bottom inset rising over a quarter second. */
    private val keyboardFrames = List(30) { (34 + (336 - 34) * (it + 1) / 30).dp }

    @Test
    fun theKeyboardSlidingRedrawsNothing() = runComposeUiTest {
        val keyboard = mutableStateOf(34.dp)
        val area = SafeArea.live(top = { 54.dp }, bottom = { keyboard.value })
        phone { FeedScreen(FakeFeed.state.copy(openThread = null), area) }
        waitForIdle()
        val box = onNode(hasSetTextAction() and hasContentDescription("Message"))
        val before = box.fetchSemanticsNode().boundsInRoot.bottom

        count()
        keyboardFrames.forEach { keyboard.value = it; waitForIdle() }
        val after = box.fetchSemanticsNode().boundsInRoot.bottom

        assertEquals(0, calls.values.sum(), "a keyboard slide redrew: $calls")
        assertTrue(before - after > 250f, "the composer rose with the keyboard (from $before to $after)")
    }

    /** What the app did before: a fresh safe area each frame, read while composing. Kept to show the cost. */
    @Test
    fun aSafeAreaReadWhileComposingRedrawsTheFeedEveryFrame() = runComposeUiTest {
        val keyboard = mutableStateOf(34.dp)
        phone { FeedScreen(FakeFeed.state.copy(openThread = null), SafeArea(top = 54.dp, bottom = keyboard.value)) }
        waitForIdle()
        count()
        keyboardFrames.forEach { keyboard.value = it; waitForIdle() }
        val perFrame = calls.values.sum() / keyboardFrames.size
        println("Before: a keyboard slide redrew $perFrame composables a frame (${calls.values.sum()} over ${keyboardFrames.size} frames)")
        assertTrue(perFrame > 0)
    }

    @Test
    fun aThreadSlidesInAndOutWithoutRedrawingItsReplies() = runComposeUiTest {
        val ui = FeedUi(ScreenPreview(), mutableStateOf(FakeFeed.state)) {}
        val thread = assertNotNull(ui.thread)
        ui.openThread(thread.id, phone = true)
        mainClock.autoAdvance = false
        phone {
            CompositionLocalProvider(LocalFeedUi provides ui) { PhoneThreadScreen(ui, thread, widthPx = 390f, SafeArea(54.dp, 34.dp)) }
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        assertTrue(ui.threadBack.value > 0.5f, "it starts off to the side")

        count()
        repeat(40) { mainClock.advanceTimeByFrame() }
        assertEquals(0f, ui.threadBack.value, "it slid all the way in")
        assertEquals(0, (calls["ReplyView"] ?: 0) + (calls["InteractiveReply"] ?: 0) + (calls["MarkdownBody"] ?: 0), "sliding in redrew replies: $calls")
        assertEquals(0, calls["PhoneThreadScreen"] ?: 0, "sliding in redrew the thread screen: $calls")

        onNodeWithContentDescription("Back to", substring = true).performClick()
        count()
        repeat(40) { mainClock.advanceTimeByFrame() }
        assertEquals(false, ui.phoneThreadOpen, "it slid away and closed")
        assertEquals(0, (calls["ReplyView"] ?: 0) + (calls["InteractiveReply"] ?: 0), "sliding out redrew replies: $calls")
    }

    @Test
    fun theDrawerOpensWithoutRedrawingTheFeedOrItsRows() = runComposeUiTest {
        mainClock.autoAdvance = false
        phone { FeedScreen(FakeFeed.state.copy(openThread = null)) }
        mainClock.advanceTimeBy(500)
        onNodeWithContentDescription("unread elsewhere", substring = true).performClick()
        // The first frames put the drawer on screen; after that it should only move.
        repeat(3) { mainClock.advanceTimeByFrame() }
        count()
        repeat(40) { mainClock.advanceTimeByFrame() }
        assertEquals(0, calls["DrawerProjectRow"] ?: 0, "rows redrew while the drawer slid: $calls")
        assertEquals(0, calls["PhoneFeed"] ?: 0, "the feed redrew while the drawer slid: $calls")
        assertEquals(0, calls["ProjectsDrawer"] ?: 0, "the drawer redrew while it slid: $calls")
    }

    @Test
    fun typingRedrawsTheBoxOnlyWhenItStopsBeingEmpty() = runComposeUiTest {
        phone { FeedScreen(FakeFeed.state.copy(openThread = null), SafeArea(54.dp, 34.dp)) }
        waitForIdle()
        val box = onNode(hasSetTextAction() and hasContentDescription("Message"))
        box.performTextInput("H")
        waitForIdle()
        count()
        "ello there".forEach { box.performTextInput(it.toString()); waitForIdle() }
        assertEquals(0, calls["Composer"] ?: 0, "each key redrew the whole composer: $calls")
    }
}
