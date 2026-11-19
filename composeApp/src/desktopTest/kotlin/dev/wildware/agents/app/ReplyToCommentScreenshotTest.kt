package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.ScreenPreview
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Replying to scout's comment: "@scout " in the box, "Replying to scout" over it; on desktop, the hover button. */
@OptIn(ExperimentalTestApi::class)
class ReplyToCommentScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private fun ComposeUiTest.save(name: String) {
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
    }

    @Test
    fun phone() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(1170, 2532) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(3f)) {
                    AgentTheme(dark = dark) {
                        Box(Modifier.fillMaxSize()) {
                            FeedScreen(FakeFeed.state, SafeArea(top = 54.dp, bottom = 34.dp), preview = ScreenPreview(threadOpen = true))
                        }
                    }
                }
            }
            // A swipe right on scout's comment, by its screen reader twin.
            val reply = SemanticsMatcher("has action 'Reply'") { node ->
                node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Reply" } == true
            }
            val node = onAllNodes(reply and hasAnyDescendant(hasText("Confirmed from my side", substring = true))).fetchSemanticsNodes().last()
            runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == "Reply" }.action() }
            save("phone-${if (dark) "dark" else "light"}-reply-to-comment")
        }
    }

    @Test
    fun desktop() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(2880, 1800) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    AgentTheme(dark = dark) {
                        Box(Modifier.fillMaxSize()) {
                            // The pointer over relay's comment, after Reply on scout's.
                            FeedScreen(FakeFeed.state, preview = ScreenPreview(replyHover = "r4"))
                        }
                    }
                }
            }
            onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Confirmed from my side", substring = true))
            waitForIdle()
            val node = onAllNodes(hasAnyDescendant(hasText("Confirmed from my side", substring = true)) and SemanticsMatcher("has action 'Reply'") { n ->
                n.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == "Reply" } == true
            }).fetchSemanticsNodes().last()
            runOnIdle { node.config[SemanticsActions.CustomActions].first { it.label == "Reply" }.action() }
            waitForIdle()
            onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Nice. Does this also fix", substring = true))
            save("desktop-${if (dark) "dark" else "light"}-reply-to-comment")
        }
    }
}
