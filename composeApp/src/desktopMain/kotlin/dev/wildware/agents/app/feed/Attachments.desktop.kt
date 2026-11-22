package dev.wildware.agents.app.feed

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/** Reads a file into a [PendingFile], or null when it can't be read. */
internal fun File.toPending(): PendingFile? = runCatching {
    if (!isFile) return null
    PendingFile(name, mimeTypeOf(name), readBytes())
}.getOrNull()

/** The desktop's own "choose files" dialog, opened over the app. */
class AwtFilePicker(private val parent: () -> Frame?) : FilePicker {
    override fun pick(onPicked: (List<PendingFile>) -> Unit) {
        SwingUtilities.invokeLater {
            val dialog = FileDialog(parent(), "Attach files", FileDialog.LOAD).apply { isMultipleMode = true }
            dialog.isVisible = true
            onPicked(dialog.files.orEmpty().mapNotNull { it.toPending() })
        }
    }
}

/**
 * Files dragged onto the box: from Finder, Explorer or a Linux file manager (which hands over a
 * list of file:// addresses), or a picture dragged out of another app. Plain text dragged into the
 * box is left alone, so the text field still takes it as text.
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onFiles: (List<PendingFile>) -> Unit): Modifier = composed {
    val hover by rememberUpdatedState(onHover)
    val take by rememberUpdatedState(onFiles)
    // One target for the box's life, so a redraw mid-drag doesn't lose the drag.
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(true)
            override fun onExited(event: DragAndDropEvent) = hover(false)
            override fun onEnded(event: DragAndDropEvent) = hover(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = event.transferable()?.let(::readFiles).orEmpty()
                take(files)
                return files.isNotEmpty()
            }
        }
    }
    dragAndDropTarget(shouldStartDragAndDrop = { event -> event.transferable()?.let(::carriesFiles) == true }, target = target)
}

/**
 * What was dragged. A test hands the [Transferable] itself as the event, as a test has no window
 * for the system's drag to come from.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.transferable(): Transferable? =
    nativeEvent as? Transferable ?: runCatching { awtTransferable }.getOrNull()

/** The flavor Linux file managers use: one file:// address a line. */
private val uriListFlavor: DataFlavor? = runCatching { DataFlavor("text/uri-list;class=java.lang.String") }.getOrNull()

/** Whether [t] holds files or a picture, not just text. */
internal fun carriesFiles(t: Transferable): Boolean = runCatching {
    t.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
        t.isDataFlavorSupported(DataFlavor.imageFlavor) ||
        fileUris(t).isNotEmpty()
}.getOrDefault(false)

/** The files or picture in [t]: what was dropped or is on the clipboard. Empty when it is only text. */
internal fun readFiles(t: Transferable): List<PendingFile> = runCatching {
    when {
        t.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ->
            (t.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>().mapNotNull { it.toPending() }
        fileUris(t).isNotEmpty() -> fileUris(t).mapNotNull { uri -> runCatching { File(URI(uri)) }.getOrNull()?.toPending() }
        t.isDataFlavorSupported(DataFlavor.imageFlavor) ->
            listOf(PendingFile("pasted-image.png", "image/png", pngBytes(t.getTransferData(DataFlavor.imageFlavor) as Image)))
        else -> emptyList()
    }
}.getOrDefault(emptyList())

/** The file:// addresses in [t], from a uri-list or from text that is nothing but such addresses. */
private fun fileUris(t: Transferable): List<String> {
    val text = listOfNotNull(uriListFlavor, DataFlavor.stringFlavor).firstNotNullOfOrNull { flavor ->
        if (t.isDataFlavorSupported(flavor)) runCatching { t.getTransferData(flavor) as? String }.getOrNull() else null
    } ?: return emptyList()
    // A uri-list may carry comment lines (#...); anything else that isn't a file address means it's just text.
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    return lines.takeIf { it.isNotEmpty() && it.all { line -> line.startsWith("file:/") } }.orEmpty()
}

/** What is on the clipboard now. A test puts its own here, as a test has no system clipboard. */
internal var clipboardContents: () -> Transferable? = { Toolkit.getDefaultToolkit().systemClipboard.getContents(null) }

actual fun clipboardFiles(): List<PendingFile>? = runCatching {
    clipboardContents()?.let(::readFiles)
}.getOrNull()?.takeIf { it.isNotEmpty() }

private fun pngBytes(image: Image): ByteArray {
    val buffered = image as? BufferedImage ?: BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB).also {
        it.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
    }
    return ByteArrayOutputStream().use { out ->
        ImageIO.write(buffered, "png", out)
        out.toByteArray()
    }
}
