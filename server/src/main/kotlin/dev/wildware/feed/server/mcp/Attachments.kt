package dev.wildware.feed.server.mcp

import dev.wildware.feed.server.App
import dev.wildware.feed.server.http.Wire
import dev.wildware.feed.server.media.Serve
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.util.Base64

/**
 * Message attachments in `get_messages` (spec/05-mcp.md §6, TS `src/mcp/attachments.ts`): images as
 * image blocks, files as text or resource blocks, inside one shared budget; everything else listed
 * with a `download_url` and the reason it was left out.
 */
object Attachments {
    const val MAX_INLINE_IMAGES = 4
    const val MAX_INLINE_BYTES = 3_000_000L
    const val MAX_INLINE_FILE_BYTES = 400_000L

    private val TEXT_MIMES = setOf(
        "application/json", "application/xml", "application/yaml", "application/x-yaml", "application/toml",
        "application/x-ndjson", "application/javascript", "application/typescript", "application/sql", "application/x-sh",
        "image/svg+xml",
    )

    fun isTextMime(mime: String) = mime.startsWith("text/") || mime in TEXT_MIMES || Regex("\\+(json|xml|yaml)$").containsMatchIn(mime)

    class Result(val blocks: List<JsonElement>, val notes: List<String>, val views: Map<String, List<JsonObject>>)

    private class Item(
        val id: String, val kind: String, val mime: String, val bytes: Long, val filename: String?, val status: String, val variants: List<String>,
        /** Removed for its age (media retention), after this many days; null while it is kept. */
        val removedAfterDays: Long? = null,
    )

    fun downloadUrl(app: App, mediaId: String): String? {
        val base = app.teams.address().trimEnd('/')
        return if (base.isEmpty()) null else "$base/api/agent/media/$mediaId"
    }

    fun forMessages(app: App, messageIds: List<String>): Result {
        val blocks = mutableListOf<JsonElement>()
        val notes = mutableListOf<String>()
        val views = LinkedHashMap<String, List<JsonObject>>()
        var images = 0
        var budget = MAX_INLINE_BYTES
        for (messageId in messageIds) {
            val attached = app.reads.mediaFor("message_id", messageId).map { m ->
                fun s(k: String) = (m[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                Item(s("id")!!, s("kind")!!, s("mime")!!, (m["bytes"] as? JsonPrimitive)?.longOrNull ?: 0, s("filename"), s("status")!!,
                    m["variants"]!!.jsonArray.map { it.jsonPrimitive.content },
                    (m["removed"] as? JsonObject)?.let { (it["afterDays"] as? JsonPrimitive)?.longOrNull ?: 0L })
            }
            if (attached.isEmpty()) continue
            val sent = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            val seen = mutableListOf<JsonObject>()
            views[messageId] = seen
            for (item in attached) {
                item.removedAfterDays?.let { days ->
                    skipped += "${item.id} (${item.kind}, removed after $days days)"
                    seen += view(app, item, false, "removed after $days days: the owner keeps media for $days days")
                    continue
                }
                val hint = hint(app, item.id)
                if (item.kind == "video") {
                    skipped += "${item.id} (video$hint)"
                    seen += view(app, item, false, "no protocol block carries a video")
                    continue
                }
                if (item.kind == "file") {
                    val name = item.filename ?: item.id
                    val read = readBytes(app, item, minOf(budget, MAX_INLINE_FILE_BYTES))
                    if (read == null) {
                        skipped += "$name (${item.mime}, ${item.bytes} bytes: too large to inline$hint)"
                        seen += view(app, item, false, "too large to inline (over $MAX_INLINE_FILE_BYTES bytes, or no room left in this read)")
                        continue
                    }
                    budget -= read.second.size
                    blocks += if (isTextMime(item.mime)) {
                        val text = decodeUtf8(read.second)
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive("Attached file ${Wire.encode(JsonPrimitive(name))} (${item.mime}, ${read.second.size} bytes):\n<<<FILE\n$text\nFILE>>>"))
                        }
                    } else {
                        buildJsonObject {
                            put("type", JsonPrimitive("resource"))
                            put("resource", buildJsonObject {
                                put("uri", JsonPrimitive("attachment:${item.id}/${Serve.encodeURIComponent(name)}"))
                                put("mimeType", JsonPrimitive(item.mime))
                                put("blob", JsonPrimitive(Base64.getEncoder().encodeToString(read.second)))
                            })
                        }
                    }
                    sent += "$name (${item.mime})"
                    seen += view(app, item, true)
                    continue
                }
                if (images >= MAX_INLINE_IMAGES) {
                    skipped += "${item.id} (over the $MAX_INLINE_IMAGES-image limit for one read$hint)"
                    seen += view(app, item, false, "over the $MAX_INLINE_IMAGES-image limit for one read")
                    continue
                }
                val read = readBytes(app, item, budget)
                if (read == null) {
                    skipped += "${item.id} (too large to inline, or still being processed$hint)"
                    seen += view(app, item, false, "too large to inline, or still being processed")
                    continue
                }
                budget -= read.second.size
                images += 1
                blocks += buildJsonObject {
                    put("type", JsonPrimitive("image"))
                    put("data", JsonPrimitive(Base64.getEncoder().encodeToString(read.second)))
                    put("mimeType", JsonPrimitive(read.first))
                }
                sent += item.id
                seen += view(app, item, true)
            }
            val parts = mutableListOf<String>()
            if (sent.isNotEmpty()) {
                parts += if (sent.size == 1) "1 attachment is included below (${sent[0]})"
                else "${sent.size} attachments are included below (${sent.joinToString(", ")})"
            }
            if (skipped.isNotEmpty()) parts += "not shown: ${skipped.joinToString("; ")}"
            notes += "message $messageId: ${parts.joinToString("; ")}."
        }
        return Result(blocks, notes, views)
    }

    private fun hint(app: App, id: String) = downloadUrl(app, id)?.let { " — fetch it with your bearer token at $it" } ?: ""

    private fun view(app: App, item: Item, included: Boolean, reason: String? = null) = buildJsonObject {
        put("media_id", JsonPrimitive(item.id))
        put("kind", JsonPrimitive(item.kind))
        put("mime", JsonPrimitive(item.mime))
        put("bytes", JsonPrimitive(item.bytes))
        put("filename", item.filename?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("status", JsonPrimitive(item.status))
        put("included", JsonPrimitive(included))
        if (item.removedAfterDays == null) downloadUrl(app, item.id)?.let { put("download_url", JsonPrimitive(it)) }
        reason?.let { put("reason", JsonPrimitive(it)) }
    }

    /** The first of thumb-1600, thumb-640, original that is listed, opens, and fits in [maxBytes]. */
    private fun readBytes(app: App, item: Item, maxBytes: Long): Pair<String, ByteArray>? {
        if (maxBytes <= 0) return null
        for (variant in listOf("thumb-1600", "thumb-640", "original")) {
            if (variant !in item.variants) continue
            val file = runCatching { app.serve.open(item.id, variant) }.getOrNull() ?: continue
            if (file.size > maxBytes) continue
            val bytes = runCatching { app.mediaPaths.store.read(file.key, 0, file.size)?.use { it.readAllBytes() } }.getOrNull() ?: continue
            return file.mime to bytes
        }
        return null
    }

    /** `new TextDecoder().decode(bytes)`: UTF-8, a leading BOM dropped, bad bytes replaced. */
    private fun decodeUtf8(bytes: ByteArray): String {
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }
}
