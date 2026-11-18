package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** The card menu with Delete on an update, and the question it asks before deleting. */
@OptIn(ExperimentalTestApi::class)
class DeleteScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val fixTitle = "Reading messages in one session no longer hides them from another"

    private fun ComposeUiTest.show(scale: Float) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(scale)) {
            AgentTheme(dark = true) { Box(Modifier.fillMaxSize()) { FeedScreen(FakeFeed.state.copy(openThread = null), startAt = "u1") } }
        }
    }

    private fun ComposeUiTest.save(name: String) {
        waitForIdle()
        val file = File(outDir, "$name.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    @Test
    fun desktop() = runDesktopComposeUiTest(2880, 1800) {
        show(2f)
        onNodeWithText(fixTitle).performScrollTo().performMouseInput { rightClick(center) }
        save("desktop-dark-delete-menu")
        onNodeWithText("Delete").performClick()
        save("desktop-dark-delete-confirm")
    }

    @Test
    fun phone() = runDesktopComposeUiTest(1170, 2532) {
        show(3f)
        onNodeWithText(fixTitle).performScrollTo().performMouseInput { rightClick(center) }
        onNodeWithText("Delete").performClick()
        save("phone-dark-delete-confirm")
    }
}
