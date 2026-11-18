package dev.wildware.agents.app.session

import dev.wildware.agents.OutboxStorage
import dev.wildware.agents.TextStore
import io.ktor.client.engine.okhttp.OkHttp
import dev.wildware.agents.app.feed.AwtFilePicker
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * The app's own folder for settings and keys:
 * `~/Library/Application Support/Agents` on a Mac, `%APPDATA%\Agents` on Windows, and
 * `$XDG_CONFIG_HOME/agents` (or `~/.config/agents`) elsewhere.
 */
fun desktopConfigDir(): File {
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    val dir = when {
        "mac" in os -> File(home, "Library/Application Support/Agents")
        "win" in os -> File(System.getenv("APPDATA") ?: home, "Agents")
        else -> File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config", "agents")
    }
    dir.mkdirs()
    privateTo(dir, directory = true)
    return dir
}

/** Lets only the current user read and write [file], where the file system supports it. */
internal fun privateTo(file: File, directory: Boolean = false) {
    runCatching {
        val perms = if (directory) "rwx------" else "rw-------"
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(perms))
    }.onFailure {
        // Windows has no POSIX modes; the per-user AppData folder is already private to the user.
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }
}

/** Writes through a temporary file and a rename, so a crash never leaves half a file. */
internal fun writePrivately(file: File, text: String) {
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    privateTo(tmp)
    if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
    }
    privateTo(file)
}

/** The device key in a file only this user can read (mode 0600). */
class FileSessionStore(private val file: File) : SessionStore {
    override fun load(): SavedSession? = runCatching {
        if (!file.exists()) null else SessionJson.decodeFromString(SavedSession.serializer(), file.readText())
    }.getOrNull()

    override fun save(session: SavedSession) = writePrivately(file, SessionJson.encodeToString(SavedSession.serializer(), session))

    override fun clear() {
        file.delete()
    }
}

class FileTextStore(private val file: File) : TextStore {
    override fun read(): String? = runCatching { file.readText() }.getOrNull()
    override fun write(text: String) = writePrivately(file, text)
}

/** The outbox list in one file and each waiting attachment in a folder beside it. */
class FileOutboxStorage(private val dir: File) : OutboxStorage {
    private val list = File(dir, "outbox.json")
    private val files = File(dir, "files").apply { mkdirs() }

    init {
        // What waits to send is the owner's own words and files: keep them private too.
        privateTo(dir, directory = true)
        privateTo(files, directory = true)
    }

    override fun load(): String = runCatching { list.readText() }.getOrDefault("")
    override fun save(text: String) = writePrivately(list, text)
    override fun writeFile(name: String, bytes: ByteArray) {
        val file = File(files, name)
        file.writeBytes(bytes)
        privateTo(file)
    }
    override fun readFile(name: String): ByteArray? = runCatching { File(files, name).readBytes() }.getOrNull()
    override fun deleteFile(name: String) {
        File(files, name).delete()
    }

    fun clear() {
        list.delete()
        files.listFiles()?.forEach { it.delete() }
    }
}

/** The computer's name, as the sign-in screen offers it. */
fun desktopDeviceName(): String {
    val name = System.getenv("COMPUTERNAME")
        ?: System.getenv("HOSTNAME")
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
    return name?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: "Desktop"
}

/** Everything the desktop app keeps, in [desktopConfigDir]. */
fun desktopPlatform(dir: File = desktopConfigDir()): AppPlatform {
    val outbox = FileOutboxStorage(File(dir, "outbox").apply { mkdirs() })
    val readMarks = File(dir, "read-marks.json")
    val feedCache = File(dir, "feed-cache.json")
    return AppPlatform(
        sessions = FileSessionStore(File(dir, "session.json")),
        readMarks = FileTextStore(readMarks),
        outbox = outbox,
        httpEngine = { OkHttp.create() },
        deviceName = desktopDeviceName(),
        imageCacheDir = File(dir, "image-cache").absolutePath,
        clearLocalData = {
            outbox.clear()
            readMarks.delete()
            feedCache.delete()
        },
        settings = FileTextStore(File(dir, "settings.txt")),
        feedCache = FileTextStore(feedCache),
        filePicker = AwtFilePicker { java.awt.Frame.getFrames().firstOrNull { it.isActive } },
        localServer = localServer(dir),
    )
}

/** The server this app can run itself, in `<config dir>/server`, stopped cleanly when the app exits. */
private fun localServer(dir: File): dev.wildware.agents.app.local.LocalServer {
    val serverDir = File(dir, "server").apply { mkdirs() }
    privateTo(serverDir, directory = true)
    val host = dev.wildware.agents.app.local.LocalServer(serverDir, writePrivately = ::writePrivately)
    Runtime.getRuntime().addShutdownHook(Thread { kotlinx.coroutines.runBlocking { host.stop() } })
    return host
}
