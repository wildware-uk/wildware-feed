package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.InternalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Composer
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedUi
import dev.wildware.agents.app.feed.LocalFeedUi
import dev.wildware.agents.app.feed.PendingFile
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.ThreadComposer
import dev.wildware.agents.app.feed.clipboardContents
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Files dragged from the file manager onto any message box attach to it, as the paperclip does:
 * the new-post box and a thread's reply box, anywhere on the box's card. While they hover, the box
 * says "Drop to attach". A copied file or picture pastes in too. Plain text is left to the text box.
 *
 * A test has no window for the system's drag to come from, so it hands the drag straight to the
 * screen's drop handling, where the window would.
 */
@OptIn(ExperimentalTestApi::class)
class FileDropTest {
    private val server = FakeServer()
    private val dir = kotlin.io.path.createTempDirectory("drops").toFile()
    private val log = File(dir, "build.log").apply { writeText("slow step") }
    private val notes = File(dir, "notes.md").apply { writeText("# notes") }
    private val restoreClipboard = clipboardContents

    @AfterTest
    fun cleanUp() {
        server.close()
        dir.deleteRecursively()
        clipboardContents = restoreClipboard
    }

    /** A drag of files, as Finder or Explorer hands it over. */
    private class Files(private val files: List<File>) : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor): Any =
            if (flavor == DataFlavor.javaFileListFlavor) files else throw UnsupportedFlavorException(flavor)
    }

    /** A drag of files as a Linux file manager hands it over: their file:// addresses, as text. */
    private class Addresses(private val files: List<File>) : Transferable {
        private val text = files.joinToString("\r\n") { it.toURI().toString() }
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.stringFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.stringFlavor
        override fun getTransferData(flavor: DataFlavor): Any =
            if (flavor == DataFlavor.stringFlavor) text else throw UnsupportedFlavorException(flavor)
    }

    /** A picture dragged out of another app, or a screenshot on the clipboard. */
    private class Picture : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any =
            if (flavor == DataFlavor.imageFlavor) BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB) else throw UnsupportedFlavorException(flavor)
    }

    /** One step of a drag at [at], made as the window makes it, with [what] standing in for the system's event. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun event(what: Transferable, at: Offset): DragAndDropEvent {
        val make = DragAndDropEvent::class.java.declaredConstructors.first { it.parameterCount == 4 }
        make.isAccessible = true
        val packed = (at.x.toRawBits().toLong() shl 32) or (at.y.toRawBits().toLong() and 0xFFFFFFFFL)
        return make.newInstance(DragAndDropTransferAction.Copy, what, packed, null) as DragAndDropEvent
    }

    /** Drags [what] over the middle of the node [over], as the window would, and lets go unless [hold]. */
    @OptIn(InternalTestApi::class, InternalComposeUiApi::class)
    private fun DesktopComposeUiTest.drag(what: Transferable, over: SemanticsMatcher, hold: Boolean = false): Boolean {
        val at = onNode(over).fetchSemanticsNode().boundsInRoot.center
        val root = scene.rootDragAndDropNode
        val taken = runOnUiThread {
            val e = event(what, at)
            val accepted = root.acceptDragAndDropTransfer(e)
            root.onStarted(e)
            root.onEntered(e)
            root.onMoved(e)
            accepted
        }
        waitForIdle()
        if (!hold) {
            runOnUiThread {
                val e = event(what, at)
                root.onDrop(e)
                root.onEnded(e)
            }
            waitForIdle()
        }
        return taken
    }

    @OptIn(InternalTestApi::class, InternalComposeUiApi::class)
    private fun DesktopComposeUiTest.letGo(what: Transferable, over: SemanticsMatcher) {
        val at = onNode(over).fetchSemanticsNode().boundsInRoot.center
        runOnUiThread {
            val e = event(what, at)
            scene.rootDragAndDropNode.onDrop(e)
            scene.rootDragAndDropNode.onEnded(e)
        }
        waitForIdle()
    }

    private fun ComposeUiTest.waitForLabel(label: String) = try {
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasContentDescription(label), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (timeout: Throwable) {
        throw AssertionError("never saw \"$label\"; the screen had:\n" + onRoot().printToString(), timeout)
    }

    private fun ComposeUiTest.shows(text: String) = onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitForWrite(method: String, path: String): String {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            server.wrote(method, path)?.let { return it }
            Thread.sleep(25)
        }
        error("no $method $path; writes were ${server.writes}")
    }

    private fun ComposeUiTest.app() {
        val platform = AppPlatform.inMemory(
            engine = { server.engine() },
            sessions = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device")),
        )
        setContent { Box(Modifier.size(1440.dp, 900.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) } }
    }

    private val newPost = hasSetTextAction() and hasContentDescription("Message")
    private val reply = hasSetTextAction() and hasContentDescription("Reply")

    @Test
    fun filesDroppedOnTheNewPostBoxUploadWithThePost() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitForLabel("Message")
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasContentDescription("Mega,", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasContentDescription("Mega,", substring = true))[0].performClick()
        waitForIdle()
        assertTrue(drag(Files(listOf(log, notes)), newPost))
        waitForLabel("Attached: build.log")
        waitForLabel("Attached: notes.md")
        onNode(newPost).performTextInput("The logs")
        onNode(hasContentDescription("Send")).performClick()
        waitForWrite("POST", "/api/media")
        val body = waitForWrite("POST", "/api/messages")
        assertTrue("md-up" in body && "The logs" in body, body)
    }

    @Test
    fun filesDroppedOnAThreadReplyBoxGoWithTheReply() = runDesktopComposeUiTest(width = 1440, height = 900) {
        val events = mutableListOf<FeedEvent>()
        val ui = FeedUi(ScreenPreview(), mutableStateOf(FakeFeed.state)) { events += it }
        setContent {
            CompositionLocalProvider(LocalFeedUi provides ui) {
                AgentTheme(dark = false) {
                    Column(Modifier.size(800.dp, 600.dp)) {
                        Composer(placeholder = "Message…", onSend = { _, _ -> })
                        ThreadComposer(ui, "u1")
                    }
                }
            }
        }
        waitForLabel("Reply")
        drag(Files(listOf(log)), reply)
        waitForLabel("Attached: build.log")
        // Only the box it was dropped on takes it.
        assertEquals(1, onAllNodes(hasContentDescription("Attached: build.log"), useUnmergedTree = true).fetchSemanticsNodes().size)
        onNode(reply).performTextInput("Here")
        // The reply box is the lower of the two, so its Send is the lower one.
        val sends = onAllNodes(hasContentDescription("Send"))
        sends[sends.fetchSemanticsNodes().withIndex().maxBy { it.value.boundsInRoot.top }.index].performClick()
        waitForIdle()
        val sent = events.filterIsInstance<FeedEvent.SendReply>().single()
        assertEquals("u1", sent.threadId)
        assertEquals(listOf("build.log"), sent.files.map(PendingFile::name))
    }

    @Test
    fun hoveringFilesSaysDropToAttachAndLeavingClearsIt() = runDesktopComposeUiTest(width = 800, height = 600) {
        composerAlone()
        val files = Files(listOf(log))
        drag(files, newPost, hold = true)
        assertTrue(shows("Drop to attach"), "the box should say Drop to attach while files hover")
        letGo(files, newPost)
        assertTrue(!shows("Drop to attach"), "the hint should go once the files are dropped")
        waitForLabel("Attached: build.log")
    }

    @Test
    fun aLinuxFileManagersDropAndAPictureBothAttach() = runDesktopComposeUiTest(width = 800, height = 600) {
        composerAlone()
        drag(Addresses(listOf(notes)), newPost)
        waitForLabel("Attached: notes.md")
        drag(Picture(), newPost)
        waitForLabel("Attached: pasted-image.png")
    }

    @Test
    fun draggedTextIsLeftToTheTextBox() = runDesktopComposeUiTest(width = 800, height = 600) {
        composerAlone()
        val taken = drag(StringSelection("just words"), newPost, hold = true)
        assertTrue(!taken, "plain text is not a file drop")
        assertTrue(!shows("Drop to attach"))
    }

    @Test
    fun pastingACopiedFileOrScreenshotAttachesIt() = runDesktopComposeUiTest(width = 800, height = 600) {
        composerAlone()
        clipboardContents = { Files(listOf(log)) }
        onNode(newPost).performClick()
        onNode(newPost).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
        waitForLabel("Attached: build.log")
        clipboardContents = { Picture() }
        onNode(newPost).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
        waitForLabel("Attached: pasted-image.png")
    }

    private fun ComposeUiTest.composerAlone() {
        setContent {
            // Set as the app sets it, so a picture drawn here doesn't stop a later test's app setting its own.
            setSingletonImageLoaderFactory { ImageLoader(it) }
            AgentTheme(dark = false) {
                Box(Modifier.size(700.dp, 300.dp)) { Composer(placeholder = "Message…", onSend = { _, _ -> }) }
            }
        }
        waitForLabel("Message")
    }
}
