package dev.wildware.agents.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.engine.AppModel
import dev.wildware.agents.app.engine.AppScreen
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.LocalTourSeen
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.Tour
import dev.wildware.agents.app.feed.TourBubbleTag
import dev.wildware.agents.app.feed.TourSeen
import dev.wildware.agents.app.feed.TourStep
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guided tour: it starts by itself on a first sign-in to a team, never again once finished or
 * skipped, comes back from Settings, steps forward and back in order, and passes over any part of
 * the screen that isn't there rather than pointing at nothing.
 */
@OptIn(ExperimentalTestApi::class)
class TourTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tidy() {
        server.close()
        scope.cancel()
    }

    private fun ComposeUiTest.showFeed(
        state: FeedState = FakeFeed.state,
        due: Boolean = true,
        preview: ScreenPreview = ScreenPreview(),
        accessibility: AccessibilityPrefs = AccessibilityPrefs(),
        done: () -> Unit = {},
    ) = setContent {
        val seen = remember { TourSeen(due, done) }
        AgentTheme(dark = false, accessibility = accessibility) {
            CompositionLocalProvider(LocalTourSeen provides seen) { FeedScreen(state, preview = preview) }
        }
    }

    private val inBubble = hasAnyAncestor(hasTestTag(TourBubbleTag))

    private fun ComposeUiTest.bubbleShowing(): Boolean = onAllNodes(hasTestTag(TourBubbleTag)).fetchSemanticsNodes().isNotEmpty()

    /** Waits for the bubble to say [count] ("2 of 6") and [title]. */
    private fun ComposeUiTest.waitForStep(count: String, title: String) {
        waitUntil(timeoutMillis = 5_000) { onAllNodes(inBubble and hasText(count, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onNode(inBubble and hasText(title, substring = true)).assertExists()
    }

    private fun ComposeUiTest.press(label: String) = onNode(inBubble and hasText(label) and hasClickAction()).performClick()

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
    }

    @Test
    fun `the tour starts by itself and steps through the screen in order`() = runDesktopComposeUiTest(1440, 900) {
        var done = 0
        showFeed { done++ }
        waitForStep("1 of 6", "Channels")
        shot("tour-desktop-1-channels")
        val titles = listOf("The feed", "Replies", "Waiting on you", "Feed, Board and Docs", "Settings")
        titles.forEachIndexed { i, title ->
            press("Next")
            waitForStep("${i + 2} of 6", title)
            if (title == "The feed") shot("tour-desktop-2-feed")
        }
        onNode(inBubble and hasText("Back") and hasClickAction()).assertExists()
        press("Done")
        waitUntil(timeoutMillis = 5_000) { !bubbleShowing() }
        assertEquals(1, done, "finishing marks it seen")
    }

    @Test
    fun `Back goes to the step before and the first step has no Back`() = runDesktopComposeUiTest(1440, 900) {
        showFeed()
        waitForStep("1 of 6", "Channels")
        onNode(inBubble and hasText("Back")).assertDoesNotExist()
        press("Next")
        waitForStep("2 of 6", "The feed")
        press("Next")
        waitForStep("3 of 6", "Replies")
        press("Back")
        waitForStep("2 of 6", "The feed")
        press("Back")
        waitForStep("1 of 6", "Channels")
    }

    @Test
    fun `Skip ends the tour and marks it seen`() = runDesktopComposeUiTest(1440, 900) {
        var done = 0
        showFeed { done++ }
        waitForStep("1 of 6", "Channels")
        press("Next")
        waitForStep("2 of 6", "The feed")
        press("Skip tour")
        waitUntil(timeoutMillis = 5_000) { !bubbleShowing() }
        assertEquals(1, done)
    }

    @Test
    fun `Escape skips the tour`() = runDesktopComposeUiTest(1440, 900) {
        var done = 0
        showFeed { done++ }
        waitForStep("1 of 6", "Channels")
        onNodeWithTag(TourBubbleTag).performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) { !bubbleShowing() }
        assertEquals(1, done)
    }

    @Test
    fun `a team that has seen the tour doesn't show it`() = runDesktopComposeUiTest(1440, 900) {
        showFeed(due = false)
        waitForIdle()
        mainClock.advanceTimeBy(3_000)
        waitForIdle()
        assertFalse(bubbleShowing())
    }

    @Test
    fun `Show the tour again starts it from the first step`() = runDesktopComposeUiTest(1440, 900) {
        showFeed(due = false, preview = ScreenPreview(alertsOpen = true))
        onNodeWithText("Show the tour again").performScrollTo().performClick()
        waitForStep("1 of 6", "Channels")
        onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.PaneTitle, "Alerts on this device")).assertDoesNotExist()
    }

    @Test
    fun `steps with nothing to show are skipped`() = runDesktopComposeUiTest(1440, 900) {
        // An empty team: no cards, so no card and no thread to show.
        showFeed(FakeFeed.state.copy(sections = emptyList(), openThread = null))
        waitForStep("1 of 4", "Channels")
        press("Next")
        waitForStep("2 of 4", "Waiting on you")
        press("Next")
        waitForStep("3 of 4", "Feed, Board and Docs")
    }

    @Test
    fun `on a phone the tour opens a thread for the reply box and closes it after`() = runDesktopComposeUiTest(390, 844) {
        showFeed(FakeFeed.state.copy(openThread = null), accessibility = AccessibilityPrefs(reduceMotion = true))
        waitForStep("1 of 6", "Channels")
        onNode(inBubble and hasText("Tap Projects", substring = true)).assertExists()
        shot("tour-phone-1-channels")
        press("Next")
        waitForStep("2 of 6", "The feed")
        press("Next")
        waitForStep("3 of 6", "Replies")
        onNodeWithText("Thread").assertExists()
        shot("tour-phone-3-reply")
        press("Next")
        waitForStep("4 of 6", "Waiting on you")
        onNodeWithText("Thread").assertDoesNotExist()
        press("Skip tour")
        waitUntil(timeoutMillis = 5_000) { !bubbleShowing() }
    }

    @Test
    fun `a step passed going back turns forward at the start`() {
        val tour = Tour()
        var ended = 0
        tour.ended = { ended++ }
        tour.start(TourStep.entries)
        tour.pass()
        assertEquals(TourStep.Card, tour.step, "the first step had nothing to show: on to the next")
        assertEquals(5, tour.steps.size, "and it is no longer counted")
        tour.next()
        assertEquals(TourStep.Reply, tour.step)
        tour.back()
        tour.pass()
        assertEquals(TourStep.Reply, tour.step, "nothing before it: back where it was")
        repeat(3) { tour.next() }
        assertEquals(TourStep.Settings, tour.step)
        tour.pass()
        assertFalse(tour.on, "the last step had nothing to show: the tour ends")
        assertEquals(1, ended)
    }

    private fun platform(sessions: MemorySessionStore, teams: MemoryTextStore) = AppPlatform(
        sessions, MemoryTextStore(), dev.wildware.agents.MemoryOutboxStorage(), { server.engine() }, "Test device",
        imageCacheDir = null, teamList = teams, guidedTour = true,
    )

    private suspend fun AppModel.signedIn(): AppScreen.Signed = withTimeout(10_000) { screen.first { it is AppScreen.Signed } as AppScreen.Signed }

    @Test
    fun `the tour is due on a first sign-in to a team and not once it is done`() = runBlocking {
        val sessions = MemorySessionStore()
        val teams = MemoryTextStore()
        val first = AppModel(platform(sessions, teams), scope, accountService = "")
        first.signIn("https://a.test", "right", "Test device")
        first.signedIn()
        assertTrue(first.tourDue(demo = false), "a new sign-in shows the tour")
        first.tourDone(demo = false)
        assertFalse(first.tourDue(demo = false))
        // The app opened again on this device: still done.
        val again = AppModel(platform(sessions, teams), scope, accountService = "")
        again.signedIn()
        assertFalse(again.tourDue(demo = false))
    }

    @Test
    fun `a device already signed in before the tour existed doesn't show it`() = runBlocking {
        val model = AppModel(platform(MemorySessionStore(SavedSession("https://a.test", "key-1", "Test device")), MemoryTextStore()), scope, accountService = "")
        model.signedIn()
        assertFalse(model.tourDue(demo = false))
    }

    @Test
    fun `the demo shows the tour once while the app runs`() {
        val model = AppModel(platform(MemorySessionStore(), MemoryTextStore()), scope, accountService = "")
        assertTrue(model.tourDue(demo = true))
        model.tourDone(demo = true)
        assertFalse(model.tourDue(demo = true))
    }
}
