package dev.wildware.agents.app.update

import dev.wildware.agents.app.ProductName
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** A step of installing failed. [message] is fit to show the owner. */
class InstallException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The script that finishes the update once the app has quit, and how to start it. */
data class Helper(
    val script: File,
    val command: List<String>,
    /** Variables the helper must not inherit (an AppImage's own, which would confuse the new one). */
    val dropEnv: List<String> = emptyList(),
)

/** What happens after an install is prepared. */
sealed interface Prepared {
    /** Everything checked and staged: start [helper], then quit. */
    data class Restart(val helper: Helper) : Prepared

    /** The app can't finish this itself: tell the owner [message] and show them [folder]. */
    data class Manual(val message: String, val folder: File?) : Prepared
}

/** Puts a verified download in place. The real one is [SystemInstaller]; tests use a fake. */
interface Installer {
    /** Where to save the download: beside an AppImage (so it can be renamed over it), else in [workDir]. */
    fun downloadTarget(installation: Installation, asset: ReleaseAsset, workDir: File): File

    /**
     * Gets everything ready that can be done while the app runs, and checks it worked, so a failure
     * here changes nothing. Throws [InstallException].
     */
    fun prepare(installation: Installation, download: File, workDir: File, pid: Long): Prepared

    /** Starts [helper] so that it outlives this app. Throws [InstallException]. */
    fun launch(helper: Helper)

    /** Undoes what [prepare] left staged (a mounted image, an unpacked folder) when the update stops short. */
    fun abandon(installation: Installation, workDir: File)
}

/** Runs a program to the end. Swappable so tests never start a real one. */
fun interface Commands {
    /** The exit code and everything it printed; exit code -1 when it couldn't start or ran out of time. */
    fun run(command: List<String>, timeoutSeconds: Long): Pair<Int, String>

    companion object {
        val System = Commands { command, timeoutSeconds ->
            runCatching {
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                val output = StringBuilder()
                val reader = Thread { output.append(process.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return@runCatching -1 to "timed out"
                }
                reader.join(2_000)
                process.exitValue() to output.toString()
            }.getOrElse { -1 to (it.message ?: "could not start ${command.first()}") }
        }
    }
}

/** The real installer: disk images on a Mac, msiexec or a folder swap on Windows, AppImage or package manager on Linux. */
class SystemInstaller(
    private val commands: Commands = Commands.System,
    /** Finds a program on the PATH, or null. */
    private val which: (String) -> File? = ::onPath,
) : Installer {
    override fun downloadTarget(installation: Installation, asset: ReleaseAsset, workDir: File): File {
        if (installation is Installation.LinuxAppImage) {
            val image = File(installation.file)
            val dir = image.absoluteFile.parentFile
            if (dir != null && canWriteIn(dir)) return File(dir, ".${image.name}.update")
        }
        return File(workDir, asset.name)
    }

    override fun prepare(installation: Installation, download: File, workDir: File, pid: Long): Prepared = when (installation) {
        is Installation.MacBundle -> prepareMac(installation, download, workDir, pid)
        is Installation.WindowsMsi -> prepareMsi(installation, download, workDir, pid)
        is Installation.WindowsPortable -> preparePortable(installation, download, workDir, pid)
        is Installation.LinuxAppImage -> prepareAppImage(installation, download, workDir, pid)
        is Installation.LinuxPackage -> preparePackage(installation, download, workDir, pid)
        is Installation.Unsupported -> throw InstallException(installation.reason)
    }

    private fun prepareMac(installation: Installation.MacBundle, dmg: File, workDir: File, pid: Long): Prepared {
        val app = File(installation.app)
        val parent = app.absoluteFile.parentFile
        if (parent == null || !canWriteIn(parent) || !app.canWrite()) {
            return Prepared.Manual(
                "$ProductName is in a folder this account can't change, so it can't update itself. " +
                    "Open the downloaded disk image and drag $ProductName into Applications.",
                dmg.parentFile,
            )
        }
        val mount = File(workDir, "mount").apply { mkdirs() }
        val (code, output) = commands.run(
            listOf("hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount.absolutePath, dmg.absolutePath),
            120,
        )
        if (code != 0) throw InstallException("Couldn't open the downloaded disk image. ${lastLine(output)}".trim())
        val newApp = mount.listFiles()?.firstOrNull { it.name.endsWith(".app") && it.isDirectory }
        if (newApp == null) {
            commands.run(listOf("hdiutil", "detach", mount.absolutePath, "-force", "-quiet"), 60)
            throw InstallException("The disk image has no app in it. Nothing was installed.")
        }
        val script = File(workDir, "update.sh")
        script.writeText(HelperScripts.mac(pid, app.absolutePath, newApp.absolutePath, mount.absolutePath, File(workDir, "update.log").absolutePath))
        return Prepared.Restart(Helper(script, listOf("/usr/bin/nohup", "/bin/sh", script.absolutePath)))
    }

    private fun prepareMsi(installation: Installation.WindowsMsi, msi: File, workDir: File, pid: Long): Prepared {
        val script = File(workDir, "update.ps1")
        script.writeText(
            HelperScripts.windowsMsi(pid, msi.absolutePath, msiLaunchers(installation.exe), File(workDir, "update.log").absolutePath, File(workDir, "msiexec.log").absolutePath),
        )
        return Prepared.Restart(Helper(script, powershell(script)))
    }

    private fun preparePortable(installation: Installation.WindowsPortable, zip: File, workDir: File, pid: Long): Prepared {
        val folder = File(installation.folder)
        val parent = folder.absoluteFile.parentFile
        if (parent == null || !canWriteIn(parent)) {
            return Prepared.Manual(
                "$ProductName is in a folder this account can't change, so it can't update itself. Unzip the download over it by hand.",
                zip.parentFile,
            )
        }
        val staged = File(parent, folder.name + ".updating")
        staged.deleteRecursively()
        try {
            unzipStrippingTop(zip, staged)
        } catch (e: Exception) {
            staged.deleteRecursively()
            throw InstallException("Couldn't unpack the download. Nothing was installed.", e)
        }
        // The new release may call the program by its new name; the folder keeps the owner's.
        val exeName = LauncherNames.map { "$it.exe" }.firstOrNull { File(staged, it).isFile }
        if (exeName == null) {
            staged.deleteRecursively()
            throw InstallException("The download has no $ProductName.exe in it. Nothing was installed.")
        }
        val exe = (Installation.parentOf(installation.exe) ?: folder.absolutePath) + "\\" + exeName
        val script = File(workDir, "update.ps1")
        script.writeText(
            HelperScripts.windowsPortable(pid, folder.absolutePath, staged.absolutePath, exe, File(workDir, "update.log").absolutePath),
        )
        return Prepared.Restart(Helper(script, powershell(script)))
    }

    private fun prepareAppImage(installation: Installation.LinuxAppImage, download: File, workDir: File, pid: Long): Prepared {
        val target = File(installation.file).absoluteFile
        val dir = target.parentFile
        if (dir == null || !canWriteIn(dir)) {
            return Prepared.Manual(
                "$ProductName is in a folder this account can't change, so it can't update itself. The new AppImage is in this folder.",
                download.parentFile,
            )
        }
        replaceAppImage(download, target)
        val script = File(workDir, "relaunch.sh")
        script.writeText(HelperScripts.linuxRelaunch(pid, target.absolutePath, File(workDir, "update.log").absolutePath))
        return Prepared.Restart(Helper(script, detached(script), dropEnv = AppImageEnv))
    }

    private fun preparePackage(installation: Installation.LinuxPackage, file: File, workDir: File, pid: Long): Prepared {
        val deb = installation.kind == AssetKind.LinuxDeb
        val by = if (deb) "sudo apt install ${file.absolutePath}" else "sudo dnf install ${file.absolutePath}"
        val pkexec = which("pkexec")
            ?: return Prepared.Manual("This computer can't ask for your password from the app. Install the update from a terminal with:\n\n$by", file.parentFile)
        val install = when {
            deb -> listOf(pkexec.absolutePath, "apt-get", "install", "-y", file.absolutePath)
            which("dnf") != null -> listOf(pkexec.absolutePath, "dnf", "install", "-y", file.absolutePath)
            else -> listOf(pkexec.absolutePath, "rpm", "-U", file.absolutePath)
        }
        val (code, output) = commands.run(install, 15 * 60)
        when (code) {
            0 -> Unit
            // pkexec: 126 when the password box was dismissed, 127 when not allowed.
            126, 127 -> throw InstallException("The install was cancelled, so nothing changed.")
            else -> throw InstallException("The package manager couldn't install the update. ${lastLine(output)}".trim())
        }
        val script = File(workDir, "relaunch.sh")
        script.writeText(HelperScripts.linuxRelaunch(pid, packageLauncher(installation.launcher), File(workDir, "update.log").absolutePath))
        return Prepared.Restart(Helper(script, detached(script)))
    }

    override fun launch(helper: Helper) {
        try {
            helper.script.setExecutable(true, true)
            val builder = ProcessBuilder(helper.command)
                .redirectInput(ProcessBuilder.Redirect.from(nullFile()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .directory(helper.script.parentFile)
            helper.dropEnv.forEach { builder.environment().remove(it) }
            builder.start()
        } catch (e: Exception) {
            throw InstallException("Couldn't start the step that finishes the update. Nothing was installed.", e)
        }
    }

    override fun abandon(installation: Installation, workDir: File) {
        when (installation) {
            is Installation.MacBundle -> {
                val mount = File(workDir, "mount")
                if (mount.exists()) commands.run(listOf("hdiutil", "detach", mount.absolutePath, "-force", "-quiet"), 60)
            }
            is Installation.WindowsPortable -> File(installation.folder).absoluteFile.let { File(it.parentFile, it.name + ".updating").deleteRecursively() }
            else -> Unit
        }
    }

    /** A detached shell on Linux: in its own session, so it outlives the app. */
    private fun detached(script: File): List<String> =
        listOfNotNull(which("setsid")?.absolutePath, "/bin/sh", script.absolutePath)

    private fun powershell(script: File) =
        listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.absolutePath)

    private fun nullFile() = File(if (File.separatorChar == '\\') "NUL" else "/dev/null")

    companion object {
        /**
         * Where the .msi puts the program, newest name first: an upgrade from before the rename
         * moves it from `%LOCALAPPDATA%\Agents` to `%LOCALAPPDATA%\Wildware Feed`.
         */
        fun msiLaunchers(currentExe: String): List<String> {
            val localAppData = Installation.parentOf(currentExe)?.let(Installation::parentOf) ?: return listOf(currentExe)
            return (LauncherNames.map { "$localAppData\\$it\\$it.exe" } + currentExe).distinct()
        }

        /** The package's program after an upgrade: same folder, maybe a new name. */
        fun packageLauncher(current: String, exists: (File) -> Boolean = File::exists): String {
            val bin = File(current).parentFile ?: return current
            return LauncherNames.map { File(bin, it) }.firstOrNull(exists)?.path ?: current
        }

        /** What an AppImage sets for itself; the new one sets its own. */
        val AppImageEnv = listOf("APPIMAGE", "APPDIR", "ARGV0", "OWD")

        fun onPath(name: String): File? =
            System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).asSequence()
                .filter { it.isNotBlank() }
                .map { File(it, name) }
                .firstOrNull { it.isFile && it.canExecute() }

        /** Whether this account can make files in [dir]: tried for real, since Windows folders lie about it. */
        fun canWriteIn(dir: File): Boolean = runCatching {
            val probe = File.createTempFile(".agents-write-check", null, dir)
            probe.delete()
            true
        }.getOrDefault(false)

        /**
         * Makes [download] the AppImage at [target] in one rename, so the file is never half there.
         * A running AppImage keeps the old copy open until it quits.
         */
        fun replaceAppImage(download: File, target: File) {
            val dir = target.parentFile
            val staged = if (download.parentFile?.absoluteFile == dir.absoluteFile) download else {
                val copy = File(dir, ".${target.name}.update")
                download.copyTo(copy, overwrite = true)
                copy
            }
            try {
                staged.setExecutable(true, false)
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                staged.delete()
                throw InstallException("Couldn't put the new AppImage in place. Nothing was changed.", e)
            }
        }

        /** Unpacks [zip] into [into], dropping its single top folder ("Wildware Feed/…"). Refuses paths that climb out. */
        fun unzipStrippingTop(zip: File, into: File) {
            into.mkdirs()
            val root = into.canonicalFile
            ZipInputStream(zip.inputStream().buffered()).use { stream ->
                while (true) {
                    val entry = stream.nextEntry ?: break
                    val name = entry.name.replace('\\', '/').trimStart('/')
                    val inner = name.substringAfter('/', "")
                    if (inner.isEmpty()) continue
                    val out = File(root, inner).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) throw InstallException("The download has a file outside its folder: $name")
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile.mkdirs()
                        out.outputStream().use { stream.copyTo(it) }
                    }
                }
            }
        }

        private fun lastLine(output: String) = output.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty().take(200)
    }
}
