package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.AppNavigator
import dev.wildware.agents.app.feed.BackSwipe
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.LocalSystemBack
import dev.wildware.agents.app.feed.SystemBack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On a phone, once a channel is open there has to be a way back to All channels: the drawer has
 * it at its top, as the desktop's sidebar does, and Android's back goes up to it.
 */
@OptIn(ExperimentalTestApi::class)
class PhoneBackToAllTest {
    /** Stands in for Android's back: the test presses it through the handler's callbacks. */
    private class FakeBack(override val ownsLeftEdge: Boolean) : SystemBack {
        var enabled = false
        var back: () -> Unit = {}

        @Composable
        override fun Handler(enabled: Boolean, onProgress: (BackSwipe) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
            this.enabled = enabled
            back = onBack
        }
    }

    /** A phone-sized feed whose engine shows whichever channel is picked, All channels for null. */
    private fun androidx.compose.ui.test.ComposeUiTest.phone(system: SystemBack, navigator: AppNavigator? = null): MutableList<String?> {
        val picked = mutableListOf<String?>()
        val state = mutableStateOf(FakeFeed.state.copy(openThread = null))
        fun show(slug: String?): FeedState =
            if (slug == null) FakeFeed.allProjects.copy(openThread = null)
            else state.value.copy(current = state.value.projects.first { it.slug == slug }, sections = FakeFeed.state.sections)
        setContent {
            CompositionLocalProvider(LocalSystemBack provides system) {
                AgentTheme(dark = true) {
                    Box(Modifier.size(390.dp, 844.dp)) {
                        FeedScreen(state, navigator = navigator, onEvent = { e ->
                            if (e is FeedEvent.SelectProject) {
                                picked += e.slug
                                state.value = show(e.slug)
                            }
                        })
                    }
                }
            }
        }
        waitForIdle()
        return picked
    }

    @Test
    fun theDrawerGoesBackToAllChannels() = runComposeUiTest {
        val picked = phone(FakeBack(ownsLeftEdge = true))
        // A channel is open: the phone's top bar opens the drawer.
        onNodeWithContentDescription("Projects", substring = true).performClick()
        waitForIdle()

        onNodeWithContentDescription("All channels", substring = true).performClick()
        waitForIdle()

        assertEquals(listOf<String?>(null), picked)
        onNodeWithText("Hold to reorder").assertDoesNotExist()
        onNodeWithContentDescription("Projects", substring = true).performClick()
        waitForIdle()
        onNodeWithContentDescription("All channels", substring = true).assertIsSelected()
    }

    @Test
    fun onAndroidBackFromTheChannelItOpenedOnGoesUpToAllChannels() = runComposeUiTest {
        val system = FakeBack(ownsLeftEdge = false)
        val picked = phone(system)

        assertTrue(system.enabled, "a channel is open: back goes up to All channels")
        system.back()
        waitForIdle()
        assertEquals(listOf<String?>(null), picked)

        // All channels is the top: back from it leaves the app, not round to the channel again.
        assertFalse(system.enabled)
    }

    @Test
    fun onAndroidBackWalksTheChannelsThenGoesUpToAll() = runComposeUiTest {
        val system = FakeBack(ownsLeftEdge = false)
        val navigator = AppNavigator()
        val picked = phone(system, navigator)

        navigator.open("ios-companion", null)
        waitForIdle()
        system.back()
        waitForIdle()
        system.back()
        waitForIdle()

        assertEquals(listOf<String?>("ios-companion", "agent-dashboard", null), picked)
        assertFalse(system.enabled)
    }
}
