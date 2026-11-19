package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.WorkStatus
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** The "…" at the top of a phone thread opens the card's own menu, the one a long press gives. */
@OptIn(ExperimentalTestApi::class)
class ThreadMenuTest {
    private val events = mutableListOf<FeedEvent>()
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private fun ComposeUiTest.thread(dark: Boolean = true) = setContent {
        AgentTheme(dark = dark) {
            Box(Modifier.size(390.dp, 844.dp)) {
                FeedScreen(FakeFeed.state, preview = ScreenPreview(threadOpen = true), onEvent = { events += it })
            }
        }
    }

    @Test
    fun theThreadsMoreButtonOpensTheCardsMenu() = runComposeUiTest {
        thread()
        onNodeWithContentDescription("More thread actions").performClick()
        waitForIdle()
        // The update's menu: everything a long press on the card offers.
        listOf("Reply", "Copy link", "Details…", "Tags…", "Work state…", "Pin", "Delete").forEach {
            onNodeWithText(it).assertExists()
        }
        onNodeWithText("Pin").performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.Pin("u1", pinned = true)), events)
    }

    @Test
    fun theWorkStateItOffersIsTheCardsOwn() = runComposeUiTest {
        thread()
        onNodeWithContentDescription("More thread actions").performClick()
        onNodeWithText("Work state…").performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.ChooseStatus("u1", WorkStatus.Done)), events)
    }

    @Test
    fun screenshots() {
        for (dark in listOf(true, false)) runDesktopComposeUiTest(1170, 2532) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(3f)) {
                    AgentTheme(dark = dark) {
                        Box(Modifier.fillMaxSize()) { FeedScreen(FakeFeed.state, preview = ScreenPreview(threadOpen = true)) }
                    }
                }
            }
            onNodeWithContentDescription("More thread actions").performClick()
            waitForIdle()
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            val file = File(outDir, "phone-${if (dark) "dark" else "light"}-thread-menu.png")
            ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
            println("screenshot: ${file.absolutePath}")
        }
    }
}
