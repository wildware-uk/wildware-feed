package dev.wildware.agents.app.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Where an install has got to. */
sealed interface InstallState {
    data object Idle : InstallState

    /** [total] is -1 when the size isn't known. */
    data class Downloading(val done: Long, val total: Long) : InstallState {
        val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    }

    data object Verifying : InstallState
    data object Installing : InstallState
    data object Restarting : InstallState
    data class Failed(val message: String) : InstallState

    /** The app can't finish it alone; [message] says what to do, and [folder] holds the download. */
    data class Manual(val message: String, val folder: String?) : InstallState
}

/**
 * Downloads a release, checks it against its published SHA-256, stages it with [installer], then
 * hands over to the helper and quits. The download can also happen ahead of time, in the
 * background ([stage]), so that installing later is quick. Nothing that changes the installed app happens before the
 * checks pass, and each way of installing either swaps the new version in whole or leaves the
 * old one as it was.
 */
class Updater(
    val installation: Installation,
    private val installer: Installer,
    /** A folder of the app's own to download into; each release gets a folder inside. */
    private val workRoot: File,
    /** Sends what's waiting and closes the live stream, before the app goes. */
    private val beforeQuit: suspend () -> Unit,
    /** Quits the app (the helper is already running and waiting for that). */
    private val quit: () -> Unit,
    private val pid: Long = ProcessHandle.current().pid(),
    private val arch: String = System.getProperty("os.arch").orEmpty(),
) {
    private val mutableState = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = mutableState.asStateFlow()

    /** Whether this copy can update itself at all (it was installed from a release). */
    val canInstall: Boolean get() = installation.kind != null

    /** Why it can't, when it can't. */
    val cannotInstallReason: String? get() = (installation as? Installation.Unsupported)?.reason

    val busy: Boolean
        get() = state.value.let { it is InstallState.Downloading || it is InstallState.Verifying || it is InstallState.Installing || it is InstallState.Restarting }

    /** Back to the start after a failure, a manual step or a cancel. */
    fun reset() {
        if (!busy) mutableState.value = InstallState.Idle
    }

    /** One download at a time: the background one and a click on Install never write the same file. */
    private val fetching = Mutex()

    /**
     * Whether [release] has the file that updates this copy, with its checksum. Until then it isn't
     * offered: a release can go out before its Mac file, which waits for Apple. A copy that can't
     * update itself (run from the source) has no file to wait for, so it hears of every release.
     */
    fun offers(release: Release): Boolean {
        val kind = installation.kind ?: return true
        return release.assets.any { it.assetKind == kind && !it.sha256.isNullOrBlank() }
    }

    /**
     * Whether [release] can be downloaded and installed here without asking anything first: this
     * copy updates itself, the release has a file for this computer, and that file has a checksum.
     */
    fun canStage(release: Release): Boolean =
        installation !is Installation.Unsupported && chooseAsset(release, installation, arch)?.sha256?.isNotBlank() == true

    /**
     * Downloads [release]'s file for this computer and checks it against its SHA-256, ready for
     * [install] or [applyOnQuit] to use without downloading again. Picks up a part-download where it
     * stopped, and does nothing more when the file is already here and checks out. Nothing about
     * the installed app changes. Returns the checked file; throws when it couldn't get one.
     */
    suspend fun stage(release: Release, source: ReleaseSource, progress: (done: Long, total: Long) -> Unit = { _, _ -> }): File =
        fetching.withLock { fetch(release, source, progress) {} }

    /** The checked file a [stage] left for [release], or null when there isn't one. */
    suspend fun staged(release: Release): File? = withContext(Dispatchers.IO) {
        val asset = chooseAsset(release, installation, arch) ?: return@withContext null
        val target = installer.downloadTarget(installation, asset, workDirFor(release))
        target.takeIf { it.isFile && checksumMatches(it, asset.sha256) }
    }

    private fun workDirFor(release: Release) = File(workRoot, release.version.replace(Regex("[^0-9A-Za-z.-]"), "_"))

    private fun checksumMatches(file: File, expected: String?) = runCatching { verifyChecksum(file, expected) }.isSuccess

    private suspend fun fetch(release: Release, source: ReleaseSource, progress: (Long, Long) -> Unit, verifying: () -> Unit): File {
        if (installation is Installation.Unsupported) throw InstallException(installation.reason)
        val asset = chooseAsset(release, installation, arch) ?: throw InstallException("Version ${release.version} has no installer for this computer.")
        // Don't fetch what can't be checked.
        if (asset.sha256.isNullOrBlank()) throw ChecksumException("This release has no checksum for ${asset.name}, so it can't be checked. Nothing was installed.")
        val workDir = workDirFor(release)
        val target = withContext(Dispatchers.IO) {
            workRoot.listFiles()?.filter { it != workDir }?.forEach { it.deleteRecursively() }
            workDir.mkdirs()
            installer.downloadTarget(installation, asset, workDir)
        }
        if (withContext(Dispatchers.IO) { target.isFile && checksumMatches(target, asset.sha256) }) {
            verifying()
            return target
        }
        val part = File(target.parentFile, target.name + ".part")
        source.download(asset, part, progress)
        verifying()
        withContext(Dispatchers.IO) {
            try {
                verifyChecksum(part, asset.sha256)
            } catch (e: ChecksumException) {
                part.delete()
                throw e
            }
            target.delete()
            if (!part.renameTo(target)) throw InstallException("Couldn't save the download.")
        }
        return target
    }

    /** Installs [release] from [source] and restarts into it. Returns when it has quit, failed, or been cancelled. */
    suspend fun install(release: Release, source: ReleaseSource) {
        if (busy) return
        (installation as? Installation.Unsupported)?.let { mutableState.value = InstallState.Failed(it.reason); return }
        val asset = chooseAsset(release, installation, arch)
        if (asset == null) {
            mutableState.value = InstallState.Failed("Version ${release.version} has no installer for this computer.")
            return
        }
        val workDir = workDirFor(release)
        var staged = false
        var file: File? = null
        try {
            mutableState.value = InstallState.Downloading(0, asset.size.takeIf { it > 0 } ?: -1)
            val target = fetching.withLock {
                fetch(release, source, { done, total -> mutableState.value = InstallState.Downloading(done, total) }) {
                    mutableState.value = InstallState.Verifying
                }
            }
            file = target
            mutableState.value = InstallState.Installing
            staged = true
            when (val prepared = withContext(Dispatchers.IO) { installer.prepare(installation, target, workDir, pid) }) {
                is Prepared.Manual -> {
                    staged = false
                    mutableState.value = InstallState.Manual(prepared.message, prepared.folder?.absolutePath)
                }
                is Prepared.Restart -> {
                    mutableState.value = InstallState.Restarting
                    beforeQuit()
                    withContext(Dispatchers.IO) { installer.launch(prepared.helper) }
                    staged = false
                    quit()
                }
                // Only asked for without a relaunch; quit all the same, as asked.
                Prepared.Done -> {
                    mutableState.value = InstallState.Restarting
                    beforeQuit()
                    staged = false
                    quit()
                }
            }
        } catch (e: CancellationException) {
            // Stopped by the owner: what was downloaded goes too.
            withContext(NonCancellable) { abandon(staged, workDir, file, keepPart = false, asset) }
            mutableState.value = InstallState.Idle
            throw e
        } catch (e: Exception) {
            // A download that broke off is kept, so trying again carries on from there.
            withContext(NonCancellable) { abandon(staged, workDir, file, keepPart = true, asset) }
            mutableState.value = InstallState.Failed(
                when (e) {
                    is ChecksumException, is InstallException, is ReleaseSourceException -> e.message.orEmpty()
                    else -> "The update failed: ${e.message ?: e::class.simpleName}. Nothing was installed."
                },
            )
        }
    }

    /**
     * Whether quitting can put [release] in on the way out: it is downloaded and checked, and
     * installing needs nothing from the owner. A Linux package asks for a password, so never.
     */
    fun appliesOnQuit(): Boolean = installation !is Installation.LinuxPackage && installation !is Installation.Unsupported

    /**
     * The app is quitting for good with [release] downloaded: puts it in without opening the app
     * again, so the next start is the new version. Blocks; call it once the window has gone, then
     * end the process (the helper waits for that). Returns whether a helper was started or the
     * new version is already in place. Any problem leaves the old version as it was.
     */
    fun applyOnQuit(release: Release): Boolean {
        if (busy || !appliesOnQuit()) return false
        val asset = chooseAsset(release, installation, arch) ?: return false
        val workDir = workDirFor(release)
        val target = installer.downloadTarget(installation, asset, workDir)
        if (!target.isFile || !checksumMatches(target, asset.sha256)) return false
        return try {
            when (val prepared = installer.prepare(installation, target, workDir, pid, relaunch = false)) {
                is Prepared.Restart -> { installer.launch(prepared.helper); true }
                Prepared.Done -> true
                is Prepared.Manual -> false
            }
        } catch (e: Exception) {
            runCatching { installer.abandon(installation, workDir) }
            false
        }
    }

    /**
     * Clears out downloads that are no use any more: those for [current] or older (an update that
     * went in) and anything unreadable. A newer one waiting to go in stays.
     */
    fun cleanUp(current: SemVer) {
        if (busy) return
        workRoot.listFiles()?.forEach { dir ->
            val version = SemVer.parse(dir.name)
            if (version == null || version <= current) runCatching { dir.deleteRecursively() }
        }
    }

    private suspend fun abandon(staged: Boolean, workDir: File, file: File?, keepPart: Boolean, asset: ReleaseAsset) = withContext(Dispatchers.IO) {
        if (staged) runCatching { installer.abandon(installation, workDir) }
        val target = file ?: runCatching { installer.downloadTarget(installation, asset, workDir) }.getOrNull()
        if (!keepPart) target?.let { runCatching { File(it.parentFile, it.name + ".part").delete() } }
        // A download beside an AppImage shouldn't be left lying around there.
        if (installation is Installation.LinuxAppImage && !keepPart) target?.let { runCatching { it.delete() } }
    }
}
