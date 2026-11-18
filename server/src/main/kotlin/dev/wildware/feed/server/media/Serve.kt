package dev.wildware.feed.server.media

import dev.wildware.feed.server.db.Database

/** Which file answers `/media/<id>/<variant>` (spec/07 §7, TS `src/media/serve.ts`). */
class Serve(private val db: Database, private val paths: MediaPaths) {
    class MediaFile(val key: String, val size: Long, val mime: String, val etag: String, val disposition: String)

    /** The file, or null for every kind of miss (all of them answer the same 404). */
    fun open(id: String, variant: String): MediaFile? {
        if (variant !in VARIANTS || !ULID.matches(id)) return null
        class Row(val kind: String, val mime: String, val sha256: String, val filename: String?, val status: String)
        val row = db.one("SELECT kind, mime, sha256, filename, status FROM media WHERE id = ?", id) {
            Row(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5))
        } ?: return null
        if (row.status == "failed") return null
        val download = row.kind == "file"
        if (!download && !Mime.isAllowed(row.mime)) return null
        if (download && variant != "original") return null
        val (key, mime) = if (variant == "original") {
            if (row.sha256.isEmpty()) return null
            paths.originalKey(id, row.mime) to if (download) Mime.DOWNLOAD else row.mime
        } else {
            val (kind, width) = when (variant) {
                "thumb-640" -> "thumb" to 640L
                "thumb-1600" -> "thumb" to 1600L
                "poster" -> "poster" to null
                else -> "mp4" to null
            }
            val stored = db.one(
                "SELECT path FROM derivatives WHERE media_id = ? AND kind = ? AND (? IS NULL OR width = ?) ORDER BY width LIMIT 1",
                id, kind, width, width,
            ) { it.getString(1) } ?: return null
            if (paths.stored(stored) == null) return null
            stored to when (variant) {
                "poster" -> "image/jpeg"
                "video" -> "video/mp4"
                else -> "image/webp"
            }
        }
        val size = paths.store.size(key) ?: return null
        return MediaFile(key, size, mime, "\"${row.sha256}-$variant\"", disposition(download, row.filename))
    }

    companion object {
        val VARIANTS = listOf("original", "thumb-640", "thumb-1600", "poster", "video")
        private val ULID = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

        /** `inline`, or `attachment` with an ASCII fallback and an RFC 5987 name (JS `encodeURIComponent`). */
        fun disposition(download: Boolean, filename: String?): String {
            if (!download) return "inline"
            if (filename == null) return "attachment"
            val ascii = buildString {
                for (c in filename) append(if (c.code in 0x20..0x7e && c != '"' && c != '\\') c else '_')
            }
            return "attachment; filename=\"$ascii\"; filename*=UTF-8''${encodeURIComponent(filename)}"
        }

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

        fun encodeURIComponent(s: String): String = buildString {
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xff).toChar()
                if (b >= 0 && c in UNRESERVED) append(c) else append("%%%02X".format(b.toInt() and 0xff))
            }
        }

        /** A byte range: whole file, a span, or unsatisfiable (spec/07 §7, TS `src/http/media/range.ts`). */
        sealed interface Range {
            data object Whole : Range
            data class Span(val start: Long, val end: Long) : Range
            data object Unsatisfiable : Range
        }

        private val rangePattern = Regex("^bytes=(\\d*)-(\\d*)$")

        fun range(header: String?, size: Long): Range {
            if (header == null) return Range.Whole
            val m = rangePattern.matchEntire(header.trim()) ?: return Range.Whole
            val (startText, endText) = m.destructured
            if (startText.isEmpty() && endText.isEmpty()) return Range.Whole
            if (size == 0L) return Range.Unsatisfiable
            if (startText.isEmpty()) {
                val n = endText.toBigInteger()
                if (n.signum() == 0) return Range.Unsatisfiable
                val start = (size.toBigInteger() - n).max(java.math.BigInteger.ZERO).toLong()
                return Range.Span(start, size - 1)
            }
            val start = startText.toBigInteger()
            if (start >= size.toBigInteger()) return Range.Unsatisfiable
            val end = if (endText.isEmpty()) size - 1 else endText.toBigInteger().min((size - 1).toBigInteger()).toLong()
            if (end < start.toLong()) return Range.Unsatisfiable
            return Range.Span(start.toLong(), end)
        }

        /** `If-None-Match` matches: `*`, or any candidate (W/ stripped) equal to the etag. */
        fun notModified(header: String?, etag: String): Boolean {
            if (header == null) return false
            val trimmed = header.trim()
            if (trimmed == "*") return true
            return trimmed.split(',').any { it.trim().removePrefix("W/") == etag }
        }
    }
}
