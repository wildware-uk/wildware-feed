package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** The desktop feed with text selected across paragraphs, and right after copying a code block. */
@OptIn(ExperimentalTestApi::class)
class SelectionScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val clipboard = FakeClipboard()

    @Suppress("DEPRECATION")
    private fun ComposeUiTest.feed(startAt: String) = setContent {
        CompositionLocalProvider(
            LocalDensity provides Density(2f),
            LocalClipboard provides clipboard,
            LocalClipboardManager provides clipboard,
        ) {
            AgentTheme(dark = true) {
                Box(Modifier.fillMaxSize()) { FeedScreen(FakeFeed.state.copy(openThread = null), startAt = startAt) }
            }
        }
    }

    private fun SemanticsNodeInteraction.charCenter(of: String): Offset {
        val node = fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].joinToString("") { it.text }
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.first().getBoundingBox(text.indexOf(of) + of.length / 2).center
    }

    private fun ComposeUiTest.save(name: String) {
        waitForIdle()
        val file = File(outDir, "$name.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    @Test
    fun selectionAndCopyButton() = runDesktopComposeUiTest(2400, 1600) {
        feed(startAt = "u1")
        val first = onNodeWithText("Two sessions on the same token", substring = true).performScrollTo()
        val list = onNodeWithText("Old markers are migrated", substring = true)
        val firstBounds = first.fetchSemanticsNode().boundsInRoot
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        val from = first.charCenter("Two sessions").copy(x = 2f)
        val to = list.charCenter("migrated") + Offset(listBounds.left - firstBounds.left, listBounds.top - firstBounds.top)
        first.performMouseInput {
            moveTo(from)
            press()
            moveTo(from + Offset(40f, 0f))
            moveTo(to)
            release()
        }
        save("desktop-dark-text-selection")
        onNodeWithContentDescription("Copy code").performScrollTo().performClick()
        save("desktop-dark-code-copied")
    }

    @Test
    fun inlineCodeCopied() = runDesktopComposeUiTest(2400, 1600) {
        feed(startAt = "u3")
        val body = onNodeWithText("Old Gradle caches", substring = true).performScrollTo()
        val at = body.charCenter("gradle --stop")
        body.performMouseInput { click(at) }
        save("desktop-dark-inline-code-copied")
    }
}
