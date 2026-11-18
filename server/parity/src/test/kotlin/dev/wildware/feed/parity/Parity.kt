package dev.wildware.feed.parity

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Where the server under test is and how to get in. Everything comes from PARITY_* variables,
 * so the same suite runs against the TypeScript server and the Kotlin one.
 *
 * - PARITY_BASE_URL        the server's origin, e.g. http://127.0.0.1:18110 (required)
 * - PARITY_ADMIN_PASSWORD  the owner password whose argon2id hash the server was started with (required)
 * - PARITY_CLI             a shell command that runs the server's own admin CLI against the server's
 *                          data, e.g. `node build/cli.js` with the server's environment. The suite runs
 *                          `$PARITY_CLI mint-token <name>` (the token is the output line of 43 base64url
 *                          characters) and `$PARITY_CLI revoke-token <agent id>` (required)
 * - PARITY_OWNER_AUTH      `device` to act as the owner with a device key instead of the login form
 * - PARITY_WEB             `0` for a server without the deprecated website (no pages, no login form)
 * - PARITY_DATA_DIR        the server's DATA_DIR, only for checks that look at files on disk (optional)
 */
object Parity {
    val baseUrl: String = env("PARITY_BASE_URL")?.trimEnd('/') ?: ""
    val adminPassword: String = env("PARITY_ADMIN_PASSWORD") ?: ""
    val cli: String = env("PARITY_CLI") ?: ""

    /** How the owner signs in: `cookie` (the login form, default) or `device` (a device key). */
    val ownerAuth: String = env("PARITY_OWNER_AUTH") ?: "cookie"

    /** False for a server without the deprecated website (PARITY_WEB=0): no pages, no login form. */
    val web: Boolean = env("PARITY_WEB") != "0"

    val configured: Boolean get() = baseUrl.isNotEmpty() && adminPassword.isNotEmpty() && cli.isNotEmpty()

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val http: HttpClient by lazy {
        HttpClient(CIO) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 90_000
                connectTimeoutMillis = 5_000
            }
        }
    }

    /** The owner, signed in once with the login form, for the whole run. */
    val owner: Owner by lazy { runBlocking { Owner.signIn() } }

    /** Two agents for the whole run; tests that revoke or flood use [mintAgent] for their own. */
    val alpha: Agent by lazy { mintAgent("parity-alpha-${suffix()}") }
    val beta: Agent by lazy { mintAgent("parity-beta-${suffix()}") }

    fun mintAgent(name: String): Agent {
        val out = runCli("mint-token", name)
        val token = out.lines().map { it.trim() }.firstOrNull { TOKEN.matches(it) }
            ?: error("no token in mint-token output: $out")
        return Agent(name, token)
    }

    fun revokeAgent(agentId: String) {
        runCli("revoke-token", agentId)
    }

    /** Run the server's CLI with one command; fails on a non-zero exit. */
    fun runCli(vararg args: String): String {
        val process = ProcessBuilder(listOf("sh", "-c", "$cli \"\$@\"", "cli") + args).redirectErrorStream(true).start()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "CLI ${args.first()} did not finish in 60s" }
        val out = process.inputStream.bufferedReader().readText()
        check(process.exitValue() == 0) { "CLI ${args.first()} failed: $out" }
        return out
    }

    fun suffix(): String = Random.nextLong(0, Long.MAX_VALUE).toString(36).take(8)

    private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")

    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
}

/** One raw request, never following redirects and never throwing on a status. */
suspend fun call(
    method: HttpMethod,
    path: String,
    body: JsonElement? = null,
    configure: HttpRequestBuilder.() -> Unit = {},
): HttpResponse = Parity.http.request(Parity.baseUrl + path) {
    this.method = method
    if (body != null) {
        contentType(ContentType.Application.Json)
        setBody(body.toString())
    }
    configure()
}

suspend fun HttpResponse.json(): JsonElement = Parity.json.parseToJsonElement(bodyAsText())
suspend fun HttpResponse.obj(): JsonObject = json().jsonObject

fun HttpRequestBuilder.bearer(token: String) = header(HttpHeaders.Authorization, "Bearer $token")

/** A urlencoded form body, as a browser's form submit sends it. */
fun HttpRequestBuilder.setFormBody(vararg fields: Pair<String, String>) =
    setBody(io.ktor.client.request.forms.FormDataContent(io.ktor.http.Parameters.build { fields.forEach { (k, v) -> append(k, v) } }))

/** A raw text body with an explicit content type, for malformed-body checks. */
fun HttpRequestBuilder.setBodyText(text: String, type: ContentType = ContentType.Application.Json) =
    setBody(io.ktor.http.content.TextContent(text, type))
