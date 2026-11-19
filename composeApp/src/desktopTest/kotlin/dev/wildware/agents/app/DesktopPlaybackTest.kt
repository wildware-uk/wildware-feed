package dev.wildware.agents.app

import dev.wildware.agents.app.feed.DesktopPlayback
import dev.wildware.agents.app.feed.VideoSource
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A Linux computer without an H.264 decoder says so, rather than showing a silent black box. */
class DesktopPlaybackTest {
    private val plugins: File = Files.createTempDirectory("gst").toFile()
    private val mp4 = VideoSource("https://dash.test/media/v1/video", "0:12", "video/mp4")

    private fun problem(video: VideoSource, linux: Boolean = true) =
        DesktopPlayback.problemFor(video, linux = linux, pluginDirs = listOf(plugins.path), core = { null })

    @Test
    fun anMp4WithNoDecoderAsksForLibav() {
        assertEquals("This video needs a codec: install gstreamer1.0-libav", problem(mp4))
        assertEquals("This video needs a codec: install gstreamer1.0-libav", problem(mp4.copy(mime = "video/quicktime")))
    }

    @Test
    fun libavOrOpenh264IsEnough() {
        File(plugins, "libgstlibav.so").writeText("")
        assertNull(problem(mp4))
        File(plugins, "libgstlibav.so").delete()
        File(plugins, "libgstopenh264.so").writeText("")
        assertNull(problem(mp4))
    }

    @Test
    fun webmNeedsNoH264AndOtherSystemsNeverAsk() {
        assertNull(problem(mp4.copy(mime = "video/webm")))
        assertNull(problem(mp4, linux = false))
    }

    @Test
    fun missingGStreamerItselfComesFirst() {
        assertEquals("no gstreamer", DesktopPlayback.problemFor(mp4, linux = true, pluginDirs = emptyList(), core = { "no gstreamer" }))
    }
}
