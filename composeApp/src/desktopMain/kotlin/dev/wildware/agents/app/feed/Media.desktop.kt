package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable
import dev.wildware.agents.app.feed.audio.AttachedSoundPlayer
import dev.wildware.agents.app.crash.CrashReporting
import dev.wildware.agents.app.feed.video.NativeVideo
import dev.wildware.agents.app.feed.video.NativeVideoSurface
import dev.wildware.agents.app.feed.video.VideoFrames
import dev.wildware.agents.app.feed.video.VideoTracker
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.kdroidfilter.composemediaplayer.DefaultVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import androidx.compose.runtime.rememberCoroutineScope

/** The app adds its own full-screen button over the video. */
actual val videoControlsHaveFullScreen: Boolean = false

/** Videos and sounds play in the app on a desktop too, streamed, never saved. */
actual val playsVideoInApp: Boolean = true

/**
 * What lets the desktop play media: one loopback proxy for the app's life (the player can't send the
 * device key itself), and whether this computer can play at all. Linux plays through GStreamer, which
 * most desktops have; without it, a video says what to install rather than failing silently.
 */
internal object DesktopPlayback {
    val proxy: LoopbackMediaProxy by lazy { LoopbackMediaProxy() }

    val problem: String? by lazy {
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("linux")) return@lazy null
        if (hasGStreamer()) null else "Playing needs GStreamer. Install gstreamer1.0-plugins-good (and -bad and -libav for more formats), then open the app again."
    }

    /** Whether the GStreamer libraries the Linux player links against are on this computer. */
    internal fun hasGStreamer(dirs: List<String> = libraryDirs()): Boolean =
        GStreamerLibs.all { lib -> dirs.any { File(it, lib).exists() } }

    private val GStreamerLibs = listOf("libgstreamer-1.0.so.0", "libgstapp-1.0.so.0")

    private fun libraryDirs(): List<String> =
        System.getenv("LD_LIBRARY_PATH").orEmpty().split(':').filter { it.isNotBlank() } + listOf(
            "/usr/lib/x86_64-linux-gnu", "/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/lib/aarch64-linux-gnu",
            "/usr/lib64", "/lib64", "/usr/lib", "/lib", "/usr/local/lib",
        )

    /** The words for a video no decoder here can play, which otherwise stays a silent black box. */
    const val NeedsCodec = "This video needs a codec: install gstreamer1.0-libav"

    /**
     * Why [video] can't play on this computer, before trying. On Linux an MP4 or MOV is H.264, which
     * only gstreamer1.0-libav (or the openh264 plugin) decodes; without one GStreamer fails quietly.
     */
    fun problemFor(
        video: VideoSource,
        linux: Boolean = isLinux,
        pluginDirs: List<String> = pluginDirs(),
        /** GStreamer itself missing: [problem]. */
        core: () -> String? = { problem },
    ): String? {
        if (!linux) return null
        core()?.let { return it }
        val h264 = video.mime.substringBefore(';').trim().lowercase() in H264Containers
        return if (h264 && !hasH264Decoder(pluginDirs)) NeedsCodec else null
    }

    /** Whether a GStreamer plugin that decodes H.264 is in one of [dirs]. */
    internal fun hasH264Decoder(dirs: List<String>): Boolean =
        H264Plugins.any { lib -> dirs.any { File(it, lib).exists() } }

    private val isLinux: Boolean get() = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    private val H264Containers = setOf("video/mp4", "video/quicktime", "video/x-m4v")
    private val H264Plugins = listOf("libgstlibav.so", "libgstopenh264.so")

    /** Where GStreamer looks for plugins: its own variables, then the usual system folders. */
    private fun pluginDirs(): List<String> =
        listOf("GST_PLUGIN_PATH", "GST_PLUGIN_SYSTEM_PATH", "GST_PLUGIN_PATH_1_0", "GST_PLUGIN_SYSTEM_PATH_1_0")
            .flatMap { System.getenv(it).orEmpty().split(':') }.filter { it.isNotBlank() } +
            listOf(
                "/usr/lib/x86_64-linux-gnu/gstreamer-1.0", "/usr/lib/aarch64-linux-gnu/gstreamer-1.0",
                "/usr/lib64/gstreamer-1.0", "/usr/lib/gstreamer-1.0", "/usr/local/lib/gstreamer-1.0",
            )

    /** The loopback address [item] plays from, with the key added on the way. */
    fun localUrl(item: Playable, auth: MediaAuth?): String = proxy.urlFor(item.url, auth.headersFor(item.url), item.fileName)
}

/**
 * A video playing in the app: inline in its card or filling the window. It streams through
 * [DesktopPlayback.proxy] with the app's own controls: play and pause, a scrubber with the time, and
 * mute. On macOS and Windows the computer's own engine plays it ([NativeVideo]); on Linux, GStreamer
 * through ComposeMediaPlayer.
 */
@Composable
actual fun InAppVideo(video: VideoSource, auth: MediaAuth?, modifier: Modifier) {
    if (NativeVideo.available) EngineVideo(video, auth, modifier) else LibraryVideo(video, auth, modifier)
}

/** A video on the computer's own engine, its frames drawn as the screen refreshes. */
@Composable
private fun EngineVideo(video: VideoSource, auth: MediaAuth?, modifier: Modifier) {
    val url = remember(video.url, auth) { DesktopPlayback.localUrl(video, auth) }
    val opened = remember(url) { runCatching { NativeVideo.open(url)!! } }
    val engine = opened.getOrNull()
    if (engine == null) {
        // The computer's engine wouldn't start: play it the older way rather than not at all.
        opened.exceptionOrNull()?.let { CrashReporting.note("video", "the video engine wouldn't open: ${it.message}") }
        LibraryVideo(video, auth, modifier)
        return
    }
    val frames = remember(engine) { VideoFrames() }
    val tracker = remember(engine) { VideoTracker() }
    DisposableEffect(engine) {
        // Carrying on where it was: the card's video going full screen, or back.
        VideoResume.take(video.url)?.let(engine::seek)
        engine.play()
        onDispose {
            val last = tracker.status
            if (!last.ended && last.positionSec > 1) VideoResume.keep(video.url, last.positionSec)
            engine.close()
            frames.close()
        }
    }
    val showing by remember(frames) { derivedStateOf { frames.image != null } }
    var muted by remember(engine) { mutableStateOf(false) }
    Box(modifier.background(Color.Black).semantics { contentDescription = "Video" }) {
        NativeVideoSurface(engine, frames, tracker, Modifier.fillMaxSize())
        val status = tracker.status
        when {
            status.error != null -> Text(status.error, Modifier.align(Alignment.Center).padding(16.dp), color = Color.White, style = AgentTheme.type.body)
            status.loading && !showing -> Text("Loading…", Modifier.align(Alignment.Center), color = Color.White.copy(alpha = 0.8f), style = AgentTheme.type.meta)
        }
        VideoControls(
            PlaybackControls(
                playing = status.playing || (status.loading && !status.ended && status.error == null),
                positionMs = (status.positionSec * 1000).toLong(),
                durationMs = (status.durationSec * 1000).toLong(),
                muted = muted,
                toggle = { if (status.playing) engine.pause() else engine.play() },
                seek = { ms -> engine.seek(ms / 1000.0) },
                toggleMute = {
                    muted = !muted
                    engine.volume = if (muted) 0f else 1f
                },
            ),
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        )
    }
}

@Composable
private fun VideoMessage(words: String, modifier: Modifier) {
    Box(modifier.background(Color.Black).semantics { contentDescription = "Video" }.padding(16.dp), contentAlignment = Alignment.Center) {
        Text(words, color = Color.White, style = AgentTheme.type.body)
    }
}

/**
 * Where a video stopped when its player closed, so the next player of the same video (the card's
 * going full screen, or back) carries on from there instead of the top. Only for a few seconds.
 */
internal object VideoResume {
    private var kept: Triple<String, Double, Long>? = null

    fun keep(url: String, seconds: Double) {
        kept = Triple(url, seconds, System.nanoTime())
    }

    fun take(url: String): Double? {
        val (keptUrl, seconds, at) = kept ?: return null
        kept = null
        return seconds.takeIf { keptUrl == url && System.nanoTime() - at < 10_000_000_000 }
    }
}

/** A video through ComposeMediaPlayer: how Linux plays, through GStreamer. */
@Composable
private fun LibraryVideo(video: VideoSource, auth: MediaAuth?, modifier: Modifier) {
    val problem = remember(video.url, video.mime) { DesktopPlayback.problemFor(video) }
    if (problem != null) {
        VideoMessage(problem, modifier)
        return
    }
    val state = remember(video.url) { DefaultVideoPlayerState() }
    val url = remember(video.url, auth) { DesktopPlayback.localUrl(video, auth) }
    LaunchedEffect(state, url) { state.openUri(url, InitialPlayerState.PLAY) }
    DisposableEffect(state) { onDispose { state.dispose() } }
    Box(modifier.background(Color.Black).semantics { contentDescription = "Video" }) {
        VideoPlayerSurface(state, Modifier.fillMaxSize(), ContentScale.Fit)
        when {
            state.error != null -> Text(
                playFailure(state.error),
                Modifier.align(Alignment.Center),
                color = Color.White,
                style = AgentTheme.type.body,
            )
            state.isLoading && !state.isPlaying -> Text(
                "Loading…",
                Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.8f),
                style = AgentTheme.type.meta,
            )
        }
        val duration = state.duration
        VideoControls(
            PlaybackControls(
                playing = state.isPlaying,
                positionMs = (state.currentTime * 1000).toLong(),
                durationMs = (duration * 1000).toLong(),
                muted = state.volume == 0f,
                toggle = { if (state.isPlaying) state.pause() else state.play() },
                seek = { ms -> if (duration > 0) state.seekTo((ms / 1000.0 / duration * 1000).toFloat().coerceIn(0f, 1000f)) },
                toggleMute = { state.volume = if (state.volume == 0f) 1f else 0f },
            ),
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        )
    }
}

/** What to say when a video won't play. On Linux a missing decoder is the usual reason, and it has a fix. */
private fun playFailure(error: VideoPlayerError?): String {
    val linux = System.getProperty("os.name").orEmpty().lowercase().contains("linux")
    return if (linux && error is VideoPlayerError.CodecError) {
        "This video needs a decoder GStreamer doesn't have. Install gstreamer1.0-libav (for MP4) and open the app again."
    } else {
        "Couldn't play this video."
    }
}

/** What the controls show and do, whichever player is behind them. */
private class PlaybackControls(
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val muted: Boolean,
    val toggle: () -> Unit,
    val seek: (Long) -> Unit,
    val toggleMute: () -> Unit,
)

/** Play and pause, where it has got to and how long it is, a scrubber, and mute: over the bottom of the picture. */
@Composable
private fun VideoControls(controls: PlaybackControls, modifier: Modifier) {
    val colors = AgentTheme.colors
    // While the thumb is held, it shows where it is going; the video seeks when it is let go.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val length = controls.durationMs
    val fraction = dragging ?: if (length > 0) (controls.positionMs.toFloat() / length).coerceIn(0f, 1f) else 0f
    val shownMs = dragging?.let { (it * length).toLong() } ?: controls.positionMs
    val position = MediaLabels.duration(shownMs) ?: "0:00"
    val total = MediaLabels.duration(length) ?: "0:00"
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundAction(if (controls.playing) Icons.Pause else Icons.Play, if (controls.playing) "Pause" else "Play", controls.toggle)
        Text(position, Modifier.padding(horizontal = 6.dp), color = Color.White, style = AgentTheme.type.meta)
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { controls.seek((it * length).toLong()) }
                dragging = null
            },
            valueRange = 0f..1f,
            enabled = length > 0,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Position"; stateDescription = "$position of $total" },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = colors.accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
        )
        Text(total, Modifier.padding(horizontal = 6.dp), color = Color.White, style = AgentTheme.type.meta)
        RoundAction(if (controls.muted) Icons.Muted else Icons.Volume, if (controls.muted) "Unmute" else "Mute", controls.toggleMute)
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).pressable(label, CircleShape, onClick = onClick).padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/**
 * Sounds play in the app, but not through the video player: on macOS and Windows it won't open a file
 * with no picture. Each is downloaded when first played and decoded by [AttachedSoundPlayer].
 */
@Composable
actual fun rememberSoundPlayer(sound: Sound, auth: MediaAuth?): SoundPlayer? {
    val scope = rememberCoroutineScope()
    val player = remember(sound.url, auth) {
        AttachedSoundPlayer(sound.durationMs, scope, load = { SoundDownloads.fetch(sound.url, auth.headersFor(sound.url)) })
    }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/** Fetches a sound whole: they are short, and a decoder wants to see all of it. */
internal object SoundDownloads {
    // The key goes with each request: the app's own server must prove it is this app's first.
    private val http by lazy { dev.wildware.agents.app.local.desktopOkHttp(okhttp3.OkHttpClient.Builder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)) }

    /** Bigger than this is no sound to play on a card (a 2-hour MP3 at 128 kbit/s is about 115 MB). */
    private const val MaxBytes = 128L * 1024 * 1024

    suspend fun fetch(url: String, headers: Map<String, String>): ByteArray = withContext(Dispatchers.IO) {
        val request = okhttp3.Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("the dashboard answered ${response.code}")
            val body = response.body ?: error("nothing came back")
            if (body.contentLength() > MaxBytes) error("too big to play")
            body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MaxBytes) error("too big to play")
                }
                out.toByteArray()
            }
        }
    }
}

@Composable
actual fun rememberPlatformMediaHandler(engine: () -> HttpClientEngine): MediaHandler = remember(engine) { DesktopMediaHandler(engine) }

/**
 * Saves files to Downloads under their own name, and does not open them: a file from an agent could
 * be a program, and opening it would run it. Videos and sounds play in the app ([InAppVideo],
 * [rememberSoundPlayer]); nothing is downloaded to play.
 */
class DesktopMediaHandler(
    private val engine: () -> HttpClientEngine,
    private val downloads: File = defaultDownloads(),
    /** Shows a saved file in its folder ([SavedFiles.reveal]). */
    private val reveal: (File) -> Boolean = { SavedFiles.reveal(it) },
    /** Puts something on the system clipboard. */
    private val clipboard: (Transferable) -> Unit = { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(it, null) },
    /** Opens a picture in the computer's own picture viewer ([SavedFiles.open]). */
    private val openFile: (File) -> Boolean = { SavedFiles.open(it) },
    /**
     * Where a picture opened elsewhere is kept while it is looked at: a folder of this app's own,
     * made new with a random name and only this user's to open, never a fixed name in the shared
     * temporary folder that another user on the computer could make first.
     */
    private val pictures: Lazy<File> = lazy {
        java.nio.file.Files.createTempDirectory("wildware-feed-pictures").toFile().also { dev.wildware.agents.app.session.privateTo(it, directory = true) }
    },
) : MediaHandler {
    override suspend fun open(item: Playable, auth: MediaAuth?): String = "Plays in the app"

    /** Where each file was saved, by its media id, for "Show in folder". */
    private val saved = java.util.concurrent.ConcurrentHashMap<String, File>()

    override suspend fun save(file: FileAttachment, auth: MediaAuth?): String {
        downloads.mkdirs()
        val target = freeName(downloads, safeFileName(file.name))
        download(file.url, auth, target)
        saved[file.id] = target
        // Downloads can be moved (say to D:\Downloads) and keeps its name; with none, files go to the home folder.
        val where = if (downloads.name.equals("Downloads", ignoreCase = true)) "Downloads" else downloads.path
        return "Saved to $where as ${target.name}"
    }

    override val showsSavedFiles: Boolean get() = true

    /** Never opens the file itself, only its folder: a file from an agent could be a program. */
    override fun showSaved(file: FileAttachment): Boolean {
        val target = saved[file.id]?.takeIf { it.exists() } ?: return false
        return reveal(target)
    }

    override val copiesImages: Boolean get() = true

    /**
     * Copies the picture itself, as an image any app can paste: decoded here (WebP too) and put on
     * the clipboard as an image, which the system offers as PNG.
     */
    override suspend fun copyImage(url: String, auth: MediaAuth?): String {
        val bytes = fetch(url, auth)
        val image = withContext(Dispatchers.Default) { PictureBytes.toBufferedImage(bytes) } ?: error("it isn't a picture this computer can read")
        clipboard(ImageSelection(image))
        return "Picture copied"
    }

    override val openPictureLabel: String get() = "Open in default app"

    /**
     * Opens the picture in the computer's own viewer. Only a picture, checked by decoding it, and
     * named for what it is (".png" and so on, never the name it was sent with), so the computer
     * hands it to its picture viewer and never runs it.
     */
    override suspend fun openPicture(url: String, mime: String, auth: MediaAuth?): String {
        val ext = PictureBytes.extension(mime) ?: error("only pictures open in another app")
        val bytes = fetch(url, auth)
        withContext(Dispatchers.Default) { PictureBytes.toBufferedImage(bytes) } ?: error("it isn't a picture this computer can read")
        val target = withContext(Dispatchers.IO) {
            val folder = pictures.value.apply { mkdirs() }
            File(folder, "picture-${Integer.toHexString(url.hashCode())}.$ext").also { it.writeBytes(bytes) }
        }
        return if (openFile(target)) "Opened in your picture viewer" else "Couldn't open a picture viewer"
    }

    private suspend fun fetch(url: String, auth: MediaAuth?): ByteArray = withContext(Dispatchers.IO) {
        HttpClient(engine()).use { http ->
            http.prepareGet(url) { auth.headersFor(url).forEach { (name, value) -> header(name, value) } }.execute { response ->
                if (!response.status.isSuccess()) error("the dashboard answered ${response.status.value}")
                val out = java.io.ByteArrayOutputStream()
                response.bodyAsChannel().toInputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > PictureBytes.MaxBytes) error("it is too big to copy")
                    }
                }
                out.toByteArray()
            }
        }
    }

    private suspend fun download(url: String, auth: MediaAuth?, target: File) = withContext(Dispatchers.IO) {
        val partial = File(target.parentFile, target.name + ".part")
        HttpClient(engine()).use { http ->
            http.prepareGet(url) { auth.headersFor(url).forEach { (name, value) -> header(name, value) } }.execute { response ->
                if (!response.status.isSuccess()) error("the dashboard answered ${response.status.value}")
                response.bodyAsChannel().toInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            }
        }
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        // It came from an agent, through the dashboard: the computer treats it as from the internet.
        markFromInternet(target)
    }

    companion object {
        /** Downloads as this computer has it, read once: on Windows it can be moved, and the registry says where. */
        private val downloadsFolder: File by lazy { SavedFiles.downloads() }

        fun defaultDownloads(): File = downloadsFolder

        /**
         * [name] with nothing that could climb out of the folder, upset a file system, or disguise
         * what the file is: text-direction overrides and other invisible characters go (they can
         * make "fdp.exe" read as ".pdf"), and a Windows device name ("CON", "NUL.txt") gets a "_".
         */
        fun safeFileName(name: String): String {
            // Only the last part of a path: "../../build.log" is saved as "build.log".
            val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
                .filterNot { Character.getType(it) == Character.FORMAT.toInt() || it.code in 0x7f..0x9f }
                .map { c -> if (c == '/' || c == '\\' || c == ':' || c.code < 32 || c in "<>\"|?*") '_' else c }
                .joinToString("")
                .trim()
                .trimStart('.')
                .trimEnd('.', ' ')
            val safe = if (WindowsDevice.matches(cleaned.substringBefore('.'))) "_$cleaned" else cleaned
            return safe.ifEmpty { "download" }.take(200)
        }

        private val WindowsDevice = Regex("^(con|prn|aux|nul|com[0-9]|lpt[0-9])$", RegexOption.IGNORE_CASE)

        /**
         * Marks [file] as downloaded from the internet, as a browser does, so the computer's own
         * checks run when it is opened: Windows' Mark of the Web, macOS's quarantine flag, and the
         * freedesktop origin on Linux. Best effort: a file system that can't hold the mark still
         * gets the file.
         */
        fun markFromInternet(file: File) {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            runCatching {
                when {
                    os.contains("win") -> java.io.FileOutputStream(file.path + ":Zone.Identifier").use {
                        it.write("[ZoneTransfer]\r\nZoneId=3\r\n".toByteArray())
                    }
                    os.contains("mac") -> {
                        val value = "0081;%08x;Wildware Feed;".format(System.currentTimeMillis() / 1000)
                        ProcessBuilder("xattr", "-w", "com.apple.quarantine", value, file.path).redirectErrorStream(true).start().waitFor()
                    }
                    else -> java.nio.file.Files.getFileAttributeView(file.toPath(), java.nio.file.attribute.UserDefinedFileAttributeView::class.java)
                        ?.write("xdg.origin.url", java.nio.charset.StandardCharsets.UTF_8.encode("wildware-feed"))
                }
            }
        }

        /** [name] in [folder], or "name (2).ext" and so on when that is taken. */
        fun freeName(folder: File, name: String): File {
            var candidate = File(folder, name)
            val stem = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
            var n = 2
            while (candidate.exists()) candidate = File(folder, "$stem ($n)$ext").also { n++ }
            return candidate
        }
    }
}

/** The four-way move cursor over a picture a drag moves. */
internal actual val panCursor: androidx.compose.ui.input.pointer.PointerIcon =
    androidx.compose.ui.input.pointer.PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR))

/** A picture on the clipboard, as an image. */
internal class ImageSelection(val image: java.awt.Image) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

    override fun getTransferData(flavor: DataFlavor): Any = if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
}

/** Reading a picture's bytes on a computer. */
internal object PictureBytes {
    /** Bigger than this is no picture to copy or open (a 100-megapixel PNG is about this). */
    const val MaxBytes = 200L * 1024 * 1024

    /** The file ending for a picture of [mime], or null when it is not a picture. */
    fun extension(mime: String): String? = when (mime.substringBefore(';').trim().lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        else -> null
    }

    /** The most pixels a picture to copy or open may have: a small file can claim a vast picture, and decoding it would run out of memory. */
    const val MaxPixels = 50_000_000L

    /**
     * [bytes] decoded (PNG, JPEG, GIF, WebP and more, through Skia) as an image for the clipboard;
     * null when it isn't one. One of more than [MaxPixels] is refused before it is decoded.
     */
    fun toBufferedImage(bytes: ByteArray): BufferedImage? {
        val image = runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
        val png = try {
            // Skia reads the size from the header; the pixels are only decoded when encoded below.
            if (image.width.toLong() * image.height.toLong() > MaxPixels) error("it is too big to copy or open")
            runCatching { image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes }.getOrNull()
        } finally {
            image.close()
        }
        return png?.let { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(it)) }
    }
}
