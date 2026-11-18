package dev.wildware.agents.app.feed

import androidx.compose.ui.Modifier

/** Pictures come in through the photo picker and the share sheet; dropping files on the box comes later. */
actual fun Modifier.fileDropTarget(onHover: (Boolean) -> Unit, onFiles: (List<PendingFile>) -> Unit): Modifier = this

/** Pasting pictures comes later; the text box still pastes text. */
actual fun clipboardFiles(): List<PendingFile>? = null
