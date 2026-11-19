package dev.wildware.agents.app.update

import java.io.File

/**
 * A MAJOR.MINOR.PATCH version. A suffix ("1.2.0-beta.1") makes it older than the plain release,
 * as semantic versioning says.
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val preRelease: String? = null) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        val numbers = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        if (numbers != 0) return numbers
        return when {
            preRelease == other.preRelease -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> preRelease.compareTo(other.preRelease)
        }
    }

    override fun toString() = "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    companion object {
        private val pattern = Regex("""^[vV]?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        /** Reads "1.2.3" or "v1.2.3"; null for anything else. */
        fun parse(text: String): SemVer? {
            val match = pattern.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, pre) = match.destructured
            return SemVer(
                major.toIntOrNull() ?: return null,
                minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null,
                pre.ifEmpty { null },
            )
        }
    }
}

/** A published release. */
data class Release(
    val version: String,
    val tag: String = "v$version",
    val publishedAt: String? = null,
    /** Markdown. */
    val notes: String = "",
    val assets: List<ReleaseAsset> = emptyList(),
) {
    val semVer: SemVer? get() = SemVer.parse(version)
}

/** One installer file in a release. [download] is its address. */
data class ReleaseAsset(
    val name: String,
    val kind: String,
    val size: Long = 0,
    /** Lower-case hex. The updater will not install a file it cannot check. */
    val sha256: String? = null,
    val download: String,
) {
    val assetKind: AssetKind? get() = AssetKind.entries.firstOrNull { it.wire == kind }
}

/** The kinds of installer a release carries. */
enum class AssetKind(val wire: String) {
    MacDmg("mac-dmg"),
    WindowsMsi("windows-msi"),
    WindowsZip("windows-zip"),
    LinuxAppImage("linux-appimage"),
    LinuxDeb("linux-deb"),
    LinuxRpm("linux-rpm"),
    /** The Android app, for phones that installed it from the release page rather than a store. */
    AndroidApk("android-apk"),
}

/** Something went wrong reading releases or downloading one. [message] is fit to show the owner. */
class ReleaseSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Where releases come from: [GitHubReleaseSource] in the app, a fake in tests. */
interface ReleaseSource {
    /** The newest release, or null when there is none. Throws [ReleaseSourceException] when it can't tell. */
    suspend fun latest(): Release?

    /**
     * Saves [asset] to [out], calling [progress] with the bytes so far and the total (or -1).
     * When [out] already holds the start of it (an earlier try stopped part way), carries on from
     * there if the server allows, else starts again. A failure leaves what arrived in [out].
     */
    suspend fun download(asset: ReleaseAsset, out: File, progress: (done: Long, total: Long) -> Unit)
}
