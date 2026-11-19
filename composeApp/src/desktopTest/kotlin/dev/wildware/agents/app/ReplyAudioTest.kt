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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Author
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FileAttachment
import dev.wildware.agents.app.feed.NotReady
import dev.wildware.agents.app.feed.Reply
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.ReplyView
import dev.wildware.agents.app.feed.Sound
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/** An agent's reply with five sounds and a file: every one shows, in order, each under its name. */
@OptIn(ExperimentalTestApi::class)
class ReplyAudioTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private val voices = (1..5).map { n ->
        Sound("v$n", "https://dash.test/media/v$n/original", 4_000L + n * 1_000, "audio/mpeg", name = "voice-$n.mp3")
    }
    private val reply = Reply(
        id = "ra",
        author = Author.AgentAuthor(FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().first().agent),
        time = "9:05",
        body = "**Five voice samples**, all reading the same line, numbered.",
        sounds = voices + Sound("v6", "", null, "audio/mpeg", name = "voice-6.mp3", notReady = NotReady.Processing),
        files = listOf(FileAttachment("doc", "https://dash.test/media/doc/original", "script.txt", "1.2 KB", "text/plain")),
    )

    @Test
    fun everySoundAndTheFileShowInOrder() = runDesktopComposeUiTest(width = 600, height = 900) {
        setContent { AgentTheme(dark = false) { ReplyView(reply) } }
        val tops = (1..5).map { n ->
            onNodeWithContentDescription("Play sound $n of 6, voice-$n.mp3, 0:0${4 + n}").assertExists()
                .fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(tops.sorted(), tops, "the sounds keep the order they were attached in")
        onNodeWithContentDescription("voice-6.mp3, Processing…").assertExists()
        onNodeWithContentDescription("Download script.txt, 1.2 KB").assertExists()
    }

    /** The owner's post answered by that reply. */
    private val post = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Post>().first { it.id == "o1" }.copy(replies = listOf(reply))

    /** The feed with that post in it: inline on a desktop, its thread open on a phone. */
    private fun state(phone: Boolean) = FakeFeed.state.copy(
        openThread = if (phone) post else null,
        sections = FakeFeed.state.sections.map { s -> s.copy(items = s.items.map { if (it.id == post.id) post else it }) },
    )

    private fun ComposeUiTest.shoot(name: String, dark: Boolean, scale: Float) {
        val phone = name == "phone"
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                AgentTheme(dark = dark) {
                    Box(Modifier.fillMaxSize()) { FeedScreen(state(phone), startAt = "o1", preview = ScreenPreview(threadOpen = phone)) }
                }
            }
        }
        if (!phone) onNodeWithText("Can someone find out why the Mac build takes 9 minutes? It used to be 4.").performScrollTo()
        onAllNodes(hasContentDescription("Play sound 1 of 6", substring = true)).onFirst().performScrollTo()
        waitForIdle()
        val file = File(outDir, "$name-${if (dark) "dark" else "light"}-reply-audio.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    @Test
    fun screenshots() {
        for (dark in listOf(true, false)) {
            runDesktopComposeUiTest(2880, 1800) { shoot("desktop", dark, 2f) }
            runDesktopComposeUiTest(1170, 2532) { shoot("phone", dark, 3f) }
        }
    }
}
