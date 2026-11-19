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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.Author
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FileAttachment
import dev.wildware.agents.app.feed.Files
import dev.wildware.agents.app.feed.LocalMediaHandler
import dev.wildware.agents.app.feed.MediaAuth
import dev.wildware.agents.app.feed.MediaHandler
import dev.wildware.agents.app.feed.NotReady
import dev.wildware.agents.app.feed.Playable
import dev.wildware.agents.app.feed.Reply
import dev.wildware.agents.app.feed.ReplyView
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.VideoSource
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Files on the desktop: one an agent sent is saved to Downloads and then found with "Show in
 * folder"; files the owner sends say they are going up, and how far they have got.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopMediaTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private class FakeHandler(override val showsSavedFiles: Boolean) : MediaHandler {
        val shown = mutableListOf<String>()
        override suspend fun open(item: Playable, auth: MediaAuth?) = "Plays in the app"
        override suspend fun save(file: FileAttachment, auth: MediaAuth?) = "Saved to Downloads as ${file.name}"
        override fun showSaved(file: FileAttachment): Boolean {
            shown += file.id
            return true
        }
    }

    private val report = FileAttachment("doc", "https://dash.test/media/doc/original", "report.pdf", "1.4 MB", "application/pdf")

    @Test
    fun aSavedFileOffersToShowItsFolder() = runDesktopComposeUiTest(width = 600, height = 300) {
        val handler = FakeHandler(showsSavedFiles = true)
        setContent { CompositionLocalProvider(LocalMediaHandler provides handler) { AgentTheme(dark = false) { Files(listOf(report)) } } }
        onNodeWithContentDescription("Show report.pdf in its folder").assertDoesNotExist()
        onNodeWithContentDescription("Download report.pdf, 1.4 MB").performClick()
        waitUntil { onAllNodes(hasText("Saved to Downloads as report.pdf")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Show report.pdf in its folder").performClick()
        assertEquals(listOf("doc"), handler.shown)
    }

    @Test
    fun aPhoneSaysWhereTheFileWentButHasNoFolderToShow() = runDesktopComposeUiTest(width = 600, height = 300) {
        setContent { CompositionLocalProvider(LocalMediaHandler provides FakeHandler(showsSavedFiles = false)) { AgentTheme(dark = false) { Files(listOf(report)) } } }
        onNodeWithContentDescription("Download report.pdf, 1.4 MB").performClick()
        waitUntil { onAllNodes(hasText("Saved to Downloads as report.pdf")).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Show report.pdf in its folder").assertDoesNotExist()
    }

    private val going = Reply(
        id = "outbox:o1",
        author = Author.Owner("Sam"),
        time = "",
        body = "Here are the logs and the screen recording.",
        sending = true,
        uploaded = 0.45f,
        attachments = listOf(Attachment("screen.mov", NotReady.Uploading.words, aspect = 16f / 9f, notReady = NotReady.Uploading)),
        files = listOf(FileAttachment("outbox:o1/1", "", "logs.zip", "3.2 MB", "application/zip", notReady = NotReady.Uploading)),
    )

    @Test
    fun aReplyGoingUpSaysHowFarAndShowsItsFiles() = runDesktopComposeUiTest(width = 600, height = 600) {
        setContent { AgentTheme(dark = false) { ReplyView(going) } }
        assertTrue(onAllNodes(hasContentDescription("Uploading 45%", substring = true)).fetchSemanticsNodes().isNotEmpty())
        onNodeWithContentDescription("logs.zip, Uploading…").assertExists()
        onAllNodes(hasContentDescription("Uploading…"), useUnmergedTree = true).onFirst().assertExists()
    }

    /** An agent's reply with a video and a file (saved), and the owner's reply still going up. */
    private val agentReply = Reply(
        id = "ra",
        author = Author.AgentAuthor(FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().first().agent),
        time = "9:05",
        body = "Found it: the cache key changed. Recording and the full report attached.",
        attachments = listOf(Attachment("build.mp4", "build.mp4", aspect = 16f / 9f, video = VideoSource("https://dash.test/media/v1/video", "0:42"))),
        files = listOf(report),
    )

    private val post = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Post>().first { it.id == "o1" }.copy(replies = listOf(agentReply, going))

    private fun state(phone: Boolean) = FakeFeed.state.copy(
        openThread = if (phone) post else null,
        sections = FakeFeed.state.sections.map { s -> s.copy(items = s.items.map { if (it.id == post.id) post else it }) },
    )

    private fun ComposeUiTest.shoot(name: String, dark: Boolean, scale: Float) {
        val phone = name == "phone"
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale), LocalMediaHandler provides FakeHandler(showsSavedFiles = !phone)) {
                AgentTheme(dark = dark) {
                    Box(Modifier.fillMaxSize()) { FeedScreen(state(phone), startAt = "o1", preview = ScreenPreview(threadOpen = phone)) }
                }
            }
        }
        onAllNodes(hasContentDescription("Download report.pdf", substring = true)).onFirst().performScrollTo()
            // Scrolled only just into view it can sit under the message box, so it is clicked as a screen reader would.
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Saved to Downloads as report.pdf")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasContentDescription(if (phone) "Play video 1 of 1" else "Show report.pdf in its folder", substring = true)).onFirst().performScrollTo()
        waitForIdle()
        val file = File(outDir, "$name-${if (dark) "dark" else "light"}-desktop-media.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }

    @Test
    fun screenshots() {
        for (dark in listOf(true, false)) {
            runDesktopComposeUiTest(2880, 2600) { shoot("desktop", dark, 2f) }
            runDesktopComposeUiTest(1170, 2532) { shoot("phone", dark, 3f) }
        }
    }
}
