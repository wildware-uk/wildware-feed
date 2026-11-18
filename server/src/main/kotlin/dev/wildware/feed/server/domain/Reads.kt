package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database

/** The read side of the owner API (spec/02, spec/03 §9). Grows into the full set of reads. */
class Reads(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    data class ProjectRef(val id: String, val slug: String)

    /** A project by id, or by slug ignoring case (spec/02 §7.2). Archived projects resolve too. */
    fun findProject(reference: String): ProjectRef? {
        val ref = reference.jsTrim()
        if (ref.isEmpty()) return null
        return db.one("SELECT id, slug FROM projects WHERE id = ?", ref) { ProjectRef(it.getString(1), it.getString(2)) }
            ?: db.one("SELECT id, slug FROM projects WHERE slug = ? COLLATE NOCASE", ref.lowercase()) { ProjectRef(it.getString(1), it.getString(2)) }
    }

    fun projectSlugsById(): Map<String, String> = db.query("SELECT id, slug FROM projects") { it.getString(1) to it.getString(2) }.toMap()
}
