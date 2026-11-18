package dev.wildware.feed.server.media

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.events.Bus
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeriveTest {
    @TempDir
    lateinit var dir: Path

    private val tools = FfmpegTools()

    private fun picture(width: Int, height: Int, format: String): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.RED; g.fillRect(0, 0, width / 2, height)
        g.color = Color.BLUE; g.fillRect(width / 2, 0, width - width / 2, height)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    /** A JPEG with an EXIF block saying "turn me clockwise" (orientation 6). */
    private fun turnedJpeg(width: Int, height: Int): ByteArray {
        val plain = picture(width, height, "jpg")
        val tiff = byteArrayOf(0x49, 0x49, 0x2a, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0)
        val app1 = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val length = app1.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + app1
        return plain.copyOfRange(0, 2) + segment + plain.copyOfRange(2, plain.size)
    }

    private fun setup(id: String, mime: String, bytes: ByteArray): Triple<Database, Derive, MediaPaths> {
        val db = Database.memory()
        val paths = MediaPaths(dir.toString())
        Files.createDirectories(paths.dir(id))
        Files.write(paths.original(id, mime), bytes)
        db.update(
            "INSERT INTO media (id, author, kind, mime, bytes, sha256, status, created_at) VALUES (?, 'human', ?, ?, ?, 'abc', 'pending', 1)",
            id, Mime.kindOf(mime), mime, bytes.size,
        )
        return Triple(db, Derive(db, Bus(boot = "t"), paths, tools), paths)
    }

    private fun png(webp: Path): BufferedImage {
        val out = dir.resolve("check-${webp.fileName}.png")
        tools.ffmpeg(listOf("-y", "-i", webp.toString(), out.toString()))
        return ImageIO.read(out.toFile())
    }

    @Test
    fun `an image gets two WebP thumbnails at exact widths, enlarged if small`() {
        assumeTrue(tools.available)
        val id = "01J0000000000000000000000A"
        val (db, derive, paths) = setup(id, "image/png", picture(200, 100, "png"))
        derive.process(id)
        assertEquals("ready", db.one("SELECT status FROM media WHERE id = ?", id) { it.getString(1) })
        assertEquals(200 to 100, db.one("SELECT width, height FROM media WHERE id = ?", id) { it.getInt(1) to it.getInt(2) })
        val rows = db.query("SELECT kind, width, height, path FROM derivatives WHERE media_id = ? ORDER BY width", id) {
            listOf(it.getString(1), it.getInt(2), it.getInt(3), it.getString(4))
        }
        assertEquals(listOf(listOf<Any>("thumb", 640, 320, "01/$id/thumb-640.webp"), listOf<Any>("thumb", 1600, 800, "01/$id/thumb-1600.webp")), rows)
        val thumb = paths.dir(id).resolve("thumb-640.webp")
        assertEquals("WEBP", String(Files.readAllBytes(thumb), 8, 4, Charsets.ISO_8859_1))
        assertEquals(640, png(thumb).width)
    }

    @Test
    fun `an EXIF-turned JPEG is measured and thumbnailed upright`() {
        assumeTrue(tools.available)
        val id = "01J0000000000000000000000B"
        val (db, derive, paths) = setup(id, "image/jpeg", turnedJpeg(200, 100))
        derive.process(id)
        assertEquals(100 to 200, db.one("SELECT width, height FROM media WHERE id = ?", id) { it.getInt(1) to it.getInt(2) })
        val thumb = png(paths.dir(id).resolve("thumb-640.webp"))
        assertEquals(640 to 1280, thumb.width to thumb.height)
        // Turned clockwise, the red left half is now on top.
        val top = Color(thumb.getRGB(320, 100))
        val bottom = Color(thumb.getRGB(320, 1180))
        assertTrue(top.red > 200 && top.blue < 60, "top is red: $top")
        assertTrue(bottom.blue > 200 && bottom.red < 60, "bottom is blue: $bottom")
    }

    @Test
    fun `an image that cannot be decoded fails and leaves a note`() {
        assumeTrue(tools.available)
        val id = "01J0000000000000000000000C"
        val good = picture(50, 50, "png")
        val (db, derive, paths) = setup(id, "image/png", good.copyOfRange(0, 33) + ByteArray(200) { 7 })
        derive.process(id)
        assertEquals("failed", db.one("SELECT status FROM media WHERE id = ?", id) { it.getString(1) })
        assertTrue(Files.readString(paths.dir(id).resolve("error.txt")).endsWith("\n"))
        assertEquals(0, db.one("SELECT count(*) FROM derivatives WHERE media_id = ?", id) { it.getInt(1) })
    }
}

class FfmpegThreadsTest {
    @Test
    fun `a thread cap goes before the input and before the output, and nothing changes without one`() {
        val args = listOf("-y", "-ss", "1", "-i", "in.mp4", "-frames:v", "1", "out.jpg")
        kotlin.test.assertEquals(args, FfmpegTools().withThreads(args))
        kotlin.test.assertEquals(
            listOf("-y", "-ss", "1", "-threads", "1", "-i", "in.mp4", "-frames:v", "1", "-threads", "1", "out.jpg"),
            FfmpegTools(threads = 1).withThreads(args),
        )
    }
}
