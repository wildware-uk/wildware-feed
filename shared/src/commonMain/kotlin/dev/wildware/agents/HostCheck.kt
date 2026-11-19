package dev.wildware.agents

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A way a host lets you sign in, as its `GET /api/server` lists it in `signIn`. */
enum class SignInMethod(val wire: String) {
    /** The owner's password: every server today. */
    Password("password"),

    /** A person's email and password, on a team server. Not offered by any server yet. */
    EmailPassword("email-password"),
    ;

    companion object {
        /** The method [wire] names, or null for one this app doesn't know yet. */
        fun of(wire: String): SignInMethod? = entries.firstOrNull { it.wire == wire }
    }
}

/** What "Connect to a host" found at an address. */
sealed interface HostCheck {
    /** The address, tidied up the way sign-in will use it. */
    val server: String

    /** A Wildware Feed server, and the ways it lets you sign in that this app knows. */
    data class Feed(override val server: String, val signIn: List<SignInMethod>) : HostCheck

    /** Something answered, but it isn't a Wildware Feed server. */
    data class NotFeed(override val server: String) : HostCheck

    /** Nothing answered: no connection, a name that doesn't exist, or it took too long. */
    data class Unreachable(override val server: String) : HostCheck

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Asks the public `GET /api/server` whether [address] is a Wildware Feed server, before any
         * password is typed. A server from before that route answers `/api/summary` with a 401 JSON
         * body instead; it signs in with the owner's password.
         */
        @Throws(CancellationException::class)
        suspend fun check(address: String, engine: HttpClientEngine): HostCheck {
            val base = SignIn.normalise(address)
            val http = HttpClient(engine) {
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = 10_000
                    requestTimeoutMillis = 15_000
                }
            }
            try {
                val info = http.fetch("$base/api/server") ?: return Unreachable(base)
                // A server that is there but failing (often a proxy with nothing behind it) can't say.
                if (info.status >= 500) return Unreachable(base)
                val body = info.json
                if (info.status == 200 && body is JsonObject) {
                    if (body.string("product") != "wildware-feed") return NotFeed(base)
                    val methods = (body["signIn"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(SignInMethod::of) }
                        ?: listOf(SignInMethod.Password)
                    return Feed(base, methods)
                }
                if (info.status != 404 && body != null) return NotFeed(base)
                // No /api/server: an older Wildware Feed server, if its summary wants a key.
                val summary = http.fetch("$base/api/summary") ?: return Unreachable(base)
                return if (summary.status == 401 && summary.json is JsonObject) Feed(base, listOf(SignInMethod.Password))
                else NotFeed(base)
            } finally {
                http.close()
            }
        }

        private class Answer(val status: Int, val json: JsonElement?)

        /** The status and JSON body at [url], or null when nothing answered. */
        private suspend fun HttpClient.fetch(url: String): Answer? {
            val response: HttpResponse = try {
                get(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unreachable: Throwable) {
                return null
            }
            val text = runCatching { response.bodyAsText() }.getOrDefault("")
            return Answer(response.status.value, runCatching { json.parseToJsonElement(text) }.getOrNull())
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
