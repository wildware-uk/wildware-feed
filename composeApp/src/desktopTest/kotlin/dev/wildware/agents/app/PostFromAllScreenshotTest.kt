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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.SafeArea
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** "All projects" with its composer, and the project picker open. */
@OptIn(ExperimentalTestApi::class)
class PostFromAllScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private fun ComposeUiTest.shoot(name: String, scale: Float, dark: Boolean, safeArea: SafeArea) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(FakeFeed.allProjects, safeArea) } }
            }
        }
        // Master has no picker; the same test draws its "before".
        runCatching { onNode(hasContentDescription("Posting to", substring = true)).performClick() }
        waitForIdle()
        val file = File(outDir, "$name.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    @Test
    fun phone() = runDesktopComposeUiTest(1170, 2532) { shoot("phone-dark-post-from-all", 3f, dark = true, SafeArea(top = 54.dp, bottom = 34.dp)) }

    @Test
    fun desktop() = runDesktopComposeUiTest(2880, 1800) { shoot("desktop-light-post-from-all", 2f, dark = false, SafeArea()) }
}
