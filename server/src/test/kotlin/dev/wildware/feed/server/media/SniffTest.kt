package dev.wildware.feed.server.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SniffTest {
    private data class Case(val label: String, val head: ByteArray, val mime: String?)

    private fun load(text: String): List<Case> =
        Json.parseToJsonElement(text).jsonObject.getValue("cases").jsonArray.map {
            val case = it.jsonObject
            val mime = case.getValue("m")
            Case(
                label = case.getValue("n").jsonPrimitive.content,
                head = Base64.getDecoder().decode(case.getValue("h").jsonPrimitive.content),
                mime = if (mime is JsonNull) null else mime.jsonPrimitive.content,
            )
        }

    private fun assertMatches(cases: List<Case>) {
        val misses = cases.filter { Sniff.mime(it.head) != it.mime }
        if (misses.isNotEmpty()) {
            val sample = misses.take(25).joinToString("\n") {
                "  ${it.label} (${it.head.size} bytes): file-type ${it.mime}, Sniff ${Sniff.mime(it.head)}"
            }
            fail("${misses.size} of ${cases.size} heads disagree with file-type:\n$sample")
        }
    }

    /** corpus.json is recorded from file-type 22.0.2 by record.mjs next to it. */
    @Test
    fun `every recorded head gets file-type's answer`() {
        val text = javaClass.getResource("/sniff/corpus.json")!!.readText()
        val cases = load(text)
        assertTrue(cases.size > 5000)
        assertMatches(cases)
    }

    /** A bigger corpus (`record.mjs … --large`) checked only when SNIFF_EXTRA_CORPUS names one. */
    @Test
    fun `an extra recorded corpus, when given, matches too`() {
        val path = System.getenv("SNIFF_EXTRA_CORPUS") ?: return
        assertMatches(load(File(path).readText()))
    }

    private fun bytes(vararg parts: Any): ByteArray = parts.fold(ByteArray(0)) { all, part ->
        all + when (part) {
            is String -> part.toByteArray(Charsets.ISO_8859_1)
            is ByteArray -> part
            is IntArray -> ByteArray(part.size) { part[it].toByte() }
            else -> error("unsupported part $part")
        }
    }

    private fun ints(vararg values: Int) = values

    private fun chunk(type: String, length: Int) = bytes(ints(0, 0, length shr 8, length and 0xFF), type, ByteArray(length + 4))

    private val pngSignature = ints(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun ftyp(brand: String) = bytes(ints(0, 0, 0, 0x18), "ftyp", brand, ints(0, 0, 2, 0), "isommp41", ByteArray(32))

    @Test
    fun `an empty or one-byte head has no type`() {
        assertNull(Sniff.mime(ByteArray(0)))
        assertNull(Sniff.mime(bytes("G")))
        assertNull(Sniff.mime(ByteArray(4100)))
    }

    @Test
    fun `a PNG is apng only when acTL comes before the first IDAT`() {
        assertEquals("image/png", Sniff.mime(bytes(pngSignature, chunk("IHDR", 13), chunk("IDAT", 10))))
        assertEquals("image/apng", Sniff.mime(bytes(pngSignature, chunk("IHDR", 13), chunk("acTL", 8), chunk("IDAT", 10))))
        assertEquals("image/png", Sniff.mime(bytes(pngSignature, chunk("IHDR", 13), chunk("IDAT", 10), chunk("acTL", 8))))
        // An IHDR of the wrong size, or a head cut inside the first chunk header, is no PNG at all.
        assertNull(Sniff.mime(bytes(pngSignature, chunk("IHDR", 12), chunk("IDAT", 10))))
        assertNull(Sniff.mime(bytes(pngSignature, ints(0, 0, 0, 13))))
    }

    @Test
    fun `ftyp major brands pick the ISO media type`() {
        assertEquals("video/quicktime", Sniff.mime(ftyp("qt  ")))
        assertEquals("audio/x-m4a", Sniff.mime(ftyp("M4A ")))
        assertEquals("audio/mp4", Sniff.mime(ftyp("M4B ")))
        assertEquals("audio/mp4", Sniff.mime(ftyp("F4A ")))
        assertEquals("video/x-m4v", Sniff.mime(ftyp("M4V ")))
        assertEquals("video/mp4", Sniff.mime(ftyp("M4P ")))
        assertEquals("video/mp4", Sniff.mime(ftyp("isom")))
        assertEquals("video/3gpp", Sniff.mime(ftyp("3gp4")))
        assertEquals("video/3gpp2", Sniff.mime(ftyp("3g2a")))
        assertEquals("image/heic", Sniff.mime(ftyp("heic")))
        assertEquals("image/avif", Sniff.mime(ftyp("avif")))
        // Only the first NUL becomes a space, and JavaScript's trim keeps NUL: this is not "qt".
        assertEquals("video/mp4", Sniff.mime(ftyp("qt\u0000\u0000")))
        assertEquals("video/quicktime", Sniff.mime(ftyp("qt \u0000")))
    }

    private fun oggPage(packet: ByteArray) = bytes("OggS", ints(0, 2), ByteArray(20), ints(1, packet.size), packet)

    @Test
    fun `Ogg is named by its first packet`() {
        assertEquals("audio/ogg; codecs=opus", Sniff.mime(oggPage(bytes("OpusHead", ByteArray(11)))))
        assertEquals("audio/ogg", Sniff.mime(oggPage(bytes(ints(1), "vorbis", ByteArray(10)))))
        assertEquals("video/ogg", Sniff.mime(oggPage(bytes(ints(0x80), "theora", ByteArray(10)))))
        assertEquals("audio/ogg", Sniff.mime(oggPage(bytes(ints(0x7F), "FLAC", ByteArray(10)))))
        assertEquals("application/ogg", Sniff.mime(oggPage(bytes("fishead\u0000", ByteArray(10)))))
        // Fewer than 36 bytes: the codec read runs off the end and nothing is named.
        assertNull(Sniff.mime(oggPage(bytes("OpusHead")).copyOf(35)))
    }

    private fun ebml(docType: String) = bytes(EBML, ints(0x80 or (docType.length + 3)), ints(0x42, 0x82, 0x80 or docType.length), docType)

    private val EBML = ints(0x1A, 0x45, 0xDF, 0xA3)

    @Test
    fun `EBML is webm or matroska by doctype`() {
        assertEquals("video/webm", Sniff.mime(ebml("webm")))
        assertEquals("video/matroska", Sniff.mime(ebml("matroska")))
        assertNull(Sniff.mime(ebml("mkv3d")))
    }

    @Test
    fun `MPEG audio frames are mp3 and ADTS is aac`() {
        assertEquals("audio/mpeg", Sniff.mime(bytes(ints(0xFF, 0xFB, 0x90, 0x64))))
        assertEquals("audio/aac", Sniff.mime(bytes(ints(0xFF, 0xF1, 0x50, 0x80))))
        assertNull(Sniff.mime(bytes(ints(0xFF, 0xFE, 0x90, 0x64)))) // reads as a UTF-16 BOM
    }

    @Test
    fun `an ID3 tag is skipped, and one running past the head is taken as mp3`() {
        val frame = bytes(ints(0xFF, 0xFB, 0x90, 0x64))
        assertEquals("audio/mpeg", Sniff.mime(bytes("ID3", ints(4, 0, 0, 0, 0, 0, 2), ByteArray(2), frame)))
        assertEquals("image/png", Sniff.mime(bytes("ID3", ints(4, 0, 0, 0, 0, 0, 0), pngSignature, chunk("IHDR", 13), chunk("IDAT", 1))))
        assertEquals("audio/mpeg", Sniff.mime(bytes("ID3", ints(4, 0, 0, 0, 0, 0x7F, 0x7F), ByteArray(100))))
        assertNull(Sniff.mime(bytes("ID3", ints(4, 0, 0, 0, 0, 0, 3), "abc")))
        assertNull(Sniff.mime(bytes("ID3", ints(4, 0, 0, 0)))) // the size field is cut off
    }

    @Test
    fun `RIFF is webp, wav or avi by its form type`() {
        assertEquals("image/webp", Sniff.mime(bytes("RIFF", ints(100, 0, 0, 0), "WEBPVP8 ")))
        assertEquals("audio/wav", Sniff.mime(bytes("RIFF", ints(100, 0, 0, 0), "WAVEfmt ")))
        assertEquals("video/vnd.avi", Sniff.mime(bytes("RIFF", ints(100, 0, 0, 0), "AVI LIST")))
        assertNull(Sniff.mime(bytes("RIFF", ints(100, 0, 0, 0), "ACONanih")))
    }

    @Test
    fun `the common signatures`() {
        assertEquals("image/jpeg", Sniff.mime(bytes(ints(0xFF, 0xD8, 0xFF, 0xE0))))
        assertEquals("image/gif", Sniff.mime(bytes("GIF89a")))
        assertEquals("audio/flac", Sniff.mime(bytes("fLaC", ByteArray(4))))
        assertEquals("application/pdf", Sniff.mime(bytes("%PDF-1.7")))
        assertEquals("application/zip", Sniff.mime(bytes("PK", ints(5, 6), ByteArray(18))))
        assertEquals("application/gzip", Sniff.mime(bytes(ints(0x1F, 0x8B, 8), ByteArray(10))))
        assertEquals("video/quicktime", Sniff.mime(bytes(ints(0, 0, 0, 8), "wide", ints(0, 0, 0, 8), "mdat")))
        assertNull(Sniff.mime(bytes("<svg xmlns=\"http://www.w3.org/2000/svg\"/>")))
        assertNull(Sniff.mime(bytes("hello, world\n")))
    }
}
