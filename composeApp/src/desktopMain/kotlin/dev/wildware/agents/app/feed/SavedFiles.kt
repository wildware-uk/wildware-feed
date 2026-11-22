package dev.wildware.agents.app.feed

import dev.wildware.agents.app.desktop.DesktopOs
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Where a desktop keeps downloads, and how to show one there, the way each system does it:
 *
 * - Windows: the Downloads folder the owner chose (it can be moved, say to D:), read from the
 *   registry; shown with `explorer /select,`, which opens the folder with the file picked out.
 * - Mac: `~/Downloads`; shown with `open -R`, Finder's "reveal".
 * - Linux: `XDG_DOWNLOAD_DIR` from `~/.config/user-dirs.dirs`; shown through the file manager's
 *   own "show items" call (Files, Dolphin, Nemo and others answer it), else the folder is opened.
 *
 * The file itself is never opened: a file from an agent could be a program.
 */
object SavedFiles {
    /** The registry value Windows keeps the Downloads folder in (its known-folder id). */
    private const val ShellFolders = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders"
    private const val DownloadsId = "{374DE290-123F-4565-9164-39C4925E467B}"

    /** The command that asks Windows where Downloads is. */
    val windowsQuery: List<String> = listOf("reg", "query", ShellFolders, "/v", DownloadsId)

    /** Downloads on this computer, or the home folder when there is none. */
    fun downloads(
        os: DesktopOs = DesktopOs.current(),
        home: File = File(System.getProperty("user.home")),
        env: (String) -> String? = System::getenv,
        run: (List<String>) -> String? = ::output,
    ): File {
        val chosen = when (os) {
            DesktopOs.Windows -> run(windowsQuery)?.let { windowsDownloads(it, env) }
            DesktopOs.Linux -> File(env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".config").path, "user-dirs.dirs")
                .takeIf { it.isFile }?.let { runCatching { linuxDownloads(it.readText(), home) }.getOrNull() }
            DesktopOs.Mac -> null
        }
        return chosen?.takeIf { it.isDirectory }
            ?: File(home, "Downloads").takeIf { it.isDirectory }
            ?: home
    }

    /**
     * The folder in `reg query` [output], with `%USERPROFILE%` and the like filled in from [env]:
     * `    {374DE290-…}    REG_EXPAND_SZ    %USERPROFILE%\Downloads`.
     */
    fun windowsDownloads(output: String, env: (String) -> String?): File? {
        val line = output.lineSequence().firstOrNull { it.trim().startsWith(DownloadsId, ignoreCase = true) } ?: return null
        val value = line.trim().split(Regex("\\s+"), limit = 3).getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var missing = false
        val path = Regex("%([^%]+)%").replace(value) { m -> env(m.groupValues[1]) ?: run { missing = true; "" } }
        return if (missing) null else File(path)
    }

    /** `XDG_DOWNLOAD_DIR="$HOME/Downloads"` from a user-dirs.dirs [text]; null when it isn't set or is just home. */
    fun linuxDownloads(text: String, home: File): File? {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("XDG_DOWNLOAD_DIR=") } ?: return null
        val value = line.removePrefix("XDG_DOWNLOAD_DIR=").trim().removeSurrounding("\"")
        val path = value.replace("\$HOME", home.path).replace("\${HOME}", home.path)
        if (!path.startsWith("/")) return null
        return File(path).takeIf { it.absoluteFile != home.absoluteFile }
    }

    /**
     * The commands that show [file] in its folder, in order: each but the last is tried and must
     * succeed, and the last is the fallback. Windows' `explorer` answers 1 even when it worked,
     * so a single command is never judged by its answer.
     */
    fun revealCommands(os: DesktopOs, file: File): List<List<String>> {
        val path = file.absolutePath
        return when (os) {
            // One argument, which Java quotes whole when the path has spaces: explorer takes "/select,C:\a b\c.pdf".
            DesktopOs.Windows -> listOf(listOf("explorer.exe", "/select,$path"))
            DesktopOs.Mac -> listOf(listOf("open", "-R", path))
            DesktopOs.Linux -> listOf(
                listOf(
                    "dbus-send", "--session", "--print-reply", "--dest=org.freedesktop.FileManager1", "--type=method_call",
                    "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.ShowItems",
                    "array:string:${file.absoluteFile.toURI()}", "string:",
                ),
                listOf("xdg-open", file.absoluteFile.parent),
            )
        }
    }

    /** Shows [file] in its folder. False when nothing could be started. */
    fun reveal(file: File, os: DesktopOs = DesktopOs.current(), start: (List<String>) -> Process? = ::startQuietly): Boolean {
        val commands = revealCommands(os, file)
        commands.dropLast(1).forEach { command ->
            val process = start(command) ?: return@forEach
            if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) return true
            process.destroy()
        }
        return start(commands.last()) != null
    }

    /** The command that opens [file] in the app the computer picks for it. */
    fun openCommand(os: DesktopOs, file: File): List<String> = when (os) {
        DesktopOs.Windows -> listOf("explorer.exe", file.absolutePath)
        DesktopOs.Mac -> listOf("open", file.absolutePath)
        DesktopOs.Linux -> listOf("xdg-open", file.absolutePath)
    }

    /** Opens [file] in the computer's own app for it. Only ever given a picture the app checked. False when nothing started. */
    fun open(file: File, os: DesktopOs = DesktopOs.current(), start: (List<String>) -> Process? = ::startQuietly): Boolean =
        start(openCommand(os, file)) != null

    private fun startQuietly(command: List<String>): Process? = runCatching {
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }.getOrNull()

    private fun output(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) text else null
    }.getOrNull()
}
