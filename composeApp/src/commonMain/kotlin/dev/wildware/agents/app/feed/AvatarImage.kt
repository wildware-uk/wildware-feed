package dev.wildware.agents.app.feed

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/** How big a member's picture goes up, in pixels a side. The dashboard shows it at 256. */
const val AvatarPixels = 384

/**
 * A picked picture made ready to be a member's picture: its middle square, at most [size] pixels a
 * side, as a PNG. Well under the dashboard's 2 MB (about 600 KB at 384). Null when it can't be read.
 * Slow for a big photo: call it off the main thread.
 */
@OptIn(ExperimentalResourceApi::class)
fun squareAvatarPng(bytes: ByteArray, size: Int = AvatarPixels): ByteArray? {
    val source = runCatching { bytes.decodeToImageBitmap() }.getOrNull() ?: return null
    val side = minOf(source.width, source.height)
    if (side <= 0) return null
    val target = minOf(size, side)
    // Halving a step at a time keeps a big photo from turning grainy on the way down.
    var image = source
    var from = IntOffset((source.width - side) / 2, (source.height - side) / 2)
    var span = side
    while (true) {
        val next = maxOf(target, span / 2)
        image = scaled(image, from, span, next)
        from = IntOffset.Zero
        span = next
        if (next == target) break
    }
    val pixels = IntArray(target * target)
    image.readPixels(pixels, 0, 0, target, target)
    return pngOf(target, target, pixels)
}

private fun scaled(image: ImageBitmap, from: IntOffset, span: Int, to: Int): ImageBitmap {
    val out = ImageBitmap(to, to)
    Canvas(out).drawImageRect(image, from, IntSize(span, span), IntOffset.Zero, IntSize(to, to), Paint().apply { filterQuality = FilterQuality.High })
    return out
}

/** A PNG of [pixels] (ARGB, a row at a time), stored rather than squeezed: no compressor needed. */
internal fun pngOf(width: Int, height: Int, pixels: IntArray): ByteArray {
    val rowBytes = width * 4 + 1
    val raw = ByteArray(rowBytes * height)
    for (y in 0 until height) {
        var at = y * rowBytes
        raw[at++] = 0 // no filter
        for (x in 0 until width) {
            val p = pixels[y * width + x]
            raw[at++] = (p shr 16).toByte()
            raw[at++] = (p shr 8).toByte()
            raw[at++] = p.toByte()
            raw[at++] = (p ushr 24).toByte()
        }
    }
    val out = Bytes()
    out.put(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
    val header = Bytes().apply { int(width); int(height); put(byteArrayOf(8, 6, 0, 0, 0)) }
    out.chunk("IHDR", header.bytes())
    out.chunk("IDAT", zlibStored(raw))
    out.chunk("IEND", ByteArray(0))
    return out.bytes()
}

/** Zlib data in stored blocks, each at most 65535 bytes, with its Adler-32 at the end. */
private fun zlibStored(data: ByteArray): ByteArray {
    val out = Bytes()
    out.put(byteArrayOf(0x78, 0x01))
    var at = 0
    do {
        val len = minOf(65535, data.size - at)
        val last = at + len >= data.size
        out.put(byteArrayOf(if (last) 1 else 0, (len and 0xff).toByte(), (len shr 8).toByte(), (len.inv() and 0xff).toByte(), ((len.inv() shr 8) and 0xff).toByte()))
        out.put(data, at, len)
        at += len
    } while (!last)
    var a = 1L
    var b = 0L
    for (byte in data) {
        a = (a + (byte.toInt() and 0xff)) % 65521
        b = (b + a) % 65521
    }
    out.int(((b shl 16) or a).toInt())
    return out.bytes()
}

private val crcTable = IntArray(256) { n ->
    var c = n
    repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
    c
}

private fun crc32(type: ByteArray, data: ByteArray): Int {
    var c = -1
    for (byte in type + data) c = crcTable[(c xor byte.toInt()) and 0xff] xor (c ushr 8)
    return c.inv()
}

/** A growing byte buffer, for the PNG's parts. */
private class Bytes {
    private var buf = ByteArray(1024)
    private var size = 0

    fun put(bytes: ByteArray, from: Int = 0, length: Int = bytes.size) {
        if (size + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + length))
        bytes.copyInto(buf, size, from, from + length)
        size += length
    }

    fun int(v: Int) = put(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))

    fun chunk(type: String, data: ByteArray) {
        val name = type.encodeToByteArray()
        int(data.size)
        put(name)
        put(data)
        int(crc32(name, data))
    }

    fun bytes() = buf.copyOf(size)
}
