package dev.wildware.feed.server.media

/**
 * The EXIF orientation (1-8) of a JPEG, PNG or WebP, read from its EXIF block; 1 when there is
 * none. This is what the TS server's image library applies before it resizes ("auto-orient").
 */
object Orientation {
    fun of(bytes: ByteArray): Int = runCatching { exif(bytes)?.let(::tiffOrientation) }.getOrNull()?.takeIf { it in 1..8 } ?: 1

    /** Orientations 5-8 turn the picture a quarter, so width and height swap. */
    fun swapsAxes(orientation: Int) = orientation in 5..8

    /**
     * The ffmpeg filters that undo [orientation], or null for none. EXIF 2 mirrors, 3 turns half,
     * 4 flips, 5 transposes, 6 turns clockwise, 7 transverses, 8 turns anticlockwise.
     */
    fun filter(orientation: Int): String? = when (orientation) {
        2 -> "hflip"
        3 -> "hflip,vflip"
        4 -> "vflip"
        5 -> "transpose=0"
        6 -> "transpose=1"
        7 -> "transpose=3"
        8 -> "transpose=2"
        else -> null
    }

    private fun u16be(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)
    private fun u32be(b: ByteArray, at: Int) = (u16be(b, at).toLong() shl 16) or u16be(b, at + 2).toLong()
    private fun u32le(b: ByteArray, at: Int) =
        (b[at].toLong() and 0xff) or ((b[at + 1].toLong() and 0xff) shl 8) or ((b[at + 2].toLong() and 0xff) shl 16) or ((b[at + 3].toLong() and 0xff) shl 24)

    private fun ascii(b: ByteArray, at: Int, n: Int) = if (at + n <= b.size) String(b, at, n, Charsets.ISO_8859_1) else ""

    /** The TIFF-structured EXIF payload, or null. */
    private fun exif(b: ByteArray): ByteArray? = when {
        b.size > 3 && (b[0].toInt() and 0xff) == 0xFF && (b[1].toInt() and 0xff) == 0xD8 -> jpegExif(b)
        ascii(b, 1, 3) == "PNG" -> pngExif(b)
        ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP" -> webpExif(b)
        else -> null
    }

    private fun jpegExif(b: ByteArray): ByteArray? {
        var at = 2
        while (at + 4 <= b.size) {
            if ((b[at].toInt() and 0xff) != 0xFF) return null
            val marker = b[at + 1].toInt() and 0xff
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { at += 2; continue }
            if (marker == 0xDA || marker == 0xD9) return null
            val length = u16be(b, at + 2)
            if (marker == 0xE1 && ascii(b, at + 4, 6) == "Exif\u0000\u0000") {
                val start = at + 10
                val end = minOf(b.size, at + 2 + length)
                return if (end > start) b.copyOfRange(start, end) else null
            }
            at += 2 + length
        }
        return null
    }

    private fun pngExif(b: ByteArray): ByteArray? {
        var at = 8
        while (at + 8 <= b.size) {
            val length = u32be(b, at).toInt()
            val type = ascii(b, at + 4, 4)
            if (type == "eXIf") return b.copyOfRange(at + 8, minOf(b.size, at + 8 + length))
            if (type == "IDAT" || type == "IEND" || length < 0) return null
            at += 12 + length
        }
        return null
    }

    private fun webpExif(b: ByteArray): ByteArray? {
        var at = 12
        while (at + 8 <= b.size) {
            val type = ascii(b, at, 4)
            val length = u32le(b, at + 4).toInt()
            if (type == "EXIF") {
                var payload = b.copyOfRange(at + 8, minOf(b.size, at + 8 + length))
                if (ascii(payload, 0, 6) == "Exif\u0000\u0000") payload = payload.copyOfRange(6, payload.size)
                return payload
            }
            if (length < 0) return null
            at += 8 + length + (length and 1)
        }
        return null
    }

    private fun tiffOrientation(t: ByteArray): Int? {
        val little = when (ascii(t, 0, 2)) { "II" -> true; "MM" -> false; else -> return null }
        fun u16(at: Int) = if (little) (t[at].toInt() and 0xff) or ((t[at + 1].toInt() and 0xff) shl 8) else u16be(t, at)
        fun u32(at: Int) = if (little) u32le(t, at) else u32be(t, at)
        val ifd = u32(4).toInt()
        val count = u16(ifd)
        for (i in 0 until count) {
            val entry = ifd + 2 + i * 12
            if (u16(entry) == 0x0112) return u16(entry + 8)
        }
        return null
    }
}
