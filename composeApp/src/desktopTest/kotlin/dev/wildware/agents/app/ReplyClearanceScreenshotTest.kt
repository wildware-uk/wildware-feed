package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** A reply box opened near the bottom of the desktop feed. */
@OptIn(ExperimentalTestApi::class)
class ReplyClearanceScreenshotTest {
    @Test
    fun desktop() = runDesktopComposeUiTest(2048, 1536) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                AgentTheme(dark = true) { Box(Modifier.fillMaxSize()) { FeedScreen(FakeFeed.state) } }
            }
        }
        waitForIdle()
        onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Reply") and hasClickAction())
        waitForIdle()
        val replies = onAllNodes(hasText("Reply") and hasClickAction())
        val lowest = replies.fetchSemanticsNodes().withIndex().filter { it.value.boundsInRoot.bottom < 1536f }.maxBy { it.value.boundsInRoot.top }.index
        replies[lowest].performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(dir, "desktop-reply-clearance.png"))
    }
}
