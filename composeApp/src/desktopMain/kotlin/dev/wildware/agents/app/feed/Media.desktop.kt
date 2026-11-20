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
 * [DesktopPlayback.proxy] and draws its own controls: play and pause, a scrubber with the time,
 * and mute.
 */
@Composable
actual fun InAppVideo(video: VideoSource, auth: MediaAuth?, modifier: Modifier) {
    val problem = remember(video.url, video.mime) { DesktopPlayback.problemFor(video) }
    if (problem != null) {
        Box(modifier.background(Color.Black).semantics { contentDescription = "Video" }.padding(16.dp), contentAlignment = Alignment.Center) {
            Text(problem, color = Color.White, style = AgentTheme.type.body)
        }
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
        VideoControls(state, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
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

/** Play and pause, where it has got to and how long it is, a scrubber, and mute: over the bottom of the picture. */
@Composable
private fun VideoControls(state: VideoPlayerState, modifier: Modifier) {
    val colors = AgentTheme.colors
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundAction(if (state.isPlaying) Icons.Pause else Icons.Play, if (state.isPlaying) "Pause" else "Play") {
            if (state.isPlaying) state.pause() else state.play()
        }
        Text(state.positionText, Modifier.padding(horizontal = 6.dp), color = Color.White, style = AgentTheme.type.meta)
        Slider(
            value = state.sliderPos,
            onValueChange = {
                state.userDragging = true
                state.sliderPos = it
            },
            onValueChangeFinished = {
                state.userDragging = false
                state.seekTo(state.sliderPos)
            },
            valueRange = 0f..1000f,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Position"; stateDescription = "${state.positionText} of ${state.durationText}" },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = colors.accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
        )
        Text(state.durationText, Modifier.padding(horizontal = 6.dp), color = Color.White, style = AgentTheme.type.meta)
        val muted = state.volume == 0f
        RoundAction(if (muted) Icons.Muted else Icons.Volume, if (muted) "Unmute" else "Mute") {
            state.volume = if (muted) 1f else 0f
        }
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
    private val http by lazy { okhttp3.OkHttpClient.Builder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build() }

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
    }

    companion object {
        /** Downloads as this computer has it, read once: on Windows it can be moved, and the registry says where. */
        private val downloadsFolder: File by lazy { SavedFiles.downloads() }

        fun defaultDownloads(): File = downloadsFolder

        /** [name] with nothing that could climb out of the folder or upset a file system. */
        fun safeFileName(name: String): String {
            // Only the last part of a path: "../../build.log" is saved as "build.log".
            val cleaned = name.substringAfterLast('/').substringAfterLast('\\').map { c -> if (c == '/' || c == '\\' || c == ':' || c.code < 32 || c in "<>\"|?*") '_' else c }
                .joinToString("")
                .trim()
                .trimStart('.')
            return cleaned.ifEmpty { "download" }.take(200)
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
