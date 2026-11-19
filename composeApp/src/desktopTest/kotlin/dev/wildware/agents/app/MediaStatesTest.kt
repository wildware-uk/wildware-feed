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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.MediaGrid
import dev.wildware.agents.app.feed.NotReady
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A picture still being made says "Processing…", one that never will says "Media unavailable". */
@OptIn(ExperimentalTestApi::class)
class MediaStatesTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun waiting(why: NotReady, name: String) = Attachment(name, why.words, aspect = 16f / 9f, notReady = why)

    @Test
    fun placeholdersSayWhyAndOpenNothing() = runDesktopComposeUiTest(width = 600, height = 900) {
        val kept = Attachment("other.png", "A chart", aspect = 16f / 9f)
        var opened = -1
        setContent {
            AgentTheme(dark = false) {
                MediaGrid(listOf(waiting(NotReady.Processing, "a.png"), waiting(NotReady.Failed, "b.png"), kept), onOpen = { opened = it })
            }
        }
        onAllNodes(hasContentDescription("Processing…"), useUnmergedTree = true).onFirst().assertExists()
        onAllNodes(hasContentDescription("Media unavailable"), useUnmergedTree = true).onFirst().assertExists()
        assertTrue(onAllNodes(hasClickAction() and hasContentDescription("Processing…", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasClickAction() and hasContentDescription("Media unavailable", substring = true)).fetchSemanticsNodes().isEmpty())
        onAllNodes(hasClickAction() and hasContentDescription("Picture 3 of 3", substring = true)).onFirst().performClick()
        assertEquals(2, opened)
    }

    /** The empty-state art update, with one picture still processing and one that failed. */
    private val state = FakeFeed.state.copy(
        openThread = null,
        sections = FakeFeed.state.sections.map { s ->
            s.copy(items = s.items.map { item ->
                if (item is FeedItem.Update && item.id == "u2") {
                    item.copy(attachments = listOf(item.attachments.first(), waiting(NotReady.Processing, "empty-state-dawn.png"), waiting(NotReady.Failed, "empty-state-night.png")))
                } else item
            })
        },
    )

    private fun ComposeUiTest.shoot(name: String, dark: Boolean, scale: Float) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(state, startAt = "u2") } }
            }
        }
        onNodeWithText("New empty-state art for the feed").performScrollTo()
        waitForIdle()
        val file = File(outDir, "$name-${if (dark) "dark" else "light"}-media-not-ready.png")
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
