package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** A phone after tapping 👀 (adds the owner's) and ✅ (takes theirs back), with the "+" picker open. */
@OptIn(ExperimentalTestApi::class)
class ReactionScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    @Test
    fun reactionsTapped() = runDesktopComposeUiTest(1170, 2532) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f)) {
                AgentTheme(dark = true) {
                    Box(Modifier.fillMaxSize()) { FeedScreen(FakeFeed.state.copy(openThread = null), startAt = "u1") }
                }
            }
        }
        onNodeWithContentDescription("eyes, 2. Double tap to add yours.").performScrollTo().performClick()
        onNodeWithContentDescription("check mark, 1, including you. Double tap to remove yours.").performClick()
        onAllNodesWithContentDescription("Add a reaction").onFirst().performClick()
        waitForIdle()
        val file = File(outDir, "phone-dark-reactions.png")
        // The picker is a popup, a second root; the first root's capture draws the whole window.
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }
}
