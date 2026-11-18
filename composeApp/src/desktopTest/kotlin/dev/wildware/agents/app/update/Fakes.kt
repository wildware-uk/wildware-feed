package dev.wildware.agents.app.update

import java.io.File

/** A release, made up. */
fun release(version: String = "1.0.2", vararg assets: ReleaseAsset = allAssets(version)) =
    Release(version = version, tag = "v$version", publishedAt = "2026-11-18T10:00:00Z", notes = "### What's new\n\n- Updates itself\n- Faster feed", assets = assets.toList())

fun allAssets(version: String) = arrayOf(
    asset("Agents-$version-mac-arm64.dmg", "mac-dmg", version),
    asset("Agents-$version-windows-x64.msi", "windows-msi", version),
    asset("Agents-$version-windows-x64-portable.zip", "windows-zip", version),
    asset("Agents-$version-x86_64.AppImage", "linux-appimage", version),
    asset("agents_${version}_amd64.deb", "linux-deb", version),
    asset("agents-$version.x86_64.rpm", "linux-rpm", version),
)

fun asset(name: String, kind: String, version: String = "1.0.2", bytes: ByteArray = name.toByteArray(), sha256: String? = sha256Of(bytes.inputStream())) =
    ReleaseAsset(name, kind, bytes.size.toLong(), sha256, "https://github.com/wildware-uk/agent-dashboard-ios/releases/download/v$version/$name")

/** Releases from memory. [failWith] makes every call fail, as offline or a 503 would. */
class FakeReleaseSource(
    var latest: Release? = release(),
    var failWith: Exception? = null,
    /** What each download writes, by asset name; the name's own bytes otherwise. */
    val bytes: MutableMap<String, ByteArray> = mutableMapOf(),
) : ReleaseSource {
    var checks = 0
    var downloads = 0

    override suspend fun latest(): Release? {
        checks++
        failWith?.let { throw it }
        return latest
    }

    override suspend fun download(asset: ReleaseAsset, out: File, progress: (Long, Long) -> Unit) {
        downloads++
        failWith?.let { throw it }
        val data = bytes[asset.name] ?: asset.name.toByteArray()
        progress(0, data.size.toLong())
        out.writeBytes(data)
        progress(data.size.toLong(), data.size.toLong())
    }
}

/** An installer that records what it was asked and does nothing real. */
class FakeInstaller(var prepared: (File) -> Prepared = { Prepared.Restart(Helper(it, listOf("true"))) }) : Installer {
    val calls = mutableListOf<String>()

    override fun downloadTarget(installation: Installation, asset: ReleaseAsset, workDir: File) = File(workDir, asset.name)

    override fun prepare(installation: Installation, download: File, workDir: File, pid: Long): Prepared {
        calls += "prepare ${download.name}"
        return prepared(download)
    }

    override fun launch(helper: Helper) {
        calls += "launch"
    }

    override fun abandon(installation: Installation, workDir: File) {
        calls += "abandon"
    }
}
