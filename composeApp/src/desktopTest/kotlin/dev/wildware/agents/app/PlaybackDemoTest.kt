package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.sun.net.httpserver.HttpServer
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.LocalMediaAuth
import dev.wildware.agents.app.feed.MediaAuth
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.VideoSource
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test

/**
 * Not a check that runs everywhere: it needs GStreamer and ffmpeg, so it runs only with
 * PLAYBACK_DEMO=<folder>. It makes a short video, serves it from a stand-in dashboard that wants the
 * device key, and lets the real player stream it through the loopback proxy inside a card, saving
 * frames as it plays. They are the screenshots (and, joined, the recording) of desktop playback.
 */
class PlaybackDemoTest {
    @Test
    fun aVideoPlaysInItsCard() {
        val out = System.getenv("PLAYBACK_DEMO")?.let(::File) ?: return
        out.mkdirs()
        val video = File(out, "demo.webm")
        if (!video.exists()) {
            ProcessBuilder(
                "ffmpeg", "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30", "-f", "lavfi", "-i", "sine=frequency=440",
                "-t", "8", "-c:v", "libvpx", "-b:v", "1M", "-c:a", "libvorbis", video.path,
            ).inheritIO().start().waitFor()
        }
        val bytes = video.readBytes()
        val key = "Bearer demo-key"
        val dashboard = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/media/demo/video") { ex ->
                if (ex.requestHeaders.getFirst("Authorization") != key) { ex.sendResponseHeaders(401, -1); ex.close(); return@createContext }
                ex.responseHeaders.add("Content-Type", "video/webm")
                ex.responseHeaders.add("Accept-Ranges", "bytes")
                val range = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.split("-")
                if (range != null) {
                    val from = range[0].toInt()
                    val to = range.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toInt() ?: (bytes.size - 1)
                    ex.responseHeaders.add("Content-Range", "bytes $from-$to/${bytes.size}")
                    ex.sendResponseHeaders(206, (to - from + 1).toLong())
                    ex.responseBody.use { it.write(bytes, from, to - from + 1) }
                } else {
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
            }
            start()
        }
        val base = "http://127.0.0.1:${dashboard.address.port}"
        val clip = Attachment("Screen recording", "Screen recording", 16f / 9f, url = null, video = VideoSource("$base/media/demo/video", "0:08", "video/webm"))
        val card = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().first { it.attachments.isEmpty() }.copy(attachments = listOf(clip))
        val state = FakeFeed.state.copy(openThread = null, sections = listOf(FakeFeed.state.sections.first().copy(label = "Today", items = listOf(card))))
        val scale = 2f
        fun record(name: String, preview: ScreenPreview, count: Int) = ImageComposeScene((1440 * scale).toInt(), (900 * scale).toInt(), Density(scale)) {
            CompositionLocalProvider(LocalMediaAuth provides MediaAuth(base, key)) {
                AgentTheme(dark = true) {
                    Box(Modifier.fillMaxSize()) { FeedScreen(state, preview = preview) }
                }
            }
        }.use { scene ->
            var t = 0L
            fun frame() = scene.render(t).also { t += 100_000_000L }
            repeat(5) { frame() }
            val frames = File(out, name).apply { mkdirs() }
            for (i in 0 until count) {
                val image = frame()
                File(frames, "f%03d.png".format(i)).writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                Thread.sleep(100)
            }
        }
        record("frames", ScreenPreview(inlineVideo = clip.video!!.url), 60)
        record("full-window", ScreenPreview(playing = clip.video), 30)
        dashboard.stop(0)
    }
}
