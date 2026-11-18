package dev.wildware.feed.server.http

import dev.wildware.feed.server.domain.DomainError
import dev.wildware.feed.server.domain.invalid
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.utils.io.toByteArray
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

    fun encode(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)

    fun error(code: String, message: String? = null): JsonObject = buildJsonObject {
        put("error", JsonPrimitive(code))
        if (message != null) put("message", JsonPrimitive(message))
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
    respondOwner(e.status, Wire.error(e.code, e.message))

suspend fun ApplicationCall.respondRedirect303(location: String, vararg headers: Pair<String, String>) =
    respondRaw(303, "", null, "Location" to location, *headers)

/** The request body as text, or null when there is none (adapter-node gives no body without a Content-Type). */
suspend fun ApplicationCall.bodyText(): String? {
    if (request.headers["Content-Type"] == null) return null
    return receiveChannel().toByteArray().toString(Charsets.UTF_8)
}

/** A JSON object body, strictly: "body must be JSON" / "body must be a JSON object". */
suspend fun ApplicationCall.jsonObjectBody(): JsonObject {
    val text = bodyText() ?: throw invalid("body must be JSON")
    val parsed = runCatching { Wire.json.parseToJsonElement(text) }.getOrElse { throw invalid("body must be JSON") }
    return parsed as? JsonObject ?: throw invalid("body must be a JSON object")
}

/** A JSON object body, leniently: anything unreadable is `{}` (the TS card and tag routes do this). */
suspend fun ApplicationCall.lenientObjectBody(): JsonObject =
    bodyText()?.let { runCatching { Wire.json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: JsonObject(emptyMap())

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
