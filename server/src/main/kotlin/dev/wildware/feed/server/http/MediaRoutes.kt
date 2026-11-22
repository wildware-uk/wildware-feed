package dev.wildware.feed.server.http

import dev.wildware.feed.server.db.Viewer
import dev.wildware.feed.server.domain.CardRef
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.App
import dev.wildware.feed.server.media.Chunks
import dev.wildware.feed.server.media.MediaError
import dev.wildware.feed.server.media.Serve
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Uploads and media serving (spec/07 §3b, §4, §7, §8). */
fun Route.mediaRoutes(app: App) {
    endpoint("/api/upload/{token}", HttpMethod.Put to { call.upload(app) })
    endpoint("/api/media", HttpMethod.Post to { call.ownerUpload(app) })
    endpoint("/media/{id}/{variant}", HttpMethod.Get to {
        val save = call.request.queryParameters["download"] == "1"
        val id = call.parameters["id"].orEmpty()
        // A file on a card in a channel this person can't read is missing (spec/15).
        val card = app.db.mediaCard(id)
        if (card != null && app.feed.cards.find(card) == null) return@to call.mediaMiss()
        call.serveMedia(app, id, call.parameters["variant"].orEmpty(), save = save)
    })
    endpoint("/api/agent/media/{id}", HttpMethod.Get to {
        val id = call.parameters["id"].orEmpty()
        val agent = call.agentMayFetch(app) ?: return@to call.mediaMiss()
        // The same rule as reading the card the file is on (issue #304, spec/15): any refusal is the same 404.
        val card = app.db.mediaCard(id)
        if (card == null || !Viewer.with(Viewer.agent(agent.id)) { app.feed.threads.mayFetch(agent.id, card) }) return@to call.mediaMiss()
        call.serveMedia(app, id, "original", attachedOnly = true)
    })
}

private const val UPLOAD_JSON = "application/json"

private val uploadStatus = mapOf(
    "token_rejected" to 403, "too_large" to 413, "unsupported_type" to 415,
    "invalid_argument" to 400, "not_found" to 404, "conflict" to 409,
)

/**
 * The body as SvelteKit's node adapter hands it over: none without a `Content-Type`, with
 * `Content-Length: 0`, or with neither a length nor chunked encoding.
 */
private suspend fun ApplicationCall.bodyOrNull(): Chunks? {
    val headers = request.headers
    if (headers["Content-Type"] == null) return null
    val length = headers["Content-Length"]
    if (length != null && length.trim() == "0") return null
    if (length == null && headers["Transfer-Encoding"] == null) return null
    val channel = receiveChannel()
    val buffer = ByteArray(64 * 1024)
    return Chunks {
        val n = channel.readAvailable(buffer)
        if (n < 0) null else buffer.copyOf(n)
    }
}

private fun ApplicationCall.declaredLength(): Long? =
    request.headers["Content-Length"]?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.toLong()

private suspend fun ApplicationCall.upload(app: App) {
    val token = parameters["token"].orEmpty()
    val verdict = app.uploadLimiter.take(token.substringBefore('.').take(64))
    if (!verdict.allowed) {
        return respondJson(
            429, Wire.error("rate_limited", "too many upload attempts for this token"), UPLOAD_JSON,
            "Cache-Control" to "no-store", "Retry-After" to verdict.retryAfterSeconds.toString(),
        )
    }
    try {
        val stored = app.uploads.ingest(token, bodyOrNull(), declaredLength())
        respondJson(201, buildJsonObject {
            put("media_id", JsonPrimitive(stored.id))
            put("kind", JsonPrimitive(stored.kind))
            put("mime", JsonPrimitive(stored.mime))
            put("bytes", JsonPrimitive(stored.bytes))
            put("sha256", JsonPrimitive(stored.sha256))
            put("status", JsonPrimitive(stored.status))
            put("deduped", JsonPrimitive(stored.deduped))
        }, UPLOAD_JSON, "Cache-Control" to "no-store")
    } catch (e: MediaError) {
        respondJson(uploadStatus.getValue(e.code), Wire.error(e.code, e.message), UPLOAD_JSON, "Cache-Control" to "no-store")
    } catch (e: Exception) {
        System.err.println("upload failed: $e")
        respondJson(
            500, Wire.error("internal_error", "the dashboard failed to store this upload; see server logs"), UPLOAD_JSON,
            "Cache-Control" to "no-store",
        )
    }
}

private suspend fun ApplicationCall.ownerUpload(app: App) {
    try {
        val stored = app.uploads.ownerUpload(
            request.queryParameters["filename"] ?: "upload",
            request.headers["Content-Type"].orEmpty(),
            bodyOrNull(),
            request.headers["Content-Length"]?.trim()?.toDoubleOrNull()?.takeIf { !it.isNaN() }?.toLong(),
        )
        respondOwner(201, buildJsonObject { put("media", stored.media) })
    } catch (e: MediaError) {
        val status = when (e.code) { "not_found" -> 404; "conflict" -> 409; else -> 400 }
        val code = when (e.code) { "not_found", "conflict" -> e.code; else -> "invalid_argument" }
        respondOwner(status, Wire.error(code, e.message))
    }
}

/** The agent behind this bearer, when it may fetch agent media at all; null is the same 404 (spec/07 §8). */
private fun ApplicationCall.agentMayFetch(app: App): dev.wildware.feed.server.domain.Agents.Agent? {
    val token = app.agents.parseBearer(request.headers["Authorization"]).first ?: return null
    val agent = app.agents.authenticate(token).first ?: return null
    return agent.takeIf { app.agentMediaLimiter.take(it.id).allowed }
}

/** The card a file is attached to, or null. */
private fun dev.wildware.feed.server.db.Database.mediaCard(id: String): CardRef? = one(
    "SELECT update_id, message_id, request_id FROM media WHERE team_id = :team AND id = ?", id,
) { rs ->
    rs.getString(1)?.let { CardRef("update", it) } ?: rs.getString(2)?.let { CardRef("message", it) } ?: rs.getString(3)?.let { CardRef("request", it) }
}

private suspend fun ApplicationCall.mediaMiss() =
    respondJson(404, JsonObject(mapOf("error" to JsonPrimitive("not_found"))), UPLOAD_JSON, "Cache-Control" to "no-store")

private suspend fun ApplicationCall.serveMedia(app: App, id: String, variant: String, attachedOnly: Boolean = false, save: Boolean = false) {
    if (attachedOnly && !app.db.mediaAttached(id)) return mediaMiss()
    // Deleting a card takes back what was on it: its files answer as any other miss.
    if (app.db.mediaOnDeletedCard(id)) return mediaMiss()
    val file = runCatching { app.serve.open(id, variant, save) }.getOrNull() ?: return mediaMiss()
    val base = listOf(
        "Content-Type" to file.mime,
        // private: browsers and the apps keep it, but no shared cache (Cloudflare in front) may hand
        // a file it checked access for to anyone else with the URL (docs/HOSTING.md §4).
        "Cache-Control" to "private, max-age=31536000, immutable",
        "X-Content-Type-Options" to "nosniff",
        "Content-Disposition" to file.disposition,
        "Content-Security-Policy" to "default-src 'none'; sandbox",
        "Accept-Ranges" to "bytes",
        "ETag" to file.etag,
    )
    if (Serve.notModified(request.headers["If-None-Match"], file.etag)) {
        return respond(FileContent(HttpStatusCode.NotModified, base, null, file.key, null, 0))
    }
    val head = request.httpMethod == HttpMethod.Head
    when (val range = Serve.range(request.headers["Range"], file.size)) {
        Serve.Companion.Range.Unsatisfiable ->
            respond(FileContent(HttpStatusCode.RequestedRangeNotSatisfiable, base + ("Content-Range" to "bytes */${file.size}"), null, file.key, null, 0))
        Serve.Companion.Range.Whole ->
            respond(FileContent(HttpStatusCode.OK, base, app.mediaPaths.store.takeUnless { head }, file.key, file.size, 0))
        is Serve.Companion.Range.Span -> {
            val length = range.end - range.start + 1
            respond(
                FileContent(
                    HttpStatusCode.PartialContent, base + ("Content-Range" to "bytes ${range.start}-${range.end}/${file.size}"),
                    app.mediaPaths.store.takeUnless { head }, file.key, length, range.start,
                ),
            )
        }
    }
}

/** On an update or a message that was deleted (a request has no deleted mark). */
private fun dev.wildware.feed.server.db.Database.mediaOnDeletedCard(id: String) = one(
    "SELECT 1 FROM media AS m WHERE m.team_id = :team AND m.id = ? AND (" +
        "EXISTS (SELECT 1 FROM updates AS u WHERE u.team_id = :team AND u.id = m.update_id AND u.deleted_at IS NOT NULL) OR " +
        "EXISTS (SELECT 1 FROM messages AS x WHERE x.team_id = :team AND x.id = m.message_id AND x.deleted_at IS NOT NULL))",
    id,
) { 1 } != null

private fun dev.wildware.feed.server.db.Database.mediaAttached(id: String) = one(
    "SELECT 1 FROM media WHERE team_id = :team AND id = ? AND (update_id IS NOT NULL OR message_id IS NOT NULL OR request_id IS NOT NULL)", id,
) { 1 } != null

/** A span of a stored object (or nothing) with exact headers; [contentLength] null means no Content-Length at all. */
private class FileContent(
    override val status: HttpStatusCode,
    private val extra: List<Pair<String, String>>,
    private val store: dev.wildware.feed.server.media.MediaStore?,
    private val key: String,
    override val contentLength: Long?,
    private val start: Long,
) : OutgoingContent.WriteChannelContent() {
    override val contentType get() = null
    override val headers: Headers get() = Headers.build { extra.forEach { (k, v) -> append(k, v) } }

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val from = store ?: return
        var left = contentLength ?: return
        val input = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { from.read(key, start, left) } ?: return
        input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (left > 0) {
                val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { stream.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt()) }
                if (n < 0) break
                channel.writeFully(buffer, 0, n)
                left -= n
            }
        }
    }
}
