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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.Follows
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.NotifyLevel
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.CardFolds
import dev.wildware.agents.app.feed.ChannelSections
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.LocalCardFolds
import dev.wildware.agents.app.feed.ProjectGroup
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.ScreenPreview
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Channels grouped into projects: the sidebar, the drawer, and a project's settings. */
@OptIn(ExperimentalTestApi::class)
class ChannelsTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots", "channels").apply { mkdirs() }
    private val events = mutableListOf<FeedEvent>()

    private val mobile = ProjectGroup("g1", "mobile", "Mobile", accent = "#5ab4ff")
    private val platform = ProjectGroup("g2", "platform", "Platform", accent = "#3ddc97")
    private val retired = ProjectGroup("g3", "retired", "Retired", archived = true)

    /** agent-dashboard and ios-companion in Mobile, rust-engine and infra in Platform, website in none. */
    private val state: FeedState = run {
        val channels = FakeFeed.state.projects.map {
            when (it.slug) {
                "agent-dashboard", "ios-companion" -> it.copy(groupId = mobile.id)
                "rust-engine", "infra" -> it.copy(groupId = platform.id)
                else -> it
            }
        }
        FakeFeed.state.copy(
            projects = channels,
            current = channels.first { it.id == FakeFeed.state.current.id },
            groups = listOf(mobile, platform, retired),
            follows = Follows(
                groupLevels = mapOf(platform.id to NotifyLevel.Replies),
                groupOf = channels.mapNotNull { c -> c.groupId?.let { c.id to it } }.toMap(),
                loaded = true,
            ),
        )
    }

    // ---- what goes where --------------------------------------------------------------------------

    @Test
    fun `channels sit under their project, the rest under Channels, and archived ones apart`() {
        val sections = ChannelSections.of(state.projects, state.groups)
        assertEquals(listOf("Mobile" to listOf("agent-dashboard", "ios-companion"), "Platform" to listOf("rust-engine", "infra")),
            sections.groups.map { (g, cs) -> g.name to cs.map { it.name } })
        assertEquals(listOf("website"), sections.loose.map { it.name })
        assertEquals(listOf("agent-dashboard v1"), sections.archivedChannels.map { it.name })
        assertEquals(listOf("Retired"), sections.archivedGroups.map { it.first.name })
    }

    @Test
    fun `a channel's level comes from its project unless it has its own`() {
        val follows = state.follows
        val infra = state.projects.first { it.slug == "infra" }
        assertEquals(NotifyLevel.Replies, follows.level(infra.id))
        assertEquals(NotifyLevel.Muted, follows.withLevel(infra.id, NotifyLevel.Muted).level(infra.id))
        assertEquals(NotifyLevel.Replies, follows.withLevel(infra.id, NotifyLevel.Muted).withLevel(infra.id, null).level(infra.id))
        assertEquals(NotifyLevel.All, follows.withGroupLevel(platform.id, null).level(infra.id))
    }

    // ---- the screen -------------------------------------------------------------------------------

    private fun ComposeUiTest.show(folds: CardFolds = CardFolds(MemoryTextStore()), preview: ScreenPreview = ScreenPreview()) = setContent {
        CompositionLocalProvider(LocalCardFolds provides folds) {
            AgentTheme(dark = false) { Box(Modifier.size(1280.dp, 820.dp)) { FeedScreen(state, preview = preview, onEvent = { if (it !is FeedEvent.ReadMembers) events += it }) } }
        }
    }

    @Test
    fun `folding a project hides its channels, and the fold is remembered`() {
        val store = MemoryTextStore()
        runComposeUiTest {
            show(CardFolds(store))
            onNode(hasText("rust-engine")).assertExists()
            onNode(hasContentDescription("Platform, project", substring = true)).performClick()
            waitForIdle()
            assertEquals(0, onAllNodes(hasText("rust-engine")).fetchSemanticsNodes().size)
        }
        runComposeUiTest {
            show(CardFolds(store))
            assertEquals(0, onAllNodes(hasText("rust-engine")).fetchSemanticsNodes().size, "still folded after a restart")
            onNode(hasText("website")).assertExists()
        }
    }

    @Test
    fun `a channel moves between projects from its settings`() = runComposeUiTest {
        show()
        onNode(hasContentDescription("Channel settings")).performClick()
        waitForIdle()
        onNode(hasText("Platform") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performScrollTo().performClick()
        waitForIdle()
        onNode(hasText("Same as Mobile (All activity)")).performScrollTo().assertExists()
        assertEquals(listOf<FeedEvent>(FeedEvent.MoveChannel("agent-dashboard", platform.id)), events)
    }

    @Test
    fun `a project's settings add a channel, set its level, and delete it only after asking`() = runComposeUiTest {
        show()
        onNode(hasContentDescription("Mobile settings")).performClick()
        waitForIdle()
        onNode(hasText("website") and isToggleable()).performScrollTo().performClick()
        onNode(hasText("Muted")).performScrollTo().performClick()
        onNode(hasText("Delete project")).performScrollTo().performClick()
        waitForIdle()
        assertTrue(events.none { it is FeedEvent.DeleteGroup }, "the first press only asks")
        onNode(hasText("Delete it: the channels stay")).performScrollTo().performClick()
        waitForIdle()
        assertEquals(
            listOf(FeedEvent.MoveChannel("website", mobile.id), FeedEvent.SetGroupNotify(mobile.id, mobile.slug, NotifyLevel.Muted), FeedEvent.DeleteGroup(mobile.slug)),
            events,
        )
    }

    @Test
    fun `new project is its own row beside new channel`() = runComposeUiTest {
        show()
        onNode(hasText("New project") and hasClickAction()).performScrollTo().performClick()
        waitForIdle()
        onNode(hasText("Create project")).assertExists()
    }

    // ---- screenshots ------------------------------------------------------------------------------

    private fun ComposeUiTest.shot(scale: Float, dark: Boolean, safeArea: SafeArea, preview: ScreenPreview = ScreenPreview()) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(scale)) {
            AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(state, safeArea, preview = preview, onEvent = { if (it !is FeedEvent.ReadMembers) events += it }) } }
        }
    }

    private fun ComposeUiTest.save(name: String) {
        waitForIdle()
        val file = File(outDir, "$name.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    private fun ComposeUiTest.projectSettings() {
        onNode(hasContentDescription("Mobile settings")).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.channelSettings() {
        onNode(hasContentDescription("Channel settings")).performClick()
        waitForIdle()
        onNode(hasText("Same as Mobile (All activity)")).performScrollTo()
    }

    private val phoneArea = SafeArea(top = 54.dp, bottom = 34.dp)
    private val drawer = ScreenPreview(drawerOpen = true)

    @Test fun desktopLight() = runDesktopComposeUiTest(2880, 1800) { shot(2f, false, SafeArea()); save("desktop-light-sidebar") }
    @Test fun desktopDark() = runDesktopComposeUiTest(2880, 1800) { shot(2f, true, SafeArea()); save("desktop-dark-sidebar") }
    @Test fun desktopProjectLight() = runDesktopComposeUiTest(2880, 1800) { shot(2f, false, SafeArea()); projectSettings(); save("desktop-light-project-settings") }
    @Test fun desktopProjectDark() = runDesktopComposeUiTest(2880, 1800) { shot(2f, true, SafeArea()); projectSettings(); save("desktop-dark-project-settings") }
    @Test fun desktopChannelLight() = runDesktopComposeUiTest(2880, 1800) { shot(2f, false, SafeArea()); channelSettings(); save("desktop-light-channel-settings") }
    @Test fun phoneDrawerLight() = runDesktopComposeUiTest(1170, 2532) { shot(3f, false, phoneArea, drawer); save("phone-light-drawer") }
    @Test fun phoneDrawerDark() = runDesktopComposeUiTest(1170, 2532) { shot(3f, true, phoneArea, drawer); save("phone-dark-drawer") }
    @Test fun phoneProjectLight() = runDesktopComposeUiTest(1170, 2532) { shot(3f, false, phoneArea, drawer); projectSettings(); save("phone-light-project-settings") }
    @Test fun phoneProjectDark() = runDesktopComposeUiTest(1170, 2532) { shot(3f, true, phoneArea, drawer); projectSettings(); save("phone-dark-project-settings") }
    @Test fun phoneChannelDark() = runDesktopComposeUiTest(1170, 2532) { shot(3f, true, phoneArea); channelSettings(); save("phone-dark-channel-settings") }
}
