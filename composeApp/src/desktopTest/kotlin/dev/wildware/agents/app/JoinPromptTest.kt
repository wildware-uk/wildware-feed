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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.JoinRequest
import dev.wildware.agents.app.feed.LocalNow
import dev.wildware.agents.app.feed.SafeArea
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** An agent asking to join shows over the feed until the owner allows or denies it. */
@OptIn(ExperimentalTestApi::class)
class JoinPromptTest {
    private val events = mutableListOf<FeedEvent>()
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val now = 1_700_000_000_000L

    private val laptop = JoinRequest(
        id = "pr1", name = "laptop-personal", code = "K7Q-3MD", host = "laptop", cwd = "/work/app",
        address = "203.0.113.5", expiresAt = now + 9 * 60_000 + 30_000,
    )
    private val runner = JoinRequest(id = "pr2", name = "ci-runner", code = "P4W-9XT", host = "build-2", askedBy = "forge", expiresAt = now + 4 * 60_000)

    private fun ComposeUiTest.feed(state: FeedState, width: Int = 1440) = setContent {
        CompositionLocalProvider(LocalNow provides { now }) {
            AgentTheme(dark = true) {
                Box(Modifier.size(width.dp, 900.dp)) { FeedScreen(state, onEvent = { events += it }) }
            }
        }
    }

    private fun ComposeUiTest.nameField() = onNode(hasContentDescription("Agent name") and hasSetTextAction())

    @Test
    fun allowSendsTheNameTheOwnerChose() = runComposeUiTest {
        feed(FakeFeed.state.copy(joining = listOf(laptop)))
        onNode(hasText("laptop-personal wants to join")).assertExists()
        onNode(hasText("K7Q-3MD")).assertExists()
        onNode(hasText("203.0.113.5")).assertExists()
        nameField().performTextReplacement("  laptop ")
        onNode(hasText("Allow")).performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.AllowAgent("pr1", "laptop")), events)
    }

    @Test
    fun anEmptyNameKeepsTheOneAskedFor() = runComposeUiTest {
        feed(FakeFeed.state.copy(joining = listOf(laptop)))
        nameField().performTextReplacement("   ")
        onNode(hasText("Allow")).performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.AllowAgent("pr1", "laptop-personal")), events)
    }

    @Test
    fun denyTurnsItAway() = runComposeUiTest {
        feed(FakeFeed.state.copy(joining = listOf(laptop)), width = 390)
        onNode(hasText("Deny")).performClick()
        waitForIdle()
        assertEquals(listOf<FeedEvent>(FeedEvent.DenyAgent("pr1")), events)
    }

    @Test
    fun laterShowsTheNextOneAndSendsNothing() = runComposeUiTest {
        feed(FakeFeed.state.copy(joining = listOf(laptop, runner)))
        onNode(hasText("1 more waiting")).assertExists()
        onNode(hasText("Later")).performClick()
        waitForIdle()
        onNode(hasText("ci-runner wants to join")).assertExists()
        onNode(hasText("forge, with its own token")).assertExists()
        onNode(hasText("Later")).performClick()
        waitForIdle()
        onNode(hasText("Allow")).assertDoesNotExist()
        assertEquals(emptyList<FeedEvent>(), events)
    }

    @Test
    fun aRefusedNameSaysWhy() = runComposeUiTest {
        feed(FakeFeed.state.copy(joining = listOf(laptop.copy(error = "Another agent is already called that. Choose another name."))))
        onNode(hasText("Another agent is already called that. Choose another name.")).assertExists()
    }

    @Test
    fun nothingShowsWithNobodyAsking() = runComposeUiTest {
        feed(FakeFeed.state)
        onNode(hasText("Allow")).assertDoesNotExist()
    }

    private fun ComposeUiTest.shoot(name: String, scale: Float, dark: Boolean, safeArea: SafeArea) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale), LocalNow provides { now }) {
                AgentTheme(dark = dark) {
                    Box(Modifier.fillMaxSize()) { FeedScreen(FakeFeed.state.copy(joining = listOf(laptop, runner)), safeArea) }
                }
            }
        }
        waitForIdle()
        val file = File(outDir, "$name-${if (dark) "dark" else "light"}-join-prompt.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    @Test
    fun screenshots() {
        for (dark in listOf(true, false)) {
            runDesktopComposeUiTest(2880, 1800) { shoot("desktop", 2f, dark, SafeArea()) }
            runDesktopComposeUiTest(1170, 2532) { shoot("phone", 3f, dark, SafeArea(top = 54.dp, bottom = 34.dp)) }
        }
    }
}
