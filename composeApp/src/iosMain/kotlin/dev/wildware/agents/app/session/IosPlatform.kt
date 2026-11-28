package dev.wildware.agents.app.session

import dev.wildware.agents.Attachable
import dev.wildware.agents.Outbox
import dev.wildware.agents.OutboxStorage
import dev.wildware.agents.TextStore
import dev.wildware.agents.app.feed.FilePicker
import dev.wildware.agents.app.feed.PendingFile
import dev.wildware.agents.app.feed.VoiceInput
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import platform.CoreFoundation.CFNotificationCenterGetDarwinNotifyCenter
import platform.CoreFoundation.CFNotificationCenterPostNotification
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorWritingForMerging
import platform.Foundation.NSURL
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
 * with the widgets, extensions and watch. Read marks live in [dataDir], a folder in the app group
 * container. The outbox is [outboxDir], the one the share sheet, Siri and alert buttons queue
 * into too, so everything waiting shows in the app and the background sender sends it all.
 * [photoPicker] attaches photos and videos, [documentPicker] any file from Files; [voiceInput]
 * records the composer's voice notes.
 */
fun iosPlatform(
    sessions: SessionStore,
    dataDir: String,
    outboxDir: String,
    photoPicker: FilePicker? = null,
    documentPicker: FilePicker? = null,
    voiceInput: VoiceInput? = null,
): AppPlatform {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
    makeDir(dataDir)
    val readMarks = "$dataDir/read-marks.json"
    // In Caches: iOS may clear it when space runs short, which costs only a slower next start.
    val feedCache = (caches ?: dataDir) + "/feed-cache.json"
    val cardFolds = "$dataDir/folded-cards.json"
    val postTarget = "$dataDir/post-target.txt"
    val outbox = IosOutboxStorage(outboxDir)
    outbox.adoptOldAppOutbox("$dataDir/AppOutbox")
    return AppPlatform(
        sessions = sessions,
        readMarks = IosTextStore(readMarks),
        outbox = outbox,
        httpEngine = { Darwin.create() },
        deviceName = UIDevice.currentDevice.name,
        imageCacheDir = caches?.let { "$it/agents-images" },
        filePicker = photoPicker,
        documentPicker = documentPicker,
        voiceInput = voiceInput,
        voiceReview = voiceInput?.let { dev.wildware.agents.VoiceReviewSetting(IosTextStore("$dataDir/voice-review.txt")) },
        outboxChanged = OutboxSignals.changes,
        foreground = AppLifecycle.foregrounds,
        feedCache = IosTextStore(feedCache),
        cardFolds = IosTextStore(cardFolds),
        postTarget = IosTextStore(postTarget),
        pushAlerts = true,
        // No link to the website that sells hosted teams (App Store guideline 3.1.1).
        linksToSignUp = false,
        // Team is offered, and linked to, only on the US App Store (guidelines 3.1.1, 3.1.3): Swift
        // tells [AppStoreFront] the storefront; until it does, nothing is offered.
        buyingLinks = AppStoreFront.buyingLinks,
        upsellMarks = IosTextStore("$dataDir/upsell.json"),
        // Team bought in the app through the App Store (StoreKit, in Swift: AppStoreBridge).
        appStore = IosAppStore,
        crashReports = dev.wildware.agents.app.crash.CrashReporting.setting,
        liveActivities = LiveActivities.status,
        notices = IosSystemNotices,
        sounds = dev.wildware.agents.app.sound.IosSounds.board("$dataDir/sounds.txt"),
        clearLocalData = {
            outbox.clear()
            remove(readMarks)
            remove(feedCache)
            remove(cardFolds)
            remove(postTarget)
        },
        // Keys: in the Keychain, as the open team's is ([KeychainText]).
        teamList = KeychainText(),
        teamFilesFor = { key -> iosTeamFiles(dataDir, caches ?: dataDir, key) },
        guidedTour = true,
    )
}

/**
 * Team [key]'s own files, in `teams/<key>` of the app group folder. The first team (empty key)
 * keeps the files it always had; only what it parks while another team is open goes in
 * `teams/first`. The outbox the share sheet, Siri and alert buttons use is always the open team's.
 */
internal fun iosTeamFiles(dataDir: String, caches: String, key: String): TeamFiles {
    val own = "$dataDir/teams/${key.ifEmpty { "first" }}"
    val home = if (key.isEmpty()) dataDir else own
    makeDir(own)
    val cache = if (key.isEmpty()) caches else "$caches/teams/$key"
    makeDir(cache)
    val readMarks = "$home/read-marks.json"
    val feedCache = "$cache/feed-cache.json"
    val cardFolds = "$home/folded-cards.json"
    val postTarget = "$home/post-target.txt"
    val outbox = IosParkedOutbox("$own/outbox")
    return TeamFiles(
        IosTextStore(readMarks), IosTextStore(feedCache), IosTextStore(cardFolds), IosTextStore(postTarget), outbox,
        clear = {
            listOf(readMarks, feedCache, cardFolds, postTarget).forEach(::remove)
            remove("$own/outbox")
            if (key.isNotEmpty()) {
                remove(own)
                remove(cache)
            }
        },
    )
}

/**
 * Where a team's outbox waits while another team is open: a plain folder only this app reads, so
 * nothing in it is sent until the team is opened and it moves back into the shared outbox.
 */
private class IosParkedOutbox(private val dir: String) : OutboxStorage {
    private val list = "$dir/queue.json"

    init {
        makeDir(dir)
    }

    override fun load(): String = IosTextStore(list).read().orEmpty()
    override fun save(text: String) = writeText(list, text)
    override fun writeFile(name: String, bytes: ByteArray) {
        bytes.toNSData().writeToFile("$dir/$name", atomically = true)
    }
    override fun readFile(name: String): ByteArray? = NSData.dataWithContentsOfFile("$dir/$name")?.toByteArray()
    override fun deleteFile(name: String) = remove("$dir/$name")
}

/**
 * The outbox's "it changed" signal between the app and its extensions. Swift hears the Darwin
 * notification each process posts after writing and calls [changed]; the app's queue then reads
 * the file again.
 */
object OutboxSignals {
    /** The Darwin notification name. Swift's `OutboxQueue.changedNotification` is the same. */
    const val Name = "dev.wildware.agents.outbox.changed"

    private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val changes: Flow<Unit> = flow

    fun changed() {
        flow.tryEmit(Unit)
    }

    /** Tells the other processes (and Swift in this one) that the file changed. */
    @OptIn(ExperimentalForeignApi::class)
    internal fun announce() {
        val name = CFStringCreateWithCString(null, Name, kCFStringEncodingUTF8) ?: return
        CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(), name, null, null, true)
        CFRelease(name)
    }
}

/**
 * The app coming back to the front. Swift calls [foreground] when the scene turns active. While the
 * app was in the background iOS froze it, and the live connection it held is almost certainly
 * dead, though nothing on it says so until the heartbeat window runs out.
 */
object AppLifecycle {
    private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val foregrounds: Flow<Unit> = flow

    fun foreground() {
        flow.tryEmit(Unit)
    }
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
 * The outbox in the app group: `queue.json` and each waiting attachment beside it, exactly as the
 * Swift `OutboxQueue` keeps them. Other processes write it too, so every read-change-write holds
 * the file through a file coordinator (Swift coordinates on the same file), and each write tells
 * the others.
 */
class IosOutboxStorage(private val dir: String) : OutboxStorage {
    private val list = "$dir/queue.json"

    init {
        makeDir(dir)
    }

    override fun load(): String = coordinated(writing = false) { IosTextStore(list).read().orEmpty() }

    override fun save(text: String) = update { text }

    override fun update(change: (String) -> String) {
        var wrote = false
        coordinated(writing = true) {
            val before = IosTextStore(list).read().orEmpty()
            val after = change(before)
            if (after != before) {
                writeText(list, after)
                wrote = true
            }
        }
        if (wrote) OutboxSignals.announce()
    }

    override fun writeFile(name: String, bytes: ByteArray) {
        bytes.toNSData().writeToFile("$dir/$name", atomically = true)
    }
    override fun readFile(name: String): ByteArray? = NSData.dataWithContentsOfFile("$dir/$name")?.toByteArray()
    override fun deleteFile(name: String) = remove("$dir/$name")

    /** Signing out: nothing queued for that account should send. */
    fun clear() {
        remove(dir)
        makeDir(dir)
        OutboxSignals.announce()
    }

    /**
     * The first iPhone build of the Compose app kept its own queue in [oldDir]. Moves anything
     * still waiting there into this one, once.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun adoptOldAppOutbox(oldDir: String) {
        val oldList = "$oldDir/outbox.json"
        val waiting = IosTextStore(oldList).read()?.let(Outbox::decode).orEmpty()
        if (waiting.isNotEmpty()) {
            waiting.flatMap { it.files }.forEach { file ->
                NSFileManager.defaultManager.moveItemAtPath("$oldDir/files/${file.name}", toPath = "$dir/${file.name}", error = null)
            }
            update { text ->
                val current = Outbox.decode(text)
                val known = current.map { it.id }.toSet()
                Outbox.encode(current + waiting.filter { it.id !in known })
            }
        }
        remove(oldDir)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun <T> coordinated(writing: Boolean, block: () -> T): T {
        val url = NSURL.fileURLWithPath(list)
        var result: Result<T>? = null
        val coordinator = NSFileCoordinator(filePresenter = null)
        if (writing) {
            coordinator.coordinateWritingItemAtURL(url, options = NSFileCoordinatorWritingForMerging, error = null) { _ ->
                result = runCatching(block)
            }
        } else {
            coordinator.coordinateReadingItemAtURL(url, options = 0u, error = null) { _ ->
                result = runCatching(block)
            }
        }
        // Coordination failed: do it anyway, as the Swift side does, rather than drop the change.
        return (result ?: runCatching(block)).getOrThrow()
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

/**
 * A picked photo or file from Swift, which hands over its bytes as NSData. The name and type are
 * made ones the dashboard takes ([Attachable]): what another app calls a file can be anything.
 */
fun pendingFile(name: String, mimeType: String, data: NSData): PendingFile {
    val clean = Attachable.fileName(name)
    return PendingFile(clean, Attachable.mimeType(mimeType, clean), data.toByteArray())
}
