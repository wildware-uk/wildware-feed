package dev.wildware.agents.app.feed

import android.app.Activity
import android.content.ClipDescription
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Files or pictures dragged in from another app, side by side on a tablet or a phone in split
 * screen. Plain text dragged in is left to the text box.
 */
actual fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onFiles: (List<PendingFile>) -> Unit): Modifier = composed {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hover by rememberUpdatedState(onHover)
    val take by rememberUpdatedState(onFiles)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) = hover(true)
            override fun onExited(event: DragAndDropEvent) = hover(false)
            override fun onEnded(event: DragAndDropEvent) = hover(false)
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val drag = event.toAndroidDragEvent()
                // Another app's files are readable only once it says so for this drop.
                context.activity()?.requestDragAndDropPermissions(drag)
                val clip = drag.clipData ?: return false
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                if (uris.isEmpty()) return false
                scope.launch {
                    val files = withContext(Dispatchers.IO) { uris.mapNotNull { context.read(it) } }
                    take(files)
                }
                return true
            }
        }
    }
    dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.mimeTypes().any { !it.startsWith("text/") || it == ClipDescription.MIMETYPE_TEXT_URILIST } },
        target = target,
    )
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Reads a dropped file by its address, with its name and type, or null when it can't be read. */
private fun Context.read(uri: Uri): PendingFile? = runCatching {
    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "file"
    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    PendingFile(name, contentResolver.getType(uri) ?: mimeTypeOf(name), bytes)
}.getOrNull()

/** Pasting pictures comes later; the text box still pastes text. */
actual fun clipboardFiles(): List<PendingFile>? = null
