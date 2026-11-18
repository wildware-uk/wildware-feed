package dev.wildware.agents.app.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * hands over to the helper and quits. Nothing that changes the installed app happens before the
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

    /** Installs [release] from [source] and restarts into it. Returns when it has quit, failed, or been cancelled. */
    suspend fun install(release: Release, source: ReleaseSource) {
        if (busy) return
        (installation as? Installation.Unsupported)?.let { mutableState.value = InstallState.Failed(it.reason); return }
        val asset = chooseAsset(release, installation, arch)
        if (asset == null) {
            mutableState.value = InstallState.Failed("Version ${release.version} has no installer for this computer.")
            return
        }
        val workDir = File(workRoot, release.version.replace(Regex("[^0-9A-Za-z.-]"), "_"))
        var staged = false
        var file: File? = null
        try {
            mutableState.value = InstallState.Downloading(0, asset.size.takeIf { it > 0 } ?: -1)
            withContext(Dispatchers.IO) {
                workRoot.listFiles()?.filter { it != workDir }?.forEach { it.deleteRecursively() }
                workDir.mkdirs()
            }
            val target = installer.downloadTarget(installation, asset, workDir)
            file = target
            val part = File(target.parentFile, target.name + ".part")
            source.download(asset, part) { done, total -> mutableState.value = InstallState.Downloading(done, total) }
            mutableState.value = InstallState.Verifying
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
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { abandon(staged, workDir, file) }
            mutableState.value = InstallState.Idle
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) { abandon(staged, workDir, file) }
            mutableState.value = InstallState.Failed(
                when (e) {
                    is ChecksumException, is InstallException, is ReleaseSourceException -> e.message.orEmpty()
                    else -> "The update failed: ${e.message ?: e::class.simpleName}. Nothing was installed."
                },
            )
        }
    }

    private suspend fun abandon(staged: Boolean, workDir: File, file: File?) = withContext(Dispatchers.IO) {
        if (staged) runCatching { installer.abandon(installation, workDir) }
        file?.let { runCatching { File(it.parentFile, it.name + ".part").delete() } }
        // A download beside an AppImage shouldn't be left lying around there.
        if (installation is Installation.LinuxAppImage) file?.let { runCatching { it.delete() } }
    }
}
