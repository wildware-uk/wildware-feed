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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Under a card: who marked it done, apart from who read it, and who has it but said nothing. */
@OptIn(ExperimentalTestApi::class)
class AckLineTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val state = FakeFeed.state.copy(openThread = null)

    @Test
    fun doneReadAndDeliveredEachSaySo() = runComposeUiTest {
        setContent { AgentTheme(dark = true) { Box(Modifier.size(1200.dp, 900.dp)) { FeedScreen(state, startAt = "o1") } } }
        // Furthest along first, each agent once.
        onNode(hasContentDescription("relay is thinking… · scout marked this done · forge has read this · delivered to lumen")).performScrollTo().assertExists()
    }

    private fun ComposeUiTest.shoot(name: String, dark: Boolean, scale: Float, startAt: String) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(state, startAt = startAt) } }
            }
        }
        onNode(hasContentDescription("delivered to lumen", substring = true)).performScrollTo()
        waitForIdle()
        val file = File(outDir, "$name-${if (dark) "dark" else "light"}-acks.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    @Test
    fun screenshots() {
        for (dark in listOf(true, false)) {
            runDesktopComposeUiTest(2880, 1800) { shoot("desktop", dark, 2f, "o1") }
            runDesktopComposeUiTest(1170, 2532) { shoot("phone", dark, 3f, "o1") }
        }
    }
}
