package dev.wildware.agents.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.VoiceNote
import dev.wildware.agents.VoiceWord
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Author
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedUi
import dev.wildware.agents.app.feed.LocalFeedUi
import dev.wildware.agents.app.feed.LocalNow
import dev.wildware.agents.app.feed.PostCard
import dev.wildware.agents.app.feed.Project
import dev.wildware.agents.app.feed.Reply
import dev.wildware.agents.app.feed.ReplyView
import dev.wildware.agents.app.feed.ScreenPreview
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** A voice note in its undo window, on a post and on a reply, light and dark: the countdown pill. */
@OptIn(ExperimentalTestApi::class)
class VoiceUndoScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val ui = FeedUi(ScreenPreview(), mutableStateOf(FakeFeed.state)) {}
    private val now = 1_000_000L
    private val until = now + VoiceNote.UndoMillis - 1_000

    private val post = FeedItem.Post(
        id = "outbox:post", time = "now", author = Author.Owner("You"), project = Project("p1", "agent-dashboard", unread = 0, hue = 0, slug = "agent-dashboard"),
        body = "Can you check the Mac build before Friday?", sending = true, voice = true, undoUntil = until,
        words = listOf(VoiceWord(18, 21, 0.3, listOf("Matt", "map"))),
    )
    private val reply = Reply(
        id = "outbox:reply", author = Author.Owner("You"), time = "now", body = "Ship it on Friday.",
        voice = true, sending = true, undoUntil = until, words = listOf(VoiceWord(11, 17, 0.4, listOf("Monday"))),
    )

    private fun ComposeUiTest.show(dark: Boolean) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(2f), LocalFeedUi provides ui, LocalNow provides { now }) {
            AgentTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(AgentTheme.colors.background).padding(24.dp)) {
                    Column(Modifier.width(560.dp)) {
                        PostCard(post)
                        ReplyView(reply, Modifier.padding(top = 24.dp, start = 16.dp))
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.save(name: String) {
        mainClock.advanceTimeByFrame()
        val file = File(outDir, "$name.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    @Test
    fun light() = runDesktopComposeUiTest(1260, 680) {
        mainClock.autoAdvance = false
        show(dark = false)
        mainClock.advanceTimeBy(600)
        save("desktop-light-voice-undo")
    }

    @Test
    fun dark() = runDesktopComposeUiTest(1260, 680) {
        mainClock.autoAdvance = false
        show(dark = true)
        mainClock.advanceTimeBy(600)
        save("desktop-dark-voice-undo")
    }

    @Test
    fun choosingAWord() = runDesktopComposeUiTest(1260, 680) {
        mainClock.autoAdvance = false
        show(dark = false)
        mainClock.advanceTimeByFrame()
        onNode(hasClickAction() and hasAnyAncestor(hasText("Ship it on Friday.")), useUnmergedTree = true).performClick()
        mainClock.advanceTimeBy(300)
        save("desktop-light-voice-word-choice")
    }
}
