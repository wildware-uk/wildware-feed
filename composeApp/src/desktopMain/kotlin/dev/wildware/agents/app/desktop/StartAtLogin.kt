package dev.wildware.agents.app.desktop

import dev.wildware.agents.app.ProductName

import java.io.File

/** Which desktop system the app runs on. */
enum class DesktopOs {
    Mac, Windows, Linux;

    companion object {
        fun current(name: String = System.getProperty("os.name")): DesktopOs {
            val os = name.lowercase()
            return when {
                "mac" in os -> Mac
                "win" in os -> Windows
                else -> Linux
            }
        }
    }
}

/**
 * Starts the app when the owner logs in, in the tray with no window ([BackgroundFlag]), the way
 * each system expects:
 *
 * - Mac: a LaunchAgent, `~/Library/LaunchAgents/dev.wildware.agents.desktop.plist`.
 * - Windows: an `Agents` value (the app's name before it was Wildware Feed, kept so it is one entry) under `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`.
 * - Linux: an XDG autostart entry, `~/.config/autostart/dev.wildware.agents.desktop.desktop`.
 *
 * Only an installed app can do this: [launcher] is the installed program, which the installers
 * name in `jpackage.app-path` (or `APPIMAGE` for an AppImage). Run from Gradle there is none,
 * and [available] is false.
 */
class StartAtLogin(
    private val os: DesktopOs = DesktopOs.current(),
    private val home: File = File(System.getProperty("user.home")),
    private val launcher: String? = installedLauncher(),
    private val run: (List<String>) -> Int = ::runCommand,
) {
    val available: Boolean get() = launcher != null

    fun isEnabled(): Boolean = when (os) {
        DesktopOs.Mac -> macFile().exists()
        DesktopOs.Linux -> linuxFile().exists()
        DesktopOs.Windows -> run(listOf("reg", "query", RunKey, "/v", RegistryValue)) == 0
    }

    /** Turns it on or off. Returns false when it couldn't, such as when the app isn't installed. */
    fun setEnabled(enabled: Boolean): Boolean {
        if (!enabled) return disable()
        val program = launcher ?: return false
        return runCatching {
            when (os) {
                DesktopOs.Mac -> write(macFile(), macPlist(program))
                DesktopOs.Linux -> write(linuxFile(), linuxEntry(program))
                DesktopOs.Windows -> run(listOf("reg", "add", RunKey, "/v", RegistryValue, "/t", "REG_SZ", "/d", "\"$program\" $BackgroundFlag", "/f")) == 0
            }
        }.getOrDefault(false)
    }

    /**
     * Points an existing entry at the program as it is now. An update can move or rename it
     * (Agents became Wildware Feed), which would leave the login start pointing at nothing.
     */
    fun refresh() {
        if (available && isEnabled()) setEnabled(true)
    }

    private fun disable(): Boolean = runCatching {
        when (os) {
            DesktopOs.Mac -> macFile().let { !it.exists() || it.delete() }
            DesktopOs.Linux -> linuxFile().let { !it.exists() || it.delete() }
            DesktopOs.Windows -> run(listOf("reg", "delete", RunKey, "/v", RegistryValue, "/f")).let { true }
        }
    }.getOrDefault(false)

    private fun write(file: File, text: String): Boolean {
        file.parentFile.mkdirs()
        file.writeText(text)
        return true
    }

    private fun macFile() = File(home, "Library/LaunchAgents/$BundleId.plist")
    private fun linuxFile() = File(home, ".config/autostart/$BundleId.desktop")

    companion object {
        /** Hidden, so it stays what it was: renaming it would leave a second, dead entry behind. */
        const val RegistryValue = "Agents"
        const val BundleId = "dev.wildware.agents.desktop"

        /** Passed by the login start: open in the tray, without a window. */
        const val BackgroundFlag = "--background"

        const val RunKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

        fun installedLauncher(): String? =
            System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() && File(it).exists() }

        fun macPlist(program: String): String = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            |<plist version="1.0">
            |<dict>
            |    <key>Label</key>
            |    <string>$BundleId</string>
            |    <key>ProgramArguments</key>
            |    <array>
            |        <string>${xml(program)}</string>
            |        <string>$BackgroundFlag</string>
            |    </array>
            |    <key>RunAtLoad</key>
            |    <true/>
            |</dict>
            |</plist>
            |""".trimMargin()

        fun linuxEntry(program: String): String = """
            |[Desktop Entry]
            |Type=Application
            |Name=$ProductName
            |Comment=Questions and updates from your agents
            |Exec=${desktopExec(program)} $BackgroundFlag
            |Icon=${iconFor(program)}
            |Terminal=false
            |X-GNOME-Autostart-enabled=true
            |""".trimMargin()

        /** The icon the Linux package installs beside the program (`/opt/agents/lib/Wildware Feed.png`), or the theme's name. */
        private fun iconFor(program: String): String {
            val lib = File(program).parentFile?.parentFile?.let { File(it, "lib") } ?: return "agents"
            return listOf("$ProductName.png", "Agents.png").map { File(lib, it) }.firstOrNull { it.exists() }?.path ?: "agents"
        }

        private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        /** Quotes a path for a desktop entry's Exec line, which splits on spaces. */
        private fun desktopExec(path: String): String =
            if (path.none { it == ' ' || it == '"' || it == '\\' }) path
            else "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun runCommand(command: List<String>): Int = runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            process.waitFor()
        }.getOrDefault(-1)
    }
}
