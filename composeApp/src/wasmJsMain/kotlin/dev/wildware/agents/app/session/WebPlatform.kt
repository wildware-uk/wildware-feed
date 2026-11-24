package dev.wildware.agents.app.session

import dev.wildware.agents.OutboxStorage
import dev.wildware.agents.TextStore
import dev.wildware.agents.app.feed.BrowserFilePicker
import io.ktor.client.engine.js.Js
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.w3c.dom.get
import org.w3c.dom.set
import kotlin.io.encoding.Base64

/**
 * The browser's platform. The app is served by the dashboard it talks to, so that is the server,
 * and the session is an HttpOnly cookie the page never sees: what this page keeps in local storage
 * (which dashboard, the device's name, read marks, the feed as it last was) is nothing a script
 * could sign in with.
 */
fun webPlatform(): AppPlatform {
    val origin = window.location.origin
    val foreground = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    // Back to a tab left in the background: its stream may have been throttled or dropped.
    window.document.addEventListener("visibilitychange") {
        if (visibilityState() == "visible") foreground.tryEmit(Unit)
    }
    val outbox = WebOutboxStorage("feed.outbox")
    val keys = listOf("feed.readMarks", "feed.feedCache", "feed.cardFolds", "feed.postTarget")
    return AppPlatform(
        sessions = WebSessionStore("feed.session"),
        readMarks = LocalStorageText("feed.readMarks"),
        outbox = outbox,
        httpEngine = { Js.create() },
        deviceName = browserName(),
        imageCacheDir = null,
        clearLocalData = {
            outbox.clear()
            keys.forEach { runCatching { localStorage.removeItem(it) } }
        },
        settings = LocalStorageText("feed.settings"),
        filePicker = BrowserFilePicker(),
        foreground = foreground,
        feedCache = LocalStorageText("feed.feedCache"),
        cardFolds = LocalStorageText("feed.cardFolds"),
        postTarget = LocalStorageText("feed.postTarget"),
        homeServer = origin,
        sounds = dev.wildware.agents.app.sound.SoundBoard(dev.wildware.agents.app.sound.WebSoundPlayer(), LocalStorageText("feed.sounds")),
        // No key in it (the cookie is the key): this tab's team, and other teams' addresses opened from here.
        teamList = LocalStorageText("feed.teams"),
        // Browser tests set "feed.tour" to "off" before the page loads, so the tour never covers what they click.
        guidedTour = runCatching { localStorage["feed.tour"] }.getOrNull() != "off",
    )
}

private fun visibilityState(): String = js("document.visibilityState")

/** "Chrome on Mac", from the browser's own description of itself, for the device list. */
internal fun browserName(): String {
    val agent = window.navigator.userAgent
    val browser = when {
        "Edg/" in agent -> "Edge"
        "Firefox/" in agent -> "Firefox"
        "Chrome/" in agent -> "Chrome"
        "Safari/" in agent -> "Safari"
        else -> "Browser"
    }
    val system = when {
        "iPhone" in agent || "iPad" in agent -> "iOS"
        "Android" in agent -> "Android"
        "Mac OS X" in agent || "Macintosh" in agent -> "Mac"
        "Windows" in agent -> "Windows"
        "Linux" in agent || "X11" in agent -> "Linux"
        else -> null
    }
    return if (system == null) "$browser (web)" else "$browser on $system"
}

/** One text value in local storage. A browser that refuses storage (private mode, full) just forgets. */
class LocalStorageText(private val key: String) : TextStore {
    override fun read(): String? = runCatching { localStorage[key] }.getOrNull()
    override fun write(text: String) {
        runCatching { localStorage[key] = text }
    }
}

/**
 * Which dashboard this browser is signed in to. It holds no key: the cookie is the key, and only
 * the server can read it. Forgetting the session also asks the server to sign this browser out,
 * which revokes the device and clears the cookie.
 */
class WebSessionStore(private val key: String) : SessionStore {
    override fun load(): SavedSession? = runCatching {
        localStorage[key]?.let { SessionJson.decodeFromString(SavedSession.serializer(), it) }
    }.getOrNull()

    override fun save(session: SavedSession) {
        runCatching { localStorage[key] = SessionJson.encodeToString(SavedSession.serializer(), session) }
    }

    override fun clear() {
        runCatching { localStorage.removeItem(key) }
        signOutCookie()
    }
}

/** `POST /api/devices/sign-out`, sent even as the page closes. */
private fun signOutCookie(): Unit = js(
    """{ fetch('/api/devices/sign-out', { method: 'POST', credentials: 'same-origin', keepalive: true }).catch(function () {}); }""",
)

/**
 * The outbox in local storage. Files waiting to go up are kept there too while they fit (a
 * browser gives a page a few megabytes); a bigger one waits in memory, so it still sends unless
 * the tab is closed first.
 */
class WebOutboxStorage(private val key: String) : OutboxStorage {
    private val memory = mutableMapOf<String, ByteArray>()

    override fun load(): String = runCatching { localStorage[key] }.getOrNull().orEmpty()

    override fun save(text: String) {
        runCatching { localStorage[key] = text }
    }

    override fun writeFile(name: String, bytes: ByteArray) {
        memory[name] = bytes
        runCatching { localStorage["$key.file.$name"] = Base64.encode(bytes) }
    }

    override fun readFile(name: String): ByteArray? = memory[name]
        ?: runCatching { localStorage["$key.file.$name"]?.let { Base64.decode(it) } }.getOrNull()

    override fun deleteFile(name: String) {
        memory.remove(name)
        runCatching { localStorage.removeItem("$key.file.$name") }
    }

    fun clear() {
        memory.clear()
        runCatching {
            val prefix = "$key.file."
            val names = (0 until localStorage.length).mapNotNull { localStorage.key(it) }.filter { it == key || it.startsWith(prefix) }
            names.forEach { localStorage.removeItem(it) }
        }
    }
}
