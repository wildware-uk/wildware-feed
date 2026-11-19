package dev.wildware.agents.app.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Duration

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
 * dialog, and the install. With [automatic] on, a newer version downloads in the background and
 * waits ([background]); then one click on "Restart to update" puts it in, and quitting puts it in
 * on the way out. With it off, installing only ever starts from the owner's click.
 */
class UpdateController(
    /** The running version, e.g. "1.0.1". */
    val current: String,
    private val checker: UpdateChecker,
    private val updater: Updater,
    private val source: StateFlow<ReleaseSource?>,
    private val scope: CoroutineScope,
    /** The "Update automatically" setting. */
    val automatic: StateFlow<Boolean> = MutableStateFlow(false),
    /** Changes that setting (from the pill's menu or the dialog). */
    private val setAutomatic: (Boolean) -> Unit = {},
    /** The waits between tries of a failed background download. */
    retryWaits: List<Duration> = AutoUpdater.RetryWaits,
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

    /** Holds the background download off while an install the owner started runs. */
    private val installRunning = MutableStateFlow(false)

    private val auto = AutoUpdater(updater, available, automatic, source, installRunning, retryWaits)

    /** The background download, and when it's done, the version waiting to go in. */
    val background: StateFlow<BackgroundUpdate> = auto.state

    private val mutableQuiet = MutableStateFlow(false)

    /**
     * The install running came from "Restart to update": the pill shows its progress, and the
     * dialog opens only if something needs saying (it failed, or needs a step by hand).
     */
    val quiet: StateFlow<Boolean> = mutableQuiet.asStateFlow()

    /** Whether [release] is being (or will be) downloaded in the background, rather than offered. */
    fun downloadsInBackground(release: Release): Boolean = auto.handles(release)

    fun setAutomatically(on: Boolean) = setAutomatic(on)

    private val checking = MutableStateFlow(false)

    /** The tray menu's update item, kept up to date. */
    val trayItem: Flow<TrayUpdate> = combine(checking, available, install, background) { checking, release, install, background ->
        TrayUpdate.of(checking, release, install, canInstall = cannotInstallReason == null, background = background, asksForPassword = asksForPassword)
    }.distinctUntilChanged()

    fun start(): Job = scope.launch {
        checker.start(this)
        auto.start(this)
    }

    /** Opens the dialog on the newer version, from the pill or the tray. */
    fun showOffer() {
        val release = available.value ?: return
        mutablePrompt.value = UpdatePrompt.Offer(release)
    }

    /** "Check for updates" from the command bar: the dialog says what it found either way. */
    fun checkByHand() {
        if (updater.busy || !checking.compareAndSet(expect = false, update = true)) return
        updater.reset()
        mutablePrompt.value = UpdatePrompt.Checking
        scope.launch {
            try {
                mutablePrompt.value = when (val result = checker.checkNow()) {
                    is CheckResult.Newer -> UpdatePrompt.Offer(result.release)
                    CheckResult.UpToDate -> UpdatePrompt.UpToDate
                    is CheckResult.Failed -> UpdatePrompt.CheckFailed(result.message)
                }
            } finally {
                checking.value = false
            }
        }
    }

    /**
     * "Check for updates…" in the tray: checks without opening the window, for the caller to say
     * what it found. Null when it didn't check, because a check or an install is already running.
     */
    suspend fun checkQuietly(): CheckResult? {
        if (updater.busy || !checking.compareAndSet(expect = false, update = true)) return null
        try {
            return checker.checkNow()
        } finally {
            checking.value = false
        }
    }

    /** "Install and restart" (in the dialog, or "Install update" in the tray), and "Retry" after a failure. */
    fun installNow() {
        if (updater.busy) return
        val release = (mutablePrompt.value as? UpdatePrompt.Offer)?.release ?: available.value ?: return
        mutablePrompt.value = UpdatePrompt.Offer(release)
        mutableQuiet.value = false
        startInstall(release)
    }

    /**
     * "Restart to update", in the pill or the tray: puts in the version downloaded in the
     * background and opens the app again, with no dialog unless it goes wrong. Returns the
     * install's job, which ends when it fails (the app quits when it works); null when there is
     * nothing downloaded or an install is already running.
     */
    fun restartToUpdate(): Job? {
        if (updater.busy) return null
        val release = (background.value as? BackgroundUpdate.Staged)?.release ?: return null
        updater.reset()
        mutablePrompt.value = null
        mutableQuiet.value = true
        return startInstall(release)
    }

    private fun startInstall(release: Release): Job? {
        val from = source.value ?: return null
        installRunning.value = true
        return scope.launch {
            try {
                updater.install(release, from)
            } finally {
                installRunning.value = false
            }
        }.also { installing = it }
    }

    /**
     * The app is quitting for good: puts in a version waiting in the background, without opening
     * the app again, so the next start is the new one. Blocks. Returns whether it started, so the
     * caller ends the process (the helper waits for that). A Linux package needs a password, so
     * it waits for "Restart to update" instead.
     */
    fun applyOnQuit(): Boolean {
        if (!automatic.value || updater.busy) return false
        val release = (background.value as? BackgroundUpdate.Staged)?.release ?: return false
        return updater.applyOnQuit(release)
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
