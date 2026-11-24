package dev.wildware.feed.server.http

import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.domain.Js
import dev.wildware.feed.server.domain.invalid
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** How bodies go out: exact bytes and exact headers, because clients and the parity suite compare them. */
object Wire {
    val json = Json { encodeDefaults = true; explicitNulls = true }

    /** `application/json` as the TS guard and MCP send it (no charset). */
    const val JSON = "application/json"
    /** What the TS `ownerAction` helper sends. */
    const val JSON_UTF8 = "application/json; charset=utf-8"

    private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** JavaScript's `new Date(ms).toISOString()`: always three millisecond digits. */
    fun isoOf(ms: Long): String = iso.format(Instant.ofEpochMilli(ms))

    fun encode(element: JsonElement): String = Js.escapeLoneSurrogates(json.encodeToString(JsonElement.serializer(), element))

    /**
     * `JSON.parse`: kotlinx happily reads a bare word like `nope` as a value, which JavaScript
     * refuses; refuse it too.
     */
    fun parseStrict(text: String): JsonElement? {
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        fun ok(e: JsonElement): Boolean = when (e) {
            is JsonPrimitive -> e.isString || e is kotlinx.serialization.json.JsonNull || e.content == "true" || e.content == "false" ||
                Regex("^-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?$").matches(e.content)
            is JsonObject -> e.values.all(::ok)
            is kotlinx.serialization.json.JsonArray -> e.all(::ok)
        }
        return parsed.takeIf(::ok)
    }

    fun error(code: String, message: String? = null): JsonObject = buildJsonObject {
        put("error", JsonPrimitive(code))
        if (message != null) put("message", JsonPrimitive(message))
    }

    /** `plan_limit`: the error, which limit, and where to upgrade (null when the server has no control plane). */
    fun planLimit(e: dev.wildware.feed.server.domain.PlanLimit): JsonObject = buildJsonObject {
        put("error", JsonPrimitive(e.code))
        put("message", JsonPrimitive(e.message))
        put("limit", JsonPrimitive(e.limit))
        put("upgradeUrl", e.upgradeUrl?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
    }
}

/** A body sent as given: [contentType] is written verbatim (null writes none). */
class RawContent(
    private val bytes: ByteArray,
    private val type: String?,
    override val status: HttpStatusCode,
    private val extra: List<Pair<String, String>> = emptyList(),
) : OutgoingContent.ByteArrayContent() {
    override val contentType: ContentType? get() = null
    override val contentLength: Long get() = bytes.size.toLong()
    override val headers: Headers get() = Headers.build {
        if (type != null) append("Content-Type", type)
        extra.forEach { (k, v) -> append(k, v) }
    }
    override fun bytes(): ByteArray = bytes
}

suspend fun ApplicationCall.respondRaw(status: Int, body: String, type: String?, vararg headers: Pair<String, String>) =
    respond(RawContent(body.toByteArray(Charsets.UTF_8), type, HttpStatusCode.fromValue(status), headers.toList()))

suspend fun ApplicationCall.respondJson(status: Int, body: JsonElement, type: String = Wire.JSON, vararg headers: Pair<String, String>) =
    respondRaw(status, Wire.encode(body), type, *headers)

/** The TS `ownerAction` envelope: utf-8 JSON, never cached. */
suspend fun ApplicationCall.respondOwner(status: Int, body: JsonElement) =
    respondJson(status, body, Wire.JSON_UTF8, "Cache-Control" to "no-store")

suspend fun ApplicationCall.respondDomainError(e: DomainError) =
    respondOwner(e.status, if (e is dev.wildware.feed.server.domain.PlanLimit) Wire.planLimit(e) else Wire.error(e.code, e.message))

suspend fun ApplicationCall.respondRedirect303(location: String, vararg headers: Pair<String, String>) =
    respondRaw(303, "", null, "Location" to location, *headers)

/** How big a request body may be. Bodies are read only up to their cap, so a big one never fills memory. */
object BodyLimits {
    /** Routes anyone can call before signing in: a name, a password, a code. */
    const val PUBLIC = 64 * 1024
    /** Any other JSON or form body: the largest owner write (a 100,000-character body) fits with room to spare. */
    const val DEFAULT = 1024 * 1024
    /** `/mcp`: a post with its body, 8 blocks of up to 100,000 characters each, apps and their data. */
    const val MCP = 8 * 1024 * 1024
}

/** Thrown when a body is over its cap: answered 413 `payload_too_large`. */
fun payloadTooLarge(max: Int) = DomainError("payload_too_large", "the body is at most $max bytes", 413)

/**
 * At most [max] bytes of the request body, counted as they arrive, or null once it is longer. A
 * declared `Content-Length` over [max] is refused before anything is read.
 */
suspend fun ApplicationCall.receiveAtMost(max: Int): ByteArray? {
    val declared = request.headers["Content-Length"]?.trim()?.toLongOrNull()
    if (declared != null && declared > max) return null
    val channel = receiveChannel()
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = channel.readAvailable(buffer, 0, buffer.size)
        if (n < 0) break
        if (out.size() + n > max) return null
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/**
 * The request body as text, at most [max] bytes (else 413), or null when there is none
 * (adapter-node gives no body without a Content-Type).
 */
suspend fun ApplicationCall.bodyText(max: Int = BodyLimits.DEFAULT): String? {
    if (request.headers["Content-Type"] == null) return null
    val bytes = receiveAtMost(max) ?: throw payloadTooLarge(max)
    return bytes.toString(Charsets.UTF_8)
}

/** A JSON object body, strictly: "body must be JSON" / "body must be a JSON object". */
suspend fun ApplicationCall.jsonObjectBody(max: Int = BodyLimits.DEFAULT): JsonObject {
    val text = bodyText(max) ?: throw invalid("body must be JSON")
    val parsed = Wire.parseStrict(text) ?: throw invalid("body must be JSON")
    return parsed as? JsonObject ?: throw invalid("body must be a JSON object")
}

/** A JSON object body, leniently: anything unreadable is `{}` (the TS card and tag routes do this). */
suspend fun ApplicationCall.lenientObjectBody(max: Int = BodyLimits.DEFAULT): JsonObject =
    bodyText(max)?.let { Wire.parseStrict(it) as? JsonObject } ?: JsonObject(emptyMap())

fun JsonElement?.jsValue(): Any? = when (this) {
    null, is kotlinx.serialization.json.JsonNull -> null
    is JsonPrimitive -> when {
        isString -> content
        content == "true" -> true
        content == "false" -> false
        else -> content.toDoubleOrNull()
    }
    is kotlinx.serialization.json.JsonArray -> map { it.jsValue() }
    is JsonObject -> mapValues { it.value.jsValue() }
}
