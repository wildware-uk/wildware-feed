package dev.wildware.agents.app.feed.video

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import com.sun.jna.Memory
import dev.wildware.agents.app.feed.VideoResume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The parts of the desktop video player that need no video engine: frames, status and resuming. */
class NativeVideoPartsTest {
    /** A BGRA picture in native memory, each row padded the way a GPU or CoreVideo pads it. */
    private fun picture(width: Int, height: Int, rowBytes: Int, colour: (x: Int, y: Int) -> Int): Memory =
        Memory(rowBytes.toLong() * height).apply {
            clear()
            for (y in 0 until height) for (x in 0 until width) setInt(y.toLong() * rowBytes + x * 4L, colour(x, y))
        }

    @Test
    fun aFrameWithPaddedRowsIsCopiedPixelForPixel() {
        val frames = VideoFrames()
        // 0xAARRGGBB as a little-endian int is the bytes B, G, R, A.
        frames.write(5, 3, rowBytes = 5 * 4 + 12, pixels = picture(5, 3, 5 * 4 + 12) { x, y -> if (x == y) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() })
        val pixels = frames.image!!.toPixelMap()
        assertEquals(5, pixels.width)
        assertEquals(3, pixels.height)
        assertEquals(Color.Red, pixels[1, 1])
        assertEquals(Color.Blue, pixels[2, 1])
        assertEquals(Color.Blue, pixels[4, 2])
        frames.close()
        assertNull(frames.image)
    }

    @Test
    fun eachFrameIsANewImageSoComposeNeverDrawsAHalfWrittenOne() {
        val frames = VideoFrames()
        val first = picture(4, 4, 16) { _, _ -> 0xFF00FF00.toInt() }
        frames.write(4, 4, 16, first)
        val shown = frames.image
        repeat(5) { frames.write(4, 4, 16, first) }
        assertNotSame(shown, frames.image)
        assertEquals(Color.Green, frames.image!!.toPixelMap()[3, 3])
        frames.close()
    }

    @Test
    fun theControlsHearOfPositionAFewTimesASecondButOfEverythingElseAtOnce() {
        val tracker = VideoTracker()
        val start = VideoStatus(playing = true, positionSec = 1.0, durationSec = 10.0, loading = false)
        tracker.offer(start)
        val held = tracker.status
        tracker.offer(start.copy(positionSec = 1.1))
        assertSame(held, tracker.status, "a tenth of a second on doesn't redraw the controls")
        tracker.offer(start.copy(positionSec = 1.3))
        assertEquals(1.3, tracker.status.positionSec)
        tracker.offer(tracker.status.copy(positionSec = 1.35, playing = false))
        assertEquals(false, tracker.status.playing, "a pause shows straight away")
    }

    @Test
    fun aVideoGoingFullScreenCarriesOnWhereItWas() {
        VideoResume.keep("https://dash.test/media/v/video", 42.5)
        assertNull(VideoResume.take("https://dash.test/media/other/video"), "another video starts at the top")
        VideoResume.keep("https://dash.test/media/v/video", 42.5)
        assertEquals(42.5, VideoResume.take("https://dash.test/media/v/video"))
        assertNull(VideoResume.take("https://dash.test/media/v/video"), "only once")
    }
}
