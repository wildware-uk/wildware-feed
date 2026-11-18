package dev.wildware.agents.app.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the update dialog is showing, apart from an install in progress. */
sealed interface UpdatePrompt {
    /** A newer [release]: its notes, and the button to install it. */
    data class Offer(val release: Release) : UpdatePrompt

    /** A check by hand is running. */
    data object Checking : UpdatePrompt

    /** A check by hand found nothing newer. */
    data object UpToDate : UpdatePrompt

    /** A check by hand couldn't finish. */
    data class CheckFailed(val message: String) : UpdatePrompt
}

/**
 * The update feature as the window and the tray see it: whether there is a newer version, the
 * dialog, and the install. Installing only ever starts from the owner's click.
 */
class UpdateController(
    /** The running version, e.g. "1.0.1". */
    val current: String,
    private val checker: UpdateChecker,
    private val updater: Updater,
    private val source: StateFlow<ReleaseSource?>,
    private val scope: CoroutineScope,
) {
    /** A release newer than this copy, or null. */
    val available: StateFlow<Release?> = checker.available

    /** How far an install has got. */
    val install: StateFlow<InstallState> = updater.state

    private val mutablePrompt = MutableStateFlow<UpdatePrompt?>(null)

    /** What the dialog shows, or null when it is closed. */
    val prompt: StateFlow<UpdatePrompt?> = mutablePrompt.asStateFlow()

    /** Why this copy can't update itself, or null when it can. */
    val cannotInstallReason: String? get() = updater.cannotInstallReason

    /** A Linux package install asks for the owner's password; the dialog says so first. */
    val asksForPassword: Boolean get() = updater.installation is Installation.LinuxPackage

    private var installing: Job? = null

    fun start(): Job = checker.start(scope)

    /** Opens the dialog on the newer version, from the pill or the tray. */
    fun showOffer() {
        val release = available.value ?: return
        mutablePrompt.value = UpdatePrompt.Offer(release)
    }

    /** "Check for updates", from the tray or the command bar: says what it found either way. */
    fun checkByHand() {
        if (updater.busy) return
        updater.reset()
        mutablePrompt.value = UpdatePrompt.Checking
        scope.launch {
            mutablePrompt.value = when (val result = checker.checkNow()) {
                is CheckResult.Newer -> UpdatePrompt.Offer(result.release)
                CheckResult.UpToDate -> UpdatePrompt.UpToDate
                is CheckResult.Failed -> UpdatePrompt.CheckFailed(result.message)
            }
        }
    }

    /** "Install and restart", and "Retry" after a failure. */
    fun installNow() {
        if (updater.busy) return
        val release = (mutablePrompt.value as? UpdatePrompt.Offer)?.release ?: available.value ?: return
        mutablePrompt.value = UpdatePrompt.Offer(release)
        val from = source.value ?: return
        installing = scope.launch { updater.install(release, from) }
    }

    /** Stops a download part way. Once installing has begun it runs to the end. */
    fun cancel() {
        if (install.value is InstallState.Downloading) installing?.cancel()
    }

    /** Closes the dialog, unless an install is under way. */
    fun dismiss() {
        if (updater.busy) return
        updater.reset()
        mutablePrompt.value = null
    }
}
