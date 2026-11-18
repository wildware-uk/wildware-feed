package dev.wildware.agents.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.wildware.agents.app.feed.FilePicker
import dev.wildware.agents.app.feed.MaxFileBytes
import dev.wildware.agents.app.feed.PendingFile
import dev.wildware.agents.app.feed.mimeTypeOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The composer's paperclip: Android's own photo picker, which needs no permission and shows only
 * what the owner picks. Each item is read off the main thread.
 */
class AndroidPhotoPicker(
    private val hooks: ActivityHooks,
    private val resolver: ContentResolver,
    private val scope: CoroutineScope,
) : FilePicker {
    override fun pick(onPicked: (List<PendingFile>) -> Unit) {
        val open = hooks.pickMedia ?: return onPicked(emptyList())
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
 * limit, so a huge video is turned away with the composer's own "over 25 MB" note rather than
 * filling the memory.
 */
fun readPending(resolver: ContentResolver, uri: Uri): PendingFile? = runCatching {
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "shared"
    val type = resolver.getType(uri) ?: mimeTypeOf(name)
    val bytes = resolver.openInputStream(uri)?.use { readCapped(it, MaxFileBytes + 1) } ?: return null
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
