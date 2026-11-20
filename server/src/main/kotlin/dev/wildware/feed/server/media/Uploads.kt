package dev.wildware.feed.server.media

import dev.wildware.feed.server.auth.Tokens
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.db.longOrNull
import dev.wildware.feed.server.domain.jsTrim
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** A refusal from the media code, with the codes the TS server's `MediaError` uses. */
class MediaError(val code: String, override val message: String) : RuntimeException(message)

/** One chunk of a request body at a time; null at the end. */
fun interface Chunks {
    suspend fun next(): ByteArray?
}

/**
 * Where media lives on disk (spec/07 §6). Keys are `<id[0:2]>/<id>/<name>` for the home team, as
 * always, and `t/<team id>/<id[0:2]>/<id>/<name>` for any other team (docs/HOSTING.md §3), so one
 * team's files can be listed, measured and deleted together. The team is the work's ([Tenant.id]).
 */
class MediaPaths(dataDir: String, store: MediaStore? = null) {
    val root: Path = Path.of(dataDir, "media").toAbsolutePath().normalize()
    val tempRoot: Path = Path.of(dataDir, "tmp", "uploads").toAbsolutePath().normalize()
    /** Scratch space for derivations; always on local disk. */
    val workRoot: Path = Path.of(dataDir, "tmp", "derive").toAbsolutePath().normalize()

    /** Where the bytes live: [root] on disk unless a bucket is configured. */
    val store: MediaStore = store ?: LocalMediaStore(root)

    /** Where this team's keys start: nothing for the home team, `t/<team id>/` for any other. */
    fun teamPrefix(team: String = Tenant.id): String = if (team == Tenant.HOME) "" else "t/$team/"

    fun originalKey(id: String, mime: String) = relative(id, "original.${Mime.extensionOf(mime)}")
    fun folder(id: String) = "${teamPrefix()}${id.substring(0, 2)}/$id/"

    fun dir(id: String): Path = root.resolve(folder(id).trimEnd('/'))
    fun original(id: String, mime: String): Path = dir(id).resolve("original.${Mime.extensionOf(mime)}")

    /** A stored derivative path (`<id[0:2]>/<id>/<name>`, team prefix first), or null when it escapes the root. */
    fun stored(relative: String): Path? = root.resolve(relative).normalize().takeIf { it.startsWith(root) && it != root }
    fun relative(id: String, name: String) = "${folder(id)}$name"
}

/**
 * Reserving uploads and taking their bytes (spec/07 §3-4; TS `src/media/upload.ts`, `ingest.ts`).
 * Nothing here publishes an event.
 */
class Uploads(
    private val db: Database,
    val paths: MediaPaths,
    private val tokenSecret: String,
    private val baseUrl: String,
    private val maxImageBytes: Long,
    private val maxVideoBytes: Long,
    private val maxFileBytes: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Grant(val mediaId: String, val uploadUrl: String, val expiresAt: Long, val maxBytes: Long)
    data class Stored(val media: JsonObject, val id: String, val kind: String, val mime: String, val bytes: Long, val sha256: String, val status: String, val deduped: Boolean)

    private fun checkFilename(filename: String): String {
        val trimmed = filename.jsTrim()
        if (trimmed.isEmpty()) throw MediaError("invalid_argument", "filename is required")
        if (trimmed.length > FILENAME_MAX) throw MediaError("invalid_argument", "filename must be at most $FILENAME_MAX characters")
        if (trimmed.codePoints().anyMatch { it < 0x20 || it == 0x7f }) throw MediaError("invalid_argument", "filename contains control characters")
        return trimmed
    }

    fun limitFor(kind: String) = when (kind) {
        "image" -> maxImageBytes
        "video" -> maxVideoBytes
        else -> maxFileBytes
    }

    private fun article(kind: String) = if (kind == "image" || kind == "audio") "an" else "a"

    private fun kindOrRefuse(mime: String) =
        Mime.kindOf(mime) ?: throw MediaError("unsupported_type", "${Wire.encode(JsonPrimitive(mime))} $MIME_HELP")

    private fun tooLarge(kind: String, limit: Long, declared: Long) =
        MediaError("too_large", "${article(kind)} $kind may be at most $limit bytes; this one declares $declared")

    /**
     * The name kept with an upload: a file's, a sound's and a video's, so the apps can say which is
     * which; never a picture's. The TS server kept it for files only (a known difference, spec/README.md).
     */
    internal fun keptName(kind: String, name: String): String? = if (kind == "image") null else name

    /** `create_upload`: a pending media row and a single-use token for it, valid 15 minutes. */
    fun reserve(agentId: String, filename: String, rawMime: String, bytes: Long): Grant {
        val now = clock()
        val name = checkFilename(filename)
        val mime = Mime.canonical(rawMime)
        val kind = kindOrRefuse(mime)
        if (bytes < 1) throw MediaError("invalid_argument", "bytes must be the whole number of bytes the file has, and at least 1")
        val limit = limitFor(kind)
        if (bytes > limit) throw tooLarge(kind, limit, bytes)
        db.one("SELECT 1 FROM agents WHERE team_id = :team AND id = ?", agentId) { 1 } ?: throw MediaError("not_found", "no such agent: $agentId")
        val maxBytes = minOf(bytes, limit)
        val expiresAt = now + TOKEN_TTL_MS
        val mediaId = Ulid.next(now)
        val tokenId = Ulid.next(now)
        db.transaction { _ ->
            insertMedia(mediaId, agentId, "agent:$agentId", kind, mime, bytes, keptName(kind, name), now)
            db.update(
                "INSERT INTO upload_tokens (team_id, id, agent_id, media_id, max_bytes, mime_allow, expires_at) VALUES (:team, ?, ?, ?, ?, ?, ?)",
                tokenId, agentId, mediaId, maxBytes, Wire.encode(kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(mime)))), expiresAt,
            )
        }
        return Grant(mediaId, "$baseUrl/api/upload/${Tokens.signUpload(tokenId, tokenSecret)}", expiresAt, maxBytes)
    }

    private fun insertMedia(id: String, agentId: String?, author: String, kind: String, mime: String, bytes: Long, filename: String?, at: Long) {
        db.update(
            "INSERT INTO media (team_id, id, agent_id, author, kind, mime, bytes, sha256, filename, status, created_at) VALUES (:team, ?, ?, ?, ?, ?, ?, '', ?, 'pending', ?)",
            id, agentId, author, kind, mime, bytes, filename, at,
        )
    }

    /** `PUT /api/upload/<token>`. [body] is null when the request carried none. */
    suspend fun ingest(token: String, body: Chunks?, contentLength: Long?): Stored {
        val now = clock()
        val tokenId = Tokens.verifyUpload(token, tokenSecret) ?: throw rejected()
        if (body == null) throw MediaError("invalid_argument", NO_BODY)
        data class Spent(val mediaId: String, val maxBytes: Long, val allow: List<String>)
        val spent = db.one(
            "UPDATE upload_tokens SET used_at = ? WHERE team_id = :team AND id = ? AND used_at IS NULL AND expires_at > ? RETURNING media_id, max_bytes, mime_allow",
            now, tokenId, now,
        ) { rs ->
            Spent(rs.getString(1), rs.getLong(2), Wire.json.parseToJsonElement(rs.getString(3)).let { el ->
                (el as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content }
            })
        } ?: throw rejected()
        try {
            val media = row(spent.mediaId) ?: throw MediaError("not_found", "the media this token was minted for is gone")
            if (media.sha256.isNotEmpty()) throw MediaError("conflict", "bytes have already landed for this media")
            return storeBytes(media, body, spent.maxBytes, spent.allow, contentLength)
        } catch (e: Throwable) {
            // A failed upload hands its token back, so the same url can be retried (decided fix 7).
            db.update("UPDATE upload_tokens SET used_at = NULL WHERE team_id = :team AND id = ? AND used_at = ?", tokenId, now)
            throw e
        }
    }

    /** `POST /api/media`: the owner's upload, row first, then the bytes. */
    suspend fun ownerUpload(filename: String, rawMime: String, body: Chunks?, contentLength: Long?): Stored {
        if (body == null) throw MediaError("invalid_argument", "the request carried no body")
        val now = clock()
        val name = checkFilename(filename)
        val mime = Mime.canonical(rawMime)
        val kind = kindOrRefuse(mime)
        val limit = limitFor(kind)
        if (contentLength != null && contentLength > limit) throw tooLarge(kind, limit, contentLength)
        val id = Ulid.next(now)
        db.update(
            "INSERT INTO media (team_id, id, agent_id, author, kind, mime, bytes, sha256, filename, status, created_at) VALUES (:team, ?, NULL, 'human', ?, ?, 0, '', ?, 'pending', ?)",
            id, kind, mime, keptName(kind, name), now,
        )
        return storeBytes(row(id)!!, body, limit, listOf(mime), contentLength)
    }

    class Row(val id: String, val kind: String, val mime: String, val sha256: String)

    private fun row(id: String): Row? =
        db.one("SELECT id, kind, mime, sha256 FROM media WHERE team_id = :team AND id = ?", id) { Row(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }

    private suspend fun storeBytes(media: Row, body: Chunks, maxBytes: Long, allowedRaw: List<String>, contentLength: Long?): Stored {
        val declared = if (media.kind == "file") media.mime else null
        val allowed = if (declared != null) emptyList() else allowedRaw.filter(Mime::isAllowed)
        if (declared == null && allowed.isEmpty()) throw MediaError("unsupported_type", "this upload authorises no servable type")
        if (contentLength != null && contentLength > maxBytes) throw overCap(maxBytes)
        val written = streamToTemp(body, maxBytes, allowed, declared)
        val deduped = place(media.id, written.temp, written.mime, written.sha256)
        db.update("UPDATE media SET bytes = ?, sha256 = ? WHERE team_id = :team AND id = ?", written.bytes, written.sha256, media.id)
        val view = mediaView(media.id)!!
        return Stored(view, media.id, media.kind, media.mime, written.bytes, written.sha256, view["status"]!!.let { (it as JsonPrimitive).content }, deduped)
    }

    private class Written(val temp: Path, val bytes: Long, val sha256: String, val mime: String)

    private suspend fun streamToTemp(body: Chunks, maxBytes: Long, allowed: List<String>, declared: String?): Written {
        val hash = MessageDigest.getInstance("SHA-256")
        val head = java.io.ByteArrayOutputStream(Mime.SNIFF_BYTES)
        var total = 0L
        var mime = declared
        var temp: Path? = null
        var out: java.io.OutputStream? = null
        fun write(bytes: ByteArray, from: Int = 0, to: Int = bytes.size) {
            if (to <= from) return
            if (out == null) {
                Files.createDirectories(paths.tempRoot)
                temp = paths.tempRoot.resolve("${UUID.randomUUID()}.part")
                out = Files.newOutputStream(temp!!, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            }
            out!!.write(bytes, from, to - from)
        }
        fun verify() {
            val sniffed = Mime.sniff(head.toByteArray())
            if (sniffed == null || !Mime.isAllowed(sniffed) || sniffed !in allowed) {
                val what = if (sniffed != null) Wire.encode(JsonPrimitive(sniffed)) else "of no recognised type"
                throw MediaError(
                    "unsupported_type",
                    "these bytes are $what, and this upload was reserved for ${allowed.joinToString(", ")}. The declared type must " +
                        "match the actual bytes; SVG and anything off the allowlist is never accepted.",
                )
            }
            mime = sniffed
            write(head.toByteArray())
        }
        try {
            while (true) {
                val chunk = body.next() ?: break
                if (chunk.isEmpty()) continue
                total += chunk.size
                if (total > maxBytes) throw overCap(maxBytes)
                hash.update(chunk)
                if (mime != null) {
                    write(chunk)
                    continue
                }
                val take = minOf(Mime.SNIFF_BYTES - head.size(), chunk.size)
                head.write(chunk, 0, take)
                if (head.size() == Mime.SNIFF_BYTES) {
                    verify()
                    write(chunk, take)
                }
            }
            if (total == 0L) throw MediaError("invalid_argument", "the request body was empty")
            if (mime == null) verify()
            out?.close()
            out = null
            return Written(temp!!, total, Tokens.hex(hash.digest()), mime!!)
        } catch (e: Throwable) {
            runCatching { out?.close() }
            temp?.let { runCatching { Files.deleteIfExists(it) } }
            throw e
        }
    }

    /** Move the temp file into the store, or link a twin with the same bytes and type (a hard link, or a copy in the bucket). */
    private fun place(id: String, temp: Path, mime: String, sha256: String): Boolean {
        val key = paths.originalKey(id, mime)
        val twin = db.one("SELECT id, mime FROM media WHERE team_id = :team AND sha256 = ? AND removed_at IS NULL ORDER BY seq DESC LIMIT 1", sha256) { it.getString(1) to it.getString(2) }
        if (twin != null && twin.first != id && twin.second == mime && paths.store.link(paths.originalKey(twin.first, twin.second), key)) {
            Files.deleteIfExists(temp)
            return true
        }
        paths.store.put(key, temp, mime)
        return false
    }

    /**
     * The media sweeper (spec/07 §10): up to 200 unattached media over an hour old, that no project
     * logo and no embed names, lose their directory and row; then unused expired tokens go.
     */
    fun sweep() {
        val now = clock()
        val ids = db.query(
            """SELECT id FROM media
                WHERE team_id = :team AND update_id IS NULL AND message_id IS NULL AND request_id IS NULL AND task_id IS NULL
                  AND status IN ('ready', 'pending', 'failed')
                  AND created_at < ?
                  AND id NOT IN (${logoIds()})
                  AND id NOT IN (${embedStrings()})
                ORDER BY seq LIMIT 200""",
            now - 3_600_000,
        ) { it.getString(1) }
        for (id in ids) {
            runCatching { paths.store.deletePrefix(paths.folder(id)) }
            db.update("DELETE FROM media WHERE team_id = :team AND id = ?", id)
        }
        db.update("DELETE FROM upload_tokens WHERE team_id = :team AND used_at IS NULL AND expires_at <= ?", now)
    }

    /** Every project's logo media id (TS: `json_extract(theme, '$.logoMediaId')`). */
    private fun logoIds() = if (db.dialect == dev.wildware.feed.server.db.Dialect.SQLITE)
        "SELECT json_extract(theme, '$.logoMediaId') FROM projects WHERE team_id = :team AND theme IS NOT NULL AND json_extract(theme, '$.logoMediaId') IS NOT NULL"
    else
        "SELECT theme::jsonb ->> 'logoMediaId' FROM projects WHERE team_id = :team AND theme IS NOT NULL AND theme::jsonb ->> 'logoMediaId' IS NOT NULL"

    /** Every string anywhere in any embed's data (TS: `json_tree` values of type text). */
    private fun embedStrings() = if (db.dialect == dev.wildware.feed.server.db.Dialect.SQLITE)
        "SELECT value FROM embeds, json_tree(embeds.data) WHERE embeds.team_id = :team AND json_tree.type = 'text'"
    else
        "SELECT v #>> '{}' FROM embeds, jsonb_path_query(embeds.data::jsonb, 'strict $.**') AS v WHERE embeds.team_id = :team AND jsonb_typeof(v) = 'string'"

    /** `Media` as the owner API answers it (`src/db/media.ts`), or null. */
    fun mediaView(id: String): JsonObject? = db.one(
        "SELECT seq, id, agent_id, author, update_id, message_id, request_id, task_id, kind, mime, bytes, sha256, width, height, duration_ms, filename, status, created_at FROM media WHERE team_id = :team AND id = ?",
        id,
    ) { rs ->
        buildJsonObject {
            put("seq", rs.getLong("seq"))
            put("id", rs.getString("id"))
            put("agentId", rs.getString("agent_id"))
            put("author", rs.getString("author"))
            put("updateId", rs.getString("update_id"))
            put("messageId", rs.getString("message_id"))
            put("requestId", rs.getString("request_id"))
            put("taskId", rs.getString("task_id"))
            put("kind", rs.getString("kind"))
            put("mime", rs.getString("mime"))
            put("bytes", rs.getLong("bytes"))
            put("sha256", rs.getString("sha256"))
            put("width", rs.longOrNull("width"))
            put("height", rs.longOrNull("height"))
            put("durationMs", rs.longOrNull("duration_ms"))
            put("filename", rs.getString("filename"))
            put("status", rs.getString("status"))
            put("createdAt", rs.getLong("created_at"))
        }
    }

    private fun rejected() = MediaError(
        "token_rejected",
        "this upload token is not valid: it may be expired, already used, or not one of ours. Call create_upload again for a fresh one.",
    )

    private fun overCap(maxBytes: Long) = MediaError(
        "too_large",
        "this upload may be at most $maxBytes bytes. Send the file you declared to create_upload, or call it again with the real size.",
    )

    companion object {
        const val FILENAME_MAX = 255
        const val TOKEN_TTL_MS = 15 * 60 * 1000L
        private const val MIME_HELP =
            "must look like \"type/subtype\", e.g. \"image/png\", \"application/pdf\" or \"text/plain\". Images and video are " +
                "rendered on the card and are checked against their magic bytes; anything else is stored as a file the owner downloads."
        private const val NO_BODY =
            "the request had no body. Send `Content-Type` set to the mime you declared to create_upload — a PUT without a " +
                "Content-Type header has its body discarded before it reaches this route. This token has NOT been used; retry with " +
                "the same upload_url."
    }
}
