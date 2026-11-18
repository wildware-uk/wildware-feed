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

    /** Something answered, but it isn't an Agent Dashboard. */
    NotADashboard,

    WrongPassword,

    /** Five wrong tries in 15 minutes locks sign-in for a while. [SignInException.retryAfterSeconds] says how long. */
    TooManyAttempts,

    /** The server has no owner password set up, so nobody can sign in yet. */
    NotSetUp,

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
    suspend fun signIn(server: String, password: String, deviceName: String, engine: HttpClientEngine): SignedIn {
        val base = normalise(server)
        val http = HttpClient(engine) { expectSuccess = false }
        try {
            val response = try {
                http.post("$base/api/devices/sign-in") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("password", password)
                        put("name", deviceName.trim().ifEmpty { "Agent Dashboard app" }.take(100))
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
                    ?: throw SignInException(SignInFailure.NotADashboard, "$base answered, but not like an Agent Dashboard.", status)
                return SignedIn(base, registration.token, registration.device)
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
                error?.error == "invalid_argument" ->
                    SignInException(SignInFailure.Refused, error.message ?: "The dashboard won't take that device name.", status)
                // A dashboard from before this sign-in existed, or not a dashboard at all.
                status == 404 || status == 405 || error == null && status < 500 ->
                    SignInException(SignInFailure.NotADashboard, "There is no Agent Dashboard at $base, or it needs updating.", status)
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
