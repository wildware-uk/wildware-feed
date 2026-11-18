package dev.wildware.agents.app.feed

import androidx.compose.ui.Modifier

/** iPhone has no file drop onto an app's box; the photo picker is the way in. */
actual fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onFiles: (List<PendingFile>) -> Unit): Modifier = this

/** Pasting pictures on iPhone comes later, with the system paste button. */
actual fun clipboardFiles(): List<PendingFile>? = null
