package dev.wildware.agents.app.session

import dev.wildware.agents.OutboxStorage
import dev.wildware.agents.TextStore
import dev.wildware.agents.app.feed.FilePicker
import dev.wildware.agents.app.feed.PendingFile
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIDevice
import platform.posix.memcpy

/**
 * The iPhone's platform. [sessions] comes from Swift, which keeps the key in the Keychain shared
 * with the widgets, extensions and watch. Read marks and the outbox live in [dataDir], a folder
 * in the app group container, so they survive the app closing. [photoPicker] attaches pictures.
 */
fun iosPlatform(sessions: SessionStore, dataDir: String, photoPicker: FilePicker? = null): AppPlatform {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
    makeDir(dataDir)
    val readMarks = "$dataDir/read-marks.json"
    val outbox = IosOutboxStorage("$dataDir/AppOutbox")
    return AppPlatform(
        sessions = sessions,
        readMarks = IosTextStore(readMarks),
        outbox = outbox,
        httpEngine = { Darwin.create() },
        deviceName = UIDevice.currentDevice.name,
        imageCacheDir = caches?.let { "$it/agents-images" },
        filePicker = photoPicker,
        clearLocalData = {
            outbox.clear()
            remove(readMarks)
        },
    )
}

/** One text file, written whole and atomically. */
class IosTextStore(private val path: String) : TextStore {
    @OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
    override fun read(): String? = runCatching {
        NSString.create(contentsOfFile = path, encoding = NSUTF8StringEncoding, error = null)?.toString()
    }.getOrNull()

    override fun write(text: String) = writeText(path, text)
}

/**
 * The app's own outbox: one list and a folder of attachments. It is separate from the queue the
 * share sheet, Siri and alert actions use (`Outbox/` in the same container), because this queue
 * is held in memory while the app runs and would overwrite their additions.
 */
class IosOutboxStorage(private val dir: String) : OutboxStorage {
    private val list = "$dir/outbox.json"
    private val files = "$dir/files"

    init {
        makeDir(files)
    }

    override fun load(): String = IosTextStore(list).read().orEmpty()
    override fun save(text: String) = writeText(list, text)
    override fun writeFile(name: String, bytes: ByteArray) {
        bytes.toNSData().writeToFile("$files/$name", atomically = true)
    }
    override fun readFile(name: String): ByteArray? = NSData.dataWithContentsOfFile("$files/$name")?.toByteArray()
    override fun deleteFile(name: String) = remove("$files/$name")

    fun clear() {
        remove(list)
        remove(files)
        makeDir(files)
    }
}

@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
private fun writeText(path: String, text: String) {
    NSString.create(string = text).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
}

@OptIn(ExperimentalForeignApi::class)
private fun makeDir(path: String) {
    NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
}

@OptIn(ExperimentalForeignApi::class)
private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val bytes = ByteArray(length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}

/** A picked photo or file from Swift, which hands over its bytes as NSData. */
fun pendingFile(name: String, mimeType: String, data: NSData): PendingFile = PendingFile(name, mimeType, data.toByteArray())
