package dev.wildware.agents.app.feed

import android.app.DownloadManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app adds its own full-screen button over the video. */
actual val videoControlsHaveFullScreen: Boolean = false

/** Videos play in the app, in the card or full screen, with the system's own controls. */
actual val playsVideoInApp: Boolean = true

/** The system's video view and its play, pause and scrub bar, streaming with the device key. */
@Composable
actual fun InAppVideo(video: VideoSource, auth: MediaAuth?, modifier: Modifier) {
    val headers = auth.headersFor(video.url)
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                val controls = MediaController(context)
                controls.setAnchorView(this)
                setMediaController(controls)
                setOnPreparedListener {
                    start()
                    controls.show()
                }
                setVideoURI(Uri.parse(video.url), headers)
            }
        },
        modifier = modifier,
        onRelease = { it.stopPlayback() },
    )
}

@Composable
actual fun rememberSoundPlayer(sound: Sound, auth: MediaAuth?): SoundPlayer? {
    val context = LocalContext.current.applicationContext
    return remember(sound.url, auth) { AndroidSoundPlayer(context, sound, auth.headersFor(sound.url)) }
}

/**
 * A sound streamed by the system's [MediaPlayer], prepared on the first play so a feed full of
 * sounds opens no connections until one is wanted.
 */
private class AndroidSoundPlayer(
    private val context: Context,
    private val sound: Sound,
    private val headers: Map<String, String>,
) : SoundPlayer {
    private val mutableState = MutableStateFlow(SoundState(durationMs = sound.durationMs ?: 0))
    override val state: StateFlow<SoundState> = mutableState.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var prepared = false
    private var wantsPlay = false

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (prepared) mutableState.value = mutableState.value.copy(positionMs = p.currentPosition.toLong())
            if (p.isPlaying) main.postDelayed(this, 200)
        }
    }

    private fun ensure(): MediaPlayer = player ?: MediaPlayer().also { p ->
        player = p
        p.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        p.setOnPreparedListener {
            prepared = true
            mutableState.value = mutableState.value.copy(durationMs = it.duration.toLong().takeIf { d -> d > 0 } ?: mutableState.value.durationMs)
            if (wantsPlay) start()
        }
        p.setOnCompletionListener {
            mutableState.value = mutableState.value.copy(playing = false, positionMs = 0)
            it.seekTo(0)
        }
        p.setOnErrorListener { _, _, _ ->
            mutableState.value = mutableState.value.copy(playing = false, failed = true)
            true
        }
        p.setDataSource(context, Uri.parse(sound.url), headers)
        p.prepareAsync()
    }

    private fun start() {
        val p = player ?: return
        p.start()
        mutableState.value = mutableState.value.copy(playing = true, failed = false)
        main.removeCallbacks(tick)
        main.post(tick)
    }

    override fun play() {
        wantsPlay = true
        ensure()
        // Until it is ready the button already shows pause; it starts the moment it can.
        if (prepared) start() else mutableState.value = mutableState.value.copy(playing = true)
    }

    override fun pause() {
        wantsPlay = false
        if (prepared) player?.pause()
        main.removeCallbacks(tick)
        mutableState.value = mutableState.value.copy(playing = false)
    }

    override fun seek(ms: Long) {
        if (prepared) player?.seekTo(ms.toInt())
        mutableState.value = mutableState.value.copy(positionMs = ms)
    }

    override fun release() {
        main.removeCallbacks(tick)
        player?.release()
        player = null
        prepared = false
    }
}

@Composable
actual fun rememberPlatformMediaHandler(engine: () -> HttpClientEngine): MediaHandler {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidMediaHandler(context) }
}

/**
 * Files go through the system's download manager: it fetches with the device key, shows its own
 * progress and finished notification, and opening the file from there uses the system's choice
 * of app. Nothing here opens a file by itself.
 */
private class AndroidMediaHandler(private val context: Context) : MediaHandler {
    override suspend fun open(item: Playable, auth: MediaAuth?): String = enqueue(item.url, item.fileName, null, auth)

    override suspend fun save(file: FileAttachment, auth: MediaAuth?): String = enqueue(file.url, file.name, file.mime, auth)

    /** A picture goes to Pictures and a video to Movies, where the gallery finds them. */
    override suspend fun saveMedia(file: FileAttachment, auth: MediaAuth?): String =
        enqueue(file.url, file.name, file.mime, auth, folder = mediaFolder(file.mime))

    private fun enqueue(url: String, name: String, mime: String?, auth: MediaAuth?, folder: String = Environment.DIRECTORY_DOWNLOADS): String {
        val safe = name.substringAfterLast('/').map { c -> if (c.code < 32 || c in "\\:<>\"|?*") '_' else c }.joinToString("").trim().trimStart('.').ifEmpty { "download" }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(safe)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        auth.headersFor(url).forEach { (key, value) -> request.addRequestHeader(key, value) }
        if (mime != null) request.setMimeType(mime)
        // Before Android 10, the public Downloads folder needs a storage permission the app doesn't ask for.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(folder, safe)
        } else {
            request.setDestinationInExternalFilesDir(context, folder, safe)
        }
        context.getSystemService(DownloadManager::class.java).enqueue(request)
        return "Downloading. It shows in your notifications when it's done."
    }
}

/** Where the system keeps a saved picture or video: Pictures, Movies, else Downloads. */
internal fun mediaFolder(mime: String): String = when {
    mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
    mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
    else -> Environment.DIRECTORY_DOWNLOADS
}
