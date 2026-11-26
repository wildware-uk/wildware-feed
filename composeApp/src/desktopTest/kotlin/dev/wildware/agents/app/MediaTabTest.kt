package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.ChannelMediaCard
import dev.wildware.agents.ChannelMediaItem
import dev.wildware.agents.ChannelMediaPage
import dev.wildware.agents.DocAuthor
import dev.wildware.agents.MediaView
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.engine.MediaWatch
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.FileAttachment
import dev.wildware.agents.app.feed.MediaAction
import dev.wildware.agents.app.feed.MediaFilter
import dev.wildware.agents.app.feed.emptyWords
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Media tab: a channel's pictures, videos and files, by kind, a page at a time, each going to its message. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class MediaTabTest {
    private val server = FakeServer()

    @AfterTest
    fun closeStreams() = server.close()

    // ---- the engine: filter and pages ------------------------------------------------------------

    private fun item(id: String, kind: String) = ChannelMediaItem(
        MediaView(id = id, messageId = "m-$id", kind = kind, status = "ready", filename = "$id.bin", variants = listOf("original")),
        ChannelMediaCard("message", "m-$id"),
        DocAuthor("member:s1", "person", "Sam"),
        "2026-11-20T10:00:00Z",
    )

    @Test
    fun aFilterReadsFromTheTopAndMoreAddsTheNextPage() = runTest(UnconfinedTestDispatcher()) {
        val asked = mutableListOf<Triple<String, MediaFilter, String?>>()
        val pages = mutableMapOf<Pair<MediaFilter, String?>, ChannelMediaPage>(
            (MediaFilter.All to null) to ChannelMediaPage(items = listOf(item("a", "image"), item("b", "file")), nextCursor = "c1"),
            (MediaFilter.All to "c1") to ChannelMediaPage(items = listOf(item("c", "video"))),
            (MediaFilter.Files to null) to ChannelMediaPage(items = listOf(item("b", "file"))),
            (MediaFilter.Videos to null) to ChannelMediaPage(),
        )
        val watch = MediaWatch(
            read = { channel, filter, cursor -> asked += Triple(channel, filter, cursor); pages.getValue(filter to cursor) },
            scope = backgroundScope,
            tile = { m -> Attachment(m.id, m.id, 1f) to Attachment(m.id, m.id, 1f) },
            file = { m -> FileAttachment(m.id, "u", m.filename ?: "", "1 KB", m.mime) },
            ago = { "5m ago" },
        )

        watch.handle(MediaAction.Show("mega"))
        var state = watch.state.value
        assertEquals(listOf("a", "b"), state.items.map { it.id })
        assertTrue(state.hasMore)
        assertEquals("Sam", state.items[0].sentBy)
        assertTrue(state.items[0].tile != null && state.items[0].file == null, "a picture is a tile")
        assertTrue(state.items[1].tile == null && state.items[1].file != null, "a file is a row")
        assertEquals("m-a", state.items[0].cardId)

        watch.handle(MediaAction.More)
        state = watch.state.value
        assertEquals(listOf("a", "b", "c"), state.items.map { it.id })
        assertFalse(state.hasMore)
        watch.handle(MediaAction.More)
        assertEquals(2, asked.count { it.second == MediaFilter.All }, "no more pages to read")

        watch.handle(MediaAction.Filter(MediaFilter.Files))
        state = watch.state.value
        assertEquals(MediaFilter.Files, state.filter)
        assertEquals(listOf("b"), state.items.map { it.id })
        assertNull(state.nextCursor)

        watch.handle(MediaAction.Filter(MediaFilter.Videos))
        state = watch.state.value
        assertTrue(state.loaded && state.items.isEmpty())
        assertEquals("No videos in this channel yet.", emptyWords(state.filter))
        assertEquals("No media in this channel yet.", emptyWords(MediaFilter.All))

        // Another channel starts again from All.
        pages[MediaFilter.All to null] = ChannelMediaPage(items = listOf(item("z", "image")))
        watch.handle(MediaAction.Show("web"))
        state = watch.state.value
        assertEquals(MediaFilter.All, state.filter)
        assertEquals(listOf("z"), state.items.map { it.id })
        assertEquals("web", asked.last().first)
    }

    // ---- the screen ------------------------------------------------------------------------------

    private val sam = """{"principal":"member:s1","kind":"person","name":"Sam"}"""
    private val forge = """{"principal":"agent:a1","kind":"agent","name":"forge"}"""

    private fun picture(id: String, name: String, card: String = "m-post") =
        """{"media":{"id":"$id","messageId":"$card","kind":"image","mime":"image/png","status":"ready","bytes":2048,"width":10,"height":10,""" +
            """"filename":"$name","variants":["original","thumb-640"]},"card":{"kind":"message","id":"$card"},"sentBy":$sam,"at":"2026-11-20T10:00:00Z"}"""

    private fun file(id: String, name: String) =
        """{"media":{"id":"$id","messageId":"m-post","kind":"file","mime":"application/pdf","status":"ready","bytes":4096,""" +
            """"filename":"$name","variants":["original"]},"card":{"kind":"message","id":"m-post"},"sentBy":$forge,"at":"2026-11-20T10:00:00Z"}"""

    private fun page(vararg items: String, next: String? = null) =
        """{"projectId":"p1","items":[${items.joinToString(",")}],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}"""

    private fun ComposeUiTest.openMedia() {
        server.docAnswers["GET /api/projects/mega/media?limit=60"] = 200 to page(picture("md1", "shot.png"), file("md2", "notes.pdf"), next = "c1")
        server.docAnswers["GET /api/projects/mega/media?cursor=c1&limit=60"] = 200 to page(file("md3", "later.pdf"))
        server.docAnswers["GET /api/projects/mega/media?kind=file&limit=60"] = 200 to page(file("md2", "notes.pdf"))
        server.docAnswers["GET /api/projects/mega/media?kind=video&limit=60"] = 200 to page()
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device")))
        setContent { Box(Modifier.size(1440.dp, 900.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) } }
        waitForNode(hasText("Fixed the unread counts"))
        waitForNode(hasText("Mega") and hasClickAction()).performClick()
        waitForNode(hasText("Media") and hasClickAction()).performClick()
        waitForNode(hasText("notes.pdf"))
    }

    private fun ComposeUiTest.waitForNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        try {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            throw AssertionError("never saw ${matcher.description}; the screen had:\n" + onRoot().printToString(), timeout)
        }
        return onAllNodes(matcher).onFirst()
    }

    private fun ComposeUiTest.waitForNone(matcher: SemanticsMatcher) {
        try {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
        } catch (timeout: Throwable) {
            throw AssertionError("still saw ${matcher.description}; the screen had:\n" + onRoot().printToString(), timeout)
        }
    }

    @Test
    fun theMediaTabShowsPicturesAndFilesAndReadsTheNextPage() = runDesktopComposeUiTest(width = 1440, height = 900) {
        openMedia()
        waitForNode(hasContentDescription("Picture: shot.png, sent by Sam", substring = true))
        waitForNode(hasText("4.0 KB  ·  forge", substring = true))
        // Two items leave the end in view: the next page is read and added.
        waitForNode(hasText("later.pdf"))
        assertTrue(synchronized(server.calls) { server.calls.toList() }.any { it == "/api/projects/mega/media?cursor=c1&limit=60" })
    }

    @Test
    fun aChipShowsOnlyThatKindOrSaysThereIsNone() = runDesktopComposeUiTest(width = 1440, height = 900) {
        openMedia()
        waitForNode(hasText("Files") and hasClickAction()).performClick()
        waitForNone(hasContentDescription("Picture: shot.png", substring = true))
        waitForNode(hasText("notes.pdf"))
        waitForNode(hasText("Videos") and hasClickAction()).performClick()
        waitForNode(hasText("No videos in this channel yet."))
    }

    @Test
    fun goToTheMessageOpensItsCardAndBackComesBackToTheTab() = runDesktopComposeUiTest(width = 1440, height = 900) {
        openMedia()
        waitForNode(hasContentDescription("Go to the message")).performClick()
        waitForNone(hasText("MEDIA  ·  CHANNEL"))
        waitForNode(hasText("Why is the Mac build slow?"))
        // Back is to the Media tab it was opened from, not the feed.
        waitForNode(hasText("Back to media")).performClick()
        waitForNode(hasText("MEDIA  ·  CHANNEL"))
    }
}
