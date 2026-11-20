package dev.wildware.agents

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Why a step of the hosted "Log in" didn't work, so the screen can say what to do next. */
enum class HostedFailure {
    /** The account service didn't answer: no signal, or it is down. */
    Unreachable,

    /** That isn't an email address the service takes. */
    BadEmail,

    /** The code is wrong; there are tries left. */
    WrongCode,

    /** Too many wrong codes: this code is dead, and maybe every code for that email until tomorrow. */
    Locked,

    /** The code was used already or is older than 15 minutes. */
    CodeExpired,

    /** Too many emails or tries in a short time. [HostedLoginException.retryAfterSeconds] says how long. */
    TooManyTries,

    /** The account sign-in ended (it expired or was signed out), so the steps start again. */
    SignedOut,

    /** The team is gone, or this email is no longer in it. */
    NoSuchTeam,

    /** The team's server can't be reached just now. */
    ServerUnavailable,

    /** The service answered something this app doesn't understand. */
    Refused,
}

class HostedLoginException(
    val failure: HostedFailure,
    message: String,
    val status: Int? = null,
    val retryAfterSeconds: Long? = null,
) : Exception(message)

/** The account service's own sign-in, held only while the steps run. Never stored. */
class HostedSession internal constructor(internal val token: String, val email: String) {
    override fun toString(): String = "HostedSession(email=$email)"
}

/** A team the signed-in email is in. [readOnly]: its billing lapsed, so people can sign in and read but not write. */
@Serializable
data class HostedTeam(
    val slug: String,
    val name: String,
    val address: String,
    val role: String = "member",
    val readOnly: Boolean = false,
)

/** The teams, and whether the list may be short ([partial]: the team server couldn't be asked, so only teams this account owns). */
data class HostedTeams(val teams: List<HostedTeam>, val partial: Boolean)

/** A one-time ticket into one team: good once, for about a minute, at [address] only. */
class HostedTicket internal constructor(val address: String, internal val ticket: String) {
    override fun toString(): String = "HostedTicket(address=$address)"
}

/**
 * The hosted "Log in" (docs/HOSTING.md §5): the account service at [baseUrl] emails a 6-digit
 * code, which proves the email; it then lists the teams that email is in and hands out a
 * one-time ticket for the one picked. The team's own server trades the ticket for an ordinary
 * device key ([SignIn.redeemTicket]); from then on the app talks only to that server.
 *
 * Nothing here is stored or logged: the account token lives in memory for the length of the
 * steps, and [signOut] ends it once the device key is in hand.
 */
class HostedAccount(baseUrl: String, private val engine: () -> HttpClientEngine) {
    val baseUrl: String = SignIn.normalise(baseUrl)

    /** Step 1: emails a code to [email]. Answers the login it belongs to, for [verify]. */
    @Throws(HostedLoginException::class, CancellationException::class)
    suspend fun requestCode(email: String): String {
        val text = call("api/app/sign-in", token = null, post = buildJsonObject { put("email", email.trim()) }.toString())
        return decode(LoginStarted.serializer(), text).loginId.takeIf { it.isNotBlank() }
            ?: throw HostedLoginException(HostedFailure.Refused, "The sign-in service answered in a way this app doesn't understand. Update the app.")
    }

    /** Step 2: the code from the email. Answers the account sign-in the next steps use. */
    @Throws(HostedLoginException::class, CancellationException::class)
    suspend fun verify(loginId: String, code: String, deviceName: String): HostedSession {
        val body = buildJsonObject {
            put("loginId", loginId)
            put("code", code.filter(Char::isDigit))
            put("deviceName", deviceName.trim().ifEmpty { "Wildware Feed app" }.take(80))
        }
        val signed = decode(Verified.serializer(), call("api/app/sign-in/verify", token = null, post = body.toString()))
        return HostedSession(signed.token, signed.email)
    }

    /** Step 3: the teams this email is in. */
    @Throws(HostedLoginException::class, CancellationException::class)
    suspend fun teams(session: HostedSession): HostedTeams {
        val list = decode(TeamList.serializer(), call("api/app/teams", session.token, post = null))
        return HostedTeams(list.teams, list.partial)
    }

    /** Step 4: a one-time ticket into the team [slug]. Ask for a new one each time; a ticket works once. */
    @Throws(HostedLoginException::class, CancellationException::class)
    suspend fun ticket(session: HostedSession, slug: String): HostedTicket {
        val text = call("api/app/teams/${slug.encodeURLPathPart()}/ticket", session.token, post = "{}")
        val wire = decode(TicketWire.serializer(), text)
        val address = SignIn.normalise(wire.address)
        // The ticket goes only to a team address over https (plain http only when the service itself
        // is, as in a test on this computer), so it can't be sent in the clear or somewhere odd.
        val allowed = address.startsWith("https://") || (this.baseUrl.startsWith("http://") && address.startsWith("http://"))
        if (!allowed || wire.ticket.isBlank()) throw HostedLoginException(HostedFailure.Refused, "The account service answered with a team address this app won't use.")
        return HostedTicket(address, wire.ticket)
    }

    /** Ends the account sign-in. Best effort: it expires on its own anyway. */
    suspend fun signOut(session: HostedSession) {
        try {
            call("api/app/sign-out", session.token, post = "{}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }

    /** Where to make an account and a team: the service's own website. */
    val signUpPage: String get() = baseUrl

    private suspend fun call(path: String, token: String?, post: String?): String {
        val http = HttpClient(engine()) { expectSuccess = false }
        try {
            val setup: HttpRequestBuilder.() -> Unit = {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                if (post != null) {
                    contentType(ContentType.Application.Json)
                    setBody(post)
                }
            }
            val response: HttpResponse = try {
                if (post != null) http.post("$baseUrl/$path", setup) else http.get("$baseUrl/$path", setup)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                throw HostedLoginException(HostedFailure.Unreachable, "Couldn't reach the sign-in service. Check your connection.")
            }
            val status = response.status.value
            val text = runCatching { response.bodyAsText() }.getOrDefault("")
            if (status in 200..299) return text
            val error = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()?.error
            val retry = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()
            throw when {
                status == 429 || error == "rate_limited" ->
                    HostedLoginException(HostedFailure.TooManyTries, "Too many tries. Wait a while, then try again.", status, retry)
                error == "invalid_email" -> HostedLoginException(HostedFailure.BadEmail, "That doesn't look like an email address.", status)
                error == "wrong_code" -> HostedLoginException(HostedFailure.WrongCode, "That code isn't right.", status)
                error == "locked" -> HostedLoginException(HostedFailure.Locked, "Too many wrong codes.", status)
                error == "expired" -> HostedLoginException(HostedFailure.CodeExpired, "That code has expired or was used already.", status)
                // With a token sent, any 401 means the account sign-in has ended.
                error == "unauthorized" || (status == 401 && token != null) ->
                    HostedLoginException(HostedFailure.SignedOut, "Your sign-in ended. Start again.", status)
                error == "no_such_team" -> HostedLoginException(HostedFailure.NoSuchTeam, "That team isn't there any more.", status)
                error == "server_unavailable" || status >= 500 ->
                    HostedLoginException(HostedFailure.ServerUnavailable, "The team's server isn't answering. Try again soon.", status, retry)
                else -> HostedLoginException(HostedFailure.Refused, "The sign-in service turned that down ($status).", status)
            }
        } finally {
            http.close()
        }
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, text: String): T =
        runCatching { json.decodeFromString(serializer, text) }.getOrNull()
            ?: throw HostedLoginException(HostedFailure.Refused, "The sign-in service answered in a way this app doesn't understand. Update the app.")

    @Serializable
    private data class LoginStarted(val loginId: String)

    @Serializable
    private data class Verified(val token: String, val email: String = "")

    @Serializable
    private data class TeamList(val teams: List<HostedTeam> = emptyList(), val partial: Boolean = false)

    @Serializable
    private data class TicketWire(val address: String, val ticket: String)

    @Serializable
    private data class ErrorBody(val error: String? = null)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
