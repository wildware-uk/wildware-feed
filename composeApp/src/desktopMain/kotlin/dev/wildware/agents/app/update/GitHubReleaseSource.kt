package dev.wildware.agents.app.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The GitHub repo whose releases update the app. A fork points this at its own. */
const val ReleasesRepo = "wildware-uk/wildware-feed"

/**
 * Releases straight from GitHub's public API, no sign-in: the newest published release, its
 * installers matched to kinds by name, and their checksums from its `SHA256SUMS.txt`.
 *
 * Without a key GitHub allows 60 calls an hour per address. When it says stop, this stops asking
 * until the time it gives, and every check in between fails quietly.
 */
class GitHubReleaseSource(
    engine: HttpClientEngine,
    private val repo: String = ReleasesRepo,
    private val userAgent: String = "WildwareFeed-desktop/$AppVersion",
    private val now: () -> Long = System::currentTimeMillis,
) : ReleaseSource, AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val http = HttpClient(engine) {
        expectSuccess = false
        followRedirects = true
        install(HttpTimeout)
    }

    /** No calls to GitHub before this time (epoch millis): it asked us to wait. */
    @Volatile
    private var quietUntil = 0L

    override suspend fun latest(): Release? {
        val response = call("https://api.github.com/repos/$repo/releases/latest")
        return when (response.status.value) {
            200 -> {
                val found = runCatching { json.decodeFromString(GitHubRelease.serializer(), response.bodyAsText()) }
                    .getOrElse { throw ReleaseSourceException("GitHub's answer about updates didn't make sense.", it) }
                if (found.draft || found.prerelease) return null
                toRelease(found)
            }
            // No published release yet (or the repo isn't public).
            404 -> null
            403, 429 -> throw slowDown(response)
            else -> throw ReleaseSourceException("GitHub answered ${response.status.value} when asked for updates.")
        }
    }

    private suspend fun toRelease(found: GitHubRelease): Release {
        val sums = found.assets.firstOrNull { it.name.equals(SumsFile, ignoreCase = true) }?.let { readSums(it.url) }.orEmpty()
        val assets = found.assets.mapNotNull { asset ->
            val kind = kindOf(asset.name) ?: return@mapNotNull null
            ReleaseAsset(asset.name, kind.wire, asset.size, sums[asset.name], asset.url)
        }
        return Release(
            version = found.tag.removePrefix("v").removePrefix("V"),
            tag = found.tag,
            publishedAt = found.publishedAt,
            notes = found.body.orEmpty(),
            assets = assets,
        )
    }

    private suspend fun readSums(url: String): Map<String, String> {
        val response = call(url, api = false)
        if (response.status.value == 403 || response.status.value == 429) throw slowDown(response)
        if (!response.status.isSuccess()) throw ReleaseSourceException("Couldn't read the release's checksums (GitHub answered ${response.status.value}).")
        return parseSums(response.bodyAsText())
    }

    override suspend fun download(asset: ReleaseAsset, out: File, progress: (Long, Long) -> Unit) {
        try {
            http.prepareGet(asset.download) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "application/octet-stream")
                timeout { connectTimeoutMillis = 15_000; socketTimeoutMillis = 60_000 }
            }.execute { response ->
                if (!response.status.isSuccess()) throw ReleaseSourceException("The download failed (GitHub answered ${response.status.value}).")
                val total = response.contentLength() ?: asset.size.takeIf { it > 0 } ?: -1L
                val channel = response.bodyAsChannel()
                withContext(Dispatchers.IO) {
                    out.outputStream().use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        progress(0, total)
                        while (true) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read < 0) break
                            if (read == 0) continue
                            stream.write(buffer, 0, read)
                            done += read
                            progress(done, total)
                        }
                        if (total > 0 && done != total) throw ReleaseSourceException("The download stopped part way. Try again.")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReleaseSourceException) {
            throw e
        } catch (e: Exception) {
            throw ReleaseSourceException("The download failed: ${e.message ?: e::class.simpleName}.", e)
        }
    }

    private suspend fun call(url: String, api: Boolean = true): HttpResponse {
        if (now() < quietUntil) throw ReleaseSourceException(SlowDownMessage)
        return try {
            http.get(url) {
                headers(api)
                timeout { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000 }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ReleaseSourceException("Couldn't reach GitHub.", e)
        }
    }

    private fun HttpRequestBuilder.headers(api: Boolean) {
        header(HttpHeaders.UserAgent, userAgent)
        if (api) {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
    }

    /** Remembers how long GitHub wants us to wait (Retry-After, or the rate limit's reset time; an hour if neither). */
    private fun slowDown(response: HttpResponse): ReleaseSourceException {
        val retryAfter = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()?.let { now() + it * 1000 }
        val reset = response.headers["x-ratelimit-reset"]?.trim()?.toLongOrNull()?.let { it * 1000 }
        quietUntil = maxOf(quietUntil, retryAfter ?: reset ?: (now() + 60 * 60 * 1000))
        return ReleaseSourceException(SlowDownMessage)
    }

    override fun close() = http.close()

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tag: String,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("published_at") val publishedAt: String? = null,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        val size: Long = 0,
        @SerialName("browser_download_url") val url: String,
    )

    companion object {
        const val SumsFile = "SHA256SUMS.txt"
        const val SlowDownMessage = "GitHub asked the app to check less often. It will try again later."

        /** Which kind of installer a release file is, from its name; null for anything else. */
        fun kindOf(name: String): AssetKind? {
            val n = name.lowercase()
            return when {
                n.endsWith(".dmg") -> AssetKind.MacDmg
                n.endsWith(".msi") -> AssetKind.WindowsMsi
                n.endsWith(".zip") && "windows" in n -> AssetKind.WindowsZip
                n.endsWith(".appimage") -> AssetKind.LinuxAppImage
                n.endsWith(".deb") -> AssetKind.LinuxDeb
                n.endsWith(".rpm") -> AssetKind.LinuxRpm
                else -> null
            }
        }

        /** `sha256sum` output: "<hex>  <file>" a line, the file maybe with a `*` or a folder in front. */
        fun parseSums(text: String): Map<String, String> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val hash = parts[0].lowercase()
                if (!hash.matches(Regex("[0-9a-f]{64}"))) return@mapNotNull null
                val file = parts[1].trim().removePrefix("*").substringAfterLast('/')
                file to hash
            }
            .toMap()
    }
}
