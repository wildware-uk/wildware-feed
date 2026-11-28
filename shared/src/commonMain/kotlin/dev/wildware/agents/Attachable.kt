package dev.wildware.agents

/**
 * What a file needs to go up with a message, for every app: a name and a type the dashboard takes,
 * and the size it takes. The phones' pickers and share sheets (Swift and Android) run what they are
 * handed through here, so any file from any app goes up as itself.
 */
object Attachable {
    /** The biggest picture the dashboard takes (PNG, JPEG, WebP or GIF). */
    const val MaxImageBytes: Long = 10L * 1024 * 1024

    /** The biggest file of any other kind the apps send. The dashboard takes more; a phone reads it all into memory. */
    const val MaxFileBytes: Long = 25L * 1024 * 1024

    /** The longest name the dashboard keeps. */
    const val MaxNameLength = 255

    /** A type as the dashboard checks it: `type/subtype`, lower case, each part at most 63 characters. */
    private val Type = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]{0,62}/[a-z0-9][a-z0-9!#$&^_.+-]{0,62}$")

    /** The most a file of type [mimeType] may weigh. */
    fun maxBytes(mimeType: String): Long = if (mediaKindOf(mimeType) == "image") MaxImageBytes else MaxFileBytes

    /** Why a file of [bytes] bytes can't go, in words for the owner, or null when it can. */
    fun refusal(name: String, mimeType: String, bytes: Long): String? {
        val most = maxBytes(mimeType)
        return when {
            bytes <= 0 -> "$name is empty, so it was left out."
            bytes > most -> "$name is over ${most / (1024 * 1024)} MB, so it was left out."
            else -> null
        }
    }

    /**
     * [raw] as a name the dashboard keeps: no folders, no control or invisible characters (the
     * dashboard refuses a name with any), at most [MaxNameLength] characters with its extension
     * kept. Blank, it is [fallback].
     */
    fun fileName(raw: String?, fallback: String = "file"): String {
        val base = raw.orEmpty().replace('\\', '/').substringAfterLast('/')
        val clean = buildString {
            var i = 0
            while (i < base.length) {
                val c = base[i]
                val pair = c.isHighSurrogate() && i + 1 < base.length && base[i + 1].isLowSurrogate()
                val code = if (pair) 0x10000 + ((c.code - 0xD800) shl 10) + (base[i + 1].code - 0xDC00) else c.code
                val width = if (pair) 2 else 1
                if (!isHidden(code)) appendRange(base, i, i + width)
                i += width
            }
        }.trim()
        val name = clean.ifEmpty { fallback }
        if (name.length <= MaxNameLength) return name
        val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length < 16 }
        return if (ext == null) name.take(MaxNameLength) else name.take(MaxNameLength - ext.length - 1).trimEnd() + "." + ext
    }

    /**
     * The type to send a file called [name] as. [raw] is what the other app said, when it said
     * anything: used when it is a real type, but a bare "application/octet-stream" (or nothing,
     * or a wildcard) gives way to what the name says.
     */
    fun mimeType(raw: String?, name: String): String {
        val plain = raw?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return if (Type.matches(plain) && plain != Download && '*' !in plain) plain else typeOf(name)
    }

    /** The type of a file from its name, for what the clipboard, drops and other apps don't say. */
    fun typeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"
        "txt", "log", "md" -> "text/plain"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "yaml", "yml" -> "application/yaml"
        "zip" -> "application/zip"
        "gz", "tgz" -> "application/gzip"
        "tar" -> "application/x-tar"
        "7z" -> "application/x-7z-compressed"
        "rtf" -> "application/rtf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg", "oga", "opus" -> "audio/ogg"
        else -> Download
    }

    /** A file the dashboard only offers to download. */
    private const val Download = "application/octet-stream"

    /**
     * Control, zero-width and text-direction characters, which the dashboard refuses in a name
     * (they can make `exe` read as `pdf`): Unicode's control and format characters.
     */
    private fun isHidden(code: Int): Boolean = when {
        code < 0x20 || code in 0x7f..0x9f -> true
        code < 0x10000 -> code.toChar().category == CharCategory.FORMAT
        else -> code == 0x110BD || code == 0x110CD || code in 0x13430..0x1343F || code in 0x1BCA0..0x1BCA3 ||
            code in 0x1D173..0x1D17A || code == 0xE0001 || code in 0xE0020..0xE007F
    }
}
