package dev.wildware.agents.app.desktop

import dev.wildware.agents.app.ProductName

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.concurrent.thread

/** Puts a [DesktopNotice] on screen. [onClick] runs when the owner clicks it, where the system says so. */
fun interface Notifier {
    fun show(notice: DesktopNotice, onClick: () -> Unit)

    /** The keys of the notices still on screen that this notifier can take down; none where the system can't. */
    fun shown(): Set<String> = emptySet()

    /** Takes these notices down, where the system allows it. */
    fun remove(keys: Set<String>) {}
}

/**
 * Linux: `notify-send` from libnotify, which every desktop's notification server understands.
 * With `--action` and `--wait` (libnotify 0.7.9 and later) it prints the action when the
 * notification is clicked, so a click opens the card. An older notify-send, or none, falls back
 * to [fallback] from then on.
 */
class NotifySendNotifier(
    private val iconFile: File,
    private val fallback: Notifier,
    private val start: (List<String>) -> Process? = { runCatching { ProcessBuilder(it).redirectErrorStream(true).start() }.getOrNull() },
    /** Closes one notification: notify-send closes the one it waits on when interrupted. */
    private val interrupt: (Process) -> Unit = { process ->
        runCatching { ProcessBuilder("kill", "-INT", process.pid().toString()).start() }
    },
) : Notifier {
    @Volatile
    private var works = true

    /** Each notification still on screen, by key: `--wait` keeps its notify-send running until it closes. */
    private val open = ConcurrentHashMap<String, Process>()

    override fun show(notice: DesktopNotice, onClick: () -> Unit) {
        if (!works) return fallback.show(notice, onClick)
        val command = command(notice, iconFile.takeIf { it.exists() }?.path)
        thread(isDaemon = true, name = "notify-send") {
            val process = start(command)
            if (process == null) {
                works = false
                fallback.show(notice, onClick)
                return@thread
            }
            open.put(notice.key, process)?.let(interrupt)
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            open.remove(notice.key, process)
            when {
                code != 0 && ("Unknown option" in output || "--action" in output || "--wait" in output) -> {
                    works = false
                    fallback.show(notice, onClick)
                }
                output.lineSequence().any { it.trim() == ActionKey } -> onClick()
            }
        }
    }

    override fun shown(): Set<String> = if (works) open.filterValues { it.isAlive }.keys.toSet() else fallback.shown()

    override fun remove(keys: Set<String>) {
        if (!works) return fallback.remove(keys)
        keys.forEach { key -> open.remove(key)?.let(interrupt) }
    }

    companion object {
        const val ActionKey = "default"

        fun command(notice: DesktopNotice, icon: String?): List<String> = buildList {
            add("notify-send")
            add("--app-name=$ProductName")
            if (icon != null) add("--icon=$icon")
            // "default" is what most notification servers run on a click on the notification itself.
            add("--action=$ActionKey=Open")
            add("--wait")
            add("--")
            add(notice.title)
            add(notice.body)
        }

        /** Writes the app icon as a PNG for notify-send, once. */
        fun iconFile(dir: File): File {
            val file = File(dir, "notification-icon.png")
            if (!file.exists()) runCatching { ImageIO.write(TrayIconImage.draw(128), "png", file) }
            return file
        }
    }
}

/**
 * The tray icon's own notification (Windows toasts, Mac notifications). A click comes back as the
 * icon's action. Java has no way to take one down again, so these stay until the owner dismisses them.
 */
class TrayNotifier(private val tray: DesktopTray) : Notifier {
    override fun show(notice: DesktopNotice, onClick: () -> Unit) {
        tray.notify(notice)
    }
}
