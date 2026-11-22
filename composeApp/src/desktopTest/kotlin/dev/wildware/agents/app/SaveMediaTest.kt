package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.DesktopMediaHandler
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FileAttachment
import dev.wildware.agents.app.feed.LocalMediaAuth
import dev.wildware.agents.app.feed.LocalMediaHandler
import dev.wildware.agents.app.feed.MediaAuth
import dev.wildware.agents.app.feed.MenuTarget
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.VideoSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** A video on a card is saved whole from its Save button: the upload itself, into Downloads, then found with "Show in folder". */
@OptIn(ExperimentalTestApi::class)
class SaveMediaTest {
    private val root: File = Files.createTempDirectory("save-media").toFile()
    private val downloads = File(root, "Downloads")
    private val original = ByteArray(4096) { (it * 31 % 251).toByte() }
    private val asked = mutableListOf<String>()
    private val keys = mutableListOf<String?>()
    private val revealed = mutableListOf<File>()

    private val handler = DesktopMediaHandler(
        engine = {
            MockEngine { request ->
                asked += request.url.toString()
                keys += request.headers["Authorization"]
                respond(original)
            }
        },
        downloads = downloads,
        reveal = { revealed += it; true },
    )

    private val clip = Attachment(
        "build.mp4", "build.mp4", aspect = 16f / 9f,
        video = VideoSource(
            "https://dash.test/media/v1/video", "0:42",
            original = FileAttachment("v1", "https://dash.test/media/v1/original?download=1", "build.mp4", "4.0 KB", "video/mp4"),
        ),
    )

    private val post = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Post>().first { it.id == "o1" }.copy(attachments = listOf(clip))
    private val state = FakeFeed.state.copy(sections = FakeFeed.state.sections.map { s -> s.copy(items = s.items.map { if (it.id == post.id) post else it }) })

    @Test
    fun theSaveButtonOnAVideoCardSavesTheOriginalToDownloads() = runDesktopComposeUiTest(1400, 1000) {
        setContent {
            CompositionLocalProvider(LocalMediaHandler provides handler, LocalMediaAuth provides MediaAuth("https://dash.test", "Bearer key-1")) {
                AgentTheme(dark = false) { Box(Modifier.fillMaxSize()) { FeedScreen(state, startAt = "o1") } }
            }
        }
        onAllNodes(hasContentDescription("Save video build.mp4")).onFirst().performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Saved to Downloads as build.mp4")).fetchSemanticsNodes().isNotEmpty() }

        val saved = File(downloads, "build.mp4")
        assertContentEquals(original, saved.readBytes(), "the upload's own bytes")
        assertEquals(listOf("https://dash.test/media/v1/original?download=1"), asked, "the original, not the transcode it plays")
        assertEquals(listOf<String?>("Bearer key-1"), keys)

        onAllNodes(hasContentDescription("Show in folder")).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(saved), revealed)
    }

    @Test
    fun aLongPressMenuOffersToSaveTheVideo() = runDesktopComposeUiTest(390, 844) {
        val menu = MenuTarget(post.id, "You", post.body, own = true, saves = listOfNotNull(clip.saveable))
        setContent {
            CompositionLocalProvider(LocalMediaHandler provides handler, LocalMediaAuth provides MediaAuth("https://dash.test", "Bearer key-1")) {
                AgentTheme(dark = false) { Box(Modifier.fillMaxSize()) { FeedScreen(state, startAt = "o1", preview = ScreenPreview(menu = menu)) } }
            }
        }
        onAllNodes(hasText("Save video")).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitUntil(timeoutMillis = 5_000) { File(downloads, "build.mp4").exists() }
        assertContentEquals(original, File(downloads, "build.mp4").readBytes())
        assertEquals(listOf("https://dash.test/media/v1/original?download=1"), asked)
    }
}
