package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.BackSwipe
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.LocalSystemBack
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.NavigationSystemBack
import dev.wildware.agents.app.feed.SystemBack
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The platform's own back swipe, driving the feed. On the iPhone that is UIKit's screen-edge pan:
 * iOS holds back a touch that starts at the very edge of the screen, so the app's own pointer
 * handling never saw an edge swipe begin at the edge, and nothing happened. UIKit's recognizer
 * gets the whole swipe, and the feed follows the finger from it.
 */
@OptIn(ExperimentalTestApi::class)
class SystemBackTest {
    /** Stands in for UIKit (or Android): the test plays the swipe through the handler's callbacks. */
    private class FakeSystemBack(override val ownsLeftEdge: Boolean) : SystemBack {
        var enabled = false
        var progress: (BackSwipe) -> Unit = {}
        var cancel: () -> Unit = {}
        var back: () -> Unit = {}

        @Composable
        override fun Handler(enabled: Boolean, onProgress: (BackSwipe) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
            this.enabled = enabled
            progress = onProgress
            cancel = onCancel
            back = onBack
        }
    }

    /** What the phone's haptics were asked for, in order. */
    private val felt = mutableListOf<androidx.compose.ui.hapticfeedback.HapticFeedbackType>()
    private val haptics = object : androidx.compose.ui.hapticfeedback.HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: androidx.compose.ui.hapticfeedback.HapticFeedbackType) {
            felt += hapticFeedbackType
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.phone(system: FakeSystemBack, preview: ScreenPreview = ScreenPreview()) = setContent {
        CompositionLocalProvider(LocalSystemBack provides system, androidx.compose.ui.platform.LocalHapticFeedback provides haptics) {
            AgentTheme(dark = true) {
                Box(Modifier.size(390.dp, 844.dp)) {
                    FeedScreen(FakeFeed.state.copy(openThread = FakeFeed.state.openThread), preview = preview)
                }
            }
        }
    }

    @Test
    fun onTheIphoneAnEdgeSwipeAtTheFeedOpensTheDrawerUnderTheFinger() = runComposeUiTest {
        val system = FakeSystemBack(ownsLeftEdge = true)
        phone(system)
        assertTrue(system.enabled, "the feed takes the left edge on the iPhone")
        onNodeWithText("Hold to reorder").assertDoesNotExist()

        system.progress(BackSwipe(progress = 0.2f, touchX = 120f))
        waitForIdle()
        onNodeWithText("Hold to reorder").assertExists()

        system.back()
        waitForIdle()
        onNodeWithText("Hold to reorder").assertExists()
    }

    @Test
    fun onTheIphoneTheEdgeSwipeTicksAtTheCommitPointAndConfirmsOnLettingGo() = runComposeUiTest {
        val system = FakeSystemBack(ownsLeftEdge = true)
        phone(system)

        system.progress(BackSwipe(progress = 0.05f, touchX = 20f))
        waitForIdle()
        assertEquals(emptyList(), felt, "nothing before the commit point")
        system.progress(BackSwipe(progress = 0.5f, touchX = 200f))
        system.progress(BackSwipe(progress = 0.6f, touchX = 230f))
        waitForIdle()
        assertEquals(listOf(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate), felt, "one tick crossing it, not one per step")
        system.back()
        waitForIdle()
        assertEquals(listOf(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate, androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureEnd), felt)
    }

    @Test
    fun onTheIphoneASwipeSlidBackFeelsNoConfirm() = runComposeUiTest {
        val system = FakeSystemBack(ownsLeftEdge = true)
        phone(system)
        system.progress(BackSwipe(progress = 0.5f, touchX = 200f))
        system.progress(BackSwipe(progress = 0.1f, touchX = 20f))
        system.cancel()
        waitForIdle()

        // Back across the line ticks again, so the finger knows it will not commit; letting go is silent.
        assertEquals(listOf(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate, androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate), felt)
    }

    @Test
    fun onAndroidBackWithNothingOpenGoesBackToTheChannelBefore() = runComposeUiTest {
        val system = FakeSystemBack(ownsLeftEdge = false)
        val navigator = dev.wildware.agents.app.feed.AppNavigator()
        val picked = mutableListOf<String?>()
        val state = androidx.compose.runtime.mutableStateOf(FakeFeed.state.copy(openThread = null))
        setContent {
            CompositionLocalProvider(LocalSystemBack provides system) {
                AgentTheme(dark = true) {
                    Box(Modifier.size(390.dp, 844.dp)) {
                        FeedScreen(state, navigator = navigator, onEvent = { e ->
                            if (e is dev.wildware.agents.app.feed.FeedEvent.SelectProject) {
                                picked += e.slug
                                state.value.projects.firstOrNull { it.slug == e.slug }?.let { state.value = state.value.copy(current = it) }
                            }
                        })
                    }
                }
            }
        }
        waitForIdle()
        assertFalse(system.enabled, "with nowhere to go back to, back leaves the app")
        navigator.open("ios-companion", null)
        waitForIdle()
        assertTrue(system.enabled, "another channel was opened: back goes to the one before")
        system.back()
        waitForIdle()
        assertEquals(listOf<String?>("ios-companion", "agent-dashboard"), picked)
        assertFalse(system.enabled)
    }

    @Test
    fun onAndroidTheSystemGivesItsOwnFeelSoTheAppAddsNone() = runComposeUiTest {
        val system = FakeSystemBack(ownsLeftEdge = false)
        phone(system, ScreenPreview(threadOpen = true))
        system.progress(BackSwipe(progress = 0.6f, touchX = 230f))
        system.back()
        waitForIdle()
        assertEquals(emptyList(), felt)
    }

    @Test
    fun anEdgeSwipeLetGoEarlySlidesTheDrawerShutAgain() = runComposeUiTest {
        val system = FakeSystemBack(ownsLeftEdge = true)
        phone(system)

        system.progress(BackSwipe(progress = 0.05f, touchX = 30f))
        waitForIdle()
        system.cancel()
        waitForIdle()

        onNodeWithText("Hold to reorder").assertDoesNotExist()
    }

    @Test
    fun onTheIphoneAnEdgeSwipeInAThreadGoesBack() = runComposeUiTest {
        val system = FakeSystemBack(ownsLeftEdge = true)
        phone(system, ScreenPreview(threadOpen = true))
        onNodeWithContentDescription("Back to agent-dashboard", substring = true).assertExists()

        system.progress(BackSwipe(progress = 0.4f, touchX = 200f))
        system.back()
        waitForIdle()

        onNodeWithContentDescription("Back to agent-dashboard", substring = true).assertDoesNotExist()
        // Going back from a thread lands on the feed, not in the drawer.
        onNodeWithText("Hold to reorder").assertDoesNotExist()
    }

    @Test
    fun whereTheSystemOwnsTheEdgeTheAppsOwnEdgeDragStandsAside() = runComposeUiTest {
        val system = FakeSystemBack(ownsLeftEdge = true)
        phone(system)

        // Two handlers on one touch would fight; on the iPhone UIKit's is the one that sees it.
        onRoot().performTouchInput { swipe(Offset(4f, 400f), Offset(330f, 400f), 250) }
        waitForIdle()

        onNodeWithText("Hold to reorder").assertDoesNotExist()
    }

    @Test
    fun onAndroidBackAtTheFeedIsLeftToTheSystem() = runComposeUiTest {
        val system = FakeSystemBack(ownsLeftEdge = false)
        phone(system)

        // Back at the top of the app leaves it; the app's own edge drag opens the drawer instead.
        assertFalse(system.enabled)
        onRoot().performTouchInput { swipe(Offset(4f, 400f), Offset(330f, 400f), 250) }
        waitForIdle()
        onNodeWithText("Hold to reorder").assertExists()
    }

    /**
     * The iPhone's handler itself, fed the way UIKit's edge pan feeds it on the phone: through
     * Compose's navigation events. A swipe started, moved and let go reaches the feed as finger
     * positions and then back; one slid back reaches it as a cancel.
     */
    @Test
    fun theIphoneHandlerHearsAnEdgePanAsItHappens() = runComposeUiTest {
        val heard = mutableListOf<String>()
        val input = DirectNavigationEventInput()
        setContent {
            val owner = LocalNavigationEventDispatcherOwner.current!!
            androidx.compose.runtime.DisposableEffect(owner) {
                owner.navigationEventDispatcher.addInput(input)
                onDispose { owner.navigationEventDispatcher.removeInput(input) }
            }
            NavigationSystemBack(ownsLeftEdge = true).Handler(
                enabled = true,
                onProgress = { heard += "at ${it.touchX.toInt()}" },
                onCancel = { heard += "cancel" },
                onBack = { heard += "back" },
            )
        }
        waitForIdle()

        input.backStarted(NavigationEvent(touchX = 10f, touchY = 300f, progress = 0f, swipeEdge = NavigationEvent.EDGE_LEFT))
        waitForIdle()
        input.backProgressed(NavigationEvent(touchX = 140f, touchY = 300f, progress = 0.4f, swipeEdge = NavigationEvent.EDGE_LEFT))
        waitForIdle()
        input.backCompleted()
        waitForIdle()
        assertEquals("at 140", heard.lastOrNull { it.startsWith("at") })
        assertEquals("back", heard.last())

        input.backStarted(NavigationEvent(touchX = 10f, touchY = 300f, progress = 0f, swipeEdge = NavigationEvent.EDGE_LEFT))
        waitForIdle()
        input.backCancelled()
        waitForIdle()
        assertEquals("cancel", heard.last())
    }
}
