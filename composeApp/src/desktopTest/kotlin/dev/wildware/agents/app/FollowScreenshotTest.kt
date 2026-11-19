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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.Follows
import dev.wildware.agents.NotifyLevel
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

/** Following a thread (the bell), and a project's notification level in its settings. */
@OptIn(ExperimentalTestApi::class)
class FollowScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val events = mutableListOf<FeedEvent>()

    /** The project raises only replies; the open thread and the first question were followed by hand. */
    private val state: FeedState = FakeFeed.state.copy(
        follows = Follows(
            levels = mapOf(FakeFeed.state.current.id to NotifyLevel.Replies),
            threads = mapOf(Follows.key("update", FakeFeed.state.openThread!!.id) to true, Follows.key("request", "q1") to true),
            loaded = true,
        ),
    )

    private fun ComposeUiTest.show(scale: Float, dark: Boolean, safeArea: SafeArea, preview: ScreenPreview = ScreenPreview()) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(scale)) {
            AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(state, safeArea, preview = preview, onEvent = { events += it }) } }
        }
    }

    private fun ComposeUiTest.save(name: String) {
        waitForIdle()
        val file = File(outDir, "$name.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    private fun ComposeUiTest.settings() {
        onNode(hasContentDescription("Project settings")).performClick()
        waitForIdle()
        onNode(hasText("Replies and mentions")).performScrollTo()
    }

    private val phoneArea = SafeArea(top = 54.dp, bottom = 34.dp)

    @Test fun phoneThreadDark() = runDesktopComposeUiTest(1170, 2532) { show(3f, true, phoneArea, ScreenPreview(threadOpen = true)); save("phone-dark-follow-thread") }
    @Test fun phoneThreadLight() = runDesktopComposeUiTest(1170, 2532) { show(3f, false, phoneArea, ScreenPreview(threadOpen = true)); save("phone-light-follow-thread") }
    @Test fun phoneSettingsDark() = runDesktopComposeUiTest(1170, 2532) { show(3f, true, phoneArea); settings(); save("phone-dark-project-notifications") }
    @Test fun phoneSettingsLight() = runDesktopComposeUiTest(1170, 2532) { show(3f, false, phoneArea); settings(); save("phone-light-project-notifications") }
    @Test fun desktopLight() = runDesktopComposeUiTest(2880, 1800) { show(2f, false, SafeArea()); save("desktop-light-follow-thread") }
    @Test fun desktopDark() = runDesktopComposeUiTest(2880, 1800) { show(2f, true, SafeArea()); save("desktop-dark-follow-thread") }
    @Test fun desktopSettingsLight() = runDesktopComposeUiTest(2880, 1800) { show(2f, false, SafeArea()); settings(); save("desktop-light-project-notifications") }
    @Test fun desktopSettingsDark() = runDesktopComposeUiTest(2880, 1800) { show(2f, true, SafeArea()); settings(); save("desktop-dark-project-notifications") }

    @Test
    fun theBellUnfollowsAFollowedThreadAndTheMenuSaysSo() = runComposeUiTest {
        setContent {
            AgentTheme(dark = true) {
                Box(Modifier.size(390.dp, 844.dp)) { FeedScreen(state, preview = ScreenPreview(threadOpen = true), onEvent = { events += it }) }
            }
        }
        val open = state.openThread!!.id
        onNodeWithContentDescription("Unfollow thread").performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.FollowThread(open, following = false)), events)
        events.clear()
        onNodeWithContentDescription("More thread actions").performClick()
        waitForIdle()
        onNodeWithText("Unfollow thread").performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.FollowThread(open, following = false)), events)
    }

    @Test
    fun pickingALevelSetsItForTheProject() = runComposeUiTest {
        setContent {
            AgentTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { FeedScreen(state, onEvent = { events += it }) } }
        }
        onNode(hasContentDescription("Project settings")).performClick()
        waitForIdle()
        onNode(hasText("Muted")).performScrollTo().performClick()
        waitForIdle()
        val project = state.current
        assertEquals(listOf<FeedEvent>(FeedEvent.SetProjectNotify(project.id, project.slug!!, NotifyLevel.Muted)), events)
    }
}
