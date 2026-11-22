package dev.wildware.agents.app.feed

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
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
 * Files dragged from the computer onto the box. The page is one canvas, so one set of page-wide
 * listeners ([WebDrops]) takes every drop and hands it to the box under the pointer. A paste of files
 * or a picture lands in the box with the focus, since a browser hands pasted files to the page.
 */
actual fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onFiles: (List<PendingFile>) -> Unit): Modifier = composed {
    val zone = remember { WebDrops.Zone() }
    SideEffect {
        zone.hover = onHover
        zone.take = onFiles
    }
    DisposableEffect(zone) {
        WebDrops.add(zone)
        onDispose { WebDrops.remove(zone) }
    }
    onGloballyPositioned { zone.bounds = it.boundsInWindow() }.onFocusChanged { zone.focused = it.hasFocus }
}

/** Every box on the page that takes files, and the page's own drag, drop and paste listeners. */
private object WebDrops {
    class Zone {
        var bounds = Rect.Zero
        var focused = false
        var hover: (Boolean) -> Unit = {}
        var take: (List<PendingFile>) -> Unit = {}
        var over = false
            set(now) {
                if (now != field) {
                    field = now
                    hover(now)
                }
            }
    }

    private val zones = mutableListOf<Zone>()

    /** The box under the pointer; the one drawn last when two overlap (a panel over the feed). */
    private fun under(e: DragEvent): Zone? {
        val ratio = window.devicePixelRatio.toFloat()
        val at = Offset(e.clientX.toFloat() * ratio, e.clientY.toFloat() * ratio)
        return zones.lastOrNull { it.bounds.contains(at) }
    }

    private fun light(target: Zone?) = zones.toList().forEach { it.over = it === target }

    private val dragOver: (Event) -> Unit = { e ->
        val drag = e as DragEvent
        if (drag.carriesFiles()) {
            // Without this the browser opens the file in place of the app.
            e.preventDefault()
            light(under(drag))
        }
    }
    private val dragLeave: (Event) -> Unit = { e -> if ((e as DragEvent).relatedTarget == null) light(null) }
    private val drop: (Event) -> Unit = { e ->
        val drag = e as DragEvent
        if (drag.carriesFiles()) {
            e.preventDefault()
            val target = under(drag)
            light(null)
            val list = drag.dataTransfer?.files
            if (target != null && list != null) MainScope().launch { target.take(readAll(list)) }
        }
    }
    private val paste: (Event) -> Unit = { e ->
        val list = (e as ClipboardEvent).clipboardData?.files
        val target = zones.lastOrNull { it.focused }
        if (target != null && list != null && list.length > 0) {
            e.preventDefault()
            MainScope().launch { target.take(readAll(list)) }
        }
    }

    fun add(zone: Zone) {
        if (zones.isEmpty()) {
            document.addEventListener("dragover", dragOver)
            document.addEventListener("dragleave", dragLeave)
            document.addEventListener("drop", drop)
            document.addEventListener("paste", paste)
        }
        zones += zone
    }

    fun remove(zone: Zone) {
        zones -= zone
        if (zones.isEmpty()) {
            document.removeEventListener("dragover", dragOver)
            document.removeEventListener("dragleave", dragLeave)
            document.removeEventListener("drop", drop)
            document.removeEventListener("paste", paste)
        }
    }
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
