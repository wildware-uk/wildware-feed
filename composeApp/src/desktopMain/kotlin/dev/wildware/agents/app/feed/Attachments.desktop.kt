package dev.wildware.agents.app.feed

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
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

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onFiles: (List<PendingFile>) -> Unit): Modifier =
    dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.dragData() is DragData.FilesList },
        target = object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = onHover(true)
            override fun onExited(event: DragAndDropEvent) = onHover(false)
            override fun onEnded(event: DragAndDropEvent) = onHover(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                    .mapNotNull { uri -> runCatching { File(URI(uri)) }.getOrNull()?.toPending() }
                onFiles(files)
                return files.isNotEmpty()
            }
        },
    )

actual fun clipboardFiles(): List<PendingFile>? = runCatching {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    when {
        clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) ->
            (clipboard.getData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>().mapNotNull { it.toPending() }
        clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) -> {
            val image = clipboard.getData(DataFlavor.imageFlavor) as Image
            listOf(PendingFile("pasted-image.png", "image/png", pngBytes(image)))
        }
        else -> null
    }
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
