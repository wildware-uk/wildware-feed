package dev.wildware.feed.server.media

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * The real type of an upload, read from its first bytes.
 *
 * A port of npm `file-type` 22.0.2 `fileTypeFromBuffer(head)`: [mime] gives the
 * `.mime` that call resolves to, or null where it resolves to `undefined`. The
 * server only ever passes the first [HEAD_BYTES] bytes of an upload (fewer at end
 * of file), so this only has to agree on heads of that size or smaller.
 *
 * The code follows file-type's own structure on purpose, check for check and in
 * the same order, because the order is part of the answer: an earlier check can
 * claim bytes a later one would have named differently. It also copies the
 * tokenizer underneath it (a read position over the head, a 4100-byte scratch
 * buffer that peeks only partly refill), since some answers depend on where an
 * earlier, failed check left that position. Comments point at the file-type
 * source (`source/index.js` unless named) so the two can be read side by side.
 *
 * Left out, because nothing reaches them on a head handed over in one piece:
 * the unknown-size stream limits, and the gzip probe (whatever is inside, a
 * gzip head is `application/gzip`). Where file-type would throw rather than
 * answer, this returns null.
 */
object Sniff {
    /** How much of an upload the server sniffs (`reasonableDetectionSizeInBytes`). */
    const val HEAD_BYTES = 4100

    fun mime(head: ByteArray): String? {
        // `fromBuffer`: a buffer of one byte or less has no type.
        if (head.size <= 1) return null
        return try {
            Detection(Tokenizer(head)).parseTokenizer(0)
        } catch (_: Unrecoverable) {
            null
        }
    }
}

// --- the tokenizer (strtok3 10.3.5 BufferTokenizer) -----------------------------------

/** strtok3 `EndOfStreamError`: a read wanted more bytes than are left. */
private class EndOfStream : RuntimeException(null, null, false, false)

/** file-type `ParserHardLimitError`: a length field larger than the whole input. */
private class HardLimit : RuntimeException(null, null, false, false)

/** Any other error; file-type would reject with it. */
private class Unrecoverable : RuntimeException(null, null, false, false)

/** One of the zip detector's errors that it recovers from (`isRecoverableZipError`). */
private class ZipError : RuntimeException(null, null, false, false)

private class Tokenizer(private val data: ByteArray) {
    val size: Int = data.size
    var position: Int = 0

    /** `peekBuffer`: copy up to [length] bytes from the position into the start of [into]. */
    fun peek(into: ByteArray, length: Int = into.size, mayBeLess: Boolean = false): Int {
        val count = minOf(size - position, length)
        if (!mayBeLess && count < length) throw EndOfStream()
        System.arraycopy(data, position, into, 0, count)
        return count
    }

    /** `readBuffer`: [peek], then move past what was copied. */
    fun read(into: ByteArray, length: Int = into.size, mayBeLess: Boolean = false): Int {
        val count = peek(into, length, mayBeLess)
        position += count
        return count
    }

    fun readBytes(length: Int): ByteArray = ByteArray(length).also { read(it) }

    /** `ignore`: skip ahead, stopping at the end rather than failing. */
    fun ignore(length: Long) {
        position += minOf(length, (size - position).toLong()).toInt()
    }

    fun peekU8(): Int {
        val one = ByteArray(1)
        peek(one)
        return one[0].toInt() and 0xFF
    }

    fun readU16(bigEndian: Boolean): Int = u16(readBytes(2), 0, bigEndian)

    fun readI32BE(): Int = u32(readBytes(4), 0, true).toInt()
}

/** file-type `safeIgnore`: refuse a skip longer than [maximum] outright. */
private fun Tokenizer.safeIgnore(length: Long, maximum: Long = size.toLong()) {
    if (length < 0 || length > maximum) throw HardLimit()
    ignore(length)
}

private fun u16(bytes: ByteArray, offset: Int, bigEndian: Boolean): Int {
    val a = bytes[offset].toInt() and 0xFF
    val b = bytes[offset + 1].toInt() and 0xFF
    return if (bigEndian) (a shl 8) or b else (b shl 8) or a
}

private fun u32(bytes: ByteArray, offset: Int, bigEndian: Boolean): Long {
    var value = 0L
    for (i in 0 until 4) {
        val index = if (bigEndian) offset + i else offset + 3 - i
        value = (value shl 8) or (bytes[index].toLong() and 0xFF)
    }
    return value
}

private fun latin1(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String =
    String(CharArray(length) { (bytes[offset + it].toInt() and 0xFF).toChar() })

/** token-types `StringType(n, 'ascii')`: each byte with its top bit dropped. */
private fun ascii(bytes: ByteArray): String = String(CharArray(bytes.size) { (bytes[it].toInt() and 0x7F).toChar() })

// --- the detector (FileTypeParser) ------------------------------------------------------

private const val MAXIMUM_DETECTION_REENTRY_COUNT = 256
private const val MAXIMUM_TIFF_TAG_COUNT = 512

private class Detection(private val tokenizer: Tokenizer) {
    private var buffer = ByteArray(Sniff.HEAD_BYTES)
    private var detectionReentryCount = 0

    /** `parseTokenizer`: the confident checks, then (if they left the position alone) the imprecise ones. */
    fun parseTokenizer(reentryCount: Int): String? {
        detectionReentryCount = reentryCount
        val initialPosition = tokenizer.position
        return try {
            detectConfident()
                ?: if (initialPosition != tokenizer.position) null else detectImprecise()
        } catch (_: EndOfStream) {
            null
        } catch (_: HardLimit) {
            null
        }
    }

    private fun check(header: IntArray, offset: Int = 0, mask: IntArray? = null): Boolean {
        for (i in header.indices) {
            val index = i + offset
            val byte = if (index < buffer.size) buffer[index].toInt() and 0xFF else null
            if (mask != null) {
                if (header[i] != (mask[i] and (byte ?: 0))) return false
            } else if (header[i] != byte) {
                return false
            }
        }
        return true
    }

    private fun check(vararg header: Int): Boolean = check(header)

    private fun checkAt(offset: Int, vararg header: Int): Boolean = check(header, offset)

    private fun checkString(text: String, offset: Int = 0, encoding: String? = null): Boolean {
        val bytes = when (encoding) {
            "utf-16le" -> IntArray(text.length * 2) { (text[it / 2].code shr (8 * (it % 2))) and 0xFF }
            "utf-16be" -> IntArray(text.length * 2) { (text[it / 2].code shr (8 * (1 - it % 2))) and 0xFF }
            else -> IntArray(text.length) { text[it].code }
        }
        return check(bytes, offset)
    }

    private fun b(index: Int): Int = buffer[index].toInt() and 0xFF

    // `detectConfident`: detections with a high degree of certainty.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun detectConfident(): String? {
        buffer = ByteArray(Sniff.HEAD_BYTES)
        tokenizer.peek(buffer, 32, mayBeLess = true)

        // -- 2-byte signatures --
        if (check(0x42, 0x4D)) return "image/bmp"
        if (check(0x0B, 0x77)) return "audio/vnd.dolby.dd-raw"
        if (check(0x78, 0x01)) return "application/x-apple-diskimage"
        if (check(0x4D, 0x5A)) return "application/x-msdownload"
        if (check(0x25, 0x21)) {
            tokenizer.peek(buffer, 24, mayBeLess = true)
            if (checkString("PS-Adobe-", 2) && checkString(" EPSF-", 14)) return "application/eps"
            return "application/postscript"
        }
        if (check(0x1F, 0xA0) || check(0x1F, 0x9D)) return "application/x-compress"
        if (check(0xC7, 0x71)) return "application/x-cpio"
        if (check(0x60, 0xEA)) return "application/x-arj"

        // -- 3-byte signatures --
        if (check(0xEF, 0xBB, 0xBF)) { // UTF-8 BOM: strip it and start over
            if (detectionReentryCount >= MAXIMUM_DETECTION_REENTRY_COUNT) return null
            detectionReentryCount++
            tokenizer.ignore(3)
            return detectConfident()
        }
        if (check(0x47, 0x49, 0x46)) return "image/gif"
        if (check(0x49, 0x49, 0xBC)) return "image/vnd.ms-photo"
        // A gzip head is gzip whatever it holds (`detectGzip` only picks the extension).
        if (check(0x1F, 0x8B, 0x8)) return "application/gzip"
        if (check(0x42, 0x5A, 0x68)) return "application/x-bzip2"
        if (checkString("ID3")) return detectId3()
        if (checkString("MP+")) return "audio/x-musepack"
        if ((b(0) == 0x43 || b(0) == 0x46) && checkAt(1, 0x57, 0x53)) return "application/x-shockwave-flash"

        // -- 4-byte signatures --
        if (check(0xFF, 0xD8, 0xFF)) {
            if (checkAt(3, 0xF7)) return "image/jls"
            return "image/jpeg"
        }
        if (check(0x4F, 0x62, 0x6A, 0x01)) return "application/avro"
        if (checkString("FLIF")) return "image/flif"
        if (checkString("8BPS")) return "image/vnd.adobe.photoshop"
        if (checkString("MPCK")) return "audio/x-musepack"
        if (checkString("FORM")) return "audio/aiff"
        if (checkString("icns")) return "image/icns"
        if (check(0x50, 0x4B, 0x3, 0x4)) return ZipDetection(tokenizer).detect()
        if (checkString("OggS")) return detectOgg()
        if (check(0x50, 0x4B) && (b(2) == 0x3 || b(2) == 0x5 || b(2) == 0x7) && (b(3) == 0x4 || b(3) == 0x6 || b(3) == 0x8)) {
            return "application/zip"
        }
        if (checkString("MThd")) return "audio/midi"
        if (checkString("wOFF") && (checkAt(4, 0x00, 0x01, 0x00, 0x00) || checkString("OTTO", 4))) return "font/woff"
        if (checkString("wOF2") && (checkAt(4, 0x00, 0x01, 0x00, 0x00) || checkString("OTTO", 4))) return "font/woff2"
        if (check(0xD4, 0xC3, 0xB2, 0xA1) || check(0xA1, 0xB2, 0xC3, 0xD4)) return "application/vnd.tcpdump.pcap"
        if (checkString("DSD ")) return "audio/x-dsf"
        if (checkString("LZIP")) return "application/lzip"
        if (checkString("fLaC")) return "audio/flac"
        if (check(0x42, 0x50, 0x47, 0xFB)) return "image/bpg"
        if (checkString("wvpk")) return "audio/wavpack"
        if (checkString("%PDF")) return "application/pdf"
        if (check(0x00, 0x61, 0x73, 0x6D)) return "application/wasm"
        if (check(0x49, 0x49)) readTiffHeader(bigEndian = false)?.let { return it }
        if (check(0x4D, 0x4D)) readTiffHeader(bigEndian = true)?.let { return it }
        if (checkString("MAC ")) return "audio/ape"
        if (check(0x1A, 0x45, 0xDF, 0xA3)) return detectEbml()
        if (checkString("SQLi")) return "application/x-sqlite3"
        if (check(0x4E, 0x45, 0x53, 0x1A)) return "application/x-nintendo-nes-rom"
        if (checkString("Cr24")) return "application/x-google-chrome-extension"
        if (checkString("MSCF") || checkString("ISc(")) return "application/vnd.ms-cab-compressed"
        if (check(0xED, 0xAB, 0xEE, 0xDB)) return "application/x-rpm"
        if (check(0xC5, 0xD0, 0xD3, 0xC6)) return "application/eps"
        if (check(0x28, 0xB5, 0x2F, 0xFD)) return "application/zstd"
        if (check(0x7F, 0x45, 0x4C, 0x46)) return "application/x-elf"
        if (check(0x21, 0x42, 0x44, 0x4E)) return "application/vnd.ms-outlook"
        if (checkString("PAR1") || checkString("PARE")) return "application/vnd.apache.parquet"
        if (checkString("ttcf")) return "font/collection"
        if (check(0xFE, 0xED, 0xFA, 0xCE) || check(0xFE, 0xED, 0xFA, 0xCF) || check(0xCE, 0xFA, 0xED, 0xFE) || check(0xCF, 0xFA, 0xED, 0xFE)) {
            return "application/x-mach-binary"
        }
        if (check(0x04, 0x22, 0x4D, 0x18)) return "application/x-lz4"
        if (checkString("regf")) return "application/x-ft-windows-registry-hive"
        if (checkString("\$FL2") || checkString("\$FL3")) return "application/x-spss-sav"

        // -- 5-byte signatures --
        if (check(0x4F, 0x54, 0x54, 0x4F, 0x00)) return "font/otf"
        if (checkString("#!AMR")) return "audio/amr"
        if (checkString("{\\rtf")) return "application/rtf"
        if (check(0x46, 0x4C, 0x56, 0x01)) return "video/x-flv"
        if (checkString("IMPM")) return "audio/x-it"
        if (LZH_METHODS.any { checkString(it, 2) }) return "application/x-lzh-compressed"
        if (check(0x00, 0x00, 0x01, 0xBA)) { // MPEG program stream
            if (check(intArrayOf(0x21), 4, intArrayOf(0xF1))) return "video/MP1S"
            if (check(intArrayOf(0x44), 4, intArrayOf(0xC4))) return "video/MP2P"
        }
        if (checkString("ITSF")) return "application/vnd.ms-htmlhelp"
        if (check(0xCA, 0xFE, 0xBA, 0xBE)) { // Mach-O universal binary or Java class
            val machOArchitectureCount = u32(buffer, 4, true)
            val javaClassFileMajorVersion = u16(buffer, 6, true)
            if (machOArchitectureCount in 1..30) return "application/x-mach-binary"
            if (javaClassFileMajorVersion > 30) return "application/java-vm"
        }
        if (checkString(".RMF")) return "application/vnd.rn-realmedia"
        if (checkString("DRACO")) return "application/x-ft-draco"

        // -- 6-byte signatures --
        if (check(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)) return "application/x-xz"
        if (checkString("<?xml ")) return "application/xml"
        if (check(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) return "application/x-7z-compressed"
        if (check(0x52, 0x61, 0x72, 0x21, 0x1A, 0x7) && (b(6) == 0x0 || b(6) == 0x1)) return "application/x-rar-compressed"
        if (checkString("solid ")) return "model/stl"
        if (checkString("AC")) {
            val version = latin1(buffer, 2, 4)
            if (version.all { it in '0'..'9' } && version.toInt() in 1000..1050) return "image/vnd.dwg"
        }
        if (checkString("070707")) return "application/x-cpio"

        // -- 7-byte signatures --
        if (checkString("BLENDER")) return "application/x-blender"
        if (checkString("!<arch>")) {
            tokenizer.ignore(8)
            if (ascii(tokenizer.readBytes(13)) == "debian-binary") return "application/x-deb"
            return "application/x-unix-archive"
        }
        if (checkString("WEBVTT") && listOf("\n", "\r", "\t", " ", "\u0000").any { checkString(it, 6) }) return "text/vtt"

        // -- 8-byte signatures --
        if (check(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return detectPng()
        if (check(0x41, 0x52, 0x52, 0x4F, 0x57, 0x31, 0x00, 0x00)) return "application/vnd.apache.arrow.file"
        if (check(0x67, 0x6C, 0x54, 0x46, 0x02, 0x00, 0x00, 0x00)) return "model/gltf-binary"
        // `mov` format variants: free, mdat (MJPEG), moov, wide
        if (checkString("free", 4) || checkString("mdat", 4) || checkString("moov", 4) || checkString("wide", 4)) return "video/quicktime"

        // -- 9-byte signatures --
        if (check(0x49, 0x49, 0x52, 0x4F, 0x08, 0x00, 0x00, 0x00, 0x18)) return "image/x-olympus-orf"
        if (checkString("gimp xcf ")) return "image/x-xcf"
        // ISO base media file format: an `ftyp` box with a printable-ish major brand.
        if (checkString("ftyp", 4) && (b(8) and 0x60) != 0x00) return isoBrandMime(latin1(buffer, 8, 4).replaceFirst('\u0000', ' ').jsTrim())

        // -- 10-byte signatures --
        if (checkString("REGEDIT4\r\n")) return "application/x-ms-regedit"

        // -- 12-byte signatures --
        if (check(0x52, 0x49, 0x46, 0x46)) { // RIFF
            if (checkString("WEBP", 8)) return "image/webp"
            if (checkAt(8, 0x41, 0x56, 0x49)) return "video/vnd.avi"
            if (checkAt(8, 0x57, 0x41, 0x56, 0x45)) return "audio/wav"
            if (checkAt(8, 0x51, 0x4C, 0x43, 0x4D)) return "audio/qcelp"
        }
        if (check(0x49, 0x49, 0x55, 0x00, 0x18, 0x00, 0x00, 0x00, 0x88, 0xE7, 0x74, 0xD8)) return "image/x-panasonic-rw2"
        if (check(0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11, 0xA6, 0xD9)) return detectAsf()
        if (check(0xAB, 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31, 0xBB, 0x0D, 0x0A, 0x1A, 0x0A)) return "image/ktx"
        if ((check(0x7E, 0x10, 0x04) || check(0x7E, 0x18, 0x04)) && checkAt(4, 0x30, 0x4D, 0x49, 0x45)) return "application/x-mie"
        if (checkAt(2, 0x27, 0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)) return "application/x-esri-shape"
        if (check(0xFF, 0x4F, 0xFF, 0x51)) return "image/j2c"
        if (check(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87, 0x0A)) { // JPEG 2000 family
            tokenizer.ignore(20)
            return when (ascii(tokenizer.readBytes(4))) {
                "jp2 " -> "image/jp2"
                "jpx " -> "image/jpx"
                "jpm " -> "image/jpm"
                "mjp2" -> "image/mj2"
                else -> null
            }
        }
        if (check(0xFF, 0x0A) || check(0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20, 0x0D, 0x0A, 0x87, 0x0A)) return "image/jxl"
        if (check(0xFE, 0xFF)) { // UTF-16 BOM, big-endian
            if (checkString("<?xml ", 2, "utf-16be")) return "application/xml"
            return null // some unknown text format
        }
        if (check(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) return "application/x-cfb"

        // Increase sample size from 32 to 256.
        tokenizer.peek(buffer, minOf(256, tokenizer.size), mayBeLess = true)

        if (checkAt(36, 0x61, 0x63, 0x73, 0x70)) return "application/vnd.iccprofile"
        if (checkString("**ACE", 7) && checkString("**", 12)) return "application/x-ace-compressed"

        // -- 15-byte signatures --
        if (checkString("BEGIN:")) {
            if (checkString("VCARD", 6)) return "text/vcard"
            if (checkString("VCALENDAR", 6)) return "text/calendar"
        }
        if (checkString("FUJIFILMCCD-RAW")) return "image/x-fujifilm-raf"
        if (checkString("Extended Module:")) return "audio/x-xm"
        if (checkString("Creative Voice File")) return "audio/x-voc"
        if (check(0x04, 0x00, 0x00, 0x00) && isAsarHeader()) return "application/x-asar"
        if (check(0x06, 0x0E, 0x2B, 0x34, 0x02, 0x05, 0x01, 0x01, 0x0D, 0x01, 0x02, 0x01, 0x01, 0x02)) return "application/mxf"
        if (checkString("SCRM", 44)) return "audio/x-s3m"
        // Raw MPEG-2 transport stream (188-byte packets), then Blu-ray BDAV (4-byte prefix per packet).
        if (check(0x47) && checkAt(188, 0x47)) return "video/mp2t"
        if (checkAt(4, 0x47) && checkAt(196, 0x47)) return "video/mp2t"
        if (checkAt(60, 0x42, 0x4F, 0x4F, 0x4B, 0x4D, 0x4F, 0x42, 0x49)) return "application/x-mobipocket-ebook"
        if (checkAt(128, 0x44, 0x49, 0x43, 0x4D)) return "application/dicom"
        if (check(0x4C, 0x00, 0x00, 0x00, 0x01, 0x14, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0xC0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46)) {
            return "application/x-ms-shortcut"
        }
        if (check(0x62, 0x6F, 0x6F, 0x6B, 0x00, 0x00, 0x00, 0x00, 0x6D, 0x61, 0x72, 0x6B, 0x00, 0x00, 0x00, 0x00)) return "application/x-ft-apple.alias"
        if (checkString("Kaydara FBX Binary  \u0000")) return "application/x-ft-fbx"
        if (checkAt(34, 0x4C, 0x50) && (checkAt(8, 0x00, 0x00, 0x01) || checkAt(8, 0x01, 0x00, 0x02) || checkAt(8, 0x02, 0x00, 0x02))) {
            return "application/vnd.ms-fontobject"
        }
        if (check(0x06, 0x06, 0xED, 0xF5, 0xD8, 0x1D, 0x46, 0xE5, 0xBD, 0x31, 0xEF, 0xE7, 0xFE, 0x74, 0xB7, 0x1D)) return "application/x-indesign"

        // -- 16-byte signatures --
        if (check(0xFF, 0xFF, 0x00, 0x00, 0x07, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00) ||
            check(0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x07, 0x00, 0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x01)
        ) {
            return "application/x-jmp-data"
        }

        // Increase sample size from 256 to 512.
        tokenizer.peek(buffer, minOf(512, tokenizer.size), mayBeLess = true)

        if ((checkString("ustar", 257) && (checkString("\u0000", 262) || checkString(" ", 262))) ||
            (checkAt(257, 0, 0, 0, 0, 0, 0) && tarHeaderChecksumMatches())
        ) {
            return "application/x-tar"
        }
        if (check(0xFF, 0xFE)) { // UTF-16 BOM, little-endian
            if (checkString("<?xml ", 2, "utf-16le")) return "application/xml"
            if (checkAt(2, 0xFF, 0x0E) && checkString("SketchUp Model", 4, "utf-16le")) return "application/vnd.sketchup.skp"
            if (checkString("Windows Registry Editor Version 5.00\r\n", 2, "utf-16le")) return "application/x-ms-regedit"
            return null // some text format
        }
        if (checkString("-----BEGIN PGP MESSAGE-----")) return "application/pgp-encrypted"
        return null
    }

    // `detectImprecise`: detections with a higher chance of false positives.
    private fun detectImprecise(): String? {
        buffer = ByteArray(Sniff.HEAD_BYTES)
        tokenizer.peek(buffer, minOf(8, tokenizer.size), mayBeLess = true)

        if (check(0x0, 0x0, 0x1, 0xBA) || check(0x0, 0x0, 0x1, 0xB3)) return "video/mpeg"
        if (check(0x00, 0x01, 0x00, 0x00, 0x00)) return "font/ttf"
        if (check(0x00, 0x00, 0x01, 0x00)) return "image/x-icon"
        if (check(0x00, 0x00, 0x02, 0x00)) return "image/x-icon"

        // The MPEG frame scan; `mpegOffsetTolerance` is 0 by default, so only offset 0.
        tokenizer.peek(buffer, minOf(4, tokenizer.size), mayBeLess = true)
        return scanMpeg(0)
    }

    /** `scanMpeg`: an MPEG 1/2 audio frame header, or ADTS (sync word 0xFFE). */
    private fun scanMpeg(offset: Int): String? {
        if (!check(intArrayOf(0xFF, 0xE0), offset, intArrayOf(0xFF, 0xE0))) return null
        if (check(intArrayOf(0x10), offset + 1, intArrayOf(0x16))) return "audio/aac"
        // MPEG-1 Layer I with CRC is byte-identical to a UTF-16 LE BOM; that variant is dropped.
        if (check(intArrayOf(0xFF, 0xFE), offset)) return null
        if (check(intArrayOf(0x08), offset + 1, intArrayOf(0x18))) return null // reserved version
        if (check(intArrayOf(0xF0), offset + 2, intArrayOf(0xF0))) return null // bad bitrate index
        if (check(intArrayOf(0x0C), offset + 2, intArrayOf(0x0C))) return null // reserved sample rate
        if (check(intArrayOf(0x02), offset + 1, intArrayOf(0x06))) return "audio/mpeg" // layer 3
        if (check(intArrayOf(0x04), offset + 1, intArrayOf(0x06))) return "audio/mpeg" // layer 2
        if (check(intArrayOf(0x06), offset + 1, intArrayOf(0x06))) return "audio/mpeg" // layer 1
        return null
    }

    /**
     * An ID3v2 tag: skip it and detect what follows. A tag that claims to run past
     * the end of the head is taken to be an mp3 without looking further.
     */
    private fun detectId3(): String? {
        tokenizer.safeIgnore(6, maximum = 6)
        val tagSize = tokenizer.readBytes(4).let {
            ((it[3].toInt() and 0x7F) or ((it[2].toInt() and 0x7F) shl 7) or ((it[1].toInt() and 0x7F) shl 14) or ((it[0].toInt() and 0x7F) shl 21)).toLong()
        }
        if (tokenizer.position + tagSize > tokenizer.size) return "audio/mpeg"
        tokenizer.safeIgnore(tagSize)
        if (detectionReentryCount >= MAXIMUM_DETECTION_REENTRY_COUNT) return null
        detectionReentryCount++
        return parseTokenizer(detectionReentryCount)
    }

    private fun detectOgg(): String {
        tokenizer.ignore(28)
        val type = tokenizer.readBytes(8)
        fun starts(vararg prefix: Int) = prefix.indices.all { (type[it].toInt() and 0xFF) == prefix[it] }
        return when {
            starts(0x4F, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64) -> "audio/ogg; codecs=opus" // OpusHead
            starts(0x80, 0x74, 0x68, 0x65, 0x6F, 0x72, 0x61) -> "video/ogg" // \x80theora
            starts(0x01, 0x76, 0x69, 0x64, 0x65, 0x6F, 0x00) -> "video/ogg" // \x01video (OGM)
            starts(0x7F, 0x46, 0x4C, 0x41, 0x43) -> "audio/ogg" // \x7FFLAC
            starts(0x53, 0x70, 0x65, 0x65, 0x78, 0x20, 0x20) -> "audio/ogg" // Speex
            starts(0x01, 0x76, 0x6F, 0x72, 0x62, 0x69, 0x73) -> "audio/ogg" // \x01vorbis
            else -> "application/ogg"
        }
    }

    /** `readTiffHeader`; null lets detection carry on (possibly from a moved position). */
    private fun readTiffHeader(bigEndian: Boolean): String? {
        val version = u16(buffer, 2, bigEndian)
        val ifdOffset = u32(buffer, 4, bigEndian)
        if (version == 42) {
            if (ifdOffset >= 6) {
                if (checkString("CR", 8)) return "image/x-canon-cr2"
                if (ifdOffset >= 8) {
                    val someId1 = u16(buffer, 8, bigEndian)
                    val someId2 = u16(buffer, 10, bigEndian)
                    if ((someId1 == 0x1C && someId2 == 0xFE) || (someId1 == 0x1F && someId2 == 0x0B)) return "image/x-nikon-nef"
                }
            }
            tokenizer.safeIgnore(ifdOffset)
            return try {
                readTiffIfd(bigEndian) ?: "image/tiff"
            } catch (_: EndOfStream) {
                null
            }
        }
        if (version == 43) return "image/tiff" // BigTIFF
        return null
    }

    private fun readTiffIfd(bigEndian: Boolean): String? {
        val numberOfTags = tokenizer.readU16(bigEndian)
        if (numberOfTags > MAXIMUM_TIFF_TAG_COUNT) return null
        repeat(numberOfTags) {
            val tagId = tokenizer.readU16(bigEndian)
            tokenizer.ignore(10)
            when (tagId) {
                50_341 -> return "image/x-sony-arw"
                50_706 -> return "image/x-adobe-dng"
            }
        }
        return null
    }

    /** `tarHeaderChecksumMatches` (tokens.js) over the 512-byte header in the buffer. */
    private fun tarHeaderChecksumMatches(): Boolean {
        val field = decodeUtf8(buffer.copyOfRange(148, 154)).stripNulTail().jsTrim()
        val readSum = parseOctalLikeJs(field) ?: return false
        var sum = 8 * 0x20L
        for (i in 0 until 148) sum += b(i)
        for (i in 156 until 512) sum += b(i)
        return readSum == sum
    }

    /** The Pickle/ASAR check: a JSON object with a truthy `files` after a 16-byte header. */
    private fun isAsarHeader(): Boolean {
        val jsonSize = u32(buffer, 12, false)
        if (jsonSize <= 12 || buffer.size < jsonSize + 16) return false
        val header = decodeUtf8(buffer.copyOfRange(16, jsonSize.toInt() + 16))
        return asarJsonHasFiles(header)
    }

    // detectors/png.js
    private fun detectPng(): String? {
        tokenizer.ignore(8) // the PNG signature
        var chunkCount = 0
        var hasSeenImageHeader = false
        do {
            chunkCount++
            if (chunkCount > 512) break
            val previousPosition = tokenizer.position
            val length = tokenizer.readI32BE()
            val type = latin1(tokenizer.readBytes(4))
            if (length < 0) return null
            if (type == "IHDR") {
                if (length != 13) return null
                hasSeenImageHeader = true
            }
            when (type) {
                "IDAT" -> return "image/png"
                "acTL" -> return "image/apng"
                else -> {
                    if (!hasSeenImageHeader && type != "CgBI") return null
                    try {
                        tokenizer.safeIgnore(length + 4L) // chunk data and CRC
                    } catch (_: HardLimit) {
                        return "image/png"
                    }
                }
            }
            if (tokenizer.position <= previousPosition) break
        } while (tokenizer.position + 8 < tokenizer.size)
        return "image/png"
    }

    // detectors/ebml.js
    private fun detectEbml(): String? {
        val root = readEbmlElement()
        return when (readEbmlChildren(root.second)) {
            "webm" -> "video/webm"
            "matroska" -> "video/matroska"
            else -> null
        }
    }

    private fun readEbmlField(): ByteArray {
        val msb = tokenizer.peekU8()
        var mask = 0x80
        var ic = 0
        while ((msb and mask) == 0 && mask != 0) {
            ic++
            mask = mask shr 1
        }
        return tokenizer.readBytes(ic + 1)
    }

    /** An element's id (null when longer than 6 bytes, as `getUintBE` gives up) and length. */
    private fun readEbmlElement(): Pair<Long?, Long> {
        val idField = readEbmlField()
        val lengthField = readEbmlField()
        lengthField[0] = (lengthField[0].toInt() xor (0x80 shr (lengthField.size - 1))).toByte()
        val lengthBytes = minOf(6, lengthField.size)
        return uintBE(idField, 0, idField.size) to uintBE(lengthField, lengthField.size - lengthBytes, lengthBytes)!!
    }

    private fun uintBE(bytes: ByteArray, offset: Int, length: Int): Long? {
        if (length > 6) return null
        var value = 0L
        for (i in offset until offset + length) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        return value
    }

    private fun readEbmlChildren(childCount: Long): String? {
        var children = childCount
        var elementCount = 0
        while (children > 0) {
            elementCount++
            if (elementCount > 256) return null
            val previousPosition = tokenizer.position
            val (id, length) = readEbmlElement()
            if (id == 0x4282L) { // DocType
                if (length > 64) return null
                return decodeUtf8(tokenizer.readBytes(length.toInt())).stripNulTail()
            }
            tokenizer.safeIgnore(length)
            children--
            if (tokenizer.position <= previousPosition) return null
        }
        return null
    }

    // detectors/asf.js
    private fun detectAsf(): String? {
        try {
            tokenizer.safeIgnore(30, maximum = 30)
            var objectCount = 0
            while (tokenizer.position + 24 < tokenizer.size) {
                objectCount++
                if (objectCount > 512) break
                val previousPosition = tokenizer.position
                val guid = tokenizer.readBytes(16)
                val size = tokenizer.readBytes(8).let { u32(it, 0, false).toULong() or (u32(it, 4, false).toULong() shl 32) }
                if (size < 24u) return null // malformed
                val payload = size - 24u
                if (guid.contentEquals(ASF_STREAM_PROPERTIES)) {
                    val typeId = tokenizer.readBytes(16)
                    if (typeId.contentEquals(ASF_AUDIO_MEDIA)) return "audio/x-ms-asf"
                    if (typeId.contentEquals(ASF_VIDEO_MEDIA)) return "video/x-ms-asf"
                    break
                }
                if (payload > tokenizer.size.toULong()) throw HardLimit()
                tokenizer.ignore(payload.toLong())
                if (tokenizer.position <= previousPosition) return null // malformed
            }
        } catch (_: EndOfStream) {
            // A truncated header is still ASF.
        } catch (_: HardLimit) {
            // So is one whose object runs past the head.
        }
        return "application/vnd.ms-asf"
    }
}

private val LZH_METHODS = listOf("-lh0-", "-lh1-", "-lh2-", "-lh3-", "-lh4-", "-lh5-", "-lh6-", "-lh7-", "-lzs-", "-lz4-", "-lz5-", "-lhd-")

private fun guid(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

private val ASF_STREAM_PROPERTIES = guid(0x91, 0x07, 0xDC, 0xB7, 0xB7, 0xA9, 0xCF, 0x11, 0x8E, 0xE6, 0x00, 0xC0, 0x0C, 0x20, 0x53, 0x65)
private val ASF_AUDIO_MEDIA = guid(0x40, 0x9E, 0x69, 0xF8, 0x4D, 0x5B, 0xCF, 0x11, 0xA8, 0xFD, 0x00, 0x80, 0x5F, 0x5C, 0x44, 0x2B)
private val ASF_VIDEO_MEDIA = guid(0xC0, 0xEF, 0x19, 0xBC, 0x4D, 0x5B, 0xCF, 0x11, 0xA8, 0xFD, 0x00, 0x80, 0x5F, 0x5C, 0x44, 0x2B)

/** The `ftyp` major-brand switch. */
private fun isoBrandMime(brand: String): String = when (brand) {
    "avif", "avis" -> "image/avif"
    "mif1" -> "image/heif"
    "msf1" -> "image/heif-sequence"
    "heic", "heix" -> "image/heic"
    "hevc", "hevx" -> "image/heic-sequence"
    "qt" -> "video/quicktime"
    "M4V", "M4VH", "M4VP" -> "video/x-m4v"
    "M4P" -> "video/mp4"
    "M4B" -> "audio/mp4"
    "M4A" -> "audio/x-m4a"
    "F4V", "F4P" -> "video/mp4"
    "F4A", "F4B" -> "audio/mp4"
    "crx" -> "image/x-canon-cr3"
    else -> when {
        brand.startsWith("3g2") -> "video/3gpp2"
        brand.startsWith("3g") -> "video/3gpp"
        else -> "video/mp4"
    }
}

// --- the zip detector (detectors/zip.js over @tokenizer/inflate 0.4.1 ZipHandler) -----------

private const val MAXIMUM_ZIP_ENTRY_SIZE = 1024 * 1024
private const val ZIP_LOCAL_FILE_HEADER = 0x04034B50L
private const val ZIP_CENTRAL_FILE_HEADER = 0x02014B50L
private const val ZIP_DATA_DESCRIPTOR = 0x08074B50L
private const val ZIP_ENCRYPTED = 0xE011CFD0L

private class ZipHeader(
    val dataDescriptor: Boolean,
    val compressedMethod: Int,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val extraFieldLength: Int,
    val filename: String,
)

/** What the per-entry callback asks for: whether to stop after this entry, and what to do with its bytes. */
private class ZipNext(val stop: Boolean = false, val handler: ((ByteArray) -> Unit)? = null)

private class ZipDetection(private val tokenizer: Tokenizer) {
    private var fileType: String? = null

    // OpenXML state
    private var hasContentTypesEntry = false
    private var hasParsedContentTypesEntry = false
    private var isParsingContentTypes = false
    private var hasUnparseableContentTypes = false
    private var hasWordDirectory = false
    private var hasPresentationDirectory = false
    private var hasSpreadsheetDirectory = false
    private var hasThreeDimensionalModelEntry = false

    // iWork state
    private var hasDocumentEntry = false
    private var hasMasterSlideEntry = false
    private var hasTablesEntry = false

    private var knownSizeDescriptorScannedBytes = 0L

    fun detect(): String {
        try {
            unzip()
        } catch (e: RuntimeException) {
            if (e !is EndOfStream && e !is HardLimit && e !is ZipError) throw e
            if (isParsingContentTypes) {
                isParsingContentTypes = false
                hasUnparseableContentTypes = true
            }
            // Cut off before [Content_Types].xml: guess from the directory names seen so far.
            if (fileType == null && e is EndOfStream && !hasContentTypesEntry) fileType = openXmlFromDirectoryNames()
        }
        return fileType ?: openXmlFromEntries() ?: iWorkFromEntries() ?: "application/zip"
    }

    private fun iWorkFromEntries(): String? = when {
        !hasDocumentEntry -> null
        hasMasterSlideEntry -> "application/vnd.apple.keynote"
        hasTablesEntry -> "application/vnd.apple.numbers"
        else -> "application/vnd.apple.pages"
    }

    private fun openXmlFromDirectoryNames(): String? = when {
        hasWordDirectory -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        hasPresentationDirectory -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        hasSpreadsheetDirectory -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        hasThreeDimensionalModelEntry -> "model/3mf"
        else -> null
    }

    /** Directory names count only when [Content_Types].xml was present but skipped. */
    private fun openXmlFromEntries(): String? {
        if (!hasContentTypesEntry || hasUnparseableContentTypes || isParsingContentTypes || hasParsedContentTypesEntry) return null
        return openXmlFromDirectoryNames()
    }

    private fun onEntry(header: ZipHeader): ZipNext {
        val filename = header.filename
        if (filename.startsWith("word/")) hasWordDirectory = true
        if (filename.startsWith("ppt/")) hasPresentationDirectory = true
        if (filename.startsWith("xl/")) hasSpreadsheetDirectory = true
        if (filename.startsWith("3D/") && filename.endsWith(".model")) hasThreeDimensionalModelEntry = true
        if (filename == "Index/Document.iwa") hasDocumentEntry = true
        if (filename.startsWith("Index/MasterSlide")) hasMasterSlideEntry = true
        if (filename.startsWith("Index/Tables/")) hasTablesEntry = true

        if (hasDocumentEntry && (hasMasterSlideEntry || hasTablesEntry)) {
            fileType = iWorkFromEntries()
            return ZipNext(stop = true)
        }

        val fromEntries = openXmlFromEntries()
        if (filename != "[Content_Types].xml" && fromEntries != null) {
            fileType = fromEntries
            return ZipNext(stop = true)
        }

        return when (filename) {
            "META-INF/mozilla.rsa" -> {
                fileType = "application/x-xpinstall"
                ZipNext(stop = true)
            }
            "META-INF/MANIFEST.MF" -> {
                fileType = "application/java-archive"
                ZipNext(stop = true)
            }
            "mimetype" -> {
                if (!canReadForDetection(header)) {
                    ZipNext()
                } else {
                    ZipNext(stop = true, handler = { fileType = mimeFromMimeType(decodeUtf8(it).jsTrim()) })
                }
            }
            "[Content_Types].xml" -> {
                hasContentTypesEntry = true
                if (!canReadForDetection(header)) {
                    hasUnparseableContentTypes = true
                    ZipNext()
                } else {
                    isParsingContentTypes = true
                    ZipNext(stop = true, handler = {
                        val mimeType = openXmlMimeFromContentTypes(decodeUtf8(it))
                        if (!mimeType.isNullOrEmpty()) fileType = mimeFromMimeType(mimeType)
                        hasParsedContentTypesEntry = true
                        isParsingContentTypes = false
                    })
                }
            }
            else -> {
                if (CLASSES_DEX.containsMatchIn(filename)) {
                    fileType = "application/vnd.android.package-archive"
                    ZipNext(stop = true)
                } else {
                    ZipNext()
                }
            }
        }
    }

    private fun canReadForDetection(header: ZipHeader) =
        header.compressedSize <= MAXIMUM_ZIP_ENTRY_SIZE && header.uncompressedSize <= MAXIMUM_ZIP_ENTRY_SIZE

    /** file-type's replacement `ZipHandler.prototype.unzip`. */
    private fun unzip() {
        var stop: Boolean
        var entryCount = 0
        knownSizeDescriptorScannedBytes = 0
        do {
            val header = readLocalFileHeader() ?: break
            entryCount++
            if (entryCount > 1024) throw ZipError()
            val next = onEntry(header)
            stop = next.stop
            tokenizer.ignore(header.extraFieldLength.toLong())
            val maximumDescriptorLength = minOf(MAXIMUM_ZIP_ENTRY_SIZE.toLong(), maxOf(0L, MAXIMUM_ZIP_ENTRY_SIZE - knownSizeDescriptorScannedBytes))
            val fileData = readEntryData(header, next.handler != null, maximumDescriptorLength)
            next.handler?.let { inflate(header, fileData!!, it) }
            if (header.dataDescriptor) {
                val descriptor = tokenizer.readBytes(16)
                if (u32(descriptor, 0, false) != ZIP_DATA_DESCRIPTOR) throw ZipError()
            }
        } while (!stop)
    }

    private fun readLocalFileHeader(): ZipHeader? {
        val signature = ByteArray(4).also { tokenizer.peek(it) }.let { u32(it, 0, false) }
        when (signature) {
            ZIP_LOCAL_FILE_HEADER -> {
                val fixed = tokenizer.readBytes(30)
                val filenameLength = u16(fixed, 26, false)
                return ZipHeader(
                    dataDescriptor = (u16(fixed, 6, false) and 0x0008) != 0,
                    compressedMethod = u16(fixed, 8, false),
                    compressedSize = u32(fixed, 18, false),
                    uncompressedSize = u32(fixed, 22, false),
                    extraFieldLength = u16(fixed, 28, false),
                    filename = decodeUtf8(tokenizer.readBytes(filenameLength)),
                )
            }
            ZIP_CENTRAL_FILE_HEADER -> return null
            ZIP_ENCRYPTED -> throw ZipError() // "Encrypted ZIP"
            else -> throw ZipError() // "Unexpected signature"
        }
    }

    private fun readEntryData(header: ZipHeader, shouldBuffer: Boolean, maximumDescriptorLength: Long): ByteArray? {
        if (header.dataDescriptor && header.compressedSize == 0L) return readDataDescriptorEntry(shouldBuffer, maximumDescriptorLength)
        if (!shouldBuffer) {
            tokenizer.safeIgnore(header.compressedSize)
            return null
        }
        val maximumLength = maxOf(0, tokenizer.size - tokenizer.position).toLong()
        if (header.compressedSize > maximumLength) throw ZipError()
        return tokenizer.readBytes(header.compressedSize.toInt())
    }

    /** An entry whose size is only in the data descriptor after it: scan for that descriptor. */
    private fun readDataDescriptorEntry(shouldBuffer: Boolean, maximumLength: Long): ByteArray? {
        // The 256 KiB sync buffer is never filled by a 4100-byte head, so each peek takes the rest of it.
        val chunks = mutableListOf<ByteArray>()
        var bytesConsumed = 0L
        while (true) {
            val rest = ByteArray(tokenizer.size - tokenizer.position).also { tokenizer.peek(it, mayBeLess = true) }
            val descriptorOffset = findDataDescriptorOffset(rest, bytesConsumed)
            val chunkLength = if (descriptorOffset >= 0) descriptorOffset else rest.size
            if (chunkLength == 0) break
            bytesConsumed += chunkLength
            if (bytesConsumed > maximumLength) throw ZipError()
            if (shouldBuffer) chunks += tokenizer.readBytes(chunkLength) else tokenizer.ignore(chunkLength.toLong())
            if (descriptorOffset >= 0) break
        }
        knownSizeDescriptorScannedBytes += bytesConsumed
        if (!shouldBuffer) return null
        return chunks.fold(ByteArray(0)) { all, chunk -> all + chunk }
    }

    private fun findDataDescriptorOffset(bytes: ByteArray, bytesConsumed: Long): Int {
        if (bytes.size < 16) return -1
        for (index in 0..bytes.size - 16) {
            if (u32(bytes, index, false) == ZIP_DATA_DESCRIPTOR && u32(bytes, index + 8, false) == bytesConsumed + index) return index
        }
        return -1
    }

    private fun inflate(header: ZipHeader, data: ByteArray, handler: (ByteArray) -> Unit) {
        when (header.compressedMethod) {
            0 -> handler(data)
            8 -> handler(inflateRaw(data))
            else -> throw ZipError() // "Unsupported ZIP compression method"
        }
    }

    /**
     * `DecompressionStream('deflate-raw')` with file-type's 1 MiB cap. A cut-off or
     * corrupt stream fails (zlib's Z_BUF_ERROR / Z_DATA_ERROR); bytes after the end
     * of the stream are ignored.
     */
    private fun inflateRaw(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val count = try {
                    inflater.inflate(chunk)
                } catch (_: DataFormatException) {
                    throw ZipError()
                }
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw ZipError()
                out.write(chunk, 0, count)
                if (out.size() > MAXIMUM_ZIP_ENTRY_SIZE) throw ZipError()
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}

private val CLASSES_DEX = Regex("""classes[0-9]*\.dex""")

/** `getOpenXmlMimeTypeFromContentTypesXml`: a string scan for the `…main+xml` content type. */
private fun openXmlMimeFromContentTypes(xml: String): String? {
    val endPosition = xml.indexOf(".main+xml\"")
    if (endPosition == -1) {
        val mimeType = "application/vnd.ms-package.3dmanufacturing-3dmodel+xml"
        return if (xml.contains("ContentType=\"$mimeType\"")) mimeType else null
    }
    val truncated = xml.substring(0, endPosition)
    return truncated.substring(truncated.lastIndexOf('"') + 1)
}

/** `getFileTypeFromMimeType`. */
private fun mimeFromMimeType(raw: String): String? {
    val mimeType = raw.lowercase()
    return when (mimeType) {
        "application/epub+zip",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.text-template",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.spreadsheet-template",
        "application/vnd.oasis.opendocument.presentation",
        "application/vnd.oasis.opendocument.presentation-template",
        "application/vnd.oasis.opendocument.graphics",
        "application/vnd.oasis.opendocument.graphics-template",
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        "application/vnd.openxmlformats-officedocument.presentationml.template",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        -> mimeType
        "application/vnd.ms-excel.sheet.macroenabled" -> "application/vnd.ms-excel.sheet.macroenabled.12"
        "application/vnd.ms-excel.template.macroenabled" -> "application/vnd.ms-excel.template.macroenabled.12"
        "application/vnd.ms-powerpoint.slideshow.macroenabled" -> "application/vnd.ms-powerpoint.slideshow.macroenabled.12"
        "application/vnd.ms-word.document.macroenabled" -> "application/vnd.ms-word.document.macroenabled.12"
        "application/vnd.ms-word.template.macroenabledtemplate" -> "application/vnd.ms-word.template.macroenabled.12"
        "application/vnd.ms-powerpoint.template.macroenabled" -> "application/vnd.ms-powerpoint.template.macroenabled.12"
        "application/vnd.ms-powerpoint.presentation.macroenabled" -> "application/vnd.ms-powerpoint.presentation.macroenabled.12"
        "application/vnd.ms-visio.drawing" -> "application/vnd.visio"
        "application/vnd.ms-package.3dmanufacturing-3dmodel+xml" -> "model/3mf"
        else -> null
    }
}

// --- JavaScript string behaviour the checks rely on ------------------------------------------

/** `new TextDecoder('utf-8').decode(bytes)`: WHATWG UTF-8, U+FFFD for bad bytes, a leading BOM dropped. */
private fun decodeUtf8(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size)
    var needed = 0
    var seen = 0
    var codePoint = 0
    var lower = 0x80
    var upper = 0xBF
    var i = 0
    while (i < bytes.size) {
        val byte = bytes[i].toInt() and 0xFF
        if (needed == 0) {
            when (byte) {
                in 0x00..0x7F -> out.append(byte.toChar())
                in 0xC2..0xDF -> { needed = 1; codePoint = byte and 0x1F }
                in 0xE0..0xEF -> {
                    if (byte == 0xE0) lower = 0xA0
                    if (byte == 0xED) upper = 0x9F
                    needed = 2
                    codePoint = byte and 0xF
                }
                in 0xF0..0xF4 -> {
                    if (byte == 0xF0) lower = 0x90
                    if (byte == 0xF4) upper = 0x8F
                    needed = 3
                    codePoint = byte and 0x7
                }
                else -> out.append('�')
            }
            i++
            continue
        }
        if (byte !in lower..upper) {
            needed = 0; seen = 0; codePoint = 0; lower = 0x80; upper = 0xBF
            out.append('�')
            continue // the byte is read again as the start of something new
        }
        lower = 0x80
        upper = 0xBF
        codePoint = (codePoint shl 6) or (byte and 0x3F)
        seen++
        if (seen == needed) {
            out.appendCodePoint(codePoint)
            needed = 0; seen = 0; codePoint = 0
        }
        i++
    }
    if (needed != 0) out.append('�')
    return if (out.startsWith('﻿')) out.substring(1) else out.toString()
}

private fun isJsLineTerminator(c: Char) = c == '\n' || c == '\r' || c == ' ' || c == ' '

/** `.replace(/\0.*$/v, '')`: cut at the first NUL that has no line break after it. */
private fun String.stripNulTail(): String {
    for (i in indices) {
        if (this[i] == '\u0000' && (i + 1 until length).none { isJsLineTerminator(this[it]) }) return substring(0, i)
    }
    return this
}

private fun isJsWhitespace(c: Char) = c in '\u0009'..'\u000D' || c == ' ' || c == ' ' || c == ' ' ||
    c in ' '..' ' || c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '　' || c == '﻿'

/** `String.prototype.trim`, whose idea of whitespace is wider than Kotlin's `trim()`. */
private fun String.jsTrim(): String = dropWhile(::isJsWhitespace).dropLastWhile(::isJsWhitespace)

/** `Number.parseInt(text, 8)` on trimmed text, as a whole number, or null for NaN. */
private fun parseOctalLikeJs(text: String): Long? {
    var i = 0
    var sign = 1L
    if (i < text.length && (text[i] == '+' || text[i] == '-')) {
        if (text[i] == '-') sign = -1
        i++
    }
    val start = i
    var value = 0L
    while (i < text.length && text[i] in '0'..'7') {
        value = value * 8 + (text[i] - '0')
        i++
    }
    return if (i == start) null else sign * value
}

/** Whether `JSON.parse(text).files` is truthy, for the ASAR check; invalid JSON is false. */
private fun asarJsonHasFiles(text: String): Boolean {
    val parser = JsonTruthParser(text)
    return try {
        val value = parser.document()
        value is Map<*, *> && truthy(value["files"])
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: StackOverflowError) {
        false
    }
}

private object JsonNull

private fun truthy(value: Any?): Boolean = when (value) {
    null, JsonNull -> false
    is Boolean -> value
    is Double -> value != 0.0 && !value.isNaN()
    is String -> value.isNotEmpty()
    else -> true // objects and arrays
}

/** Just enough of `JSON.parse` to answer [asarJsonHasFiles]: strict grammar, last duplicate key wins. */
private class JsonTruthParser(private val text: String) {
    private var i = 0

    fun document(): Any {
        skipSpace()
        val value = value()
        skipSpace()
        require(i == text.length)
        return value
    }

    private fun skipSpace() {
        while (i < text.length && text[i] in " \t\n\r") i++
    }

    private fun value(): Any {
        require(i < text.length)
        return when (text[i]) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", JsonNull)
            else -> number()
        }
    }

    private fun literal(word: String, value: Any): Any {
        require(text.startsWith(word, i))
        i += word.length
        return value
    }

    private fun obj(): Map<String, Any> {
        val map = HashMap<String, Any>()
        i++
        skipSpace()
        if (i < text.length && text[i] == '}') {
            i++
            return map
        }
        while (true) {
            skipSpace()
            require(i < text.length && text[i] == '"')
            val key = string()
            skipSpace()
            require(i < text.length && text[i] == ':')
            i++
            skipSpace()
            map[key] = value()
            skipSpace()
            require(i < text.length)
            when (text[i++]) {
                ',' -> continue
                '}' -> return map
                else -> throw IllegalArgumentException()
            }
        }
    }

    private fun array(): List<Any> {
        val list = ArrayList<Any>()
        i++
        skipSpace()
        if (i < text.length && text[i] == ']') {
            i++
            return list
        }
        while (true) {
            skipSpace()
            list += value()
            skipSpace()
            require(i < text.length)
            when (text[i++]) {
                ',' -> continue
                ']' -> return list
                else -> throw IllegalArgumentException()
            }
        }
    }

    private fun string(): String {
        i++ // opening quote
        val out = StringBuilder()
        while (true) {
            require(i < text.length)
            val c = text[i++]
            when {
                c == '"' -> return out.toString()
                c < ' ' -> throw IllegalArgumentException()
                c == '\\' -> {
                    require(i < text.length)
                    when (val e = text[i++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            require(i + 4 <= text.length)
                            val hex = text.substring(i, i + 4)
                            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                            out.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> throw IllegalArgumentException()
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private fun number(): Double {
        val start = i
        if (i < text.length && text[i] == '-') i++
        require(i < text.length && text[i] in '0'..'9')
        if (text[i] == '0') i++ else while (i < text.length && text[i] in '0'..'9') i++
        if (i < text.length && text[i] == '.') {
            i++
            require(i < text.length && text[i] in '0'..'9')
            while (i < text.length && text[i] in '0'..'9') i++
        }
        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
            i++
            if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
            require(i < text.length && text[i] in '0'..'9')
            while (i < text.length && text[i] in '0'..'9') i++
        }
        return text.substring(start, i).toDouble()
    }
}
