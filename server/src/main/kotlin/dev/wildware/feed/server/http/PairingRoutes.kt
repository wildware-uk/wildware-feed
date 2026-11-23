package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.Pairings
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsString
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Agent pairing over plain HTTP, for an agent that has no token yet (MCP needs one), and the
 * owner's side: the list, Allow and Deny. See [Pairings] for the rules, [PairingWords] for what
 * the agent is told.
 *
 * `/api/pair` is public, so it is limited per client address ([App.pairStartLimiter],
 * [App.pairPollLimiter]) and its body is small; the poll secret is 32 random bytes; nothing here
 * logs a token.
 */
fun Route.pairingRoutes(app: App) {
    endpoint(
        "/api/pair",
        HttpMethod.Get to { call.respondJson(200, buildJsonObject { put("instructions", PairingWords.howTo(app.teams.address())) }, Wire.JSON_UTF8) },
        HttpMethod.Post to {
            val address = call.clientAddressOrNull(app)
            if (!call.allowed(app.pairStartLimiter, address ?: "unknown")) return@to
            val body = call.smallObjectBody() ?: return@to
            fun field(key: String): String? {
                val value = body[key] ?: return null
                if (value is JsonNull) return null
                return value.jsString ?: throw invalid("$key must be a string")
            }
            val name = field("name") ?: throw invalid("name is required: the name you want to join under, e.g. \"laptop-personal\"")
            val started = app.pairings.start(name, field("host"), field("cwd"), address, askedBy = null)
            call.respondJson(201, PairingWords.started(app.teams.address(), started, mcp = false), Wire.JSON_UTF8, "Cache-Control" to "no-store")
        },
    )
    endpoint(
        "/api/pair/{id}",
        HttpMethod.Get to {
            val address = call.clientAddressOrNull(app)
            if (!call.allowed(app.pairPollLimiter, address ?: "unknown")) return@to
            val secret = Guard.bearer(call.request.headers["Authorization"])
                ?: return@to call.respondJson(
                    401,
                    Wire.error("missing_secret", "send the poll secret you were given as Authorization: Bearer <poll_secret>"),
                    Wire.JSON_UTF8,
                )
            val poll = app.pairings.await(call.parameters["id"].orEmpty(), secret, Pairings.HOLD_MS)
            call.respondJson(200, PairingWords.poll(app.teams.address(), poll, mcp = false), Wire.JSON_UTF8, "Cache-Control" to "no-store")
        },
    )

    endpoint(
        "/api/pairings",
        HttpMethod.Get to {
            val seq = app.bus.lastSeq
            val pending = app.pairings.pending()
            call.respondOwner(200, buildJsonObject {
                put("seq", seq)
                put("at", Wire.isoOf(app.clock()))
                put("pairings", JsonArray(pending.map(app.pairings::ownerJson)))
            })
        },
    )
    endpoint(
        "/api/pairings/{id}/approve",
        HttpMethod.Post to {
            val body = call.lenientObjectBody()
            val name = body["name"]?.let { if (it is JsonNull) null else it.jsString ?: throw invalid("name must be a string") }
            // Who allowed it: removing them revokes it (People.remove).
            val pairing = app.pairings.approve(call.parameters["id"].orEmpty(), name, call.person.id)
            call.respondOwner(200, buildJsonObject {
                put("pairing", app.pairings.ownerJson(pairing))
                put("agent", buildJsonObject { put("id", pairing.agentId); put("name", pairing.name) })
            })
        },
    )
    endpoint(
        "/api/pairings/{id}/deny",
        HttpMethod.Post to {
            val pairing = app.pairings.deny(call.parameters["id"].orEmpty())
            call.respondOwner(200, buildJsonObject { put("pairing", app.pairings.ownerJson(pairing)) })
        },
    )
}

/** The client's address, or null when the configured address header is missing. */
private fun ApplicationCall.clientAddressOrNull(app: App): String? = runCatching { limiterAddress(app.config) }.getOrNull()

/** Take one from [limiter] for [key], or answer 429 with Retry-After and return false. */
private suspend fun ApplicationCall.allowed(limiter: dev.wildware.feed.server.auth.SlidingLimiter, key: String): Boolean {
    val verdict = limiter.take(key)
    if (verdict.allowed) return true
    val s = verdict.retryAfterSeconds
    respondJson(429, Wire.error("rate_limited", "too many pairing calls from this address: retry after ${s}s"), Wire.JSON_UTF8, "Retry-After" to s.toString())
    return false
}

/** A JSON object body of at most [PAIR_BODY_MAX] bytes, or null with the refusal already sent. */
private suspend fun ApplicationCall.smallObjectBody(): JsonObject? {
    if (request.headers["Content-Type"] == null) throw invalid("body must be JSON: {\"name\": \"…\"}")
    val bytes = receiveAtMost(PAIR_BODY_MAX)
    if (bytes == null) {
        respondJson(413, Wire.error("too_large", "a pairing request is at most $PAIR_BODY_MAX bytes"), Wire.JSON_UTF8)
        return null
    }
    val parsed = Wire.parseStrict(bytes.toString(Charsets.UTF_8)) ?: throw invalid("body must be JSON")
    return parsed as? JsonObject ?: throw invalid("body must be a JSON object")
}

private const val PAIR_BODY_MAX = 4096

/**
 * What a pairing agent is told, over HTTP and MCP alike: plain steps it can follow unaided. The
 * token appears only in [poll]'s answer on the one poll that collects it.
 */
object PairingWords {
    const val SERVER_NAME = "agent-dashboard"

    fun howTo(base: String) =
        "To connect an agent to this dashboard: POST $base/api/pair with JSON {\"name\": \"<a name for you, e.g. laptop-personal>\", " +
            "\"host\": \"<this machine's hostname>\", \"cwd\": \"<your working directory>\"}. Tell your owner the code it returns; " +
            "they allow or deny you in the app. Then GET $base/api/pair/<pairing_id> with the header " +
            "Authorization: Bearer <poll_secret>, again and again while state is \"pending\" (each call waits up to 25 seconds). " +
            "When state is \"approved\" the answer carries your token, once: save it into your MCP config and reconnect."

    fun started(base: String, started: Pairings.Started, mcp: Boolean): JsonObject {
        val p = started.pairing
        val next = if (mcp) {
            "call await_pairing({pairing_id: \"${p.id}\", poll_secret: \"<poll_secret>\"}) and keep calling it while state is \"pending\""
        } else {
            "GET $base/api/pair/${p.id} with the header Authorization: Bearer <poll_secret>, and call it again while state is \"pending\""
        }
        return buildJsonObject {
            put("state", p.state)
            put("pairing_id", p.id)
            put("poll_secret", started.secret)
            put("code", p.code)
            put("name", p.name)
            put("expires_at", Wire.isoOf(p.expiresAt))
            put("poll_url", "$base/api/pair/${p.id}")
            put(
                "instructions",
                "Your request to join as \"${p.name}\" is waiting for your owner. Tell them the code ${p.code}: the app shows it " +
                    "beside your name, and they choose Allow or Deny. Keep the poll_secret to yourself. Now $next; " +
                    "each call waits up to ${Pairings.HOLD_MS / 1000} seconds. This request expires at ${Wire.isoOf(p.expiresAt)}.",
            )
        }
    }

    fun poll(base: String, poll: Pairings.Poll, mcp: Boolean): JsonObject {
        val p = poll.pairing
        val again = if (mcp) "call await_pairing again" else "GET this URL again"
        return buildJsonObject {
            // The poll that collects the token says "approved"; later ones say "collected".
            put("state", if (poll.token != null) "approved" else p.state)
            put("pairing_id", p.id)
            put("code", p.code)
            put("expires_at", Wire.isoOf(p.expiresAt))
            val token = poll.token
            when {
                token != null -> {
                    val name = poll.agentName ?: p.name
                    put("agent", buildJsonObject { put("id", p.agentId); put("name", name) })
                    put("token", token)
                    put("mcp_url", "$base/mcp")
                    put("authorization_header", "Authorization: Bearer $token")
                    put(
                        "claude_code_command",
                        "claude mcp add --transport http --scope local $SERVER_NAME $base/mcp --header \"Authorization: Bearer $token\"",
                    )
                    put("instructions", approved(base, name))
                }
                p.state == "pending" -> put("instructions", "Your owner has not decided yet. That is not a refusal: $again, and keep going while state is \"pending\".")
                p.state == "collected" -> put("instructions", "This pairing's token was already handed over, and it has been used or it is too late to show it again. If it was lost, start a new pairing.")
                p.state == "denied" -> put("instructions", "Your owner denied this pairing. Do not try to connect another way; ask them first if you think it was a mistake.")
                else -> put("instructions", "Nobody decided before this pairing expired. Start a new one if you still need to connect, and tell your owner first.")
            }
        }
    }

    private fun approved(base: String, name: String) =
        "You are allowed in as \"$name\". Save the token now. If this answer is lost, poll again with the same secret: the same " +
            "token comes back until it is first used, for up to 10 minutes, and never after. It is not stored anywhere " +
            "you can read it back. Put it in your MCP client's config for the $SERVER_NAME server ($base/mcp, Streamable HTTP) as the " +
            "header Authorization: Bearer <token>. In Claude Code, run claude_code_command from this directory; if a $SERVER_NAME " +
            "server is set up already, first run claude mcp remove --scope local $SERVER_NAME, or change its Authorization header. " +
            "--scope local keeps the change to this directory, so another session that shared your old token keeps it. Then reconnect " +
            "(/mcp in Claude Code, or start a new session) and call register_session: you are \"$name\" from then on."
}
