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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.ScreenPreview
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tapping an agent in the list opens it, and it can be renamed there, as the website's rail allows. */
@OptIn(ExperimentalTestApi::class)
class AgentRenameTest {
    private val events = mutableListOf<FeedEvent>()
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    /** What the dashboard says back to a rename: null for done, or why not. */
    private var answer: String? = null

    private fun ComposeUiTest.feed(width: Int, preview: ScreenPreview = ScreenPreview()) = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.size(width.dp, 900.dp)) {
                FeedScreen(FakeFeed.state, preview = preview, onEvent = { event ->
                    events += event
                    if (event is FeedEvent.RenameAgent) event.done(answer)
                })
            }
        }
    }

    private fun ComposeUiTest.openForge() =
        onNode(hasClickAction() and hasContentDescription("forge, online", substring = true)).performScrollTo().performClick()

    private fun ComposeUiTest.nameField() = onNode(hasContentDescription("Agent name") and hasSetTextAction())

    @Test
    fun anAgentOpensFromTheListAndIsRenamed() = runComposeUiTest {
        feed(1440)
        openForge()
        nameField().performTextReplacement("forge-mac ")
        onNode(hasText("Rename")).performClick()
        waitForIdle()
        val rename = events.filterIsInstance<FeedEvent.RenameAgent>().single()
        assertEquals("a2" to "forge-mac", rename.agentId to rename.name, "trimmed")
        onNode(hasText("Rename")).assertDoesNotExist()
    }

    @Test
    fun aRefusedNameSaysWhyAndStaysOpen() = runComposeUiTest {
        answer = "That name is taken."
        feed(1440)
        openForge()
        nameField().performTextReplacement("scout")
        onNode(hasText("Rename")).performClick()
        waitForIdle()
        onNode(hasText("That name is taken.")).assertExists()
    }

    @Test
    fun anEmptyNameIsNotSent() = runComposeUiTest {
        feed(1440)
        openForge()
        nameField().performTextReplacement("   ")
        onNode(hasText("Rename")).performClick()
        waitForIdle()
        onNode(hasText("An agent needs a name.")).assertExists()
        assertEquals(emptyList(), events.filterIsInstance<FeedEvent.RenameAgent>())
    }

    @Test
    fun aPhoneOpensAnAgentFromTheDrawer() = runComposeUiTest {
        feed(390, ScreenPreview(drawerOpen = true))
        openForge()
        nameField().assertExists()
    }

    private fun ComposeUiTest.shoot(name: String, scale: Float, dark: Boolean, safeArea: SafeArea, preview: ScreenPreview = ScreenPreview()) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(FakeFeed.state, safeArea, preview = preview) } }
            }
        }
        openForge()
        waitForIdle()
        val file = File(outDir, "$name-${if (dark) "dark" else "light"}-agent-rename.png")
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
