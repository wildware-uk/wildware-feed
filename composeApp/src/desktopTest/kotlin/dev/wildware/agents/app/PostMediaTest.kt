package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.FileAttachment
import dev.wildware.agents.app.feed.Sound
import dev.wildware.agents.app.feed.VideoSource
import kotlin.test.Test

/** Posts and replies show their videos, pictures, sounds and files, as updates do. */
@OptIn(ExperimentalTestApi::class)
class PostMediaTest {
    private val post = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Post>().first()
    private val video = Attachment(title = "Screen recording", altText = "Screen recording", aspect = 16f / 9f, url = null, video = VideoSource("https://dash.test/media/v1/video", "0:12"))
    private val sound = Sound("s1", "https://dash.test/media/s1/original", 9_000, "audio/mpeg")
    private val file = FileAttachment("f1", "https://dash.test/media/f1/original", "crash.log", "1.5 KB", "text/plain")

    private fun withMedia(): FeedState {
        val reply = post.replies.firstOrNull()
        val withReply = if (reply != null) post.replies else emptyList()
        val p = post.copy(
            attachments = listOf(video), sounds = listOf(sound), files = listOf(file),
            replies = withReply.mapIndexed { i, r -> if (i == 0) r.copy(attachments = listOf(video.copy(title = "Reply clip", altText = "Reply clip"))) else r },
        )
        return FakeFeed.state.copy(sections = FakeFeed.state.sections.map { s -> s.copy(items = s.items.map { if (it.id == p.id) p else it }) })
    }

    private fun ComposeUiTest.show(state: FeedState, width: Int, height: Int) = setContent {
        AgentTheme(dark = false) { Box(Modifier.size(width.dp, height.dp)) { FeedScreen(state) } }
    }

    private fun ComposeUiTest.find(label: String) {
        onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(label, substring = true))
        onNodeWithContentDescription(label, substring = true).assertExists()
    }

    @Test
    fun aPostShowsItsVideoSoundAndFileOnADesktop() = runComposeUiTest {
        show(withMedia(), 1440, 900)
        find("Play video 1 of 1, 0:12")
        find("Download crash.log")
    }

    @Test
    fun aPostShowsItsVideoOnAPhone() = runComposeUiTest {
        show(withMedia(), 390, 844)
        find("Play video 1 of 1, 0:12")
        find("Download crash.log")
    }

    @Test
    fun aReplyShowsItsVideoInThePhoneThread() = runComposeUiTest {
        val fix = FakeFeed.state.openThread as FeedItem.Update
        val thread = fix.copy(replies = fix.replies.mapIndexed { i, r -> if (i == 1) r.copy(attachments = listOf(video), files = listOf(file)) else r })
        val state = FakeFeed.state.copy(
            openThread = thread,
            sections = FakeFeed.state.sections.map { s -> s.copy(items = s.items.map { if (it.id == thread.id) thread else it }) },
        )
        setContent { AgentTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { FeedScreen(state, preview = dev.wildware.agents.app.feed.ScreenPreview(threadOpen = true)) } } }
        val inThread = androidx.compose.ui.test.hasAnyAncestor(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.PaneTitle, "Thread"),
        )
        onNode(hasContentDescription("Play video 1 of 1, 0:12") and inThread).assertExists()
        onNode(hasContentDescription("Download crash.log", substring = true) and inThread).assertExists()
    }
}
