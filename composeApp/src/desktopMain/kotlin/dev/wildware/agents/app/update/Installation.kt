package dev.wildware.agents.app.update

import dev.wildware.agents.app.desktop.DesktopOs
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * What the installed program is called: the new name first, then the one releases up to 1.0.1
 * used. An update can find either, and replaces the old one in place.
 */
val LauncherNames = listOf("Wildware Feed", "Agents")

/**
 * How this copy of the app was installed, which decides which file an update needs and how it
 * goes in. Paths are kept as text so Windows paths read the same when tested on Linux.
 */
sealed interface Installation {
    /** The installer kind that updates this copy, or null when it can't update itself. */
    val kind: AssetKind?

    /** The app bundle, e.g. `/Applications/Wildware Feed.app` (or `Agents.app` from before the rename). */
    data class MacBundle(val app: String) : Installation {
        override val kind = AssetKind.MacDmg
    }

    /** Installed from the .msi, per user, into `%LOCALAPPDATA%\Wildware Feed` (`%LOCALAPPDATA%\Agents` before the rename). */
    data class WindowsMsi(val exe: String) : Installation {
        override val kind = AssetKind.WindowsMsi
    }

    /** Unzipped from the portable .zip into [folder] (the one holding the .exe). */
    data class WindowsPortable(val folder: String, val exe: String) : Installation {
        override val kind = AssetKind.WindowsZip
    }

    /** A single .AppImage file, run from wherever the owner keeps it. */
    data class LinuxAppImage(val file: String) : Installation {
        override val kind = AssetKind.LinuxAppImage
    }

    /** Installed by the system's package manager from the .deb or .rpm; [launcher] is the program. */
    data class LinuxPackage(val launcher: String, override val kind: AssetKind) : Installation

    /** Not installed from a release (run from the source, say), so there is nothing to replace. */
    data class Unsupported(val reason: String) : Installation {
        override val kind: AssetKind? = null
    }

    companion object {
        /** The real one, from the launcher's own properties and environment. */
        fun current(): Installation = detect(
            os = DesktopOs.current(),
            appPath = System.getProperty("jpackage.app-path"),
            env = System.getenv(),
            exists = { File(it).exists() },
        )

        /**
         * Works it out from [appPath] (`jpackage.app-path`, set by the installed launcher only),
         * `APPIMAGE` (set by an AppImage), and where things are on disk.
         */
        fun detect(os: DesktopOs, appPath: String?, env: Map<String, String>, exists: (String) -> Boolean): Installation {
            val notInstalled = Unsupported("This copy wasn't installed from a release, so it can't update itself.")
            return when (os) {
                DesktopOs.Mac -> {
                    val app = appPath?.let(::macBundleOf) ?: return notInstalled
                    MacBundle(app)
                }
                DesktopOs.Windows -> {
                    val exe = appPath?.takeIf { it.isNotBlank() } ?: return notInstalled
                    val folder = parentOf(exe) ?: return notInstalled
                    val local = env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }
                    val msiFolder = local != null && LauncherNames.any { samePath(folder, joinWindows(local, it)) }
                    if (msiFolder) WindowsMsi(exe) else WindowsPortable(folder, exe)
                }
                DesktopOs.Linux -> {
                    env["APPIMAGE"]?.takeIf { it.isNotBlank() }?.let { return LinuxAppImage(it) }
                    val launcher = appPath?.takeIf { it.isNotBlank() } ?: return notInstalled
                    when {
                        // dpkg keeps a list of every installed package's files here.
                        exists("/var/lib/dpkg/info/agents.list") -> LinuxPackage(launcher, AssetKind.LinuxDeb)
                        exists("/usr/bin/rpm") -> LinuxPackage(launcher, AssetKind.LinuxRpm)
                        else -> Unsupported("This copy wasn't installed from the .deb, .rpm or AppImage, so it can't update itself.")
                    }
                }
            }
        }

        /** `/Applications/Wildware Feed.app/Contents/MacOS/Wildware Feed` → `/Applications/Wildware Feed.app`. */
        private fun macBundleOf(path: String): String? {
            val at = path.indexOf(".app/")
            return when {
                at >= 0 -> path.substring(0, at + 4)
                path.endsWith(".app") -> path
                else -> null
            }
        }

        internal fun parentOf(path: String): String? {
            val at = path.trimEnd('/', '\\').lastIndexOfAny(charArrayOf('/', '\\'))
            return if (at <= 0) null else path.substring(0, at)
        }

        private fun joinWindows(dir: String, name: String) = dir.trimEnd('\\', '/') + "\\" + name

        private fun samePath(a: String, b: String): Boolean {
            fun norm(p: String) = p.replace('/', '\\').trimEnd('\\').lowercase()
            return norm(a) == norm(b)
        }
    }
}

/**
 * The file in [release] that updates [installation] on a processor of [arch] (`os.arch`), or
 * null when the release has none for it.
 */
fun chooseAsset(release: Release, installation: Installation, arch: String = System.getProperty("os.arch").orEmpty()): ReleaseAsset? {
    val kind = installation.kind ?: return null
    if (!archFits(kind, arch)) return null
    return release.assets.firstOrNull { it.assetKind == kind }
}

/** The Mac build is Apple silicon only; the Windows and Linux ones are 64-bit Intel/AMD only. */
private fun archFits(kind: AssetKind, arch: String): Boolean {
    val a = arch.lowercase()
    val arm = a == "aarch64" || a == "arm64"
    val x64 = a == "amd64" || a == "x86_64" || a == "x64"
    return if (kind == AssetKind.MacDmg) arm else x64
}

/** The file's SHA-256, in lower-case hex. */
fun sha256Of(file: File): String = file.inputStream().use(::sha256Of)

fun sha256Of(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** The download didn't match its published checksum, or had none to check. */
class ChecksumException(message: String) : Exception(message)

/** Throws [ChecksumException] unless [file]'s SHA-256 is [expected]. A missing checksum fails too. */
fun verifyChecksum(file: File, expected: String?) {
    val want = expected?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        ?: throw ChecksumException("This release has no checksum for ${file.name}, so it can't be checked. Nothing was installed.")
    val got = sha256Of(file)
    if (got != want) throw ChecksumException("The download didn't match its checksum, so it wasn't installed. Try again.")
}
