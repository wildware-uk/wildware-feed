package dev.wildware.agents.app.update

import kotlinx.coroutines.CompletableDeferred
import java.io.File

/** A release, made up. */
fun release(version: String = "1.0.2", vararg assets: ReleaseAsset = allAssets(version)) =
    Release(version = version, tag = "v$version", publishedAt = "2026-11-18T10:00:00Z", notes = "### What's new\n\n- Updates itself\n- Faster feed", assets = assets.toList())

/** A release's files as named from 1.0.2 on, as Wildware Feed. */
fun allAssets(version: String) = arrayOf(
    asset("Wildware-Feed-$version-mac-arm64.dmg", "mac-dmg", version),
    asset("Wildware-Feed-$version-windows-x64.msi", "windows-msi", version),
    asset("Wildware-Feed-$version-windows-x64-portable.zip", "windows-zip", version),
    asset("Wildware-Feed-$version-x86_64.AppImage", "linux-appimage", version),
    asset("wildware-feed_${version}_amd64.deb", "linux-deb", version),
    asset("wildware-feed-$version.x86_64.rpm", "linux-rpm", version),
)

/** The same files as named up to 1.0.1, as Agents. */
fun oldAssets(version: String) = arrayOf(
    asset("Agents-$version-mac-arm64.dmg", "mac-dmg", version),
    asset("Agents-$version-windows-x64.msi", "windows-msi", version),
    asset("Agents-$version-windows-x64-portable.zip", "windows-zip", version),
    asset("Agents-$version-x86_64.AppImage", "linux-appimage", version),
    asset("agents_${version}_amd64.deb", "linux-deb", version),
    asset("agents-$version.x86_64.rpm", "linux-rpm", version),
)

fun asset(name: String, kind: String, version: String = "1.0.2", bytes: ByteArray = name.toByteArray(), sha256: String? = sha256Of(bytes.inputStream())) =
    ReleaseAsset(name, kind, bytes.size.toLong(), sha256, "https://github.com/wildware-uk/wildware-feed/releases/download/v$version/$name")

/** Releases from memory. [failWith] makes every call fail, as offline or a 503 would. */
class FakeReleaseSource(
    var latest: Release? = release(),
    var failWith: Exception? = null,
    /** What each download writes, by asset name; the name's own bytes otherwise. */
    val bytes: MutableMap<String, ByteArray> = mutableMapOf(),
) : ReleaseSource {
    var checks = 0
    var downloads = 0

    /** When set, each check waits for it, so a test can see a check part way. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun latest(): Release? {
        checks++
        gate?.await()
        failWith?.let { throw it }
        return latest
    }

    /** Downloads to fail before one works, each with [failWith] or a 502. */
    var failNext = 0

    /** When set, the next download writes this many bytes and then breaks off, as a dropped connection would. */
    var breakAfter: Int? = null

    /** Where each download started from: 0, or the bytes an earlier one left. */
    val startedFrom = mutableListOf<Long>()

    override suspend fun download(asset: ReleaseAsset, out: File, progress: (Long, Long) -> Unit) {
        downloads++
        failWith?.let { throw it }
        if (failNext > 0) {
            failNext--
            throw ReleaseSourceException("The download failed (GitHub answered 502).")
        }
        val data = bytes[asset.name] ?: asset.name.toByteArray()
        // Carries on from what is there, as a server that honours Range does.
        val have = if (out.isFile && out.length() <= data.size) out.length() else 0L
        startedFrom += have
        progress(have, data.size.toLong())
        breakAfter?.let { cut ->
            breakAfter = null
            out.writeBytes(data.copyOfRange(0, cut))
            throw ReleaseSourceException("The download stopped part way. Try again.")
        }
        if (have > 0) out.appendBytes(data.copyOfRange(have.toInt(), data.size)) else out.writeBytes(data)
        progress(data.size.toLong(), data.size.toLong())
    }
}

/** An installer that records what it was asked and does nothing real. */
class FakeInstaller(var prepared: (File) -> Prepared = { Prepared.Restart(Helper(it, listOf("true"))) }) : Installer {
    val calls = mutableListOf<String>()

    override fun downloadTarget(installation: Installation, asset: ReleaseAsset, workDir: File) = File(workDir, asset.name)

    override fun prepare(installation: Installation, download: File, workDir: File, pid: Long, relaunch: Boolean): Prepared {
        calls += "prepare ${download.name}" + if (relaunch) "" else " without relaunch"
        return prepared(download)
    }

    override fun launch(helper: Helper) {
        calls += "launch"
    }

    override fun abandon(installation: Installation, workDir: File) {
        calls += "abandon"
    }
}
