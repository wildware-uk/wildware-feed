package dev.wildware.agents.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.wildware.agents.Attachable
import dev.wildware.agents.app.feed.FilePicker
import dev.wildware.agents.app.feed.PendingFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The composer's paperclip: Android's own photo picker, which needs no permission and shows only
 * what the owner picks. Each item is read off the main thread.
 */
class AndroidPhotoPicker(
    hooks: ActivityHooks,
    resolver: ContentResolver,
    scope: CoroutineScope,
) : FilePicker by UriPicker({ hooks.pickMedia }, resolver, scope)

/**
 * The paperclip's "Files": Android's documents picker, for any file from any app or drive. Like
 * the photo picker it needs no permission; the owner's pick is the permission.
 */
class AndroidDocumentPicker(
    hooks: ActivityHooks,
    resolver: ContentResolver,
    scope: CoroutineScope,
) : FilePicker by UriPicker({ hooks.pickDocuments }, resolver, scope)

/** Opens what [opener] gives (while an activity is on screen) and reads each picked item. */
private class UriPicker(
    private val opener: () -> ((onPicked: (List<Uri>) -> Unit) -> Unit)?,
    private val resolver: ContentResolver,
    private val scope: CoroutineScope,
) : FilePicker {
    override fun pick(onPicked: (List<PendingFile>) -> Unit) {
        val open = opener() ?: return onPicked(emptyList())
        open { uris ->
            scope.launch {
                val files = withContext(Dispatchers.IO) { uris.mapNotNull { readPending(resolver, it) } }
                withContext(Dispatchers.Main) { onPicked(files) }
            }
        }
    }
}

/**
 * Reads a shared or picked item into a [PendingFile]. It stops one byte past the composer's
 * limit for its type, so a huge video is turned away with the composer's own "over 25 MB" note
 * rather than filling the memory.
 */
fun readPending(resolver: ContentResolver, uri: Uri): PendingFile? = runCatching {
    val shown = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment
    // Another app's name and type can be anything: make them ones the dashboard takes.
    val name = Attachable.fileName(shown, fallback = "shared")
    val type = Attachable.mimeType(resolver.getType(uri), name)
    val bytes = resolver.openInputStream(uri)?.use { readCapped(it, Attachable.maxBytes(type) + 1) } ?: return null
    PendingFile(name, type, bytes)
}.getOrNull()

/** At most [limit] bytes from [input]. */
internal fun readCapped(input: java.io.InputStream, limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (total < limit) {
        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
        if (n < 0) break
        out.write(buffer, 0, n)
        total += n
    }
    return out.toByteArray()
}
