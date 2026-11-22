package dev.wildware.agents.app.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.WebElementView
import io.ktor.client.engine.HttpClientEngine
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.w3c.dom.HTMLAnchorElement
import kotlin.js.Promise
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.HTMLAudioElement
import org.w3c.dom.HTMLVideoElement

/*
 * The web app is served by the dashboard itself and signs in with a cookie, so the browser sends
 * the owner's session with every media request on its own: an <img>, <video> or download link to
 * the dashboard just works, and no key is ever handed to a page element.
 */

/** A browser's video controls have their own full-screen button. */
actual val videoControlsHaveFullScreen: Boolean = true

/**
 * Videos are handed to [BrowserMediaHandler], which plays them in a `<video>` over the whole page.
 * Compose's own way of placing a page element in the app ([InAppVideo]) misdraws the app around it,
 * in a card or full screen, so the app does not use it here.
 */
actual val playsVideoInApp: Boolean = false

/** Not used on the web ([playsVideoInApp] is false); a plain `<video>` should anything ask for one. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun InAppVideo(video: VideoSource, auth: MediaAuth?, modifier: Modifier) {
    WebElementView(
        factory = { videoElement(video.url) },
        modifier = modifier,
        onRelease = { it.stop() },
    )
}

private fun videoElement(url: String): HTMLVideoElement = (document.createElement("video") as HTMLVideoElement).apply {
    src = url
    controls = true
    autoplay = true
    setAttribute("playsinline", "")
    style.width = "100%"
    style.height = "100%"
    style.backgroundColor = "black"
}

private fun HTMLVideoElement.stop() {
    pause()
    removeAttribute("src")
    load()
}

/**
 * A video over the whole page, outside the app's canvas: the browser's own player, a close button,
 * and Escape or a click beside the video to close it.
 */
internal object VideoLayer {
    private var open: (() -> Unit)? = null

    /** [save]: the original to offer a Save link for; null shows none. */
    fun show(url: String, save: FileAttachment? = null) {
        open?.invoke()
        val layer = document.createElement("div") as HTMLDivElement
        layer.setAttribute("role", "dialog")
        layer.setAttribute("aria-label", "Video")
        layer.style.cssText = "position:fixed;inset:0;z-index:10;background:rgba(0,0,0,.92);display:flex;align-items:center;justify-content:center"
        val video = videoElement(url)
        video.style.cssText = "max-width:100%;max-height:100%;width:100%;height:100%;object-fit:contain;background:black"
        val close = document.createElement("button") as HTMLButtonElement
        close.textContent = "\u2715"
        close.setAttribute("aria-label", "Close video")
        close.style.cssText = "position:fixed;top:12px;left:12px;z-index:11;width:40px;height:40px;border-radius:20px;border:0;" +
            "background:rgba(0,0,0,.6);color:white;font:18px system-ui,sans-serif;cursor:pointer"
        layer.appendChild(video)
        layer.appendChild(close)
        // The original, as a download: the dashboard sends it as an attachment under its own name.
        if (save != null) {
            val link = document.createElement("a") as HTMLAnchorElement
            link.href = save.url
            link.download = save.name
            link.rel = "noopener"
            link.textContent = "Save"
            link.setAttribute("aria-label", "Save video ${save.name}")
            link.style.cssText = "position:fixed;top:12px;right:12px;z-index:11;height:40px;line-height:40px;padding:0 16px;border-radius:20px;" +
                "background:rgba(0,0,0,.6);color:white;font:600 14px system-ui,sans-serif;text-decoration:none"
            layer.appendChild(link)
        }
        document.body?.appendChild(layer)
        val escape: (Event) -> Unit = { e ->
            if ((e as KeyboardEvent).key == "Escape") {
                e.stopPropagation()
                e.preventDefault()
                open?.invoke()
            }
        }
        // Capture, so Escape closes the video before the app hears it.
        document.addEventListener("keydown", escape, true)
        open = {
            open = null
            document.removeEventListener("keydown", escape, true)
            video.stop()
            layer.remove()
        }
        close.addEventListener("click") { open?.invoke() }
        layer.addEventListener("click") { e -> if (e.target == layer) open?.invoke() }
        close.focus()
    }
}

@Composable
actual fun rememberSoundPlayer(sound: Sound, auth: MediaAuth?): SoundPlayer? = remember(sound.url) { WebSoundPlayer(sound) }

/** A sound in an `<audio>` element, made on the first play so a feed of sounds loads nothing until one is wanted. */
private class WebSoundPlayer(private val sound: Sound) : SoundPlayer {
    private val mutableState = MutableStateFlow(SoundState(durationMs = sound.durationMs ?: 0))
    override val state: StateFlow<SoundState> = mutableState.asStateFlow()

    private var audio: HTMLAudioElement? = null

    private fun ensure(): HTMLAudioElement = audio ?: (document.createElement("audio") as HTMLAudioElement).also { a ->
        a.preload = "auto"
        a.src = sound.url
        fun update(playing: Boolean = mutableState.value.playing, failed: Boolean = false) {
            val length = a.duration
            mutableState.value = mutableState.value.copy(
                positionMs = (a.currentTime * 1000).toLong(),
                durationMs = if (length.isFinite() && length > 0) (length * 1000).toLong() else mutableState.value.durationMs,
                playing = playing,
                failed = failed,
            )
        }
        a.addEventListener("timeupdate") { update() }
        a.addEventListener("loadedmetadata") { update() }
        a.addEventListener("ended") {
            a.currentTime = 0.0
            update(playing = false)
        }
        a.addEventListener("error") { update(playing = false, failed = true) }
        audio = a
    }

    override fun play() {
        mutableState.value = mutableState.value.copy(playing = true, failed = false)
        ensure().play()
    }

    override fun pause() {
        audio?.pause()
        mutableState.value = mutableState.value.copy(playing = false)
    }

    override fun seek(ms: Long) {
        ensure().currentTime = ms / 1000.0
        mutableState.value = mutableState.value.copy(positionMs = ms)
    }

    override fun release() {
        audio?.let {
            it.pause()
            it.removeAttribute("src")
            it.load()
        }
        audio = null
    }
}

@Composable
actual fun rememberPlatformMediaHandler(engine: () -> HttpClientEngine): MediaHandler = remember { BrowserMediaHandler }

/** Plays a video over the page, opens other media in a new tab, and downloads files through the browser. */
private object BrowserMediaHandler : MediaHandler {
    override suspend fun open(item: Playable, auth: MediaAuth?): String {
        if (item is VideoSource) {
            VideoLayer.show(item.url, item.original)
            return "Esc closes the video"
        }
        window.open(item.url, "_blank", "noopener")
        return "Opened in a new tab"
    }

    override suspend fun save(file: FileAttachment, auth: MediaAuth?): String {
        val link = document.createElement("a") as HTMLAnchorElement
        link.href = file.url
        link.download = file.name
        link.rel = "noopener"
        document.body?.appendChild(link)
        link.click()
        link.remove()
        return "Downloading ${file.name}"
    }

    /** Where the browser has ClipboardItem (Chrome, Edge, Safari, recent Firefox); elsewhere the button is hidden. */
    override val copiesImages: Boolean by lazy { clipboardTakesImages() }

    override suspend fun copyImage(url: String, auth: MediaAuth?): String {
        writeImageToClipboard(url).await<JsAny?>()
        return "Picture copied"
    }

    override val openPictureLabel: String get() = "Open in new tab"

    override suspend fun openPicture(url: String, mime: String, auth: MediaAuth?): String {
        window.open(url, "_blank", "noopener")
        return "Opened in a new tab"
    }
}

private fun clipboardTakesImages(): Boolean =
    js("typeof ClipboardItem !== 'undefined' && !!navigator.clipboard && typeof navigator.clipboard.write === 'function'")

/**
 * Puts the picture at [url] on the clipboard as a PNG. The item is made at once, with the picture
 * still loading inside it, because Safari only lets a click write to the clipboard straight away.
 * A picture that isn't a PNG is redrawn as one, which every browser can paste.
 */
private fun writeImageToClipboard(url: String): Promise<JsAny?> = js(
    """navigator.clipboard.write([new ClipboardItem({ 'image/png': fetch(url, { credentials: 'include' })
        .then(function (r) { if (!r.ok) throw new Error('the dashboard answered ' + r.status); return r.blob(); })
        .then(function (blob) {
            if (blob.type === 'image/png') return blob;
            return createImageBitmap(blob).then(function (bitmap) {
                var canvas = document.createElement('canvas');
                canvas.width = bitmap.width;
                canvas.height = bitmap.height;
                canvas.getContext('2d').drawImage(bitmap, 0, 0);
                return new Promise(function (done, failed) {
                    canvas.toBlob(function (png) { if (png) done(png); else failed(new Error('it could not be made a PNG')); }, 'image/png');
                });
            });
        }) })])"""
)

/** No grab hand here: the pointing hand says the picture can be moved. */
internal actual val panCursor: androidx.compose.ui.input.pointer.PointerIcon = androidx.compose.ui.input.pointer.PointerIcon.Hand
