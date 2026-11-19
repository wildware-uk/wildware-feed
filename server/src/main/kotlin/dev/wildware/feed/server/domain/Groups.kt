package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Groups of channels (migration 053, spec/12-channels-and-projects.md). The app calls a group a
 * project; a row of `projects` is a channel. A channel is in at most one group.
 */
class Groups(private val feed: Feed) {
    private val db get() = feed.db

    data class Patch(
        val name: String? = null,
        val slug: String? = null,
        val status: String? = null,
        /** `Given(null)` clears the theme. */
        val theme: Given<Projects.ThemeInput?>? = null,
    )

    fun byId(id: String): GroupRow? = row("id", id)
    fun bySlug(slug: String): GroupRow? = row("slug", slug)

    private fun row(column: String, value: String) =
        db.one("SELECT $COLUMNS FROM project_groups WHERE $column = ?", value) { GroupRow(json(it)) }

    /** As [Projects.find]: an id-shaped reference tries the id first, anything else the slug first. */
    fun find(reference: String): GroupRow? {
        val ref = reference.jsTrim()
        if (ref.isEmpty()) throw invalid("group is required")
        return if (Reads.ID.matches(ref)) byId(ref) ?: bySlug(ref.lowercase()) else bySlug(ref.lowercase()) ?: byId(ref)
    }

    fun resolve(reference: String): GroupRow = find(reference) ?: throw notFound("no such group: ${reference.jsTrim()}")

    /** By name, as the app lists them. */
    fun list(): List<GroupRow> = db.query("SELECT $COLUMNS FROM project_groups ORDER BY lower(name), seq") { GroupRow(json(it)) }

    /** Idempotent on the slug, as [Projects.create]: an existing group comes back untouched. */
    fun create(name: String, slug: String?): Pair<GroupRow, Boolean> = feed.op {
        val cleanName = requiredText(name, "name", Projects.NAME_MAX)
        val cleanSlug = Slugs.slugFor(cleanName, slug)
        bySlug(cleanSlug)?.let { return@op it to false }
        val now = feed.now()
        val id = Ulid.next(now)
        db.update(
            "INSERT INTO project_groups (id, slug, name, status, created_at, updated_at) VALUES (?, ?, ?, 'active', ?, ?)",
            id, cleanSlug, cleanName, now, now,
        )
        val group = byId(id)!!
        changed("group.created", group)
        group to true
    }

    fun update(reference: String, patch: Patch): GroupRow = feed.op {
        val group = resolve(reference)
        val sets = LinkedHashMap<String, Any?>()
        patch.name?.let { sets["name"] = requiredText(it, "name", Projects.NAME_MAX) }
        patch.slug?.let {
            val slug = Slugs.assertSlug(it)
            val holder = bySlug(slug)
            if (holder != null && holder.id != group.id) throw conflict("slug already in use: $slug")
            sets["slug"] = slug
        }
        patch.status?.let {
            if (it != "active" && it != "archived") throw invalid("status must be active or archived")
            sets["status"] = it
        }
        patch.theme?.let { t -> sets["theme"] = t.value?.let { feed.projects.mergeTheme(group.theme as? JsonObject, it) }?.let(Wire::encode) }
        if (sets.isEmpty()) throw invalid("update requires at least one field to change")
        sets["updated_at"] = feed.now()
        db.update("UPDATE project_groups SET ${sets.keys.joinToString(", ") { "$it = ?" }} WHERE id = ?", *sets.values.toTypedArray(), group.id)
        byId(group.id)!!.also { changed("group.updated", it) }
    }

    /** Deleting a group only ungroups its channels; what anyone chose for it goes with it. */
    fun delete(reference: String): GroupRow = feed.op {
        val group = resolve(reference)
        val channels = db.query("SELECT id, slug FROM projects WHERE group_id = ?", group.id) { it.getString(1) to it.getString(2) }
        db.update("UPDATE projects SET group_id = NULL, updated_at = ? WHERE group_id = ?", feed.now(), group.id)
        db.update("DELETE FROM group_subscriptions WHERE group_id = ?", group.id)
        db.update("DELETE FROM project_groups WHERE id = ?", group.id)
        for ((id, slug) in channels) feed.publish("project.updated", buildJsonObject { put("projectId", id); put("slug", slug) })
        changed("group.deleted", group)
        group
    }

    private fun changed(type: String, group: GroupRow) =
        feed.publish(type, buildJsonObject { put("groupId", group.id); put("slug", group.slug) })

    companion object {
        const val COLUMNS = "seq, id, slug, name, status, created_at, updated_at, theme"

        fun json(rs: java.sql.ResultSet): JsonObject = with(Rows) {
            buildJsonObject {
                long(rs, "seq", "seq")
                str(rs, "id", "id")
                str(rs, "slug", "slug")
                str(rs, "name", "name")
                str(rs, "status", "status")
                long(rs, "createdAt", "created_at")
                long(rs, "updatedAt", "updated_at")
                put("theme", json(rs, "theme"))
            }
        }
    }
}

val Feed.groups: Groups get() = Groups(this)
