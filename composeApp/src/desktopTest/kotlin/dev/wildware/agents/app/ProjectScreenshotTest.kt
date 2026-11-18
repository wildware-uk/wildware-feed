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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.SafeArea
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** A project in its own colours, its settings, and making a new one. */
@OptIn(ExperimentalTestApi::class)
class ProjectScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private fun themed(background: String, accent: String): FeedState {
        val p = FakeFeed.state.current.copy(background = background, accent = accent, description = "The status wall agents post to")
        return FakeFeed.state.copy(current = p, projects = FakeFeed.state.projects.map { if (it.id == p.id) p else it })
    }

    private fun ComposeUiTest.show(state: FeedState, scale: Float, dark: Boolean, safeArea: SafeArea) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(scale)) {
            AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(state, safeArea) } }
        }
    }

    private fun ComposeUiTest.save(name: String) {
        waitForIdle()
        val file = File(outDir, "$name.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    @Test
    fun phone() = runDesktopComposeUiTest(1170, 2532) {
        show(themed("#0f3d3e", "#ff7a59"), 3f, dark = true, SafeArea(top = 54.dp, bottom = 34.dp))
        save("phone-project-colours")
        onNode(hasContentDescription("Project settings")).performClick()
        save("phone-project-settings")
    }

    @Test
    fun phoneNew() = runDesktopComposeUiTest(1170, 2532) {
        show(FakeFeed.state, 3f, dark = true, SafeArea(top = 54.dp, bottom = 34.dp))
        onNode(hasContentDescription("Projects", substring = true)).performClick()
        waitForIdle()
        save("phone-drawer-new-project")
        onNode(hasText("New project") and hasClickAction()).performScrollTo().performClick()
        save("phone-new-project")
    }

    @Test
    fun desktop() = runDesktopComposeUiTest(2880, 1800) {
        show(themed("#f3ead8", "#ff7a59"), 2f, dark = false, SafeArea())
        save("desktop-project-colours")
        onNode(hasContentDescription("Project settings")).performClick()
        save("desktop-project-settings")
    }
}
