package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.domain.CardRef
import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.domain.Secrets
import dev.wildware.feed.server.domain.invalid
import dev.wildware.feed.server.domain.jsBoolean
import dev.wildware.feed.server.domain.jsString
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Shared secrets (docs/SECRETS.md). The owner's routes list, add, change, reveal and audit them;
 * `/api/agent/secret/<token>` is where an agent's single-use URL is spent. No route here ever logs
 * a body, and every answer that could carry a value is `Cache-Control: no-store`.
 */
fun Route.secretRoutes(app: App) {
    val secrets = app.secrets

    endpoint(
        "/api/secrets",
        HttpMethod.Get to {
            val names = agentNames(app)
            call.respondOwner(200, buildJsonObject {
                put("enabled", secrets.enabled)
                put("secrets", JsonArray(secrets.list().map { secrets.ownerJson(it, names) }))
            })
        },
        HttpMethod.Post to {
            val body = call.jsonObjectBody()
            val secret = secrets.create(
                name = body["name"].jsString ?: throw invalid("name is required"),
                value = valueOf(body) ?: throw invalid("value is required"),
                note = body.optionalString("note"),
                allAgents = body["allAgents"]?.let { it.jsBoolean ?: throw invalid("allAgents must be true or false") } ?: false,
                agentIds = body.agentIds() ?: emptyList(),
                actor = call.owner(app),
            )
            call.respondOwner(201, buildJsonObject { put("secret", secrets.ownerJson(secret, agentNames(app))) })
        },
    )
    endpoint(
        "/api/secrets/audit",
        HttpMethod.Get to {
            val only = call.request.queryParameters["secret"]?.takeIf { it.isNotEmpty() }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
            call.respondOwner(200, buildJsonObject {
                put("entries", JsonArray(secrets.audit(only, limit).map(secrets::auditJson)))
            })
        },
    )
    endpoint(
        "/api/secrets/{id}",
        HttpMethod.Patch to {
            val body = call.jsonObjectBody()
            val secret = secrets.change(
                id = call.parameters["id"].orEmpty(),
                actor = call.owner(app),
                value = valueOf(body),
                noteGiven = "note" in body,
                note = body.optionalString("note"),
                allAgents = body["allAgents"]?.let { it.jsBoolean ?: throw invalid("allAgents must be true or false") },
                agentIds = body.agentIds(),
                paused = body["paused"]?.let { it.jsBoolean ?: throw invalid("paused must be true or false") },
            )
            call.respondOwner(200, buildJsonObject { put("secret", secrets.ownerJson(secret, agentNames(app))) })
        },
        HttpMethod.Delete to {
            secrets.delete(call.parameters["id"].orEmpty(), call.owner(app))
            call.respondOwner(200, buildJsonObject { put("deleted", true) })
        },
    )
    endpoint(
        "/api/secrets/{id}/reveal",
        HttpMethod.Post to {
            val body = call.lenientObjectBody()
            val purpose = body["purpose"].jsString ?: "reveal"
            val value = secrets.reveal(call.parameters["id"].orEmpty(), purpose, call.owner(app))
            val text = utf8(value)
            call.respondOwner(200, buildJsonObject {
                put("value", text ?: java.util.Base64.getEncoder().encodeToString(value))
                put("encoding", if (text != null) "utf8" else "base64")
            })
        },
    )
    // A secret an agent attached to a post (migration 066): opened in place, one at a time, and audited.
    endpoint(
        "/api/cards/{kind}/{id}/secrets/{name}/reveal",
        HttpMethod.Post to {
            val body = call.lenientObjectBody()
            val purpose = body["purpose"].jsString ?: "reveal"
            val card = CardRef(call.parameters["kind"].orEmpty(), call.parameters["id"].orEmpty())
            val value = secrets.revealShared(card, call.parameters["name"].orEmpty(), purpose, call.owner(app), call.person.name)
            val text = utf8(value)
            call.respondOwner(200, buildJsonObject {
                put("value", text ?: java.util.Base64.getEncoder().encodeToString(value))
                put("encoding", if (text != null) "utf8" else "base64")
            })
        },
    )
    endpoint(
        "/api/requests/{id}/secret",
        HttpMethod.Post to {
            val body = call.jsonObjectBody()
            val value = valueOf(body) ?: throw invalid("value is required")
            val secret = secrets.provide(app.requests, call.parameters["id"].orEmpty(), value, body.optionalString("note"), call.owner(app), call.person.id)
            call.respondOwner(200, buildJsonObject {
                put("secret", secrets.ownerJson(secret, agentNames(app)))
                put("request", app.requests.find(call.parameters["id"].orEmpty())?.json ?: JsonNull)
            })
        },
    )

    // The agent's side. The token in the path is the whole permission: see docs/SECRETS.md for why
    // it is not also the agent's bearer token (that would put a long-lived token in the transcript).
    endpoint(
        "/api/agent/secret/{token}",
        HttpMethod.Get to {
            if (!call.linkAllowed(app)) return@to
            val token = call.parameters["token"].orEmpty()
            val value = try {
                secrets.redeemRead(token, call.whoFetched(app))
            } catch (e: Secrets.Rejected) {
                return@to call.respondJson(403, Wire.error("token_rejected", e.message), Wire.JSON_UTF8, *NO_STORE)
            }
            call.respond(RawContent(value, "application/octet-stream", HttpStatusCode.OK, NO_STORE.toList() + ("X-Content-Type-Options" to "nosniff")))
        },
        // HEAD must never spend a URL (a GET handler would otherwise answer it).
        HttpMethod.Head to { call.respondRaw(405, "", null, "Allow" to "GET, PUT, POST") },
        HttpMethod.Put to { call.storeSecret(app) },
        HttpMethod.Post to { call.storeSecret(app) },
    )
}

private val NO_STORE = arrayOf("Cache-Control" to "no-store")

private suspend fun ApplicationCall.storeSecret(app: App) {
    if (!linkAllowed(app)) return
    val token = parameters["token"].orEmpty()
    // A dead link is refused before its body is read.
    if (!app.secrets.writeLinkLive(token)) {
        return respondJson(403, Wire.error("token_rejected", Secrets.Rejected().message), Wire.JSON_UTF8, *NO_STORE)
    }
    val body = try {
        boundedBody(Secrets.VALUE_MAX_BYTES)
    } catch (e: DomainError) {
        return respondJson(e.status, Wire.error(e.code, e.message), Wire.JSON_UTF8, *NO_STORE)
    }
    try {
        val secret = app.secrets.redeemWrite(token, body, whoFetched(app))
        respondJson(201, buildJsonObject {
            put("name", secret.name)
            put("bytes", secret.bytes)
            put("saved", true)
        }, Wire.JSON_UTF8, *NO_STORE)
    } catch (e: Secrets.Rejected) {
        respondJson(403, Wire.error("token_rejected", e.message), Wire.JSON_UTF8, *NO_STORE)
    } catch (e: DomainError) {
        respondJson(e.status, Wire.error(e.code, e.message), Wire.JSON_UTF8, *NO_STORE)
    }
}

/** Take one from the per-address limit on secret links, or answer 429. */
private suspend fun ApplicationCall.linkAllowed(app: App): Boolean {
    val address = runCatching { limiterAddress(app.config) }.getOrNull() ?: "unknown"
    val verdict = app.secretLinkLimiter.take(address)
    if (verdict.allowed) return true
    respondJson(
        429, Wire.error("rate_limited", "too many secret links from this address: retry after ${verdict.retryAfterSeconds}s"), Wire.JSON_UTF8,
        "Retry-After" to verdict.retryAfterSeconds.toString(), *NO_STORE,
    )
    return false
}

/** Where a link was used from, for the audit: the client address and its User-Agent, short. */
private fun ApplicationCall.whoFetched(app: App): String {
    val address = runCatching { clientAddress(app.config) }.getOrNull() ?: "unknown address"
    val agent = request.headers["User-Agent"]?.filter { it.code in 0x20..0x7e }?.take(80)
    return if (agent.isNullOrBlank()) "from $address" else "from $address ($agent)"
}

/** The raw body, whatever its Content-Type (so `curl -T file` works), at most [max] bytes. */
private suspend fun ApplicationCall.boundedBody(max: Int): ByteArray {
    val bytes = receiveAtMost(max) ?: throw DomainError("too_large", "a secret is at most $max bytes", 413)
    if (bytes.isEmpty()) throw invalid("the body was empty: send the secret's bytes, e.g. curl -fsS -T <file> <url>")
    return bytes
}

/** Who on the owner's side did it: the device whose key signed this request. */
private fun ApplicationCall.owner(app: App): Secrets.Actor =
    // The device the guard let in, whether its key came as a Bearer header or as the web app's cookie.
    Secrets.Actor.owner(attributes.getOrNull(CallerKey)?.device ?: Guard.bearer(request.headers["Authorization"])?.let(app.devices::authenticate))

private fun agentNames(app: App): (String) -> String? {
    val names = app.agents.list(includeRevoked = true).associate { it.id to it.name }
    return { names[it] }
}

private fun valueOf(body: JsonObject): ByteArray? {
    val raw = body["value"] ?: return null
    if (raw is JsonNull) return null
    return (raw.jsString ?: throw invalid("value must be a string")).toByteArray(Charsets.UTF_8)
}

private fun JsonObject.optionalString(key: String): String? {
    val v = this[key] ?: return null
    if (v is JsonNull) return null
    return v.jsString ?: throw invalid("$key must be a string")
}

private fun JsonObject.agentIds(): List<String>? {
    val v = this["agentIds"] ?: return null
    if (v is JsonNull) return null
    val list = v as? JsonArray ?: throw invalid("agentIds must be a list of agent ids")
    return list.map { it.jsString ?: throw invalid("agentIds must be a list of agent ids") }
}

private fun utf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: CharacterCodingException) {
    null
}
