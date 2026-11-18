@file:OptIn(ExperimentalForeignApi::class)

package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFAudio.*
import platform.AVFoundation.*
import platform.AVKit.AVPlayerViewController
import platform.CoreMedia.*
import platform.Foundation.*
import platform.UIKit.*

/** Videos play in the app, full screen, in the system's own player. */
actual val playsVideoInApp: Boolean = true

/** A streamed asset, sending the device key to the dashboard (and to nobody else). */
private fun asset(url: String, auth: MediaAuth?): AVURLAsset {
    val headers = auth.headersFor(url)
    val options: Map<Any?, Any?>? = if (headers.isEmpty()) null else mapOf("AVURLAssetHTTPHeaderFieldsKey" to headers)
    return AVURLAsset.URLAssetWithURL(NSURL.URLWithString(url)!!, options)
}

/** The system's player: play, pause, scrub, full screen and AirPlay. */
@Composable
actual fun InAppVideo(video: VideoSource, auth: MediaAuth?, modifier: Modifier) {
    val player = remember(video.url) { AVPlayer.playerWithPlayerItem(AVPlayerItem.playerItemWithAsset(asset(video.url, auth))) }
    DisposableEffect(player) {
        playAloud()
        player.play()
        onDispose { player.pause() }
    }
    UIKitViewController(
        factory = {
            AVPlayerViewController().apply {
                this.player = player
                showsPlaybackControls = true
            }
        },
        modifier = modifier,
    )
}

/** Sound even with the ring switch on silent, as a video app plays. */
private fun playAloud() {
    AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
}

@Composable
actual fun rememberSoundPlayer(sound: Sound, auth: MediaAuth?): SoundPlayer? {
    val scope = rememberCoroutineScope()
    return remember(sound.url, auth) { IosSoundPlayer(sound, auth, scope) }
}

/** A sound streamed by [AVPlayer], made on the first play so a feed of sounds opens nothing until one is wanted. */
private class IosSoundPlayer(
    private val sound: Sound,
    private val auth: MediaAuth?,
    private val scope: CoroutineScope,
) : SoundPlayer {
    private val mutableState = MutableStateFlow(SoundState(durationMs = sound.durationMs ?: 0))
    override val state: StateFlow<SoundState> = mutableState.asStateFlow()

    private var player: AVPlayer? = null
    private var ticking: Job? = null

    private fun ensure(): AVPlayer = player ?: AVPlayer.playerWithPlayerItem(AVPlayerItem.playerItemWithAsset(asset(sound.url, auth))).also { player = it }

    override fun play() {
        playAloud()
        ensure().play()
        mutableState.value = mutableState.value.copy(playing = true, failed = false)
        tick()
    }

    override fun pause() {
        player?.pause()
        ticking?.cancel()
        mutableState.value = mutableState.value.copy(playing = false)
    }

    override fun seek(ms: Long) {
        ensure().seekToTime(CMTimeMakeWithSeconds(ms / 1000.0, 600))
        mutableState.value = mutableState.value.copy(positionMs = ms)
    }

    override fun release() {
        ticking?.cancel()
        player?.pause()
        player = null
    }

    /** Follows the player while it plays: where it is, how long it is, whether it ended or failed. */
    private fun tick() {
        ticking?.cancel()
        ticking = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val p = player ?: break
                val item = p.currentItem
                val position = CMTimeGetSeconds(p.currentTime())
                val length = item?.let { CMTimeGetSeconds(it.duration) } ?: Double.NaN
                val now = mutableState.value
                val failed = item?.status == AVPlayerItemStatusFailed
                val ended = length.isFinite() && position.isFinite() && position >= length - 0.05 && p.rate == 0f
                mutableState.value = now.copy(
                    positionMs = if (position.isFinite() && !ended) (position * 1000).toLong() else if (ended) 0 else now.positionMs,
                    durationMs = if (length.isFinite() && length > 0) (length * 1000).toLong() else now.durationMs,
                    playing = !failed && !ended && now.playing,
                    failed = failed,
                )
                if (failed || ended) {
                    if (ended) p.seekToTime(CMTimeMakeWithSeconds(0.0, 600))
                    break
                }
                delay(200)
            }
        }
    }
}

@Composable
actual fun rememberPlatformMediaHandler(engine: () -> HttpClientEngine): MediaHandler = remember(engine) { IosMediaHandler(engine) }

/**
 * Files are fetched with the device key into the app's temporary folder, then offered in the share
 * sheet: Save to Files, AirDrop, or open in another app. Nothing is opened by itself.
 */
private class IosMediaHandler(private val engine: () -> HttpClientEngine) : MediaHandler {
    override suspend fun open(item: Playable, auth: MediaAuth?): String = share(item.url, item.fileName, auth)

    override suspend fun save(file: FileAttachment, auth: MediaAuth?): String = share(file.url, file.name, auth)

    private suspend fun share(url: String, name: String, auth: MediaAuth?): String {
        val bytes = HttpClient(engine()).use { http ->
            val response = http.get(url) { auth.headersFor(url).forEach { (key, value) -> header(key, value) } }
            if (!response.status.isSuccess()) error("the dashboard answered ${response.status.value}")
            response.readRawBytes()
        }
        val folder = NSTemporaryDirectory() + "wildware-feed-files"
        NSFileManager.defaultManager.createDirectoryAtPath(folder, withIntermediateDirectories = true, attributes = null, error = null)
        val safe = name.substringAfterLast('/').map { c -> if (c.code < 32 || c in "\\:<>\"|?*") '_' else c }.joinToString("").trim().trimStart('.').ifEmpty { "download" }
        val path = "$folder/$safe"
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        if (!data.writeToFile(path, atomically = true)) error("couldn't keep it on this phone")
        withContext(Dispatchers.Main) {
            val sheet = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(path)), applicationActivities = null)
            val top = topController() ?: error("nothing on screen to show it from")
            // On iPad the sheet is a popover and needs somewhere to point.
            sheet.popoverPresentationController?.sourceView = top.view
            top.presentViewController(sheet, animated = true, completion = null)
        }
        return "Choose where to keep it"
    }

    private fun topController(): UIViewController? {
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }
}
