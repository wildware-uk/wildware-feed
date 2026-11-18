package dev.wildware.agents.app.desktop

import java.awt.CheckboxMenuItem
import java.awt.EventQueue
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.ItemEvent

/** What the tray menu does. Called on the AWT thread. */
interface TrayActions {
    /** Shows the window, at [focusId]'s card when there is one. */
    fun open(focusId: String?)
    fun markAllRead()
    fun quit()
    fun setKeepRunning(on: Boolean)

    /** Returns whether it is now on. */
    fun setStartAtLogin(on: Boolean): Boolean
    fun setDoNotDisturb(on: Boolean)

    /** "Check for updates…": checks now and shows what it found. */
    fun checkForUpdates()

    /** "Update to 1.0.2…": shows the window with the update's notes and button. */
    fun openUpdate()

    fun setCheckForUpdates(on: Boolean)
}

/**
 * The menu bar (Mac) or system tray (Windows, Linux) icon: the waiting-question count on its
 * badge and in its hover text, and a menu with Open, Mark all read, the three switches and Quit.
 *
 * Some Linux desktops (GNOME without an extension) have no tray: then [supported] is false and
 * the app quits when its window closes, so it can never be left running out of sight.
 */
class DesktopTray(private val actions: TrayActions, settings: DesktopSettings, private val startAtLoginAvailable: Boolean) {
    val supported: Boolean = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    private val style = if (DesktopOs.current() == DesktopOs.Mac) TrayStyle.MenuBar else TrayStyle.Tile

    private var icon: TrayIcon? = null
    private var look = TrayLook.of(null)

    /** The card the last notification was about, for a click on it (Windows reports that as the icon's action). */
    @Volatile
    private var lastFocus: Pair<String, Long>? = null

    private val keepRunning = CheckboxMenuItem("Keep running when the window closes", settings.keepRunningInTray)
    private val startAtLogin = CheckboxMenuItem("Start at login", settings.startAtLogin)
    private val doNotDisturb = CheckboxMenuItem("Do not disturb", settings.doNotDisturb)
    private val checkAutomatically = CheckboxMenuItem("Check for updates automatically", settings.checkForUpdates)

    /** "Update to 1.0.2…", at the top of the menu while a newer version is out. */
    private val updateItem = MenuItem("Update…").apply { addActionListener { actions.openUpdate() } }
    private var menu: PopupMenu? = null
    private var updateVersion: String? = null

    fun install() {
        if (!supported) return
        EventQueue.invokeLater {
            val menu = PopupMenu().apply {
                updateVersion?.let { version ->
                    updateItem.label = "Update to $version…"
                    add(updateItem)
                    addSeparator()
                }
                add(MenuItem("Open Agents").apply { addActionListener { actions.open(null) } })
                add(MenuItem("Mark all read").apply { addActionListener { actions.markAllRead() } })
                addSeparator()
                add(keepRunning.apply { addItemListener { actions.setKeepRunning(it.stateChange == ItemEvent.SELECTED) } })
                add(startAtLogin.apply {
                    isEnabled = startAtLoginAvailable
                    if (!startAtLoginAvailable) label = "Start at login (installed app only)"
                    addItemListener { event ->
                        val on = actions.setStartAtLogin(event.stateChange == ItemEvent.SELECTED)
                        state = on
                    }
                })
                add(doNotDisturb.apply { addItemListener { actions.setDoNotDisturb(it.stateChange == ItemEvent.SELECTED) } })
                add(checkAutomatically.apply { addItemListener { actions.setCheckForUpdates(it.stateChange == ItemEvent.SELECTED) } })
                add(MenuItem("Check for updates…").apply { addActionListener { actions.checkForUpdates() } })
                addSeparator()
                add(MenuItem("Quit Agents").apply { addActionListener { actions.quit() } })
            }
            this.menu = menu
            val tray = SystemTray.getSystemTray()
            val size = tray.trayIconSize.let { maxOf(it.width, it.height, 16) }
            icon = TrayIcon(TrayIconImage.draw(size * 2, look.badge, style), look.tooltip, menu).apply {
                isImageAutoSize = true
                // A click on the icon (and, on Windows, on a notification it showed) opens the app.
                addActionListener {
                    val focus = lastFocus?.takeIf { (_, at) -> System.currentTimeMillis() - at < NoticeClickMillis }?.first
                    lastFocus = null
                    actions.open(focus)
                }
            }
            runCatching { tray.add(icon) }
        }
    }

    fun show(look: TrayLook) {
        if (look == this.look) return
        this.look = look
        EventQueue.invokeLater {
            val icon = icon ?: return@invokeLater
            val size = SystemTray.getSystemTray().trayIconSize.let { maxOf(it.width, it.height, 16) }
            icon.image = TrayIconImage.draw(size * 2, look.badge, style)
            icon.toolTip = look.tooltip
        }
    }

    /** Adds "Update to [version]…" to the top of the menu, or takes it away when [version] is null. */
    fun showUpdate(version: String?) {
        EventQueue.invokeLater {
            if (version == updateVersion) return@invokeLater
            val had = updateVersion != null
            updateVersion = version
            val menu = menu ?: return@invokeLater
            if (had) {
                // The item and the separator after it.
                menu.remove(1)
                menu.remove(0)
            }
            if (version != null) {
                updateItem.label = "Update to $version…"
                menu.insert(updateItem, 0)
                menu.insertSeparator(1)
            }
        }
    }

    /** Shows [notice] from the tray icon. Returns false when there is no tray to show it from. */
    fun notify(notice: DesktopNotice): Boolean {
        val icon = icon ?: return false
        lastFocus = notice.focusId to System.currentTimeMillis()
        EventQueue.invokeLater { icon.displayMessage(notice.title, notice.body, TrayIcon.MessageType.NONE) }
        return true
    }

    fun remove() {
        EventQueue.invokeLater { icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } } }
    }

    private companion object {
        /** A click within this long of a notification is taken as a click on it. */
        const val NoticeClickMillis = 60_000L
    }
}
