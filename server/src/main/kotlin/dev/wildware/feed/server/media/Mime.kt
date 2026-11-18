package dev.wildware.feed.server.media

import dev.wildware.feed.server.domain.jsTrim

/** Media types, kinds and stored extensions (spec/07-media-uploads-shares.md §2, TS `src/media/mime.ts`). */
object Mime {
    val IMAGE = listOf("image/png", "image/jpeg", "image/webp", "image/gif")
    val VIDEO = listOf("video/mp4", "video/webm", "video/quicktime")
    val AUDIO = listOf("audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav")
    val ALLOWED = IMAGE + VIDEO + AUDIO
    const val DOWNLOAD = "application/octet-stream"

    /** How many leading bytes the sniffer sees. */
    const val SNIFF_BYTES = 4100

    private val pattern = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]{0,62}/[a-z0-9][a-z0-9!#$&^_.+-]{0,62}$")

    private val extensions = mapOf(
        "image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp", "image/gif" to "gif",
        "video/mp4" to "mp4", "video/webm" to "webm", "video/quicktime" to "mov",
        "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/ogg" to "ogg", "audio/wav" to "wav",
    )

    private val aliases = mapOf(
        "audio/x-m4a" to "audio/mp4", "audio/m4a" to "audio/mp4", "audio/x-wav" to "audio/wav",
        "audio/wave" to "audio/wav", "audio/vorbis" to "audio/ogg", "audio/opus" to "audio/ogg",
    )

    /** `raw.split(';')[0].trim().toLowerCase()`, then the aliases. */
    fun canonical(raw: String): String {
        val plain = raw.substringBefore(';').jsTrim().lowercase()
        return aliases[plain] ?: plain
    }

    fun isAllowed(mime: String) = mime in ALLOWED

    private fun isFile(mime: String) = !isAllowed(mime) && pattern.matches(mime)

    fun kindOf(mime: String): String? = when {
        mime in IMAGE -> "image"
        mime in VIDEO -> "video"
        mime in AUDIO -> "audio"
        isFile(mime) -> "file"
        else -> null
    }

    fun extensionOf(mime: String): String? = extensions[mime] ?: if (isFile(mime)) "bin" else null

    /** What the bytes are, canonicalised, or null. */
    fun sniff(head: ByteArray): String? {
        if (head.isEmpty()) return null
        return Sniff.mime(head)?.let(::canonical)
    }
}
