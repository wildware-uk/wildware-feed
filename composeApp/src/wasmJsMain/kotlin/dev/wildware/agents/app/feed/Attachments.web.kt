package dev.wildware.agents.app.feed

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.DragEvent
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.clipboard.ClipboardEvent
import org.w3c.dom.events.Event
import org.w3c.files.FileList
import org.w3c.files.get

/**
 * Files dragged from the computer onto the box. The page is one canvas, so the box listens on the
 * whole page and takes only a drop that lands inside it. A paste of files or a picture while the box
 * has focus lands here too, since a browser hands pasted files to the page's paste event.
 */
actual fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onFiles: (List<PendingFile>) -> Unit): Modifier = composed {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var focused by remember { mutableStateOf(false) }
    val hover by rememberUpdatedState(onHover)
    val take by rememberUpdatedState(onFiles)
    DisposableEffect(Unit) {
        var over = false
        fun inside(e: DragEvent): Boolean {
            val ratio = window.devicePixelRatio.toFloat()
            return bounds.contains(androidx.compose.ui.geometry.Offset(e.clientX.toFloat() * ratio, e.clientY.toFloat() * ratio))
        }
        fun set(now: Boolean) {
            if (now != over) {
                over = now
                hover(now)
            }
        }
        val dragOver: (Event) -> Unit = { e ->
            val drag = e as DragEvent
            if (drag.carriesFiles()) {
                // Without this the browser opens the file in place of the app.
                e.preventDefault()
                set(inside(drag))
            }
        }
        val dragLeave: (Event) -> Unit = { e -> if ((e as DragEvent).relatedTarget == null) set(false) }
        val drop: (Event) -> Unit = { e ->
            val drag = e as DragEvent
            if (drag.carriesFiles()) {
                e.preventDefault()
                val hit = inside(drag)
                set(false)
                val list = drag.dataTransfer?.files
                if (hit && list != null) MainScope().launch { take(readAll(list)) }
            }
        }
        val paste: (Event) -> Unit = { e ->
            val list = (e as ClipboardEvent).clipboardData?.files
            if (focused && list != null && list.length > 0) {
                e.preventDefault()
                MainScope().launch { take(readAll(list)) }
            }
        }
        document.addEventListener("dragover", dragOver)
        document.addEventListener("dragleave", dragLeave)
        document.addEventListener("drop", drop)
        document.addEventListener("paste", paste)
        onDispose {
            document.removeEventListener("dragover", dragOver)
            document.removeEventListener("dragleave", dragLeave)
            document.removeEventListener("drop", drop)
            document.removeEventListener("paste", paste)
        }
    }
    onGloballyPositioned { bounds = it.boundsInWindow() }.onFocusChanged { focused = it.hasFocus }
}

private fun DragEvent.carriesFiles(): Boolean {
    val types = dataTransfer?.types ?: return false
    return (0 until types.length).any { types[it]?.toString() == "Files" }
}

/** A browser hands pasted files to the paste event instead ([fileDropTarget] takes them there). */
actual fun clipboardFiles(): List<PendingFile>? = null

/** The browser's own file dialog, through a hidden `<input type="file">`. */
class BrowserFilePicker : FilePicker {
    override fun pick(onPicked: (List<PendingFile>) -> Unit) {
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.multiple = true
        var done = false
        input.addEventListener("change") {
            if (!done) {
                done = true
                val list = input.files
                MainScope().launch { onPicked(if (list == null) emptyList() else readAll(list)) }
            }
        }
        input.addEventListener("cancel") {
            if (!done) {
                done = true
                onPicked(emptyList())
            }
        }
        input.click()
    }
}

/** Reads every file in [list] into memory, skipping any the browser can't read. */
internal suspend fun readAll(list: FileList): List<PendingFile> = (0 until list.length).mapNotNull { i ->
    val file = list[i] ?: return@mapNotNull null
    runCatching {
        val bytes = file.readBytes()
        PendingFile(file.name, file.type.ifEmpty { mimeTypeOf(file.name) }, bytes)
    }.getOrNull()
}
