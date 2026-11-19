package dev.wildware.agents

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Why signing in didn't work, so the screen can say something useful. */
enum class SignInFailure {
    /** The address didn't answer at all: a typo, no signal, or the server is down. */
    Unreachable,

    /** Something answered, but it isn't a Wildware Feed server. */
    NotADashboard,

    WrongPassword,

    /** Five wrong tries in 15 minutes locks sign-in for a while. [SignInException.retryAfterSeconds] says how long. */
    TooManyAttempts,

    /** The server has no owner password set up, so nobody can sign in yet. */
    NotSetUp,

    /** The invite code is wrong, used up, revoked or expired. */
    BadInvite,

    /** The server turned it down for a reason of its own, such as a device name it won't take. [SignInException.message] says what it said. */
    Refused,
}

class SignInException(
    val failure: SignInFailure,
    message: String,
    val status: Int? = null,
    val retryAfterSeconds: Long? = null,
) : Exception(message)

/** A signed-in device: the server it talks to and the key it uses from now on. Store [token] safely. */
data class SignedIn(val server: String, val token: String, val device: DeviceRegistration.Device)

/**
 * Signs in with the owner password and gets a long-lived device key named [deviceName], in one
 * request to `POST /api/devices/sign-in`. The password is never stored.
 */
object SignIn {
    private val json = Json { ignoreUnknownKeys = true }

    @Throws(SignInException::class, CancellationException::class)
    suspend fun signIn(server: String, password: String, deviceName: String, engine: HttpClientEngine): SignedIn =
        request(server, password, deviceName, engine, cookie = false)

    /**
     * The web app's sign-in, to the server that served it. The device key comes back as a cookie the
     * page's code can't read, and the browser sends it with every request to that server from then
     * on. [SignedIn.token] is [CookieSession]: there is no key to keep.
     */
    @Throws(SignInException::class, CancellationException::class)
    suspend fun signInWithCookie(server: String, password: String, deviceName: String, engine: HttpClientEngine): SignedIn =
        request(server, password, deviceName, engine, cookie = true)

    /** Signs in as a person on a team, with their email and password (migration 055). */
    @Throws(SignInException::class, CancellationException::class)
    suspend fun signInWithEmail(server: String, email: String, password: String, deviceName: String, engine: HttpClientEngine): SignedIn =
        request(server, password, deviceName, engine, cookie = false, email = email)

    /** [signInWithEmail] for the web app: the key comes back as a cookie. */
    @Throws(SignInException::class, CancellationException::class)
    suspend fun signInWithEmailCookie(server: String, email: String, password: String, deviceName: String, engine: HttpClientEngine): SignedIn =
        request(server, password, deviceName, engine, cookie = true, email = email)

    /**
     * Joins a team with an invite: its code, or the link it came as, plus a name, email and password
     * for the new account. Signs this device in, as sign-in does.
     */
    @Throws(SignInException::class, CancellationException::class)
    suspend fun acceptInvite(
        server: String,
        invite: String,
        name: String,
        email: String,
        password: String,
        deviceName: String,
        engine: HttpClientEngine,
        cookie: Boolean,
    ): SignedIn = send(server, "api/invite/accept", engine, cookie) {
        put("token", invite.trim())
        put("name", name.trim())
        put("email", email.trim())
        put("password", password)
        put("deviceName", deviceName.trim().ifEmpty { "Wildware Feed app" }.take(100))
    }

    /** The server address an invite link points at, so pasting the link alone is enough. Null for a bare code. */
    fun serverOfInvite(invite: String): String? {
        val text = invite.trim()
        Regex("^(https?://[^/?#]+(?:/[^?#]*?)?)/join/[A-Za-z0-9_-]{43}").find(text)?.let { return it.groupValues[1] }
        Regex("[?&]server=([^&]+)").find(text)?.let { return decodeQuery(it.groupValues[1]) }
        return null
    }

    /** `%xx` and `+` in a query value, decoded as UTF-8. */
    private fun decodeQuery(value: String): String {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val hex = if (c == '%' && i + 2 < value.length) value.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                bytes += hex.toByte()
                i += 3
            } else {
                bytes += (if (c == '+') " " else c.toString()).encodeToByteArray().toList()
                i += 1
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    /** The token a cookie session keeps: none. [DashboardClient] sends no key for it; the browser sends the cookie. */
    const val CookieSession: String = ""

    private suspend fun request(server: String, password: String, deviceName: String, engine: HttpClientEngine, cookie: Boolean, email: String? = null): SignedIn =
        send(server, "api/devices/sign-in", engine, cookie) {
            if (email != null) put("email", email.trim())
            put("password", password)
            put("name", deviceName.trim().ifEmpty { "Wildware Feed app" }.take(100))
        }

    private suspend fun send(
        server: String,
        path: String,
        engine: HttpClientEngine,
        cookie: Boolean,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): SignedIn {
        val base = normalise(server)
        val http = HttpClient(engine) { expectSuccess = false }
        try {
            val response = try {
                http.post("$base/$path") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        fields()
                        if (cookie) put("cookie", true)
                    }.toString())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unreachable: Throwable) {
                throw SignInException(SignInFailure.Unreachable, "Couldn't reach $base. Check the address and your connection.")
            }
            val status = response.status.value
            val text = runCatching { response.bodyAsText() }.getOrDefault("")
            if (status == 201 || status == 200) {
                val registration = runCatching { json.decodeFromString(DeviceRegistration.serializer(), text) }.getOrNull()
                    ?: throw SignInException(SignInFailure.NotADashboard, "$base answered, but not like a Wildware Feed server.", status)
                return SignedIn(base, if (cookie) CookieSession else registration.token, registration.device)
            }
            val error = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
            throw when {
                error?.error == "wrong_password" ->
                    SignInException(SignInFailure.WrongPassword, error.message ?: "That password is not right.", status)
                error?.error == "rate_limited" || status == 429 -> SignInException(
                    SignInFailure.TooManyAttempts,
                    error?.message ?: "Too many attempts. Try again later.",
                    status,
                    response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull(),
                )
                error?.error == "misconfigured" ->
                    SignInException(SignInFailure.NotSetUp, "This dashboard has no owner password set up yet.", status)
                error?.error == "invalid_argument" || error?.error == "conflict" ->
                    SignInException(SignInFailure.Refused, error.message ?: "The dashboard won't take that device name.", status)
                error?.error == "invalid_invite" ->
                    SignInException(SignInFailure.BadInvite, error.message ?: "That invite has expired, was used already, or isn't right.", status)
                // A dashboard from before this sign-in existed, or not a dashboard at all.
                status == 404 || status == 405 || error == null && status < 500 ->
                    SignInException(SignInFailure.NotADashboard, "There is no Wildware Feed server at $base, or it needs updating.", status)
                status >= 500 -> SignInException(SignInFailure.Unreachable, "The dashboard had a problem ($status). Try again soon.", status)
                else -> SignInException(SignInFailure.Refused, error?.message ?: "The dashboard refused the sign-in ($status).", status)
            }
        } finally {
            http.close()
        }
    }

    /** Adds https:// when no scheme was typed and drops a trailing slash, so "dash.example.com/" works. */
    fun normalise(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        return if ("://" in trimmed) trimmed else "https://$trimmed"
    }

    @Serializable
    private data class ErrorBody(val error: String? = null, val message: String? = null)
}
