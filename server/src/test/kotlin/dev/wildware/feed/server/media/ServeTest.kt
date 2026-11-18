package dev.wildware.feed.server.media

import dev.wildware.feed.server.media.Serve.Companion.Range
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServeTest {
    @Test
    fun `ranges follow the TS parser`() {
        assertEquals(Range.Whole, Serve.range(null, 10))
        assertEquals(Range.Whole, Serve.range("", 10))
        assertEquals(Range.Whole, Serve.range("bytes=-", 10))
        assertEquals(Range.Whole, Serve.range("bytes=0-1,4-5", 10))
        assertEquals(Range.Whole, Serve.range("Bytes=0-1", 10))
        assertEquals(Range.Span(0, 9), Serve.range(" bytes=0-9 ", 20))
        assertEquals(Range.Span(5, 19), Serve.range("bytes=5-", 20))
        assertEquals(Range.Span(16, 19), Serve.range("bytes=-4", 20))
        assertEquals(Range.Span(0, 19), Serve.range("bytes=-400", 20))
        assertEquals(Range.Span(3, 19), Serve.range("bytes=3-99999999999999999999999", 20))
        assertEquals(Range.Unsatisfiable, Serve.range("bytes=20-", 20))
        assertEquals(Range.Unsatisfiable, Serve.range("bytes=5-2", 20))
        assertEquals(Range.Unsatisfiable, Serve.range("bytes=-0", 20))
        assertEquals(Range.Unsatisfiable, Serve.range("bytes=0-0", 0))
    }

    @Test
    fun `a file downloads with an ASCII name and an encoded one`() {
        assertEquals("inline", Serve.disposition(false, "a.png"))
        assertEquals("attachment", Serve.disposition(true, null))
        assertEquals("attachment; filename=\"_ _x_.txt\"; filename*=UTF-8''%C3%BC%20%22x%22.txt", Serve.disposition(true, "ü \"x\".txt"))
        assertEquals("attachment; filename=\"__.txt\"; filename*=UTF-8''%F0%9F%98%80.txt", Serve.disposition(true, "😀.txt"))
        assertEquals("a-_.!~*'()%2F%5C", Serve.encodeURIComponent("a-_.!~*'()/\\"))
    }

    @Test
    fun `if-none-match matches star, a candidate or a weak one`() {
        val etag = "\"abc-original\""
        assertTrue(Serve.notModified(" * ", etag))
        assertTrue(Serve.notModified("\"x\", W/\"abc-original\"", etag))
        assertTrue(Serve.notModified(etag, etag))
        assertFalse(Serve.notModified("\"abc-thumb-640\"", etag))
        assertFalse(Serve.notModified(null, etag))
    }

    @Test
    fun `orientation comes from the EXIF block of a JPEG`() {
        fun jpeg(orientation: Int, little: Boolean): ByteArray {
            val tiff = if (little) {
                byteArrayOf(0x49, 0x49, 0x2a, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0, 0)
            } else {
                byteArrayOf(0x4d, 0x4d, 0, 0x2a, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0)
            }
            val app1 = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
            val length = app1.size + 2
            return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + app1 +
                byteArrayOf(0xFF.toByte(), 0xDA.toByte())
        }
        assertEquals(6, Orientation.of(jpeg(6, little = true)))
        assertEquals(8, Orientation.of(jpeg(8, little = false)))
        assertEquals(1, Orientation.of(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte())))
        assertEquals(1, Orientation.of(ByteArray(3)))
        assertTrue(Orientation.swapsAxes(6))
        assertFalse(Orientation.swapsAxes(3))
    }

    @Test
    fun `mimes canonicalise and pick a kind`() {
        assertEquals("audio/mp4", Mime.canonical(" Audio/X-M4A ; codecs=x"))
        assertEquals("image", Mime.kindOf("image/png"))
        assertEquals("file", Mime.kindOf("image/svg+xml"))
        assertEquals(null, Mime.kindOf("nonsense"))
        assertEquals("bin", Mime.extensionOf("text/plain"))
        assertEquals("jpg", Mime.extensionOf("image/jpeg"))
    }

    @Test
    fun `poster seek times and playable codecs`() {
        assertEquals("1", FfmpegTools.js(FfmpegTools.posterSeconds(null)))
        assertEquals("1", FfmpegTools.js(FfmpegTools.posterSeconds(5000)))
        assertEquals("0.25", FfmpegTools.js(FfmpegTools.posterSeconds(500)))
        assertEquals("0.6", FfmpegTools.js(FfmpegTools.posterSeconds(1200)))
        assertTrue(FfmpegTools.webPlayable("video/mp4", FfmpegTools.Probe(1, 2, 2, "h264", null)))
        assertFalse(FfmpegTools.webPlayable("video/mp4", FfmpegTools.Probe(1, 2, 2, "h264", "opus")))
        assertFalse(FfmpegTools.webPlayable("video/quicktime", FfmpegTools.Probe(1, 2, 2, "h264", "aac")))
    }
}
