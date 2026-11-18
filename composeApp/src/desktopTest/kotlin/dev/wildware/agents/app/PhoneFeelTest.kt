package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipe
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedSection
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.hasText
import dev.wildware.agents.app.feed.MarkdownCache
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.MenuTarget
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

    private fun ComposeUiTest.phoneFeed(preview: ScreenPreview = ScreenPreview(), reduceMotion: Boolean = false, state: FeedState = FakeFeed.state.copy(openThread = null)) = setContent {
        AgentTheme(dark = true, AccessibilityPrefs(reduceMotion = reduceMotion)) {
            Box(Modifier.size(390.dp, 844.dp)) { FeedScreen(state, SafeArea(54.dp, 34.dp), preview = preview) }
        }
    }

    private val menu = ScreenPreview(menu = MenuTarget("u1", "forge", "Reading messages in one session no longer hides them", own = false))

    @Test
    fun theMenuSheetSinksAwayRatherThanVanishing() = runComposeUiTest {
        mainClock.autoAdvance = false
        phoneFeed(menu)
        mainClock.advanceTimeBy(300)
        onNodeWithText("Cancel").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithText("Cancel").assertExists("still on screen, on its way out")
        mainClock.advanceTimeBy(800)
        onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun withReducedMotionTheMenuGoesAtOnce() = runComposeUiTest {
        mainClock.autoAdvance = false
        phoneFeed(menu, reduceMotion = true)
        mainClock.advanceTimeBy(300)
        onNodeWithText("Cancel").performClick()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun theFirstLoadShowsTheFeedsShapeNotAWait() = runComposeUiTest {
        phoneFeed(state = FakeFeed.state.copy(openThread = null, loaded = false, sections = emptyList()))
        onNodeWithContentDescription("Loading the feed").assertExists()
        onNodeWithText("Loading the feed…").assertDoesNotExist()
    }

    @Test
    fun aButtonAnswersATouchInTheNextFrame() = runComposeUiTest {
        mainClock.autoAdvance = false
        phoneFeed()
        mainClock.advanceTimeBy(300)
        val button = onNodeWithContentDescription("Notifications")
        val before = button.captureToImage().toPixelMap()
        button.performTouchInput { down(center) }
        mainClock.advanceTimeByFrame()
        val pressed = button.captureToImage().toPixelMap()
        button.performTouchInput { up() }
        mainClock.advanceTimeBy(600)
        val after = button.captureToImage().toPixelMap()
        val middle = before.width / 2 to before.height / 2
        assertTrue(before[middle.first, middle.second] != pressed[middle.first, middle.second], "pressing changed nothing in the first frame")
        assertEquals(before[middle.first, middle.second], after[middle.first, middle.second], "it eased back once let go")
    }

    /** A finger that lands on a card and then scrolls cancels the press: the card must not stay dimmed. */
    @Test
    fun aCancelledPressLetsGo() = runComposeUiTest {
        mainClock.autoAdvance = false
        val source = MutableInteractionSource()
        val press = PressInteraction.Press(androidx.compose.ui.geometry.Offset(10f, 10f))
        setContent {
            AgentTheme(dark = true) {
                Box(Modifier.size(44.dp).background(AgentTheme.colors.background).indication(source, LocalIndication.current).testTag("button"))
            }
        }
        mainClock.advanceTimeByFrame()
        val before = onNodeWithTag("button").captureToImage().toPixelMap()
        runOnIdle { kotlinx.coroutines.runBlocking { source.emit(press) } }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val pressed = onNodeWithTag("button").captureToImage().toPixelMap()
        runOnIdle { kotlinx.coroutines.runBlocking { source.emit(PressInteraction.Cancel(press)) } }
        mainClock.advanceTimeBy(600)
        val after = onNodeWithTag("button").captureToImage().toPixelMap()
        assertTrue(before[22, 22] != pressed[22, 22], "the press showed")
        assertEquals(before[22, 22], after[22, 22], "the press let go")
    }

    /** Parsing markdown mid-frame drops frames on a phone. The engine parses ahead, off the UI thread. */
    @Test
    fun theUiThreadNeverParsesMarkdown() = runComposeUiTest {
        val server = FakeServer()
        val parsedOn = java.util.concurrent.ConcurrentLinkedQueue<Thread>()
        var uiThread: Thread? = null
        MarkdownCache.onParse = { parsedOn += Thread.currentThread() }
        try {
            val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device")))
            setContent {
                SideEffect { uiThread = Thread.currentThread() }
                Box(Modifier.size(390.dp, 844.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) }
            }
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Fixed the unread counts", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            assertTrue(parsedOn.isNotEmpty(), "the engine parsed the feed's markdown")
            assertTrue(parsedOn.none { it == uiThread }, "${parsedOn.count { it == uiThread }} parses ran on the UI thread")
        } finally {
            MarkdownCache.onParse = null
            server.close()
        }
    }

    /** Scrolling must only draw the cards that come into view: never the screen, the header or cards already shown. */
    @Test
    fun scrollingDrawsOnlyTheCardsComingIntoView() = runComposeUiTest {
        val cards = FakeFeed.state.sections.flatMap { it.items }
        val long = FakeFeed.state.copy(openThread = null, sections = List(12) { s ->
            FeedSection("Day $s", cards.map { item ->
                when (item) {
                    is FeedItem.Update -> item.copy(id = "${item.id}-$s")
                    is FeedItem.Post -> item.copy(id = "${item.id}-$s")
                    is FeedItem.Question -> item.copy(id = "${item.id}-$s")
                }
            })
        })
        phone { FeedScreen(long, SafeArea(54.dp, 34.dp)) }
        waitForIdle()
        count()
        // Five slow drags up, each a screen's height, as a thumb reads down the feed.
        repeat(5) {
            onRoot().performTouchInput { swipe(Offset(195f, 760f), Offset(195f, 120f), durationMillis = 400) }
            waitForIdle()
        }
        val cardsDrawn = (calls["UpdateCard"] ?: 0) + (calls["PostCard"] ?: 0) + (calls["QuestionCard"] ?: 0)
        println("Scrolling five screens drew $cardsDrawn cards and ${calls.values.sum()} composables")
        assertEquals(0, calls["PhoneFeed"] ?: 0, "the screen redrew while scrolling: $calls")
        assertEquals(0, calls["FeedHeader"] ?: 0, "the header redrew while scrolling")
        assertTrue(cardsDrawn in 1..40, "drew $cardsDrawn cards for about five screens of scrolling")
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
