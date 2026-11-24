package dev.wildware.agents

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/*
 * A channel's docs (server migration 076, spec/17): markdown pages people and agents keep together,
 * linked by title with [[Doc name]]. Read on the channel reads them; Post edits them. Every save
 * names the version it was based on, and one based on an old version comes back as [DocSave.Conflict]
 * with the text as it is now, never saved over it.
 */

/** Who saved a doc: [kind] is "person" or "agent". */
@Serializable
data class DocAuthor(val principal: String = "", val kind: String = "person", val name: String = "")

/** One doc. [body] is empty in a list. Times are ISO-8601, as the dashboard sends them. */
@Serializable
data class DocView(
    val id: String,
    val projectId: String = "",
    val title: String,
    val body: String = "",
    val version: Long = 1,
    val size: Int = 0,
    val createdAt: String = "",
    val createdBy: DocAuthor? = null,
    val updatedAt: String = "",
    val updatedBy: DocAuthor? = null,
) {
    val updatedAtMillis: Long get() = isoMillis(updatedAt)
}

/** A doc that links to another: its "Linked from" row. */
@Serializable
data class DocRef(val id: String, val title: String)

/** One doc as its page shows it: the doc, the docs linking to it, and whether you may edit it. */
@Serializable
data class DocPage(val doc: DocView, val backlinks: List<DocRef> = emptyList(), val canEdit: Boolean = false)

/** A channel's docs, newest change first, without their text. */
@Serializable
data class DocList(val projectId: String = "", val docs: List<DocView> = emptyList(), val canEdit: Boolean = false)

/** One kept version. [body] only when read on its own. */
@Serializable
data class DocVersion(
    val version: Long,
    val title: String = "",
    val body: String? = null,
    val size: Int = 0,
    val savedAt: String = "",
    val savedBy: DocAuthor? = null,
    val restoredFrom: Long? = null,
) {
    val savedAtMillis: Long get() = isoMillis(savedAt)
}

@Serializable
data class DocVersions(val versions: List<DocVersion> = emptyList(), val kept: Int = 100)

@Serializable
internal data class DocVersionBody(val version: DocVersion)

@Serializable
internal data class DocConflictBody(val error: String? = null, val message: String? = null, val baseVersion: Long = 0, val doc: DocView)

/** What a save came to: saved, or refused because someone else saved first ([current] is their text). */
sealed interface DocSave {
    data class Saved(val page: DocPage) : DocSave
    data class Conflict(val current: DocView, val message: String) : DocSave
}

/** A doc changed, by the live stream: [change] is created, saved, restored or deleted. */
data class DocChange(val projectId: String, val docId: String, val version: Long, val change: String, val by: String)

/** The channel's docs; with [query], those whose title or text holds it, title matches first. */
@Throws(Exception::class)
suspend fun DashboardClient.docs(channel: String, query: String? = null): DocList =
    http.get("api/projects/${channel.encodeURLPathPart()}/docs") {
        authorise()
        if (!query.isNullOrBlank()) parameter("q", query)
    }.decoded()

@Throws(Exception::class)
suspend fun DashboardClient.doc(id: String): DocPage =
    http.get("api/docs/${id.encodeURLPathPart()}") { authorise() }.decoded()

/** The doc a [[link]] names in [channel], in any case, or null when nobody has written it yet. */
@Throws(Exception::class)
suspend fun DashboardClient.docByTitle(channel: String, title: String): DocPage? {
    val answer = http.get("api/projects/${channel.encodeURLPathPart()}/docs/by-title?title=${title.encodeURLParameter()}") { authorise() }
    if (answer.status.value == 404) return null
    return answer.decoded()
}

@Throws(Exception::class)
suspend fun DashboardClient.createDoc(channel: String, title: String, body: String = ""): DocPage =
    http.post("api/projects/${channel.encodeURLPathPart()}/docs") {
        authorise()
        jsonBody(buildJsonObject { put("title", title); put("body", body) })
    }.decoded()

/** Saves [body] (and [title], when renaming), based on [baseVersion]. A newer save in between is a [DocSave.Conflict]. */
@Throws(Exception::class)
suspend fun DashboardClient.saveDoc(id: String, baseVersion: Long, body: String?, title: String? = null): DocSave {
    val answer = http.put("api/docs/${id.encodeURLPathPart()}") {
        authorise()
        jsonBody(buildJsonObject {
            put("baseVersion", baseVersion)
            if (body != null) put("body", body)
            if (title != null) put("title", title)
        })
    }
    return conflictOr(answer) { DocSave.Saved(answer.decoded()) }
}

@Throws(Exception::class)
suspend fun DashboardClient.deleteDoc(id: String) {
    http.delete("api/docs/${id.encodeURLPathPart()}") { authorise() }.requireSuccess()
}

@Throws(Exception::class)
suspend fun DashboardClient.docVersions(id: String): DocVersions =
    http.get("api/docs/${id.encodeURLPathPart()}/versions") { authorise() }.decoded()

@Throws(Exception::class)
suspend fun DashboardClient.docVersion(id: String, version: Long): DocVersion =
    http.get("api/docs/${id.encodeURLPathPart()}/versions/$version") { authorise() }.decoded<DocVersionBody>().version

/** Makes kept [version] the doc's text again: a new save, based on [baseVersion]. */
@Throws(Exception::class)
suspend fun DashboardClient.restoreDoc(id: String, version: Long, baseVersion: Long): DocSave {
    val answer = http.post("api/docs/${id.encodeURLPathPart()}/restore") {
        authorise()
        jsonBody(buildJsonObject { put("version", version); put("baseVersion", baseVersion) })
    }
    return conflictOr(answer) { DocSave.Saved(answer.decoded()) }
}

private suspend fun DashboardClient.conflictOr(answer: io.ktor.client.statement.HttpResponse, saved: suspend () -> DocSave): DocSave {
    if (answer.status.value == 409 && !answer.status.isSuccess()) {
        val text = answer.bodyAsText()
        val conflict = runCatching { json.decodeFromString<DocConflictBody>(text) }.getOrNull()
        if (conflict != null) return DocSave.Conflict(conflict.doc, conflict.message ?: "Someone else saved this doc first.")
        // A 409 that is not a version conflict (a title another doc has): refused as usual.
        throw DashboardException(409, "conflict", runCatching { json.decodeFromString<DashboardClient.ErrorBody>(text).message }.getOrNull() ?: "The dashboard refused it.")
    }
    return saved()
}

private fun HttpRequestBuilder.jsonBody(body: JsonObject) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

private fun isoMillis(text: String): Long = runCatching { Instant.parse(text).toEpochMilliseconds() }.getOrDefault(0L)

/**
 * `[[Doc name]]` and `[[Doc name|shown text]]` links in markdown, outside code: the same rule the
 * dashboard uses for "Linked from".
 */
object DocLinks {
    /** One link: the doc's [title], and the words shown for it. */
    data class Link(val title: String, val shown: String?) {
        val label: String get() = shown ?: title
    }

    /**
     * Where a link points, as a markdown link: `wildware-doc:<title>`, or
     * `wildware-doc:<channel>/<title>` for a link in a message (each part percent-encoded, so a `/`
     * in a title never reads as the channel's end). The app opens it itself.
     */
    const val Scheme = "wildware-doc:"

    /** A link's address read back: the doc's [title], in [channel] (a slug; null: the channel open). */
    data class Target(val channel: String?, val title: String)

    private val link = Regex("""\[\[([^\[\]|\r\n]+)(?:\|([^\[\]\r\n]*))?]]""")
    private val fence = Regex("(?m)^ {0,3}(```|~~~)[\\s\\S]*?(^ {0,3}\\1[^\\n]*$|\\z)")
    private val inlineCode = Regex("`[^`\\n]*`")

    /** The links [markdown] makes, in order, outside code. */
    fun parse(markdown: String): List<Link> = pieces(markdown).filter { !it.second }.flatMap { found(it.first) }

    /** The doc a link address names, or null when it isn't a doc link. */
    fun targetOf(url: String): Target? {
        if (!url.startsWith(Scheme)) return null
        val rest = url.removePrefix(Scheme)
        val slash = rest.indexOf('/')
        val channel = if (slash >= 0) decode(rest.substring(0, slash)).trim().ifEmpty { null } else null
        val title = decode(if (slash >= 0) rest.substring(slash + 1) else rest).trim()
        return if (title.isEmpty()) null else Target(channel, title)
    }

    /** The doc title a link address names, or null when it isn't a doc link. */
    fun titleOf(url: String): String? = targetOf(url)?.title

    /** The address of a link to the doc titled [title], in [channel] when given. */
    fun urlOf(title: String, channel: String?): String =
        Scheme + (channel?.let { encode(it) + "/" } ?: "") + encode(title.trim())

    /**
     * [markdown] with each `[[link]]` outside code turned into an ordinary markdown link to
     * `wildware-doc:<title>` (in [channel], when given), so any markdown view draws it as a link the
     * app can open.
     */
    fun toMarkdownLinks(markdown: String, channel: String?): String {
        if (!markdown.contains("[[")) return markdown
        return pieces(markdown).joinToString("") { (part, code) ->
            if (code) part else link.replace(part) { m ->
                val title = m.groupValues[1].trim()
                if (title.isEmpty()) m.value
                else "[${escapeLabel(m.groups[2]?.value?.trim()?.takeIf { it.isNotEmpty() } ?: title)}](${urlOf(title, channel)})"
            }
        }
    }

    /** [text] cut into pieces, each with whether it is code (a fenced block or an inline span). */
    private fun pieces(text: String): List<Pair<String, Boolean>> {
        val fences = fence.findAll(text).map { it.range }.toList()
        val spans = inlineCode.findAll(text).map { it.range }.filter { r -> fences.none { r.first in it } }
        val out = ArrayList<Pair<String, Boolean>>()
        var at = 0
        for (range in (fences + spans).sortedBy { it.first }) {
            if (range.first < at) continue
            if (range.first > at) out += text.substring(at, range.first) to false
            out += text.substring(range.first, range.last + 1) to true
            at = range.last + 1
        }
        if (at < text.length) out += text.substring(at) to false
        return out
    }

    private fun found(part: String): List<Link> = link.findAll(part).mapNotNull { m ->
        val title = m.groupValues[1].trim()
        if (title.isEmpty()) null else Link(title, m.groups[2]?.value?.trim()?.takeIf { it.isNotEmpty() })
    }.toList()

    private fun escapeLabel(text: String) = text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")

    /** Percent-encodes what a markdown link address can't hold. */
    private fun encode(title: String): String = buildString {
        for (b in title.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code) append(c.toChar())
            else append('%').append(HEX[c shr 4]).append(HEX[c and 15])
        }
    }

    private fun decode(text: String): String {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val hex = if (c == '%' && i + 2 < text.length) text.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                bytes += hex.toByte()
                i += 3
            } else {
                c.toString().encodeToByteArray().forEach { bytes += it }
                i++
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private const val HEX = "0123456789ABCDEF"
}
