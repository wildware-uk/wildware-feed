package dev.wildware.agents.app

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.Collections

/**
 * The hosted account service at `account.test`, in memory, for [FakeServer.otherHost]: the code is
 * always [code], and each ticket it hands out is one the [team] server will take once.
 */
class FakeAccount(private val team: FakeServer) {
    var code = "123456"
    var teams = listOf(team("acme", "Acme"), team("globex", "Globex", readOnly = true))

    /** Every sign-in email is refused with this error and status (rate_limited, 429, say). */
    var refuseEmail: Pair<String, Int>? = null

    /** The team server refuses the next ticket, as if it had run out. */
    var spoilNextTicket = false

    /** Answers every team list and ticket with 401, as an ended account sign-in does. */
    var signedOut = false

    /** The ticket asks for a team that has gone. */
    var teamGone = false

    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val open: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private var wrong = 0
    private var minted = 0

    fun team(slug: String, name: String, readOnly: Boolean = false) =
        """{"id":"t-$slug","slug":"$slug","name":"$name","address":"https://dash.test","role":"member","readOnly":$readOnly,"billing":null}"""

    val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData? = handler@{ request ->
        if (request.url.host != "account.test") return@handler null
        val path = request.url.encodedPath
        calls += path
        val body = when (val content = request.body) {
            is TextContent -> content.text
            is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
            else -> ""
        }
        when (path) {
            "/api/app/sign-in" -> refuseEmail?.let { (error, status) -> json("""{"error":"$error"}""", status, "900") }
                ?: run {
                    wrong = 0
                    json("""{"loginId":"login-1","expiresInSeconds":900}""")
                }
            "/api/app/sign-in/verify" -> when {
                "\"code\":\"$code\"" in body -> {
                    open += "account-token"
                    json("""{"token":"account-token","expiresAt":"2027-02-18T10:00:00.000Z","email":"ada@example.com"}""")
                }
                ++wrong >= 5 -> json("""{"error":"locked"}""", 401)
                else -> json("""{"error":"wrong_code"}""", 401)
            }
            "/api/app/sign-out" -> {
                open -= "account-token"
                respond("", HttpStatusCode.NoContent)
            }
            else -> when {
                signedOut || request.headers[HttpHeaders.Authorization] != "Bearer account-token" -> json("""{"error":"unauthorized"}""", 401)
                path == "/api/app/teams" -> json("""{"teams":[${teams.joinToString(",")}],"partial":false}""")
                path.endsWith("/ticket") && teamGone -> json("""{"error":"no_such_team"}""", 404)
                path.endsWith("/ticket") -> {
                    val ticket = "ticket-${++minted}"
                    if (spoilNextTicket) spoilNextTicket = false else team.tickets += ticket
                    json("""{"address":"https://dash.test","ticket":"$ticket","expiresAt":"2026-11-20T09:01:00.000Z"}""")
                }
                else -> json("""{"error":"not_found"}""", 404)
            }
        }
    }

    private fun MockRequestHandleScope.json(body: String, status: Int = 200, retryAfter: String? = null) = respond(
        body,
        HttpStatusCode.fromValue(status),
        if (retryAfter == null) headersOf(HttpHeaders.ContentType, "application/json")
        else headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf(retryAfter)),
    )
}
