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
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.AccessRow
import dev.wildware.agents.app.feed.AccessState
import dev.wildware.agents.app.feed.AccessView
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.accessTarget
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A channel's Permissions panel (server spec/14): who can see it, changing a level, the private switch. */
@OptIn(ExperimentalTestApi::class)
class PermissionsPanelTest {
    private val events = mutableListOf<FeedEvent>()
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private fun panel(canManage: Boolean) = AccessView(
        target = accessTarget(FakeFeed.state.current.id, group = false),
        loaded = true,
        isPrivate = false,
        canManage = canManage,
        people = listOf(
            AccessRow("member", "founder", "Shaun", "manage", "Team owner: always manages", own = null, editable = false),
            AccessRow("member", "m2", "Sam", "post", "Team default", own = null, editable = true),
            AccessRow("member", "m3", "Rin", "read", "Set here", own = "read", editable = true),
        ),
        agents = listOf(AccessRow("agent", "a2", "forge", "post", "From the project: Post", own = null, editable = true)),
    )

    private fun state(canManage: Boolean): FeedState =
        FakeFeed.state.copy(access = AccessState(panels = mapOf(accessTarget(FakeFeed.state.current.id, false) to panel(canManage))))

    private fun ComposeUiTest.show(state: FeedState) = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.size(1440.dp, 900.dp)) { FeedScreen(state, onEvent = { events += it }) }
        }
    }

    private fun ComposeUiTest.openPermissions() {
        onNode(hasContentDescription("Channel settings")).performClick()
        onNode(hasText("Permissions…") and hasClickAction()).performScrollTo().performClick()
        waitForIdle()
    }

    @Test
    fun thePanelListsEveryoneWithTheirLevelAndWhereItComesFrom() = runComposeUiTest {
        show(state(canManage = true))
        openPermissions()
        assertTrue(FeedEvent.ReadAccess(FakeFeed.state.current.id, false) in events, "opening it reads it")
        onNode(hasText("Only people and agents listed can see this")).assertExists()
        onNode(hasText("Manage · Team owner: always manages")).assertExists()
        onNode(hasText("Post · Team default")).assertExists()
        onNode(hasText("Read · Set here")).assertExists()
        onNode(hasText("Post · From the project: Post")).assertExists()
    }

    @Test
    fun someoneWhoManagesItChangesALevelAndThePrivateSwitch() = runComposeUiTest {
        show(state(canManage = true))
        openPermissions()
        // Sam's row: Read.
        onNode(hasContentDescription("Sam: Post, Team default")).performScrollTo()
        onAllNodes(hasText("Read") and hasClickAction())[0].performClick()
        // Rin's own setting taken away: inherit again.
        onNode(hasText("Inherit") and hasClickAction()).performScrollTo().performClick()
        onNode(hasText("Only people and agents listed can see this")).performClick()
        waitForIdle()
        val changes = events.filterIsInstance<FeedEvent.SetAccessLevel>()
        assertEquals(listOf("m2" to "read", "m3" to null), changes.map { it.who to it.level })
        assertEquals(listOf(true), events.filterIsInstance<FeedEvent.SetAccessPrivate>().map { it.isPrivate })
    }

    @Test
    fun someoneWhoDoesNotManageItOnlyReadsIt() = runComposeUiTest {
        show(state(canManage = false))
        openPermissions()
        onNode(hasText("Post · Team default")).assertExists()
        onNode(hasText("Inherit")).assertDoesNotExist()
        onNode(hasText("Only people who manage this can change it.", substring = true)).assertExists()
        onNode(hasText("Only people and agents listed can see this")).performClick()
        waitForIdle()
        assertTrue(events.none { it is FeedEvent.SetAccessPrivate || it is FeedEvent.SetAccessLevel })
    }

    @Test
    fun phone() = runDesktopComposeUiTest(1170, 2532) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f)) {
                AgentTheme(dark = true) { Box(Modifier.fillMaxSize()) { FeedScreen(state(canManage = true), SafeArea(top = 54.dp, bottom = 34.dp), onEvent = { events += it }) } }
            }
        }
        openPermissions()
        val file = File(outDir, "phone-permissions.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
        onNode(hasText("Post · Team default")).assertExists()
    }
}
