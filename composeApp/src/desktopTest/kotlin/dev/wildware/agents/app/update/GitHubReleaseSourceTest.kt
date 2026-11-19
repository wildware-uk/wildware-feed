package dev.wildware.agents.app.update

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GitHub's releases API, against a fake GitHub. Nothing reaches the network. */
class GitHubReleaseSourceTest {
    private val latestUrl = "https://api.github.com/repos/wildware-uk/wildware-feed/releases/latest"
    private val dl = "https://github.com/wildware-uk/wildware-feed/releases/download/v1.0.2"
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val seen = mutableListOf<Pair<String, Map<String, String?>>>()

    private fun releaseJson(draft: Boolean = false, prerelease: Boolean = false) = """
        {"tag_name":"v1.0.2","name":"Agents 1.0.2","draft":$draft,"prerelease":$prerelease,
         "published_at":"2026-11-18T10:00:00Z","body":"- Updates itself","author":{"login":"x"},
         "assets":[
          {"name":"Agents-1.0.2-mac-arm64.dmg","size":10,"browser_download_url":"$dl/Agents-1.0.2-mac-arm64.dmg"},
          {"name":"Agents-1.0.2-windows-x64.msi","size":11,"browser_download_url":"$dl/Agents-1.0.2-windows-x64.msi"},
          {"name":"Agents-1.0.2-windows-x64-portable.zip","size":12,"browser_download_url":"$dl/Agents-1.0.2-windows-x64-portable.zip"},
          {"name":"Agents-1.0.2-x86_64.AppImage","size":13,"browser_download_url":"$dl/Agents-1.0.2-x86_64.AppImage"},
          {"name":"agents_1.0.2_amd64.deb","size":14,"browser_download_url":"$dl/agents_1.0.2_amd64.deb"},
          {"name":"agents-1.0.2.x86_64.rpm","size":15,"browser_download_url":"$dl/agents-1.0.2.x86_64.rpm"},
          {"name":"SHA256SUMS.txt","size":16,"browser_download_url":"$dl/SHA256SUMS.txt"}
         ]}
    """.trimIndent()

    private val sums = "$hashA  Agents-1.0.2-mac-arm64.dmg\n$hashB  agents_1.0.2_amd64.deb\n"

    private fun source(now: () -> Long = { 1_000_000L }, respond: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(String) -> io.ktor.client.request.HttpResponseData) =
        GitHubReleaseSource(
            MockEngine { request ->
                seen += request.url.toString() to listOf(HttpHeaders.Accept, "X-GitHub-Api-Version", HttpHeaders.UserAgent, HttpHeaders.Authorization)
                    .associateWith { request.headers[it] }
                respond(request.url.toString())
            },
            userAgent = "Agents-desktop/test",
            now = now,
        )

    @Test
    fun readsTheLatestReleaseWithKindsAndChecksums() = runTest {
        val release = source { url ->
            when (url) {
                latestUrl -> respond(releaseJson(), HttpStatusCode.OK, json)
                "$dl/SHA256SUMS.txt" -> respond(sums, HttpStatusCode.OK)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }.latest()!!
        assertEquals("1.0.2", release.version)
        assertEquals("v1.0.2", release.tag)
        assertEquals("- Updates itself", release.notes)
        assertEquals(
            listOf("mac-dmg", "windows-msi", "windows-zip", "linux-appimage", "linux-deb", "linux-rpm"),
            release.assets.map { it.kind },
            "every installer, and not the checksum file",
        )
        assertEquals(hashA, release.assets[0].sha256)
        assertEquals(hashB, release.assets[4].sha256)
        assertNull(release.assets[1].sha256, "no line in the sums, no checksum")
        assertEquals("$dl/Agents-1.0.2-mac-arm64.dmg", release.assets[0].download)

        val (url, headers) = seen.first()
        assertEquals(latestUrl, url)
        assertEquals("application/vnd.github+json", headers[HttpHeaders.Accept])
        assertEquals("2022-11-28", headers["X-GitHub-Api-Version"])
        assertEquals("Agents-desktop/test", headers[HttpHeaders.UserAgent])
        assertNull(headers[HttpHeaders.Authorization], "no key: the repo is public")
    }

    @Test
    fun noReleaseIsNullAndDraftsOrPrereleasesAreIgnored() = runTest {
        assertNull(source { respondError(HttpStatusCode.NotFound) }.latest())
        assertNull(source { respond(releaseJson(draft = true), HttpStatusCode.OK, json) }.latest())
        assertNull(source { respond(releaseJson(prerelease = true), HttpStatusCode.OK, json) }.latest())
    }

    @Test
    fun aRateLimitBacksOffUntilGitHubSays() = runTest {
        var now = 1_000_000L
        var calls = 0
        val github = source(now = { now }) {
            calls++
            respond("{}", HttpStatusCode.Forbidden, headersOf("x-ratelimit-remaining" to listOf("0"), "x-ratelimit-reset" to listOf("2000")))
        }
        val e = assertFailsWith<ReleaseSourceException> { github.latest() }
        assertEquals(GitHubReleaseSource.SlowDownMessage, e.message)
        assertEquals(1, calls)

        now = 1_500_000L
        assertFailsWith<ReleaseSourceException> { github.latest() }
        assertEquals(1, calls, "doesn't ask again before the reset time")

        now = 2_000_001L
        assertFailsWith<ReleaseSourceException> { github.latest() }
        assertEquals(2, calls, "asks again after it")
    }

    @Test
    fun retryAfterIsHonouredToo() = runTest {
        var now = 0L
        var calls = 0
        val github = source(now = { now }) {
            calls++
            respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "60"))
        }
        assertFailsWith<ReleaseSourceException> { github.latest() }
        now = 59_000
        assertFailsWith<ReleaseSourceException> { github.latest() }
        assertEquals(1, calls)
        now = 60_001
        assertFailsWith<ReleaseSourceException> { github.latest() }
        assertEquals(2, calls)
    }

    @Test
    fun offlineIsAnErrorTheCheckerSwallows() = runTest {
        val github = GitHubReleaseSource(MockEngine { throw java.io.IOException("no route to host") })
        assertEquals("Couldn't reach GitHub.", assertFailsWith<ReleaseSourceException> { github.latest() }.message)
    }

    @Test
    fun downloadsFollowGitHubsRedirect() = runTest {
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        val out = File(Files.createTempDirectory("agents-download").toFile(), "a.dmg")
        val progress = mutableListOf<Pair<Long, Long>>()
        val github = source { url ->
            if (url.startsWith(dl)) respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://objects.githubusercontent.com/x/a.dmg"))
            else respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, bytes.size.toString()))
        }
        github.download(ReleaseAsset("a.dmg", "mac-dmg", bytes.size.toLong(), null, "$dl/a.dmg"), out) { d, t -> progress += d to t }
        assertContentEquals(bytes, out.readBytes())
        assertEquals(150_000L to 150_000L, progress.last())
        assertEquals(listOf("$dl/a.dmg", "https://objects.githubusercontent.com/x/a.dmg"), seen.map { it.first })
        out.parentFile.deleteRecursively()
    }

    @Test
    fun aDownloadThatBrokeOffCarriesOnWithARange() = runTest {
        val bytes = ByteArray(1000) { (it % 251).toByte() }
        val out = File(Files.createTempDirectory("agents-download").toFile(), "a.dmg").apply { writeBytes(bytes.copyOfRange(0, 400)) }
        val ranges = mutableListOf<String?>()
        val github = GitHubReleaseSource(
            MockEngine { request ->
                ranges += request.headers[HttpHeaders.Range]
                respond(
                    bytes.copyOfRange(400, 1000),
                    HttpStatusCode.PartialContent,
                    headersOf(HttpHeaders.ContentLength to listOf("600"), HttpHeaders.ContentRange to listOf("bytes 400-999/1000")),
                )
            },
            userAgent = "Agents-desktop/test",
        )
        val progress = mutableListOf<Pair<Long, Long>>()
        github.download(ReleaseAsset("a.dmg", "mac-dmg", 1000, null, "$dl/a.dmg"), out) { d, t -> progress += d to t }
        assertEquals(listOf<String?>("bytes=400-"), ranges)
        assertContentEquals(bytes, out.readBytes())
        assertEquals(400L to 1000L, progress.first())
        assertEquals(1000L to 1000L, progress.last())
        out.parentFile.deleteRecursively()
    }

    @Test
    fun aServerThatIgnoresTheRangeStartsTheFileAgain() = runTest {
        val bytes = ByteArray(1000) { (it % 251).toByte() }
        val out = File(Files.createTempDirectory("agents-download").toFile(), "a.dmg").apply { writeBytes(ByteArray(400) { 7 }) }
        val github = GitHubReleaseSource(
            MockEngine { respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "1000")) },
            userAgent = "Agents-desktop/test",
        )
        github.download(ReleaseAsset("a.dmg", "mac-dmg", 1000, null, "$dl/a.dmg"), out) { _, _ -> }
        assertContentEquals(bytes, out.readBytes())
        out.parentFile.deleteRecursively()
    }

    @Test
    fun aFailedDownloadSaysSo() = runTest {
        val out = File(Files.createTempDirectory("agents-download").toFile(), "a.dmg")
        val e = assertFailsWith<ReleaseSourceException> {
            source { respondError(HttpStatusCode.BadGateway) }.download(ReleaseAsset("a.dmg", "mac-dmg", 1, null, "$dl/a.dmg"), out) { _, _ -> }
        }
        assertEquals("The download failed (GitHub answered 502).", e.message)
        out.parentFile.deleteRecursively()
    }

    @Test
    fun namesMapToKinds() {
        assertEquals(AssetKind.MacDmg, GitHubReleaseSource.kindOf("Agents-1.0.2-mac-arm64.dmg"))
        assertEquals(AssetKind.WindowsZip, GitHubReleaseSource.kindOf("Agents-1.0.2-windows-x64-portable.zip"))
        assertEquals(AssetKind.LinuxAppImage, GitHubReleaseSource.kindOf("Agents-1.0.2-x86_64.AppImage"))
        assertEquals(AssetKind.AndroidApk, GitHubReleaseSource.kindOf("Wildware-Feed-1.0.2-android.apk"))
        // The Play Store bundle is not something a phone installs from the release page.
        assertNull(GitHubReleaseSource.kindOf("Wildware-Feed-1.0.2-android.aab"))
        assertNull(GitHubReleaseSource.kindOf("source.zip"))
        assertNull(GitHubReleaseSource.kindOf("SHA256SUMS.txt"))
    }

    @Test
    fun checksumFilesInEachStyleAreRead() {
        val text = "$hashA  dist/Agents.dmg\n${hashB.uppercase()} *agents.deb\n# comment\nnot a line\n"
        assertEquals(mapOf("Agents.dmg" to hashA, "agents.deb" to hashB), GitHubReleaseSource.parseSums(text))
        assertTrue(GitHubReleaseSource.parseSums("").isEmpty())
    }
}
