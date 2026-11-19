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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.ScreenPreview
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** A project pins to the top of the list from its settings, and wears a pin there. */
@OptIn(ExperimentalTestApi::class)
class ProjectPinTest {
    private val events = mutableListOf<FeedEvent>()
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    /** The open project, pinned. */
    private val pinned: FeedState = FakeFeed.state.current.copy(pinned = true).let { p ->
        FakeFeed.state.copy(current = p, projects = FakeFeed.state.projects.map { if (it.id == p.id) p else it })
    }

    private fun ComposeUiTest.feed(width: Int, state: FeedState, preview: ScreenPreview = ScreenPreview()) = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.size(width.dp, 900.dp)) { FeedScreen(state, preview = preview, onEvent = { events += it }) }
        }
    }

    @Test
    fun pinningFromTheSettingsPinsIt() = runComposeUiTest {
        feed(1440, FakeFeed.state)
        onNode(hasContentDescription("Project settings")).performClick()
        onNode(hasText("Pin project")).performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf(FeedEvent.PinProject("agent-dashboard", true)), events.filterIsInstance<FeedEvent.PinProject>())
    }

    @Test
    fun aPinnedProjectSaysSoAndOffersUnpin() = runComposeUiTest {
        feed(1440, pinned)
        onNode(hasContentDescription("${pinned.current.name}, pinned", substring = true)).assertExists()
        onNode(hasContentDescription("Project settings")).performClick()
        onNode(hasText("Unpin project")).performScrollTo().performClick()
        waitForIdle()
        assertEquals(listOf(FeedEvent.PinProject("agent-dashboard", false)), events.filterIsInstance<FeedEvent.PinProject>())
    }

    @Test
    fun thePhoneDrawerSaysItIsPinnedToo() = runComposeUiTest {
        feed(390, pinned, ScreenPreview(drawerOpen = true))
        onNode(hasContentDescription("${pinned.current.name}, pinned", substring = true)).assertExists()
    }

    private fun ComposeUiTest.shoot(name: String, scale: Float, dark: Boolean, safeArea: SafeArea, preview: ScreenPreview = ScreenPreview()) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(pinned, safeArea, preview = preview) } }
            }
        }
        waitForIdle()
        val file = File(outDir, "$name-${if (dark) "dark" else "light"}-pinned-project.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    @Test
    fun screenshots() {
        for (dark in listOf(true, false)) {
            runDesktopComposeUiTest(2880, 1800) { shoot("desktop", 2f, dark, SafeArea()) }
            runDesktopComposeUiTest(1170, 2532) { shoot("phone", 3f, dark, SafeArea(top = 54.dp, bottom = 34.dp), ScreenPreview(drawerOpen = true)) }
        }
    }
}
