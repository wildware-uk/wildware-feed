package dev.wildware.agents

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The hosted account service and two team servers, in memory: `account.test` emails "codes"
 * (always [code]), lists [teams] and hands out one-time tickets; each team's address trades a
 * ticket for a device key once. Every request is kept, so a test can check what went where.
 */
class FakeHosted {
    data class Seen(val host: String, val method: String, val path: String, val authorization: String?, val body: String)

    val seen = mutableListOf<Seen>()

    var code = "123456"
    var teams = listOf(
        """{"id":"t1","slug":"acme","name":"Acme","address":"https://acme.feed.test","role":"owner","readOnly":false,"billing":{"state":"trialing","trialEnd":null}}""",
        """{"id":"t2","slug":"globex","name":"Globex","address":"https://globex.feed.test","role":"member","readOnly":true,"billing":null}""",
    )
    var partial = false

    /** Answer every sign-in email with this status and error instead (429 rate_limited, say). */
    var refuseSignIn: Pair<HttpStatusCode, String>? = null

    /** Answer every team list with 401, as an expired account sign-in does. */
    var expired = false

    /** The address a ticket says to use; null is the team's own. */
    var ticketAddress: String? = null

    /** The next ticket is one the team never heard of, as if it ran out on the way. */
    var spoilNextTicket = false

    /** Answer signed-in calls with a 401 that has no body. */
    var bare401 = false

    private var wrongTries = 0
    private var loginUsed = false
    private val sessions = mutableSetOf<String>()
    private val tickets = mutableMapOf<String, String>() // ticket -> team host
    private var minted = 0

    /** Tickets handed out, in order. */
    val issued = mutableListOf<String>()

    /** Account sign-ins still open. */
    val openSessions: Set<String> get() = sessions

    fun engine() = MockEngine { request -> route(request) }

    private fun MockRequestHandleScope.route(request: HttpRequestData): io.ktor.client.request.HttpResponseData {
        val host = request.url.host
        val path = request.url.encodedPath
        val body = request.textOf()
        seen += Seen(host, request.method.value, path, request.headers[HttpHeaders.Authorization], body)
        val fields = runCatching { Json.parseToJsonElement(body) as JsonObject }.getOrNull()
        fun field(name: String) = fields?.get(name)?.jsonPrimitive?.content
        if (host == "account.test") {
            if (path == "/api/app/sign-in") {
                refuseSignIn?.let { (status, error) -> return json("""{"error":"$error"}""", status, HttpHeaders.RetryAfter to "3600") }
                if ('@' !in field("email").orEmpty()) return json("""{"error":"invalid_email"}""", HttpStatusCode.BadRequest)
                wrongTries = 0
                loginUsed = false
                return json("""{"loginId":"login-1","expiresInSeconds":900}""")
            }
            if (path == "/api/app/sign-in/verify") {
                if (field("loginId") != "login-1" || loginUsed) return json("""{"error":"expired"}""", HttpStatusCode.Unauthorized)
                if (wrongTries >= 5) return json("""{"error":"locked"}""", HttpStatusCode.Unauthorized)
                if (field("code") != code) {
                    wrongTries++
                    return json("""{"error":"${if (wrongTries >= 5) "locked" else "wrong_code"}"}""", HttpStatusCode.Unauthorized)
                }
                loginUsed = true
                val token = "account-token-${sessions.size + 1}"
                sessions += token
                return json("""{"token":"$token","expiresAt":"2027-02-18T10:00:00.000Z","email":"ada@example.com"}""")
            }
            val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
            if (bare401) return respond("", HttpStatusCode.Unauthorized)
            if (token == null || token !in sessions || expired) return json("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized)
            return when {
                path == "/api/app/teams" && request.method == HttpMethod.Get ->
                    json("""{"teams":[${teams.joinToString(",")}],"partial":$partial}""")
                path.startsWith("/api/app/teams/") && path.endsWith("/ticket") -> {
                    val slug = path.removePrefix("/api/app/teams/").removeSuffix("/ticket")
                    val team = teams.firstOrNull { "\"slug\":\"$slug\"" in it }
                        ?: return json("""{"error":"no_such_team"}""", HttpStatusCode.NotFound)
                    val address = Regex("\"address\":\"([^\"]+)\"").find(team)!!.groupValues[1]
                    val ticket = "ticket-${++minted}"
                    if (spoilNextTicket) spoilNextTicket = false else tickets[ticket] = address.removePrefix("https://")
                    issued += ticket
                    json("""{"address":"${ticketAddress ?: address}","ticket":"$ticket","expiresAt":"2026-11-20T09:01:00.000Z"}""")
                }
                path == "/api/app/sign-out" -> {
                    sessions -= token
                    respond("", HttpStatusCode.NoContent)
                }
                else -> json("""{"error":"not_found"}""", HttpStatusCode.NotFound)
            }
        }
        // A team's own server.
        if (path == "/api/devices/ticket" && request.method == HttpMethod.Post) {
            val ticket = field("ticket")
            // Once only, and only at the team's own address.
            if (ticket == null || tickets[ticket] != host) {
                return json("""{"error":"invalid_ticket","message":"That sign-in has expired or was used already. Sign in again."}""", HttpStatusCode.Unauthorized)
            }
            tickets -= ticket
            return json(
                """{"device":{"id":"d-$host","name":"${field("deviceName")}","createdAt":"2026-11-20T09:00:00.000Z"},"token":"device-key-$host"}""",
                HttpStatusCode.Created,
            )
        }
        return json("""{"error":"not_found"}""", HttpStatusCode.NotFound)
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg extra: Pair<String, String>) =
        respond(body, status, headersOf(*(listOf(HttpHeaders.ContentType to listOf("application/json")) + extra.map { it.first to listOf(it.second) }).toTypedArray()))
}
