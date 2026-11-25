package dev.wildware.feed.server.github

import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Tokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.RSAPrivateKey
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64

/** A GitHub App installation: its id and the user or org it is on. */
data class Installation(val id: Long, val account: String)

/** A PR as GitHub has it now, read once when it is linked. */
data class PullSnapshot(
    val title: String,
    val state: String,
    val headSha: String?,
    val checks: Map<String, PrState.Check>,
    val reviews: Map<String, String>,
)

/**
 * What the server asks GitHub, as the app (docs/GITHUB.md). Nothing here polls: each call is made
 * once, when a PR is linked or an installation is connected. Tests put in a fake.
 */
interface GitHubApi {
    /** The app's installation that covers [repo] (`owner/name`), or null when none does. */
    fun installationFor(repo: String): Long?

    /** The PR's state, CI and reviews now, or null when it can't be read. */
    fun pull(installationId: Long, repo: String, number: Long): PullSnapshot?

    /** A user token for the `code` GitHub sent back to the callback, or null when it is refused. */
    fun userToken(code: String): String?

    /** This app's installations the token's user can see. */
    fun userInstallations(token: String): List<Installation>
}

/** [GitHubApi] over HTTPS, signed as the app with its private key (RS256 JWT, `iss` its client id). */
class HttpGitHubApi(private val settings: Config.GitHub, private val clock: () -> Long = System::currentTimeMillis) : GitHubApi {
    private val key: PrivateKey = loadKey(settings.privateKey)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    /** Installation tokens last an hour; one is kept until five minutes before then. */
    private val tokens = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, Long>>()

    private fun jwt(): String {
        val now = clock() / 1000
        val header = b64(Json.encodeToString(JsonElement.serializer(), buildJsonObject { put("alg", "RS256"); put("typ", "JWT") }))
        val claims = b64(Json.encodeToString(JsonElement.serializer(), buildJsonObject { put("iat", now - 60); put("exp", now + 540); put("iss", settings.clientId) }))
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update("$header.$claims".toByteArray(Charsets.US_ASCII))
            sign()
        }
        return "$header.$claims.${Tokens.base64url(signature)}"
    }

    private fun installationToken(id: Long): String? {
        tokens[id]?.takeIf { it.second > clock() + 5 * 60_000 }?.let { return it.first }
        val r = send("POST", "$API/app/installations/$id/access_tokens", "Bearer ${jwt()}", "{}") ?: return null
        val token = r["token"]?.jsonPrimitive?.content ?: return null
        val expires = r["expires_at"]?.jsonPrimitive?.content?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: (clock() + 3_000_000)
        tokens[id] = token to expires
        return token
    }

    override fun installationFor(repo: String): Long? =
        send("GET", "$API/repos/$repo/installation", "Bearer ${jwt()}")?.get("id")?.jsonPrimitive?.longOrNull

    override fun pull(installationId: Long, repo: String, number: Long): PullSnapshot? {
        val token = installationToken(installationId) ?: return null
        val auth = "token $token"
        val pr = send("GET", "$API/repos/$repo/pulls/$number", auth) ?: return null
        val sha = pr["head"]?.jsonObject?.get("sha")?.jsonPrimitive?.content
        var checks = emptyMap<String, PrState.Check>()
        if (sha != null) {
            sendAny("GET", "$API/repos/$repo/commits/$sha/check-runs?per_page=100", auth)?.jsonObject?.get("check_runs")?.jsonArray?.forEach { run ->
                checks = PrState.merged(checks, Webhook.checkOfRun(run.jsonObject))
            }
            sendAny("GET", "$API/repos/$repo/commits/$sha/status", auth)?.jsonObject?.get("statuses")?.jsonArray?.forEach { s ->
                val o = s.jsonObject
                val context = o["context"]?.jsonPrimitive?.content ?: return@forEach
                if (context !in checks) checks = checks + (context to PrState.Check.ofStatus(context, o["state"]?.jsonPrimitive?.content ?: "pending", o["target_url"]?.jsonPrimitive?.contentOrNull))
            }
        }
        var reviews = emptyMap<String, String>()
        (sendAny("GET", "$API/repos/$repo/pulls/$number/reviews?per_page=100", auth) as? JsonArray)?.forEach { r ->
            val o = r.jsonObject
            val login = o["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content ?: return@forEach
            reviews = PrState.reviewed(reviews, login, o["state"]?.jsonPrimitive?.content ?: return@forEach)
        }
        return PullSnapshot(
            title = pr["title"]?.jsonPrimitive?.content ?: "",
            state = PrState.state(pr["state"]?.jsonPrimitive?.content, pr["draft"]?.jsonPrimitive?.booleanOrNull == true, pr["merged"]?.jsonPrimitive?.booleanOrNull == true),
            headSha = sha,
            checks = checks,
            reviews = reviews,
        )
    }

    override fun userToken(code: String): String? {
        val form = "client_id=${enc(settings.clientId)}&client_secret=${enc(settings.clientSecret)}&code=${enc(code)}"
        val request = HttpRequest.newBuilder(URI.create("https://github.com/login/oauth/access_token"))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val r = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull() ?: return null
        if (r.statusCode() != 200) return null
        return runCatching { Json.parseToJsonElement(r.body()).jsonObject["access_token"]?.jsonPrimitive?.content }.getOrNull()
    }

    override fun userInstallations(token: String): List<Installation> {
        val r = sendAny("GET", "$API/user/installations?per_page=100", "Bearer $token")?.jsonObject ?: return emptyList()
        return r["installations"]?.jsonArray.orEmpty().mapNotNull { i ->
            val o = i.jsonObject
            val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            Installation(id, o["account"]?.jsonObject?.get("login")?.jsonPrimitive?.content ?: "")
        }
    }

    private fun send(method: String, url: String, auth: String, body: String? = null): JsonObject? = sendAny(method, url, auth, body) as? JsonObject

    private fun sendAny(method: String, url: String, auth: String, body: String? = null): JsonElement? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Authorization", auth)
            .header("User-Agent", USER_AGENT)
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
            .build()
        val r = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull() ?: return null
        if (r.statusCode() !in 200..299) return null
        return runCatching { Json.parseToJsonElement(r.body()) }.getOrNull()
    }

    private fun b64(text: String) = Tokens.base64url(text.toByteArray(Charsets.UTF_8))
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    companion object {
        private const val API = "https://api.github.com"
        private const val USER_AGENT = "wildware-feed"

        /** The app's `.pem`: PKCS#1 (`BEGIN RSA PRIVATE KEY`, as GitHub gives it) or PKCS#8. */
        fun loadKey(pem: String): PrivateKey {
            val text = pem.replace("\\n", "\n")
            val der = Base64.getMimeDecoder().decode(text.lines().filterNot { it.startsWith("-----") }.joinToString("").trim())
            val pkcs8 = if ("BEGIN RSA PRIVATE KEY" in text) {
                PrivateKeyInfo(AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE), RSAPrivateKey.getInstance(der)).encoded
            } else {
                der
            }
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        }
    }
}
