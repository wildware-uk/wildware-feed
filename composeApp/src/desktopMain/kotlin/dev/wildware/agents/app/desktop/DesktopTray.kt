package dev.wildware.agents.app.desktop

import dev.wildware.agents.app.ProductName
import dev.wildware.agents.app.update.TrayUpdate
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

    /** "Check for updates…": checks now, without opening the window, and says what it found. */
    fun checkForUpdates()

    /** "Install update 1.0.2": installs it and restarts, as the dialog's button does. */
    fun installUpdate()

    /** "Update to 1.0.2…": shows the window with the update's notes and button. */
    fun openUpdate()

    fun setCheckForUpdates(on: Boolean)

    /** "Restart to update to 1.0.2": puts in the version downloaded in the background and opens the app again. */
    fun restartToUpdate() {}

    /** "What's new in 1.0.2…": shows the window with the update's notes. */
    fun showUpdateNotes() = openUpdate()

    fun setUpdateAutomatically(on: Boolean) {}

    /** "This computer…": shows the window with the settings for the server this app runs. */
    fun openThisComputer() {}
}

/**
 * The menu bar (Mac) or system tray (Windows, Linux) icon: the waiting-question count on its
 * badge and in its hover text, and a menu with Open, Mark all read, the update item, the
 * switches and Quit.
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
    private var lastFocus: Pair<String?, Long>? = null

    private val keepRunning = CheckboxMenuItem("Keep running when the window closes", settings.keepRunningInTray)
    private val startAtLogin = CheckboxMenuItem("Start at login", settings.startAtLogin)
    private val doNotDisturb = CheckboxMenuItem("Do not disturb", settings.doNotDisturb)
    private val checkAutomatically = CheckboxMenuItem("Check for updates automatically", settings.checkForUpdates)
    private val updateAutomatically = CheckboxMenuItem("Update automatically", settings.updateAutomatically)

    /** "Check for updates…", "Checking…", then "Install update 1.0.2" once one is found. */
    @Volatile
    private var update: TrayUpdate = TrayUpdate.Check
    private val updateItem = MenuItem(update.label).apply {
        addActionListener {
            when (update) {
                TrayUpdate.Check -> actions.checkForUpdates()
                is TrayUpdate.Install -> actions.installUpdate()
                is TrayUpdate.Open -> actions.openUpdate()
                is TrayUpdate.Restart -> actions.restartToUpdate()
                TrayUpdate.Checking, TrayUpdate.Installing, is TrayUpdate.Downloading -> Unit
            }
        }
    }

    /** "What's new in 1.0.2…", after the update item while a newer version is out. */
    private val notesItem = MenuItem("What's new…").apply { addActionListener { actions.showUpdateNotes() } }

    @Volatile
    private var notesFor: String? = null

    /** "This computer…", in the menu only while signed in to the server this app runs. */
    private val thisComputerItem = MenuItem("This computer…").apply { addActionListener { actions.openThisComputer() } }
    private var menu: PopupMenu? = null

    @Volatile
    private var showingThisComputer = false

    fun install() {
        if (!supported) return
        EventQueue.invokeLater {
            val menu = PopupMenu().apply {
                add(MenuItem("Open $ProductName").apply { addActionListener { actions.open(null) } })
                add(MenuItem("Mark all read").apply { addActionListener { actions.markAllRead() } })
                add(updateItem)
                notesFor?.let { notesItem.label = notesLabel(it); add(notesItem) }
                if (showingThisComputer) add(thisComputerItem)
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
                add(updateAutomatically.apply { addItemListener { actions.setUpdateAutomatically(it.stateChange == ItemEvent.SELECTED) } })
                addSeparator()
                add(MenuItem("Quit $ProductName").apply { addActionListener { actions.quit() } })
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

    /** Adds or removes "This computer…", after the update item. */
    fun showThisComputer(show: Boolean) {
        if (show == showingThisComputer) return
        showingThisComputer = show
        EventQueue.invokeLater {
            val menu = menu ?: return@invokeLater
            if (show) menu.insert(thisComputerItem, menu.indexOf(updateItem) + 1 + (if (notesFor != null) 1 else 0)) else menu.remove(thisComputerItem)
        }
    }

    /** Keeps the "Update automatically" tick in step when it's changed from the window. */
    fun showUpdateAutomatically(on: Boolean) {
        EventQueue.invokeLater { if (updateAutomatically.state != on) updateAutomatically.state = on }
    }

    /** Shows "What's new in [version]…" after the update item, or takes it away when null. */
    fun showUpdateNotes(version: String?) {
        if (version == notesFor) return
        val was = notesFor
        notesFor = version
        EventQueue.invokeLater {
            val menu = menu ?: return@invokeLater
            if (version == null) {
                menu.remove(notesItem)
                return@invokeLater
            }
            notesItem.label = notesLabel(version)
            if (was == null) menu.insert(notesItem, menu.indexOf(updateItem) + 1)
        }
    }

    private fun notesLabel(version: String) = "What's new in $version…"

    private fun PopupMenu.indexOf(item: MenuItem): Int = (0 until itemCount).firstOrNull { getItem(it) === item } ?: (itemCount - 1)

    /** Shows [item] as the menu's update item. */
    fun showUpdate(item: TrayUpdate) {
        update = item
        EventQueue.invokeLater {
            updateItem.label = item.label
            updateItem.isEnabled = item.enabled
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
