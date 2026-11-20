package dev.wildware.agents.app.feed.bench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.agents.app.feed.video.MediaEngineVideo
import dev.wildware.agents.app.feed.video.NativeVideo
import dev.wildware.agents.app.feed.video.Win
import dev.wildware.agents.app.feed.video.NativeVideoSurface
import dev.wildware.agents.app.feed.video.VideoFrames
import dev.wildware.agents.app.feed.video.VideoTracker
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.layout.ContentScale
import com.sun.net.httpserver.HttpServer
import dev.wildware.agents.app.feed.LoopbackMediaProxy
import io.github.kdroidfilter.composemediaplayer.DefaultVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import org.junit.Assume.assumeTrue
import java.awt.Dimension
import java.io.File
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.swing.SwingUtilities
import kotlin.test.Test

/**
 * Measures the desktop video player on real clips: how long until the first frame, how many frames
 * reach the screen, how often it stalls, how long a seek takes, and how much CPU it burns. Runs only
 * when VIDEO_BENCH_CLIPS names a folder of clips (the video-bench workflow makes them with ffmpeg);
 * prints one "BENCH {...}" line per run and writes them to build/video-bench.txt.
 */
class VideoBench {
    private val clips = System.getenv("VIDEO_BENCH_CLIPS")?.let(::File)
    private val out = File("build/video-bench.txt")
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("windows")

    /** One way of playing a video, so the same measurements can compare players. */
    interface Candidate {
        val name: String

        @Composable
        fun Surface(modifier: Modifier)

        fun open(url: String)
        fun seekTo(fraction: Float)

        /** Seconds into the video, as the player reports it. */
        val currentTime: Double

        /** A new object each time a new frame is shown; compared by identity. */
        fun frame(): Any?
        fun dispose()
    }

    /** The player the app ships today: ComposeMediaPlayer, its frames copied into Compose. */
    class MediaPlayerCandidate : Candidate {
        override val name = "composemediaplayer"
        private val state = DefaultVideoPlayerState()
        private val frames: State<*>? = run {
            val delegate = state.delegate
            generateSequence<Class<*>>(delegate.javaClass) { it.superclass }
                .mapNotNull { c -> runCatching { c.getDeclaredField("currentFrameState") }.getOrNull() }
                .firstOrNull()
                ?.apply { isAccessible = true }
                ?.get(delegate) as? State<*>
        }

        @Composable
        override fun Surface(modifier: Modifier) = VideoPlayerSurface(state, modifier, ContentScale.Fit)

        override fun open(url: String) = state.openUri(url, InitialPlayerState.PLAY)
        override fun seekTo(fraction: Float) = state.seekTo(fraction * 1000f)
        override val currentTime: Double get() = state.currentTime
        override fun frame(): Any? = frames?.value
        override fun dispose() = state.dispose()
    }

    /** The computer's own engine, its frames pulled at each screen refresh ([NativeVideo]). */
    class EngineCandidate : Candidate {
        override val name = "engine"
        private var video by mutableStateOf<NativeVideo?>(null)
        private val frames = VideoFrames()
        private val tracker = VideoTracker()

        @Composable
        override fun Surface(modifier: Modifier) {
            video?.let { NativeVideoSurface(it, frames, tracker, modifier) }
        }

        override fun open(url: String) = onEdt {
            video = NativeVideo.open(url)!!.also { it.play() }
        }

        override fun seekTo(fraction: Float) = onEdt {
            val v = video ?: return@onEdt
            v.seek(fraction * v.status().durationSec)
        }

        override val currentTime: Double get() = onEdt { video?.status()?.positionSec ?: 0.0 }
        override fun frame(): Any? = frames.image
        override fun dispose() = onEdt {
            video?.close()
            video = null
            frames.close()
        }
    }

    private val candidates: List<Pair<String, () -> Candidate>> =
        listOf<Pair<String, () -> Candidate>>("composemediaplayer" to { MediaPlayerCandidate() }) +
            if (NativeVideo.available) listOf("engine" to { EngineCandidate() }) else emptyList()

    @Test
    fun bench() {
        val dir = clips
        assumeTrue("set VIDEO_BENCH_CLIPS to run", dir != null && dir.isDirectory)
        out.parentFile.mkdirs()
        out.writeText("")
        val server = ClipServer(dir!!, delayMs = 80)
        val proxy = LoopbackMediaProxy()
        val runs = listOf(
            "1080p60.mp4" to 10_000L,
            // No sound: shows whether a slow start is the engine waiting on a sound device.
            "1080p60-silent.mp4" to 5_000L,
            "4k30.mp4" to 10_000L,
            "screen1440p.mp4" to 10_000L,
        )
        try {
            for ((name, make) in candidates) {
                for ((clip, ms) in runs) {
                    if (!File(dir, clip).exists()) continue
                    measurePlayback(make, clip, File(dir, clip).absolutePath, "file", ms)
                    measurePlayback(make, clip, proxy.urlFor(server.url(clip), mapOf("Authorization" to "Bearer bench"), clip), "proxy+80ms", ms)
                }
                if (name == "engine" && windows) {
                    // The same engine created in real-time mode, to compare how fast it starts.
                    MediaEngineVideo.createFlags = 4
                    for (clip in listOf("1080p60.mp4", "1080p60-silent.mp4")) {
                        measurePlayback(make, clip, File(dir, clip).absolutePath, "file,realtime", 5_000)
                    }
                    MediaEngineVideo.createFlags = 0
                }
                if (File(dir, "long1080p30.mp4").exists()) {
                    measureSeeks(make, proxy.urlFor(server.url("long1080p30.mp4"), emptyMap(), "long1080p30.mp4"), "proxy+80ms")
                    measureSeeks(make, File(dir, "long1080p30.mp4").absolutePath, "file")
                }
            }
        } finally {
            proxy.close()
            server.close()
        }
    }

    private class Window(candidate: Candidate) {
        val window: ComposeWindow = onEdt {
            ComposeWindow().apply {
                size = Dimension(1280, 760)
                setContent { Box(Modifier.fillMaxSize()) { candidate.Surface(Modifier.fillMaxSize()) } }
                isVisible = true
            }
        }

        fun close() = onEdt { window.dispose() }
    }

    private fun measurePlayback(make: () -> Candidate, clip: String, url: String, route: String, windowMs: Long) {
        val c = make()
        val w = Window(c)
        val cpu = CpuMeter()
        try {
            Thread.sleep(300)
            val t0 = System.nanoTime()
            c.open(url)
            val first = waitForFrame(c, 15_000)
            val startupMs = if (first == null) -1 else (System.nanoTime() - t0) / 1_000_000
            cpu.start()
            val gaps = mutableListOf<Long>()
            var last = c.frame()
            var lastAt = System.nanoTime()
            var frames = 0
            val end = System.nanoTime() + windowMs * 1_000_000
            while (System.nanoTime() < end) {
                val f = c.frame()
                if (f != null && f !== last) {
                    val now = System.nanoTime()
                    if (last != null) gaps += (now - lastAt) / 1_000
                    last = f
                    lastAt = now
                    frames++
                }
                Thread.sleep(0, 250_000)
            }
            val cpuPct = cpu.stop()
            val fps = frames * 1000.0 / windowMs
            val sorted = gaps.sorted()
            val p95 = sorted.getOrNull((sorted.size * 0.95).toInt())?.div(1000.0) ?: -1.0
            val stalls = gaps.count { it > 100_000 }
            report(
                "playback", c.name, clip, route,
                "startupMs" to startupMs, "fps" to "%.1f".format(fps), "p95FrameGapMs" to "%.1f".format(p95),
                "stallsOver100ms" to stalls, "cpuPct" to "%.0f".format(cpuPct), "videoSec" to "%.1f".format(c.currentTime),
            )
        } finally {
            c.dispose()
            w.close()
        }
    }

    private fun measureSeeks(make: () -> Candidate, url: String, route: String) {
        val c = make()
        val w = Window(c)
        try {
            Thread.sleep(300)
            c.open(url)
            if (waitForFrame(c, 15_000) == null) {
                report("seek", c.name, "long1080p30.mp4", route, "error" to "no first frame")
                return
            }
            Thread.sleep(2_000)
            for (target in listOf(0.5f, 0.9f, 0.1f, 0.75f)) {
                val before = c.frame()
                val t0 = System.nanoTime()
                c.seekTo(target)
                val wanted = 120.0 * target
                var ms = -1L
                val deadline = System.nanoTime() + 10_000_000_000
                while (System.nanoTime() < deadline) {
                    if (c.frame() !== before && kotlin.math.abs(c.currentTime - wanted) < 2.0) {
                        ms = (System.nanoTime() - t0) / 1_000_000
                        break
                    }
                    Thread.sleep(1)
                }
                report("seek", c.name, "long1080p30.mp4", route, "to" to "%.0f%%".format(target * 100), "ms" to ms, "at" to "%.1f".format(c.currentTime))
                Thread.sleep(1_000)
            }
        } finally {
            c.dispose()
            w.close()
        }
    }

    private fun waitForFrame(c: Candidate, timeoutMs: Long): Any? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            c.frame()?.let { return it }
            Thread.sleep(1)
        }
        return null
    }

    private fun report(kind: String, player: String, clip: String, route: String, vararg values: Pair<String, Any>) {
        val os = System.getProperty("os.name") + " " + System.getProperty("os.arch") + (Win.driver?.let { " d3d=$it" } ?: "")
        val line = "BENCH " + (listOf("kind" to kind, "player" to player, "clip" to clip, "route" to route, "os" to os) + values)
            .joinToString(" ") { (k, v) -> "$k=$v" }
        println(line)
        out.appendText(line + "\n")
    }

    /** The whole process's CPU over a span, as a percentage of one core. */
    private class CpuMeter {
        private val bean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        private var cpu0 = 0L
        private var wall0 = 0L

        fun start() {
            cpu0 = bean.processCpuTime
            wall0 = System.nanoTime()
        }

        fun stop(): Double = (bean.processCpuTime - cpu0) * 100.0 / (System.nanoTime() - wall0)
    }

    /** Serves the clips over HTTP with Range, each answer held back [delayMs] like a far-off server. */
    private class ClipServer(private val dir: File, private val delayMs: Long) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            createContext("/") { ex ->
                ex.use {
                    val file = File(dir, ex.requestURI.path.trimStart('/'))
                    if (!file.isFile) {
                        ex.sendResponseHeaders(404, -1)
                        return@use
                    }
                    Thread.sleep(delayMs)
                    val size = file.length()
                    val range = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.split('-')
                    val start = range?.getOrNull(0)?.toLongOrNull() ?: 0
                    val endIncl = range?.getOrNull(1)?.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                    ex.responseHeaders.add("Content-Type", "video/mp4")
                    ex.responseHeaders.add("Accept-Ranges", "bytes")
                    val length = endIncl - start + 1
                    if (range != null) {
                        ex.responseHeaders.add("Content-Range", "bytes $start-$endIncl/$size")
                        ex.sendResponseHeaders(206, length)
                    } else {
                        ex.sendResponseHeaders(200, length)
                    }
                    if (ex.requestMethod == "HEAD") return@use
                    runCatching {
                        RandomAccessFile(file, "r").use { raf ->
                            raf.seek(start)
                            val buf = ByteArray(64 * 1024)
                            var left = length
                            while (left > 0) {
                                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) break
                                ex.responseBody.write(buf, 0, n)
                                left -= n
                            }
                        }
                    }
                }
            }
            start()
        }

        fun url(name: String) = "http://127.0.0.1:${server.address.port}/$name"

        override fun close() = server.stop(0)
    }
}

internal fun <T> onEdt(block: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
}
