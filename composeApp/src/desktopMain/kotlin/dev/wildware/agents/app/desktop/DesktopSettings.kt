package dev.wildware.agents.app.desktop

import dev.wildware.agents.app.session.writePrivately
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The desktop app's own switches, set from the tray menu. */
@Serializable
data class DesktopSettings(
    /** Closing the window leaves the app running in the tray, so notifications keep coming. */
    val keepRunningInTray: Boolean = true,
    /** Off until the owner turns it on. Mirrors what is registered with the system. */
    val startAtLogin: Boolean = false,
    /** No desktop notifications at all while on. The tray count still updates. */
    val doNotDisturb: Boolean = false,
    /** Looks for a newer version at start and every few hours. */
    val checkForUpdates: Boolean = true,
    /**
     * Downloads a newer version in the background as soon as it's found, then offers one
     * "Restart to update" button; quitting puts it in too. Off: a newer version is only offered,
     * and nothing downloads until the owner clicks Install.
     */
    val updateAutomatically: Boolean = true,
)

/** Reads and writes [DesktopSettings] as `desktop.json` in the app's folder. A broken file reads as the defaults. */
class DesktopSettingsStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): DesktopSettings =
        runCatching { json.decodeFromString(DesktopSettings.serializer(), file.readText()) }.getOrDefault(DesktopSettings())

    fun save(settings: DesktopSettings) {
        runCatching { writePrivately(file, json.encodeToString(DesktopSettings.serializer(), settings)) }
    }
}
