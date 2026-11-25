package dev.wildware.feed.server.media

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.events.Bus
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every video's playback copy: an mp4 within 1280x720, made once, old videos included (migration 078). */
class PlaybackCopyTest {
    @TempDir
    lateinit var dir: Path

    private val tools = FfmpegTools()

    /** A short test-pattern clip with sound: H.264 and AAC, in an mp4 or a mov. */
    private fun clip(width: Int, height: Int, ext: String = "mp4", seconds: Double = 0.5): ByteArray {
        val out = dir.resolve("src-${width}x$height.$ext")
        tools.ffmpeg(
            listOf(
                "-y", "-f", "lavfi", "-i", "testsrc=size=${width}x$height:rate=24", "-f", "lavfi", "-i", "sine=frequency=440",
                "-t", seconds.toString(), "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", out.toString(),
            ),
        )
        return Files.readAllBytes(out)
    }

    private class World(val db: Database, val derive: Derive, val paths: MediaPaths, val bus: Bus)

    private fun world(): World {
        val db = Database.memory()
        val paths = MediaPaths(dir.toString())
        val bus = Bus(boot = "t")
        return World(db, Derive(db, bus, paths, tools, workers = 1), paths, bus)
    }

    private fun World.add(id: String, mime: String, bytes: ByteArray, status: String = "pending") {
        Files.createDirectories(paths.dir(id))
        Files.write(paths.original(id, mime), bytes)
        db.update(
            "INSERT INTO media (id, author, kind, mime, bytes, sha256, status, created_at) VALUES (?, 'human', 'video', ?, ?, 'abc', ?, 1)",
            id, mime, bytes.size, status,
        )
    }

    private fun World.mp4s(id: String) = db.query("SELECT path, width, height FROM derivatives WHERE media_id = ? AND kind = 'mp4'", id) {
        Triple(it.getString(1), it.getInt(2), it.getInt(3))
    }

    /** Top-level box types of an mp4, in order. */
    private fun boxes(file: Path): List<String> {
        val bytes = Files.readAllBytes(file)
        val out = mutableListOf<String>()
        var at = 0L
        while (at + 8 <= bytes.size) {
            val b = ByteBuffer.wrap(bytes, at.toInt(), 8)
            var size = b.int.toLong() and 0xffffffffL
            val type = String(bytes, at.toInt() + 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) size = ByteBuffer.wrap(bytes, at.toInt() + 8, 8).long
            if (size < 8) break
            out += type
            at += size
        }
        return out
    }

    private fun World.checkCopy(id: String, width: Int, height: Int) {
        val (path, w, h) = mp4s(id).single()
        assertEquals("01/$id/${Derive.PLAYBACK_NAME}", path)
        assertEquals(width to height, w to h)
        val file = paths.root.resolve(path)
        val probe = tools.probe(file)
        assertEquals("h264", probe.videoCodec)
        assertEquals("aac", probe.audioCodec)
        assertEquals(width to height, probe.width to probe.height)
        val order = boxes(file)
        assertTrue(order.indexOf("moov") in 0 until order.indexOf("mdat"), "faststart: the index comes first, $order")
    }

    @Test
    fun `a 1080p mp4 is ready with its poster, then gets a 720p copy`() {
        assumeTrue(tools.available)
        val w = world()
        val id = "01J0000000000000000000001A"
        w.add(id, "video/mp4", clip(1920, 1080))
        w.derive.process(id)
        assertEquals("ready", w.db.one("SELECT status FROM media WHERE id = ?", id) { it.getString(1) })
        assertTrue(w.mp4s(id).isEmpty(), "it plays as uploaded, so it doesn't wait for the copy")
        assertEquals(id, w.derive.nextPlayback())
        w.derive.playback(id)
        w.checkCopy(id, 1280, 720)
        assertEquals(1920 to 1080, w.db.one("SELECT width, height FROM media WHERE id = ?", id) { it.getInt(1) to it.getInt(2) }, "the original's size stays")
        assertNull(w.derive.nextPlayback(), "made once")
    }

    @Test
    fun `a 4K mov that won't play as uploaded is ready only with its 720p copy`() {
        assumeTrue(tools.available)
        val w = world()
        val id = "01J0000000000000000000001B"
        w.add(id, "video/quicktime", clip(3840, 2160, "mov", seconds = 0.25))
        w.derive.process(id)
        assertEquals("ready", w.db.one("SELECT status FROM media WHERE id = ?", id) { it.getString(1) })
        w.checkCopy(id, 1280, 720)
        assertNull(w.derive.nextPlayback())
    }

    @Test
    fun `an upright video fits 720 wide, and a small one is never enlarged`() {
        assumeTrue(tools.available)
        val w = world()
        val upright = "01J0000000000000000000001C"
        w.add(upright, "video/quicktime", clip(1080, 1920, "mov"))
        w.derive.process(upright)
        w.checkCopy(upright, 720, 1280)
        val small = "01J0000000000000000000001D"
        w.add(small, "video/quicktime", clip(322, 242, "mov"))
        w.derive.process(small)
        w.checkCopy(small, 322, 242)
        // An odd size becomes even, as H.264 needs.
        val odd = "01J0000000000000000000001E"
        w.add(odd, "video/mp4", clip(1286, 722), status = "ready")
        w.derive.playback(odd)
        val (_, ow, oh) = w.mp4s(odd).single()
        assertTrue(ow % 2 == 0 && oh % 2 == 0 && ow <= 1280 && oh <= 720, "$ow x $oh")
    }

    @Test
    fun `an old video gets its copy once, in place of its full-size transcode`() {
        assumeTrue(tools.available)
        val w = world()
        val old = "01J0000000000000000000001F"
        w.add(old, "video/quicktime", clip(1920, 1080, "mov"), status = "ready")
        // What an older server made: the whole size, as video.mp4.
        val full = w.paths.dir(old).resolve("video.mp4")
        Files.write(full, byteArrayOf(1, 2, 3))
        w.db.update("INSERT INTO derivatives (id, media_id, kind, path, bytes, width, height) VALUES ('d1', ?, 'mp4', ?, 3, 1920, 1080)", old, "01/$old/video.mp4")
        // Already small enough, or not a video, or broken: left alone, or tried once.
        val fine = "01J0000000000000000000001G"
        w.add(fine, "video/quicktime", clip(640, 360, "mov"), status = "ready")
        w.db.update("INSERT INTO derivatives (id, media_id, kind, path, bytes, width, height) VALUES ('d2', ?, 'mp4', ?, 3, 640, 360)", fine, "01/$fine/video.mp4")
        val broken = "01J0000000000000000000001H"
        w.add(broken, "video/mp4", clip(320, 240).copyOfRange(0, 200), status = "ready")
        val pending = "01J0000000000000000000001J"
        w.add(pending, "video/mp4", clip(640, 360))

        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        w.bus.subscribe { e -> if (e.type == "media.ready") seen += e.payload.toString() }
        val deadline = System.currentTimeMillis() + 120_000
        // Until the apps are told about the old one (so they switch to its copy), and the broken one was tried.
        while (seen.none { old in it } || w.db.one("SELECT playback_tried_at FROM media WHERE id = ?", broken) { it.getObject(1) } == null) {
            assertTrue(System.currentTimeMillis() < deadline, "the backfill gets to them")
            w.derive.tick()
            Thread.sleep(20)
        }
        w.derive.close()
        assertEquals("ready", w.db.one("SELECT status FROM media WHERE id = ?", pending) { it.getString(1) }, "a new upload went first")
        w.checkCopy(old, 1280, 720)
        assertFalse(Files.exists(full), "the old full-size transcode is gone")
        assertEquals(listOf(Triple("01/$fine/video.mp4", 640, 360)), w.mp4s(fine), "a copy small enough already stays")
        assertTrue(w.mp4s(broken).isEmpty(), w.mp4s(broken).toString())
        assertNull(w.derive.nextPlayback(), "nothing is tried twice")
    }
}
