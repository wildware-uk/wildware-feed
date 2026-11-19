package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.ScreenPreview
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A thread's reply box sits at the top of its replies, right under the card, on a phone and on a
 * desktop: the newest reply is just below it, and "Replying to …" opens above it.
 */
@OptIn(ExperimentalTestApi::class)
class ReplyBoxTopTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val fix = FakeFeed.state.openThread as FeedItem.Update
    private val newest = "Deployed to the box. Restarted once, all channels reconnected in under 2s."
    private val inThread = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Thread"))

    /** Where it starts, even when scrolled out of view (bounds are clipped to the screen). */
    private fun SemanticsNodeInteraction.top() = fetchSemanticsNode().positionInRoot.y

    /** Runs a reply's own accessibility action, as a screen reader (or a long press's menu) would. */
    private fun ComposeUiTest.act(on: SemanticsNodeInteraction, label: String) {
        val action = on.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
        runOnIdle { action.action() }
        waitForIdle()
    }

    private fun ComposeUiTest.phone(dark: Boolean = false, scale: Float = 1f, width: Int = 390, height: Int = 844) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(scale)) {
            AgentTheme(dark = dark) { Box(Modifier.size(width.dp, height.dp)) { FeedScreen(FakeFeed.state, preview = ScreenPreview(threadOpen = true)) } }
        }
    }

    @Test
    fun onAPhoneTheBoxSitsBetweenTheCardAndTheNewestReply() = runDesktopComposeUiTest(390, 844) {
        phone()
        val box = onNode(hasSetTextAction() and inThread)
        val card = onAllNodes(hasText(fix.title!!, substring = true) and inThread).onFirst()
        val reply = onAllNodes(hasText(newest, substring = true) and inThread).onFirst()
        assertTrue(card.top() < box.top(), "the card is above the box")
        assertTrue(box.top() < reply.top(), "the newest reply is under the box")
    }

    @Test
    fun answeringAReplyOpensTheChipAboveTheBox() = runDesktopComposeUiTest(390, 844) {
        phone()
        // The reply's own row: once answered, the chip quotes the same words.
        val reply = onAllNodes(hasAnyDescendantText(newest) and hasCustomAction("Reply") and inThread).onFirst()
        act(reply, "Reply")
        val chip = onAllNodes(hasText("Replying to relay") and inThread).onFirst()
        val box = onNode(hasSetTextAction() and inThread)
        assertTrue(chip.top() < box.top(), "the chip is above the box")
        assertTrue(box.top() < reply.top(), "and both above the replies")
    }

    @Test
    fun onADesktopTheBoxOpensAtTheTopOfTheReplies() = runDesktopComposeUiTest(1440, 1000) {
        setContent { AgentTheme(dark = false) { Box(Modifier.size(1440.dp, 1000.dp)) { FeedScreen(FakeFeed.state.copy(openThread = null), startAt = fix.id) } } }
        val reply = onAllNodes(hasText(newest, substring = true)).onFirst()
        onAllNodes(hasText("Reply") and hasClickAction()).onFirst().performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        val box = onAllNodes(hasSetTextAction() and hasContentDescription("Reply")).onFirst()
        assertTrue(box.top() < reply.top(), "the newest reply is under the box")
    }

    private fun hasAnyDescendantText(text: String) = androidx.compose.ui.test.hasAnyDescendant(hasText(text, substring = true))
    private fun hasCustomAction(label: String) = SemanticsMatcher("has custom action $label") { node ->
        node.config.getOrElseNullable(SemanticsActions.CustomActions) { null }?.any { it.label == label } == true
    }
    private fun hasClickAction() = androidx.compose.ui.test.hasClickAction()

    private fun ComposeUiTest.shoot(name: String, dark: Boolean) {
        if (name == "phone") {
            phone(dark, scale = 3f, width = 1170, height = 2532)
            act(onAllNodes(hasAnyDescendantText(newest) and hasCustomAction("Reply") and inThread).onFirst(), "Reply")
            // The box with the two newest replies under it.
            onAllNodes(hasText("Confirmed from my side", substring = true) and inThread).onFirst().performScrollTo()
        } else {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(FakeFeed.state.copy(openThread = null), startAt = fix.id) } }
                }
            }
            onAllNodes(hasText("Reply") and hasClickAction()).onFirst().performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            // The innermost match: the card holding the thread has the same words inside it.
            act(onAllNodes(hasAnyDescendantText(newest) and hasCustomAction("Reply")).onLast(), "Reply")
            onAllNodes(hasSetTextAction() and hasContentDescription("Reply")).onFirst().performScrollTo()
        }
        waitForIdle()
        val file = File(outDir, "$name-${if (dark) "dark" else "light"}-reply-box-top.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    @Test
    fun screenshots() {
        for (dark in listOf(true, false)) {
            runDesktopComposeUiTest(2880, 1800) { shoot("desktop", dark) }
            runDesktopComposeUiTest(1170, 2532) { shoot("phone", dark) }
        }
    }
}
