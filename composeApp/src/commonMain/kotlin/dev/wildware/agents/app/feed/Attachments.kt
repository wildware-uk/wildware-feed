package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable

/** A file the owner picked, dropped or pasted, waiting to go up with a message. */
class PendingFile(val name: String, val mimeType: String, val bytes: ByteArray) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

/** The biggest file the composer takes. Larger ones are turned away with a message. */
const val MaxFileBytes = 25L * 1024 * 1024

/**
 * Picks files to attach: a file dialog on desktop, the photo picker on iPhone (handed in from
 * Swift). It calls [onPicked] once, with nothing if the owner cancels.
 */
fun interface FilePicker {
    fun pick(onPicked: (List<PendingFile>) -> Unit)
}

val LocalFilePicker = staticCompositionLocalOf<FilePicker?> { null }

/** Takes dropped files: on desktop, from the file manager. Elsewhere it does nothing. */
expect fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onFiles: (List<PendingFile>) -> Unit): Modifier

/** Files or a picture on the clipboard, for paste; null when it only holds text. */
expect fun clipboardFiles(): List<PendingFile>?

/** The type of a file from its name, for what the clipboard and drops don't say. */
fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "heic" -> "image/heic"
    "pdf" -> "application/pdf"
    "txt", "log", "md" -> "text/plain"
    "json" -> "application/json"
    "mp4" -> "video/mp4"
    "mov" -> "video/quicktime"
    else -> "application/octet-stream"
}

/** The files waiting in the composer: a thumbnail for pictures, a chip for the rest, each with a remove button. */
@Composable
fun AttachmentStrip(files: List<PendingFile>, onRemove: (PendingFile) -> Unit, modifier: Modifier = Modifier) {
    if (files.isEmpty()) return
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        files.forEach { file ->
            Box(Modifier.semantics(mergeDescendants = false) { contentDescription = "Attached: ${file.name}" }) {
                if (file.isImage) {
                    AsyncImage(
                        model = file.bytes,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(shape).border(1.dp, colors.border, shape),
                    )
                } else {
                    Column(
                        Modifier.width(120.dp).clip(shape).background(colors.surfaceSunken).border(1.dp, colors.border, shape).padding(10.dp),
                    ) {
                        Icon(Icons.Paperclip, null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
                        MetaText(file.name, Modifier.padding(top = 4.dp).clearAndSetSemantics { }, color = colors.text)
                    }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(2.dp).clip(CircleShape).pressable("Remove ${file.name}", CircleShape) { onRemove(file) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Close, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
    }
}

internal fun tooBig(file: PendingFile) = file.bytes.size > MaxFileBytes

