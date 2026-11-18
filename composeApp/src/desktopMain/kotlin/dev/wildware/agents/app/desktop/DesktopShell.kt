package dev.wildware.agents.app.desktop

import dev.wildware.agents.StreamStatus
import dev.wildware.agents.app.engine.AppModel
import dev.wildware.agents.app.engine.AppScreen
import dev.wildware.agents.app.engine.DashboardSession
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
) : TrayActions {
    private val mutableSettings = MutableStateFlow(settingsStore.load().let { it.copy(startAtLogin = startAtLogin.available && startAtLogin.isEnabled()) })
    val settings: StateFlow<DesktopSettings> = mutableSettings.asStateFlow()

    val tray = DesktopTray(this, mutableSettings.value, startAtLogin.available)
    private val notifier = notifierFor(tray)
    private var watching: Job? = null

    /** Whether closing the window should leave the app running: only with a tray to come back from. */
    val keepRunningOnClose: Boolean get() = tray.supported && mutableSettings.value.keepRunningInTray

    fun start() {
        tray.install()
        watching = scope.launch {
            model.screen.collectLatest { screen ->
                val session = (screen as? AppScreen.Signed)?.session
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
            rules.next(ready, waiting, feed)
        }.collectLatest { notices ->
            if (mutableSettings.value.doNotDisturb || windowFocused()) return@collectLatest
            notices.forEach { notice -> notifier.show(notice) { open(notice.focusId) } }
        }
    }

    fun stop() {
        watching?.cancel()
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

    private fun update(change: (DesktopSettings) -> DesktopSettings) {
        val next = change(mutableSettings.value)
        mutableSettings.value = next
        settingsStore.save(next)
    }
}
