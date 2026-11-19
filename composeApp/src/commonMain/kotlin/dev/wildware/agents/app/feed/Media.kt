package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Something that plays: a video or a sound. [fileName] is what to call it when it is handed to another app. */
interface Playable {
    val url: String
    val fileName: String
}

/** A video on a card. [url] is the web-playable transcode when there is one, else the original. */
@Immutable
data class VideoSource(
    override val url: String,
    /** "0:42", or null when the length is unknown. */
    val duration: String?,
    val mime: String = "video/mp4",
) : Playable {
    override val fileName: String get() = "video.${MediaLabels.extension(mime, "mp4")}"
}

/** A sound on a card, played where it sits. */
@Immutable
data class Sound(
    val id: String,
    override val url: String,
    val durationMs: Long?,
    val mime: String,
    /** Removed by the owner's media retention after this many days: it shows a placeholder and never plays. */
    val removedAfterDays: Int? = null,
) : Playable {
    override val fileName: String get() = "sound.${MediaLabels.extension(mime, "mp3")}"
}

/** A file on a card: a download, never something to show. */
@Immutable
data class FileAttachment(
    val id: String,
    val url: String,
    val name: String,
    /** "1.5 KB" */
    val size: String,
    val mime: String,
    /** Removed by the owner's media retention after this many days: it shows a placeholder and never downloads. */
    val removedAfterDays: Int? = null,
)

/** How long a sound or video is and how big a file is, said the way the website says it. */
object MediaLabels {
    /** "m:ss", or null when nothing measured it. */
    fun duration(ms: Long?): String? {
        if (ms == null || ms <= 0) return null
        val total = (ms + 500) / 1000
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    /** "512 B", "1.5 KB", "15 MB"; "empty" for nothing at all. */
    fun size(bytes: Long): String {
        if (bytes <= 0) return "empty"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val shown = if (value < 10) oneDecimal(value) else kotlin.math.round(value).toLong().toString()
        return "$shown ${units[unit]}"
    }

    private fun oneDecimal(value: Double): String {
        val tenths = kotlin.math.round(value * 10).toLong()
        return "${tenths / 10}.${tenths % 10}"
    }

    /** The name a file shows, or "Untitled file" when nobody wrote one down. */
    fun fileName(name: String?): String = name?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled file"

    /** A file extension for [mime], so the app it is handed to knows what it is. */
    fun extension(mime: String, fallback: String): String = when (mime.substringBefore(';').trim().lowercase()) {
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        "video/webm", "audio/webm" -> "webm"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/x-m4a", "audio/aac" -> "m4a"
        "audio/ogg" -> "ogg"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/flac" -> "flac"
        else -> fallback
    }
}

/**
 * What the device does with media the app hands over: playing it in the system's own player, or
 * keeping a file. Each returns a short line to show the owner ("Saved to Downloads").
 */
interface MediaHandler {
    suspend fun open(item: Playable, auth: MediaAuth?): String

    suspend fun save(file: FileAttachment, auth: MediaAuth?): String
}

val LocalMediaHandler = staticCompositionLocalOf<MediaHandler?> { null }

/** The device's own [MediaHandler], downloading with the app's network engine. */
@Composable
expect fun rememberPlatformMediaHandler(engine: () -> HttpClientEngine): MediaHandler

/** Whether videos play inside the app (phones) or are handed to the system's player (desktop). */
expect val playsVideoInApp: Boolean

/** Whether the platform's own video controls already have a full-screen button (the iPhone's do). */
expect val videoControlsHaveFullScreen: Boolean

/** A video playing with the platform's own controls: play, scrub and full screen. Streamed, never saved. */
@Composable
expect fun InAppVideo(video: VideoSource, auth: MediaAuth?, modifier: Modifier)

/** Where a sound has got to. */
@Immutable
data class SoundState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val failed: Boolean = false,
)

/** A sound playing in the app. */
interface SoundPlayer {
    val state: StateFlow<SoundState>
    fun play()
    fun pause()
    fun seek(ms: Long)
    fun release()
}

/** A player for [sound], or null where sounds are handed to the system's player instead (desktop). */
@Composable
expect fun rememberSoundPlayer(sound: Sound, auth: MediaAuth?): SoundPlayer?

/** The one sound playing: starting another pauses it, as on the website. */
internal object NowPlaying {
    val id = MutableStateFlow<String?>(null)
}

/** The headers a media download needs: the device key, for the dashboard's own media only. */
fun MediaAuth?.headersFor(url: String): Map<String, String> {
    val auth = this ?: return emptyMap()
    val key = auth.authorization ?: return emptyMap()
    return if (url.startsWith(auth.serverUrl.trimEnd('/'))) mapOf("Authorization" to key) else emptyMap()
}
