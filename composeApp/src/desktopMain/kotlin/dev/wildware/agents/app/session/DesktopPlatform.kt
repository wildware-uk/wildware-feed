package dev.wildware.agents.app.session

import dev.wildware.agents.OutboxStorage
import dev.wildware.agents.TextStore
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
fun desktopPlatform(dir: File = desktopConfigDir(), quiet: () -> Boolean = { false }): AppPlatform {
    val outbox = FileOutboxStorage(File(dir, "outbox").apply { mkdirs() })
    val readMarks = File(dir, "read-marks.json")
    val feedCache = File(dir, "feed-cache.json")
    val cardFolds = File(dir, "folded-cards.json")
    val postTarget = File(dir, "post-target.txt")
    val localServer = localServer(dir)
    return AppPlatform(
        sessions = FileSessionStore(File(dir, "session.json")),
        readMarks = FileTextStore(readMarks),
        outbox = outbox,
        httpEngine = { dev.wildware.agents.app.local.desktopHttpEngine(localServer) },
        deviceName = desktopDeviceName(),
        imageCacheDir = File(dir, "image-cache").absolutePath,
        clearLocalData = {
            outbox.clear()
            readMarks.delete()
            feedCache.delete()
            cardFolds.delete()
            postTarget.delete()
        },
        settings = FileTextStore(File(dir, "settings.txt")),
        feedCache = FileTextStore(feedCache),
        cardFolds = FileTextStore(cardFolds),
        postTarget = FileTextStore(postTarget),
        filePicker = AwtFilePicker { java.awt.Frame.getFrames().firstOrNull { it.isActive } },
        localServer = localServer,
        crashReports = dev.wildware.agents.app.crash.CrashReporting.setting,
        // Java can't add a sound to the system's notifications, so the app plays its own beside them.
        sounds = dev.wildware.agents.app.sound.SoundBoard(dev.wildware.agents.app.sound.DesktopSoundPlayer(), FileTextStore(File(dir, "sounds.txt")), quiet),
        // Keys, like session.json: written 0600.
        teamList = FileTextStore(File(dir, "teams.json")),
        teamFilesFor = { key -> desktopTeamFiles(dir, key) },
        guidedTour = true,
    )
}

/**
 * Team [key]'s own files, in `teams/<key>`. The first team (empty key) keeps the files it always
 * had at the top of [dir]; only what it parks while another team is open goes in `teams/first`.
 */
internal fun desktopTeamFiles(dir: File, key: String): TeamFiles {
    val own = File(File(dir, "teams"), key.ifEmpty { "first" })
    val home = if (key.isEmpty()) dir else own
    val readMarks = File(home, "read-marks.json")
    val feedCache = File(home, "feed-cache.json")
    val cardFolds = File(home, "folded-cards.json")
    val postTarget = File(home, "post-target.txt")
    own.mkdirs()
    privateTo(own.parentFile, directory = true)
    privateTo(own, directory = true)
    val outbox = FileOutboxStorage(File(own, "outbox").apply { mkdirs() })
    return TeamFiles(
        FileTextStore(readMarks), FileTextStore(feedCache), FileTextStore(cardFolds), FileTextStore(postTarget), outbox,
        clear = {
            outbox.clear()
            listOf(readMarks, feedCache, cardFolds, postTarget).forEach { it.delete() }
            if (key.isNotEmpty()) own.deleteRecursively()
        },
    )
}

/** The server this app can run itself, in `<config dir>/server`, stopped cleanly when the app exits. */
private fun localServer(dir: File): dev.wildware.agents.app.local.LocalServer {
    val serverDir = File(dir, "server").apply { mkdirs() }
    privateTo(serverDir, directory = true)
    val host = dev.wildware.agents.app.local.LocalServer(serverDir, writePrivately = ::writePrivately)
    Runtime.getRuntime().addShutdownHook(Thread { kotlinx.coroutines.runBlocking { host.stop() } })
    dev.wildware.agents.app.local.LocalServerGuard.host = host
    return host
}
