package dev.wildware.agents.app.desktop

import dev.wildware.agents.StreamStatus
import dev.wildware.agents.app.engine.AppModel
import dev.wildware.agents.app.engine.AppScreen
import dev.wildware.agents.app.engine.DashboardSession
import dev.wildware.agents.app.ProductName
import dev.wildware.agents.app.update.CheckResult
import dev.wildware.agents.app.update.UpdateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Where a click should land: [cardId] in [projectSlug]'s feed, or every project's when null. */
data class OpenAt(val projectSlug: String?, val cardId: String)

/**
 * Everything the desktop adds around the window: the tray count, notifications, and the three
 * switches. It follows whichever dashboard is signed in, and keeps working while the window is
 * hidden in the tray.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopShell(
    private val model: AppModel,
    private val scope: CoroutineScope,
    private val settingsStore: DesktopSettingsStore,
    private val startAtLogin: StartAtLogin,
    /** Shows the window, at a card when given one. */
    private val openWindow: (OpenAt?) -> Unit,
    /** Whether the window is on screen and focused: then nothing pops up over it. */
    private val windowFocused: () -> Boolean,
    private val quit: () -> Unit,
    notifierFor: (DesktopTray) -> Notifier,
    /** The updater, when there is one; created once the settings say whether it checks by itself. */
    updatesFor: (StateFlow<DesktopSettings>) -> UpdateController? = { null },
    /** Opens the settings for the server this app runs, when there is one. */
    private val thisComputer: () -> Unit = {},
) : TrayActions {
    private val mutableSettings = MutableStateFlow(
        settingsStore.load().let {
            // After an update the program may have moved; the login start follows it.
            runCatching { startAtLogin.refresh() }
            it.copy(startAtLogin = startAtLogin.available && startAtLogin.isEnabled())
        },
    )
    val settings: StateFlow<DesktopSettings> = mutableSettings.asStateFlow()

    val tray = DesktopTray(this, mutableSettings.value, startAtLogin.available)
    private val notifier = notifierFor(tray)
    private var watching: Job? = null
    private var watchingUpdates: Job? = null
    private var checkingUpdates: Job? = null

    val updates: UpdateController? = updatesFor(settings)

    /** Whether closing the window should leave the app running: only with a tray to come back from. */
    val keepRunningOnClose: Boolean get() = tray.supported && mutableSettings.value.keepRunningInTray

    fun start() {
        tray.install()
        updates?.let { updates ->
            checkingUpdates = updates.start()
            watchingUpdates = scope.launch { updates.trayItem.collect { tray.showUpdate(it) } }
        }
        watching = scope.launch {
            model.screen.collectLatest { screen ->
                val session = (screen as? AppScreen.Signed)?.session
                tray.showThisComputer(session?.saved?.local == true)
                if (session == null) {
                    tray.show(TrayLook.of(null))
                    return@collectLatest
                }
                follow(session)
            }
        }
    }

    private suspend fun follow(session: DashboardSession) {
        val dashboard = session.dashboard
        val rules = NotificationRules()
        combine(dashboard.ready, dashboard.waiting, dashboard.everything.state, dashboard.status) { ready, waiting, feed, status ->
            tray.show(TrayLook.of(waiting.size, offline = status == StreamStatus.Offline))
            val slugs = dashboard.projects.value.associate { it.project.id to it.project.slug }
            rules.next(ready, waiting, feed, session.alerts.value.prefs) { id -> id?.let(slugs::get) }
        }.collectLatest { notices ->
            if (mutableSettings.value.doNotDisturb || windowFocused()) return@collectLatest
            notices.forEach { notice -> notifier.show(notice) { open(notice.focusId) } }
        }
    }

    fun stop() {
        watching?.cancel()
        watchingUpdates?.cancel()
        checkingUpdates?.cancel()
        tray.remove()
    }

    override fun open(focusId: String?) = openWindow(focusId?.let(::openAt))

    /** The card, in its own project's feed when that is known (else every project's). */
    private fun openAt(cardId: String): OpenAt {
        val dashboard = (model.screen.value as? AppScreen.Signed)?.session?.dashboard ?: return OpenAt(null, cardId)
        val projectId = dashboard.everything.state.value.card(cardId)?.projectId
            ?: dashboard.waiting.value.firstOrNull { it.id == cardId }?.request?.projectId
        val slug = projectId?.let { id -> dashboard.projects.value.firstOrNull { it.project.id == id }?.project?.slug }
        return OpenAt(slug, cardId)
    }

    override fun markAllRead() {
        (model.screen.value as? AppScreen.Signed)?.session?.markAllRead()
    }

    override fun quit() = quit.invoke()

    override fun setKeepRunning(on: Boolean) = update { it.copy(keepRunningInTray = on) }

    override fun setStartAtLogin(on: Boolean): Boolean {
        val done = startAtLogin.setEnabled(on)
        val now = if (done) on else startAtLogin.isEnabled()
        update { it.copy(startAtLogin = now) }
        return now
    }

    override fun setDoNotDisturb(on: Boolean) = update { it.copy(doNotDisturb = on) }

    override fun checkForUpdates() {
        val updates = updates ?: return
        scope.launch {
            val result = updates.checkQuietly() ?: return@launch
            // An answer to the owner's own click, so it shows even with Do not disturb on.
            notifier.show(updateNotice(result, updates.current)) {
                if (result is CheckResult.Newer) openUpdate() else open(null)
            }
        }
    }

    override fun installUpdate() {
        val updates = updates ?: return
        openWindow(null)
        // The dialog shows the download and install; one that can't install says why instead.
        if (updates.cannotInstallReason == null) updates.installNow() else updates.showOffer()
    }

    override fun openUpdate() {
        val updates = updates ?: return
        openWindow(null)
        updates.showOffer()
    }

    override fun setCheckForUpdates(on: Boolean) = update { it.copy(checkForUpdates = on) }

    override fun openThisComputer() {
        openWindow(null)
        thisComputer()
    }

    private fun update(change: (DesktopSettings) -> DesktopSettings) {
        val next = change(mutableSettings.value)
        mutableSettings.value = next
        settingsStore.save(next)
    }
}

/** What a check from the tray found, as a notification. */
fun updateNotice(result: CheckResult, current: String): DesktopNotice = when (result) {
    CheckResult.UpToDate -> DesktopNotice(UpdateNoticeKey, "You're up to date", "You have $ProductName $current, the newest version.", null)
    is CheckResult.Newer -> DesktopNotice(
        UpdateNoticeKey,
        "$ProductName ${result.release.version} is available",
        "Choose Install update ${result.release.version} in the tray menu to install it and restart.",
        null,
    )
    is CheckResult.Failed -> DesktopNotice(UpdateNoticeKey, "Couldn't check for updates", result.message, null)
}

private const val UpdateNoticeKey = "update-check"
