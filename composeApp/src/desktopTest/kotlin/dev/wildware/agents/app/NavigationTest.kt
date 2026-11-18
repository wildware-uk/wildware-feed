package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.AppNavigator
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.OpenCard
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class NavigationTest {
    @Test
    fun aProjectPageWithAFocusOpensThatCard() {
        assertEquals(OpenCard("garden-game", "abc"), OpenCard.fromPath("/projects/garden-game?focus=abc"))
    }

    @Test
    fun aFullAddressReadsTheSameAsItsPath() {
        assertEquals(OpenCard("garden-game", "abc"), OpenCard.fromPath("/projects/garden-game?x=1&focus=abc#top"))
    }

    @Test
    fun aProjectPageAloneOpensTheProject() {
        assertEquals(OpenCard("harbor-app", null), OpenCard.fromPath("/projects/harbor-app"))
    }

    @Test
    fun theHomePageOpensEveryProject() {
        assertEquals(OpenCard(null, null), OpenCard.fromPath("/"))
        assertEquals(OpenCard(null, null), OpenCard.fromPath(""))
    }

    @Test
    fun theSameCardTwiceIsTwoRequests() {
        val navigator = AppNavigator()
        navigator.open("p", "c")
        val first = navigator.requests.value
        navigator.openPath("/projects/p?focus=c")
        assertNotEquals(first, navigator.requests.value)
        assertEquals("c", navigator.requests.value?.cardId)
    }

    @Test
    fun aRequestMadeBeforeTheFeedShowsOpensItsCardOnce() = runComposeUiTest {
        val events = mutableListOf<FeedEvent>()
        val navigator = AppNavigator()
        // Like a tapped notification launching the app: the request is there before the feed.
        navigator.open("agent-dashboard", "u1")
        setContent {
            AgentTheme(dark = true) {
                Box(Modifier.size(390.dp, 844.dp)) {
                    FeedScreen(FakeFeed.state, navigator = navigator, onEvent = { events += it })
                }
            }
        }
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.OpenThread("u1")), events)
        assertNull(navigator.requests.value)
    }

    @Test
    fun anotherProjectIsPickedFirst() = runComposeUiTest {
        val events = mutableListOf<FeedEvent>()
        val navigator = AppNavigator()
        setContent {
            AgentTheme(dark = true) {
                Box(Modifier.size(390.dp, 844.dp)) {
                    FeedScreen(FakeFeed.state, navigator = navigator, onEvent = { events += it })
                }
            }
        }
        waitForIdle()
        navigator.openPath("/projects/infra")
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.SelectProject("infra")), events)
    }
}
