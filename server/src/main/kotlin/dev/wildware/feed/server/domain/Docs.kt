package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Dialect
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.db.longOrNull
import dev.wildware.feed.server.db.Viewer
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.SQLException

/**
 * Linked markdown docs in each channel (migration 076, spec/17).
 *
 * A doc belongs to one channel. Whoever can read the channel can read its docs; whoever can post in
 * it can create, edit, restore and delete them ([ChannelAccess]). A channel the viewer can't read
 * hides its docs: they answer as missing.
 *
 * `[[Doc name]]` in a doc's text links to the doc with that title in the same channel, whatever the
 * case; `[[Doc name|shown text]]` shows other words. Each save records the links it makes, so a doc
 * can list the docs that link to it ("Linked from").
 *
 * Every save carries the version it was based on. A save based on an older version than the doc's
 * own is refused with [Conflict], which carries the doc as it is now, so the saver can merge: nothing
 * is ever overwritten silently. Each saved state is kept as a version (the newest [KEEP_VERSIONS]),
 * with who saved it, and an old one can be restored, which is a new save.
 *
 * Docs are reference material: the free plan's 30-day history never hides them.
 *
 * The doc titled "Index" (any case) is the channel's index: what the channel is for and links to
 * its docs. Agents read it first. It is listed first, and can be edited but not deleted or
 * renamed. The server never writes one: a channel without one offers [indexTemplate], a first
 * draft from its name and description for someone to save.
 */
class Docs(private val feed: Feed) {
    private val db get() = feed.db

    /** Who saved something, by principal, with the name to show. [kind] is "agent" or "person". */
    data class Who(val principal: String, val kind: String, val name: String) {
        val json: JsonObject get() = buildJsonObject {
            put("principal", principal)
            put("kind", kind)
            put("name", name)
        }
    }

    data class DocRow(
        val id: String, val projectId: String, val title: String, val body: String, val version: Long,
        val createdBy: String, val createdAt: Long, val updatedBy: String, val updatedAt: Long,
    )

    data class VersionRow(val version: Long, val title: String, val body: String?, val savedBy: String, val savedAt: Long, val restoredFrom: Long?, val size: Int)

    /** A save based on [baseVersion] when the doc is already at [current]'s version: someone else saved in between. */
    class Conflict(val current: DocRow, val baseVersion: Long) : DomainError(
        "conflict",
        "this doc changed since version $baseVersion: it is at version ${current.version} now. " +
            "Read it again, merge your change into the new text, and save with base_version ${current.version}",
        409,
    )

    // ---- reading -------------------------------------------------------------------------------

    /** The channel's docs, its Index first, then newest change first, without their text. */
    fun list(projectRef: String): List<DocRow> {
        val project = feed.projects.resolve(projectRef)
        return db.query(
            "SELECT $COLUMNS FROM docs WHERE team_id = :team AND project_id = ? AND deleted_at IS NULL " +
                "ORDER BY CASE WHEN title_key = ? THEN 0 ELSE 1 END, updated_at DESC, seq DESC LIMIT ?",
            project.id, INDEX_KEY, MAX_DOCS,
        ) { row(it) }
    }

    /** The channel's Index, or null when nobody has written one yet. */
    fun index(projectRef: String): DocRow? = byKey(feed.projects.resolve(projectRef).id, INDEX_KEY)

    /**
     * A first Index for [projectRef], for someone to save as the doc titled [INDEX]: from the
     * channel's name and description, linking the docs already there. Nothing is saved.
     */
    fun indexDraft(projectRef: String): String {
        val project = feed.projects.resolve(projectRef)
        val titles = db.query(
            "SELECT title FROM docs WHERE team_id = :team AND project_id = ? AND deleted_at IS NULL ORDER BY lower(title), seq LIMIT ?",
            project.id, MAX_DOCS,
        ) { it.getString(1) }
        return indexTemplate(project.name, project.description, titles)
    }

    /** One doc. A doc in a channel this viewer can't read, or deleted, answers as missing. */
    fun get(id: String): DocRow {
        val raw = id.jsTrim()
        if (raw.isEmpty()) throw invalid("doc_id is required")
        val row = db.one("SELECT $COLUMNS FROM docs WHERE team_id = :team AND id = ? AND deleted_at IS NULL", raw) { row(it) }
        if (row == null || !feed.access.visible(row.projectId)) throw notFound("no such doc: $raw")
        return row
    }

    /** The doc titled [title] in the channel (any case), or null. */
    fun byTitle(projectRef: String, title: String): DocRow? {
        val project = feed.projects.resolve(projectRef)
        return byKey(project.id, key(title))
    }

    private fun byKey(projectId: String, key: String): DocRow? =
        db.one("SELECT $COLUMNS FROM docs WHERE team_id = :team AND project_id = ? AND title_key = ? AND deleted_at IS NULL", projectId, key) { row(it) }

    /** The docs in the same channel whose text links to [doc], by title. */
    fun backlinks(doc: DocRow): List<DocRow> = db.query(
        "SELECT ${COLUMNS.split(", ").joinToString(", ") { "d.$it" }} FROM doc_links AS l JOIN docs AS d ON d.team_id = l.team_id AND d.id = l.doc_id " +
            "WHERE l.team_id = :team AND l.project_id = ? AND l.target_key = ? AND d.deleted_at IS NULL AND d.id <> ? ORDER BY lower(d.title), d.seq",
        doc.projectId, key(doc.title), doc.id,
    ) { row(it) }

    /** Whether this work's viewer may change docs in [projectId]. */
    fun canEdit(projectId: String): Boolean = feed.access.level(projectId) >= ChannelAccess.Level.POST

    /**
     * Docs whose title or text contains [query] (any case), in [projectRef] or, with none, in every
     * channel this viewer can read; title matches first, then the newest.
     */
    fun search(projectRef: String?, query: String, limit: Int): List<DocRow> {
        val q = query.jsTrim()
        if (q.isEmpty()) throw invalid("query is required")
        if (q.length > 200) throw invalid("query must be at most 200 characters")
        val projectId = projectRef?.takeIf { it.isNotBlank() }?.let { feed.projects.resolve(it).id }
        val pattern = "%" + q.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return db.query(
            "SELECT $COLUMNS FROM docs WHERE team_id = :team AND deleted_at IS NULL AND (? IS NULL OR project_id = ?)" +
                "${feed.access.notHidden("project_id")} AND (title_key LIKE ? ESCAPE '\\' OR lower(body) LIKE ? ESCAPE '\\') " +
                "ORDER BY CASE WHEN title_key LIKE ? ESCAPE '\\' THEN 0 ELSE 1 END, updated_at DESC, seq DESC LIMIT ?",
            projectId, projectId, pattern, pattern, pattern, limit.coerceIn(1, 100),
        ) { row(it) }
    }

    /** The doc's kept versions, newest first, without their text. */
    fun versions(id: String): List<VersionRow> {
        val doc = get(id)
        val bytes = if (db.dialect == Dialect.POSTGRES) "octet_length(body)" else "length(CAST(body AS BLOB))"
        return db.query(
            "SELECT version, title, saved_by, saved_at, restored_from, $bytes AS size FROM doc_versions WHERE team_id = :team AND doc_id = ? ORDER BY version DESC",
            doc.id,
        ) { VersionRow(it.getLong("version"), it.getString("title"), null, it.getString("saved_by"), it.getLong("saved_at"), it.longOrNull("restored_from"), it.getInt("size")) }
    }

    /** One kept version, with its text. */
    fun version(id: String, version: Long): VersionRow {
        val doc = get(id)
        return db.one(
            "SELECT version, title, body, saved_by, saved_at, restored_from FROM doc_versions WHERE team_id = :team AND doc_id = ? AND version = ?",
            doc.id, version,
        ) { VersionRow(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getLong(5), it.longOrNull("restored_from"), utf8Size(it.getString(3))) }
            ?: throw notFound("doc ${doc.id} has no version $version kept (the newest $KEEP_VERSIONS are)")
    }

    // ---- writing -------------------------------------------------------------------------------

    /** A new doc in the channel, at version 1. A title another doc in the channel has is a conflict. */
    fun create(projectRef: String, title: String, body: String, actor: String): DocRow = feed.op {
        val project = feed.projects.resolve(projectRef)
        feed.access.requirePost(project.id) { notFound("no such project: ${projectRef.jsTrim()}") }
        val name = checkTitle(title)
        checkBody(body)
        byKey(project.id, key(name))?.let { throw conflict("a doc called ${JsText.quote(it.title)} is already in this channel (id ${it.id}): edit that one, or pick another title") }
        val count = db.one("SELECT count(*) FROM docs WHERE team_id = :team AND project_id = ? AND deleted_at IS NULL", project.id) { it.getLong(1) } ?: 0
        if (count >= MAX_DOCS) throw invalid("a channel holds at most $MAX_DOCS docs: delete one you no longer need first")
        val now = feed.now()
        val id = Ulid.next(now)
        try {
            db.update(
                "INSERT INTO docs (team_id, id, project_id, title, title_key, body, version, created_by, created_at, updated_by, updated_at) " +
                    "VALUES (:team, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)",
                id, project.id, name, key(name), body, actor, now, actor, now,
            )
        } catch (e: SQLException) {
            // Someone made it a moment ago.
            if (byKey(project.id, key(name)) != null) throw conflict("a doc called ${JsText.quote(name)} is already in this channel")
            throw e
        }
        val doc = DocRow(id, project.id, name, body, 1, actor, now, actor, now)
        keepVersion(doc, null)
        relink(doc)
        announce(doc, "created")
        doc
    }

    /**
     * Save [doc_id]'s new [title] and/or [body] (null keeps it), based on [baseVersion]. Refused with
     * [Conflict] when the doc has moved past it.
     */
    fun save(id: String, baseVersion: Long, title: String?, body: String?, actor: String, restoredFrom: Long? = null): DocRow = feed.op {
        val doc = get(id)
        feed.access.requirePost(doc.projectId) { notFound("no such doc: ${doc.id}") }
        if (baseVersion != doc.version) throw Conflict(doc, baseVersion)
        val name = title?.let(::checkTitle) ?: doc.title
        val text = body ?: doc.body
        checkBody(text)
        if (isIndex(doc) && key(name) != INDEX_KEY) throw invalid(INDEX_STAYS)
        if (key(name) != key(doc.title)) {
            byKey(doc.projectId, key(name))?.let { throw conflict("a doc called ${JsText.quote(it.title)} is already in this channel (id ${it.id})") }
        }
        if (name == doc.title && text == doc.body && restoredFrom == null) return@op doc
        val now = feed.now()
        val next = doc.version + 1
        val changed = try {
            db.update(
                "UPDATE docs SET title = ?, title_key = ?, body = ?, version = ?, updated_by = ?, updated_at = ? WHERE team_id = :team AND id = ? AND version = ?",
                name, key(name), text, next, actor, now, doc.id, doc.version,
            )
        } catch (e: SQLException) {
            if (byKey(doc.projectId, key(name)) != null) throw conflict("a doc called ${JsText.quote(name)} is already in this channel")
            throw e
        }
        // Another save got in between this read and write.
        if (changed == 0) throw Conflict(get(doc.id), baseVersion)
        val saved = doc.copy(title = name, body = text, version = next, updatedBy = actor, updatedAt = now)
        keepVersion(saved, restoredFrom)
        relink(saved)
        announce(saved, if (restoredFrom != null) "restored" else "saved")
        saved
    }

    /** One find-and-replace: [old] must be found exactly once in the text as the edits before it left it. */
    data class Edit(val old: String, val new: String)

    /**
     * Apply [edits] in order to the doc's text, all or none, and save it (with a new [title], if
     * given), based on [baseVersion]. Each old text must be non-empty and found exactly once in the
     * text as the edits before it left it.
     */
    fun edit(id: String, baseVersion: Long, edits: List<Edit>, actor: String, title: String? = null): DocRow = feed.op {
        if (edits.isEmpty()) throw invalid("edits is required: at least one { old_str, new_str }")
        if (edits.size > MAX_EDITS) throw invalid("at most $MAX_EDITS edits in one call")
        val doc = get(id)
        feed.access.requirePost(doc.projectId) { notFound("no such doc: ${doc.id}") }
        if (baseVersion != doc.version) throw Conflict(doc, baseVersion)
        var text = doc.body
        edits.forEachIndexed { i, e ->
            val n = i + 1
            if (e.old.isEmpty()) throw invalid("edit $n: old_str is empty; to add text, include some text around where it goes")
            val first = text.indexOf(e.old)
            if (first < 0) throw invalid("edit $n: old_str was not found in the doc (after the edits before it); nothing was changed")
            if (text.indexOf(e.old, first + 1) >= 0) throw invalid("edit $n: old_str is found more than once; include more of the text around it so it is found once; nothing was changed")
            text = text.substring(0, first) + e.new + text.substring(first + e.old.length)
        }
        save(doc.id, baseVersion, title, text, actor)
    }

    /** Make kept [version] the doc's text and title again: a new save, based on [baseVersion]. */
    fun restore(id: String, version: Long, baseVersion: Long, actor: String): DocRow = feed.op {
        val old = version(id, version)
        save(id, baseVersion, old.title, old.body!!, actor, restoredFrom = old.version)
    }

    /** Delete the doc: gone from the channel, its title free again. */
    fun delete(id: String, actor: String): DocRow = feed.op {
        val doc = get(id)
        feed.access.requirePost(doc.projectId) { notFound("no such doc: ${doc.id}") }
        if (isIndex(doc)) throw invalid(INDEX_STAYS)
        val now = feed.now()
        db.update(
            "UPDATE docs SET deleted_at = ?, title_key = ?, updated_by = ?, updated_at = ? WHERE team_id = :team AND id = ?",
            now, "deleted:" + doc.id, actor, now, doc.id,
        )
        db.update("DELETE FROM doc_links WHERE team_id = :team AND doc_id = ?", doc.id)
        announce(doc.copy(updatedBy = actor, updatedAt = now), "deleted")
        doc
    }

    private fun keepVersion(doc: DocRow, restoredFrom: Long?) {
        db.update(
            "INSERT INTO doc_versions (team_id, id, doc_id, version, title, body, saved_by, saved_at, restored_from) VALUES (:team, ?, ?, ?, ?, ?, ?, ?, ?)",
            Ulid.next(doc.updatedAt), doc.id, doc.version, doc.title, doc.body, doc.updatedBy, doc.updatedAt, restoredFrom,
        )
        db.update("DELETE FROM doc_versions WHERE team_id = :team AND doc_id = ? AND version <= ?", doc.id, doc.version - KEEP_VERSIONS)
    }

    /** Record the links [doc]'s text makes now, for the docs they point at to list it. */
    private fun relink(doc: DocRow) {
        db.update("DELETE FROM doc_links WHERE team_id = :team AND doc_id = ?", doc.id)
        for (target in DocLinks.parse(doc.body).map { key(it.target) }.distinct().take(MAX_LINKS)) {
            db.update("INSERT INTO doc_links (team_id, doc_id, project_id, target_key) VALUES (:team, ?, ?, ?)", doc.id, doc.projectId, target)
        }
    }

    private fun announce(doc: DocRow, change: String) {
        feed.publish("doc.updated", buildJsonObject {
            put("projectId", doc.projectId)
            put("docId", doc.id)
            put("title", doc.title)
            put("version", doc.version)
            put("by", doc.updatedBy)
            put("change", change)
        })
    }

    // ---- shapes --------------------------------------------------------------------------------

    /** Names for these principals: people (even ones who left) and agents (even revoked ones). */
    fun names(principals: Collection<String>): Map<String, Who> {
        val out = HashMap<String, Who>()
        val members = principals.filter { it.startsWith(Viewer.MEMBER) }.map { it.removePrefix(Viewer.MEMBER) }.distinct()
        val agents = principals.filter { it.startsWith(Viewer.AGENT) }.map { it.removePrefix(Viewer.AGENT) }.distinct()
        for (chunk in members.chunked(CHUNK)) {
            db.query(
                "SELECT m.id, u.name FROM memberships AS m JOIN users AS u ON u.id = m.user_id WHERE m.team_id = :team AND m.id IN (${chunk.joinToString(", ") { "?" }})",
                *chunk.toTypedArray(),
            ) { Viewer.MEMBER + it.getString(1) to it.getString(2) }.forEach { (p, n) -> out[p] = Who(p, Assignees.PERSON, n) }
        }
        for (chunk in agents.chunked(CHUNK)) {
            db.query(
                "SELECT id, name FROM agents WHERE team_id = :team AND id IN (${chunk.joinToString(", ") { "?" }})",
                *chunk.toTypedArray(),
            ) { Viewer.AGENT + it.getString(1) to it.getString(2) }.forEach { (p, n) -> out[p] = Who(p, Assignees.AGENT, n) }
        }
        for (p in principals) {
            out.getOrPut(p) { Who(p, if (p.startsWith(Viewer.AGENT)) Assignees.AGENT else Assignees.PERSON, if (p.startsWith(Viewer.AGENT)) "an agent" else "someone") }
        }
        return out
    }

    /** A doc as the owner API shows it; [withBody] false leaves its text out (lists). */
    fun json(doc: DocRow, names: Map<String, Who>, withBody: Boolean = true): JsonObject = buildJsonObject {
        put("id", doc.id)
        put("projectId", doc.projectId)
        put("title", doc.title)
        put("index", isIndex(doc))
        if (withBody) put("body", doc.body)
        put("version", doc.version)
        put("size", utf8Size(doc.body))
        put("createdAt", Wire.isoOf(doc.createdAt))
        put("createdBy", names[doc.createdBy]?.json ?: JsonNull)
        put("updatedAt", Wire.isoOf(doc.updatedAt))
        put("updatedBy", names[doc.updatedBy]?.json ?: JsonNull)
    }

    fun versionJson(v: VersionRow, names: Map<String, Who>): JsonObject = buildJsonObject {
        put("version", v.version)
        put("title", v.title)
        v.body?.let { put("body", it) }
        put("size", v.size)
        put("savedAt", Wire.isoOf(v.savedAt))
        put("savedBy", names[v.savedBy]?.json ?: JsonNull)
        put("restoredFrom", v.restoredFrom?.let(::JsonPrimitive) ?: JsonNull)
    }

    /** Names for every principal these docs mention. */
    fun namesFor(docs: List<DocRow>): Map<String, Who> = names(docs.flatMap { listOf(it.createdBy, it.updatedBy) })

    fun listJson(docs: List<DocRow>): JsonArray {
        val names = namesFor(docs)
        return JsonArray(docs.map { json(it, names, withBody = false) })
    }

    private fun row(rs: java.sql.ResultSet) = DocRow(
        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5),
        rs.getString(6), rs.getLong(7), rs.getString(8), rs.getLong(9),
    )

    companion object {
        private const val COLUMNS = "id, project_id, title, body, version, created_by, created_at, updated_by, updated_at"
        private const val CHUNK = 200

        /** The most a doc's text may be, in UTF-8 bytes: 1 MiB. */
        const val MAX_BYTES = 1024 * 1024

        /** The most docs one channel holds. */
        const val MAX_DOCS = 1000

        /** How many versions of each doc are kept: the newest. */
        const val KEEP_VERSIONS = 100

        const val MAX_TITLE = 200
        const val MAX_EDITS = 50
        private const val MAX_LINKS = 500

        /** The title of a channel's index doc, and its key: any case finds it. */
        const val INDEX = "Index"
        const val INDEX_KEY = "index"

        private const val INDEX_STAYS =
            "The Index can't be deleted or renamed, only edited: it is where people and agents learn what this channel is for"

        fun isIndex(doc: DocRow): Boolean = key(doc.title) == INDEX_KEY

        /** A first Index: what the channel is for, links to the [docs] already there, and a note to keep it current. */
        fun indexTemplate(name: String, description: String?, docs: List<String>): String = buildString {
            append("# ").append(name).append("\n\n")
            append("## What this channel is for\n\n")
            append(description?.jsTrim()?.takeIf { it.isNotEmpty() } ?: "Nobody has said yet. Write a few lines: what this channel is for, and what it is not for.")
            append("\n\n## Docs\n\n")
            val linked = docs.filter { key(it) != INDEX_KEY }
            if (linked.isEmpty()) append("No other docs yet.\n") else linked.forEach { append("- [[").append(it).append("]]\n") }
            append("\n## Keep this current\n\n")
            append("Read this first when you start work here. When the channel's purpose changes, change it here. ")
            append("Link every doc from this page, or from a doc this page links to.\n")
        }

        /** A title as links find it: trimmed, any case. */
        fun key(title: String): String = title.jsTrim().lowercase()

        fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

        /** A title is short, on one line, and can't hold the characters a link is made of. */
        fun checkTitle(title: String): String {
            val t = requiredText(title, "title", MAX_TITLE)
            if (t.any { it == '[' || it == ']' || it == '|' || it == '\n' || it == '\r' }) {
                throw invalid("a title can't contain [ ] | or a line break, as [[links]] are made of them")
            }
            return t
        }

        fun checkBody(body: String) {
            val size = utf8Size(body)
            if (size > MAX_BYTES) throw invalid("a doc is at most 1 MB of text (this is ${size / 1024} KB): split it into linked docs")
        }
    }
}

/** `[[Doc name]]` and `[[Doc name|shown text]]` links in markdown, outside code. */
object DocLinks {
    data class Link(val target: String, val shown: String?)

    private val LINK = Regex("""\[\[([^\[\]|\r\n]+)(?:\|([^\[\]\r\n]*))?]]""")
    private val FENCE = Regex("(?m)^ {0,3}(```|~~~)[\\s\\S]*?(^ {0,3}\\1[^\\n]*$|\\z)")
    private val INLINE_CODE = Regex("`[^`\\n]*`")

    fun parse(text: String): List<Link> {
        val plain = INLINE_CODE.replace(FENCE.replace(text, ""), "")
        return LINK.findAll(plain).mapNotNull { m ->
            val target = m.groupValues[1].jsTrim()
            if (target.isEmpty()) null else Link(target, m.groups[2]?.value?.jsTrim()?.takeIf { it.isNotEmpty() })
        }.toList()
    }
}

val Feed.docs: Docs get() = Docs(this)
