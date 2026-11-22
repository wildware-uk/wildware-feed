package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.DesktopMediaHandler
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FileAttachment
import dev.wildware.agents.app.feed.Gallery
import dev.wildware.agents.app.feed.LocalMediaAuth
import dev.wildware.agents.app.feed.LocalMediaHandler
import dev.wildware.agents.app.feed.MediaAuth
import dev.wildware.agents.app.feed.PictureRect
import dev.wildware.agents.app.feed.ScreenPreview
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The picture viewer on a computer: fitted, zoomed with the wheel and keys, panned, copied, paged and closed. */
@OptIn(ExperimentalTestApi::class)
class ImageViewerTest {
    private val png: ByteArray = ByteArrayOutputStream().also { out ->
        ImageIO.write(BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB).apply { setRGB(3, 4, 0xFFFF0000.toInt()) }, "png", out)
    }.toByteArray()
    private val asked = mutableListOf<String>()
    private val copied = mutableListOf<Transferable>()
    private val handler = DesktopMediaHandler(
        engine = {
            MockEngine { request ->
                asked += request.url.toString()
                respond(png)
            }
        },
        downloads = File(Files.createTempDirectory("viewer").toFile(), "Downloads"),
        reveal = { true },
        clipboard = { copied += it },
        openFile = { true },
    )

    private fun picture(n: Int) = Attachment(
        title = "Chart $n",
        altText = "Test chart number $n",
        aspect = 4f / 3f,
        url = "https://dash.test/media/p$n/thumb-1600",
        original = FileAttachment("p$n", "https://dash.test/media/p$n/original?download=1", "chart-$n.png", "2.0 MB", "image/png"),
        fullUrl = "https://dash.test/media/p$n/original",
        width = 4000,
        height = 3000,
    )

    private val pictures = listOf(picture(1), picture(2), picture(3))

    private fun ComposeUiTest.viewer(images: List<Attachment> = pictures) = setContent {
        CompositionLocalProvider(LocalMediaHandler provides handler, LocalMediaAuth provides MediaAuth("https://dash.test", "Bearer key-1")) {
            AgentTheme(dark = false) {
                Box(Modifier.fillMaxSize()) { FeedScreen(FakeFeed.state, preview = ScreenPreview(viewer = Gallery(images))) }
            }
        }
    }

    /** Where the picture is drawn, in the window. */
    private fun ComposeUiTest.rect(): Rect {
        waitForIdle()
        val node = onNode(SemanticsMatcher.keyIsDefined(PictureRect)).fetchSemanticsNode()
        return node.config[PictureRect].translate(node.boundsInRoot.topLeft)
    }

    /** The space the picture is shown in: the window between the bar and the hint. */
    private fun ComposeUiTest.page(): Rect {
        waitForIdle()
        return onNode(SemanticsMatcher.keyIsDefined(PictureRect)).fetchSemanticsNode().boundsInRoot
    }

    /** "Zoom 24%": what the bar says when the 4000 × 3000 picture is fitted. */
    private fun ComposeUiTest.fitted(): String {
        val p = page()
        return "Zoom ${(minOf(p.width / 4000f, p.height / 3000f) * 100).roundToInt()}%"
    }

    private fun near(a: Float, b: Float, slack: Float = 2f) = abs(a - b) <= slack

    @Test
    fun itOpensFittedWithTheWholePictureInTheWindow() = runDesktopComposeUiTest(1200, 800) {
        viewer()
        val r = rect()
        val p = page()
        assertTrue(p.top > 0f && p.bottom < 800f, "the bar and the hint cover none of it: $p")
        assertTrue(r.left >= p.left - 0.5f && r.top >= p.top - 0.5f && r.right <= p.right + 0.5f && r.bottom <= p.bottom + 0.5f, "the whole picture shows: $r in $p")
        assertTrue(near(r.height, p.height), "it fills the height: $r in $p")
        assertTrue(near(r.width, 4000f * p.height / 3000f), "never stretched: $r")
        onNodeWithContentDescription(fitted()).assertExists()
        onNodeWithText("1 / 3").assertExists()
        onNodeWithText("chart-1.png · 2.0 MB · 4000 × 3000", substring = true).assertExists()
    }

    @Test
    fun theWheelZoomsAroundThePointer() = runDesktopComposeUiTest(1200, 800) {
        viewer()
        val fittedAt = rect()
        onRoot().performMouseInput {
            moveTo(Offset(400f, 300f))
            scroll(1f)
        }
        assertTrue(rect().width < fittedAt.width, "scrolling down zooms out: $fittedAt then ${rect()}")
        // From 100% the picture is bigger than the window both ways, so nothing holds it in the middle.
        onNodeWithContentDescription("Actual size").performClick()
        val before = rect()
        val at = Offset(400f, 300f)
        val fx = (at.x - before.left) / before.width
        val fy = (at.y - before.top) / before.height
        onRoot().performMouseInput {
            moveTo(at)
            scroll(-1f)
        }
        val after = rect()
        assertTrue(after.width > before.width * 1.1f, "zoomed in: $before then $after")
        assertTrue(near((at.x - after.left) / after.width, fx, 0.002f), "the point under the pointer stays put (x): $before then $after")
        assertTrue(near((at.y - after.top) / after.height, fy, 0.002f), "the point under the pointer stays put (y): $before then $after")
    }

    @Test
    fun aDragPansAndCannotLoseThePicture() = runDesktopComposeUiTest(1200, 800) {
        viewer()
        onNodeWithContentDescription("Actual size").performClick()
        onNodeWithContentDescription("Zoom 100%").assertExists()
        val start = rect()
        assertTrue(near(start.width, 4000f), "100% is one picture pixel to one screen pixel: $start")
        onRoot().performMouseInput {
            moveTo(Offset(600f, 400f))
            press()
            moveBy(Offset(40f, 0f))
            moveBy(Offset(100f, 0f))
            release()
        }
        val moved = rect()
        assertTrue(near(moved.left - start.left, 140f), "it follows the drag: $start then $moved")
        onRoot().performMouseInput {
            moveTo(Offset(600f, 400f))
            press()
            moveBy(Offset(40f, 40f))
            moveBy(Offset(5000f, 5000f))
            release()
        }
        val pushed = rect()
        val p = page()
        assertTrue(near(pushed.left, p.left) && near(pushed.top, p.top), "its corner stops at the corner: $pushed in $p")
        onNodeWithContentDescription("Close pictures").assertExists()
    }

    @Test
    fun ctrlZeroFitsAgainAndDoubleClickGoesToActualSize() = runDesktopComposeUiTest(1200, 800) {
        viewer()
        onRoot().performMouseInput { doubleClick(Offset(600f, 400f)) }
        waitForIdle()
        onNodeWithContentDescription("Zoom 100%").assertExists()
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Equals) } }
        onNodeWithContentDescription("Zoom 125%").assertExists()
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Zero) } }
        onNodeWithContentDescription(fitted()).assertExists()
        val r = rect()
        val p = page()
        assertTrue(near(r.height, p.height) && near(r.center.x, p.center.x) && near(r.center.y, p.center.y), "fitted and in the middle: $r in $p")
    }

    @Test
    fun ctrlCPutsThePictureOnTheClipboard() = runDesktopComposeUiTest(1200, 800) {
        viewer()
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        waitUntil(timeoutMillis = 5_000) { copied.isNotEmpty() }
        assertEquals(listOf("https://dash.test/media/p1/original"), asked, "the original, not the feed's smaller copy")
        val item = copied.single()
        assertTrue(item.isDataFlavorSupported(DataFlavor.imageFlavor), "an image, not a link")
        val image = item.getTransferData(DataFlavor.imageFlavor) as BufferedImage
        assertEquals(40 to 30, image.width to image.height)
        assertEquals(0xFFFF0000.toInt(), image.getRGB(3, 4))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(androidx.compose.ui.test.hasText("Picture copied")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theArrowKeysGoThroughThreePictures() = runDesktopComposeUiTest(1200, 800) {
        viewer()
        onNodeWithText("1 / 3").assertExists()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNodeWithText("2 / 3").assertExists()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNodeWithText("3 / 3").assertExists()
        onNodeWithContentDescription("Next picture").assertDoesNotExist()
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        onNodeWithText("2 / 3").assertExists()
        onNodeWithContentDescription("Previous picture").performClick()
        waitForIdle()
        onNodeWithText("1 / 3").assertExists()
    }

    @Test
    fun escapeCloses() = runDesktopComposeUiTest(1200, 800) {
        viewer()
        onNodeWithContentDescription("Close pictures").assertExists()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithContentDescription("Close pictures").assertDoesNotExist()
    }

    @Test
    fun aClickBesideThePictureClosesButTheEndOfADragDoesNot() = runDesktopComposeUiTest(1200, 800) {
        viewer()
        // Fitted, the picture is under 1000 wide in the middle: x 20 is beside it.
        onRoot().performMouseInput {
            moveTo(Offset(20f, 400f))
            press()
            moveBy(Offset(30f, 0f))
            moveBy(Offset(30f, 0f))
            release()
        }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithContentDescription("Close pictures").assertExists()
        onRoot().performMouseInput { click(Offset(20f, 400f)) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        onNodeWithContentDescription("Close pictures").assertDoesNotExist()
    }

    @Test
    fun aRightClickOffersCopySaveLinkAndZoom() = runDesktopComposeUiTest(1200, 800) {
        viewer()
        onRoot().performMouseInput { rightClick(Offset(600f, 400f)) }
        waitForIdle()
        listOf("Copy image", "Save picture", "Copy link", "Open in default app", "Show in folder", "Fit to window", "Actual size (100%)").forEach {
            onNodeWithText(it).assertExists()
        }
        onNodeWithText("Actual size (100%)").performClick()
        onNodeWithContentDescription("Zoom 100%").assertExists()
    }
}
