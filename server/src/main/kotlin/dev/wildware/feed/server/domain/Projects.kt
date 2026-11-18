package dev.wildware.feed.server.domain

import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Projects (spec/02 §2, §7.1-7.4; TS `src/domain/projects.ts`). */
class Projects(private val feed: Feed) {
    private val db get() = feed.db

    /** The theme half of a patch; each field left out is kept, null removes it. */
    data class ThemeInput(
        val background: Given<String?>? = null,
        val accent: Given<String?>? = null,
        val logoMediaId: Given<String?>? = null,
        val logoReplacesName: Given<Boolean?>? = null,
    )

    data class Patch(
        val name: String? = null,
        val description: Given<String?>? = null,
        val slug: String? = null,
        val status: String? = null,
        val pinned: Boolean? = null,
        /** `Given(null)` clears the theme. */
        val theme: Given<ThemeInput?>? = null,
        /** Anything; checked by [assertBoard]. `Given(JsonNull)` is the default board. */
        val board: Given<JsonElement>? = null,
    )

    fun byId(id: String): ProjectRow? = row("id", id)
    fun bySlug(slug: String): ProjectRow? = row("slug", slug)

    private fun row(column: String, value: String) =
        db.one("SELECT ${Rows.PROJECT} FROM projects WHERE $column = ?", value) { ProjectRow(Rows.project(it)) }

    /** An id-shaped reference tries the id first, anything else the (lowercased) slug first. */
    fun find(reference: String): ProjectRow? {
        val ref = reference.jsTrim()
        if (ref.isEmpty()) throw invalid("project is required")
        return if (Reads.ID.matches(ref)) byId(ref) ?: bySlug(ref.lowercase()) else bySlug(ref.lowercase()) ?: byId(ref)
    }

    fun resolve(reference: String): ProjectRow = find(reference) ?: throw notFound("no such project: ${reference.jsTrim()}")

    /** Pinned first, then newest. */
    fun list(status: String?): List<ProjectRow> = db.query(
        "SELECT ${Rows.PROJECT} FROM projects WHERE (? IS NULL OR status = ?) ORDER BY pinned DESC, seq DESC",
        status, status,
    ) { ProjectRow(Rows.project(it)) }

    /** Idempotent on the slug: an existing project comes back untouched, with `created` false. */
    fun create(name: String, slug: String?, description: String?): Pair<ProjectRow, Boolean> = feed.op {
        val cleanName = requiredText(name, "name", NAME_MAX)
        val cleanSlug = Slugs.slugFor(cleanName, slug)
        val cleanDescription = optionalText(description, "description", DESCRIPTION_MAX)
        bySlug(cleanSlug)?.let { return@op it to false }

        val now = feed.now()
        val id = dev.wildware.feed.server.db.Ulid.next()
        db.update(
            "INSERT INTO projects (id, slug, name, description, status, pinned, created_at, updated_at) VALUES (?, ?, ?, ?, 'active', 0, ?, ?)",
            id, cleanSlug, cleanName, cleanDescription, now, now,
        )
        val project = byId(id)!!
        feed.publish("project.created", buildJsonObject { put("projectId", project.id); put("slug", project.slug) })
        project to true
    }

    /** Every given field is written and `updatedAt` bumped, even when nothing changes. */
    fun update(reference: String, patch: Patch): ProjectRow = feed.op {
        val project = resolve(reference)
        val sets = LinkedHashMap<String, Any?>()
        var given = false
        var slug: String? = null
        val name = patch.name?.let { requiredText(it, "name", NAME_MAX) }
        val description = patch.description?.let { optionalText(it.value, "description", DESCRIPTION_MAX) }
        val theme = patch.theme?.let { t -> t.value?.let { mergeTheme(project.theme as? JsonObject, it) } }
        val board = patch.board?.let { assertBoard(it.value) }
        if (patch.slug != null) {
            slug = Slugs.assertSlug(patch.slug)
            val holder = bySlug(slug)
            if (holder != null && holder.id != project.id) throw conflict("slug already in use: $slug")
        }

        if (slug != null) { sets["slug"] = slug; given = true }
        if (name != null) { sets["name"] = name; given = true }
        if (patch.description != null) { sets["description"] = description; given = true }
        if (patch.status != null) { sets["status"] = patch.status; given = true }
        if (patch.pinned != null) { sets["pinned"] = if (patch.pinned) 1 else 0; given = true }
        if (patch.theme != null) { sets["theme"] = theme?.let(Wire::encode); given = true }
        if (patch.board != null) { sets["board"] = board?.let(Wire::encode); given = true }
        if (!given) throw invalid("update requires at least one field to change")
        sets["updated_at"] = feed.now()

        db.update(
            "UPDATE projects SET ${sets.keys.joinToString(", ") { "$it = ?" }} WHERE id = ?",
            *sets.values.toTypedArray(), project.id,
        )
        val updated = byId(project.id)!!
        feed.publish("project.updated", buildJsonObject { put("projectId", updated.id); put("slug", updated.slug) })
        updated
    }

    /** `mergeTheme`: the stored keys keep their order, new ones are appended; `{}` becomes null. */
    fun mergeTheme(current: JsonObject?, input: ThemeInput): JsonObject? {
        val theme = LinkedHashMap<String, JsonElement>(current ?: emptyMap())
        for ((field, given) in listOf("background" to input.background, "accent" to input.accent)) {
            if (given == null) continue
            val value = given.value
            if (value == null) theme.remove(field) else theme[field] = JsonPrimitive(colour(value, field))
        }
        input.logoMediaId?.let { g ->
            val id = g.value
            if (id == null) {
                theme.remove("logoMediaId")
                theme.remove("logoReplacesName")
            } else {
                theme["logoMediaId"] = JsonPrimitive(logo(id))
            }
        }
        input.logoReplacesName?.let { g -> if (g.value == true) theme["logoReplacesName"] = JsonPrimitive(true) else theme.remove("logoReplacesName") }
        if (theme["logoReplacesName"].truthy() && !theme["logoMediaId"].truthy()) {
            throw invalid("logoReplacesName needs a logo to show instead of the name")
        }
        return if (theme.isEmpty()) null else JsonObject(theme)
    }

    private fun colour(value: String, field: String): String {
        val trimmed = value.jsTrim()
        if (!THEME_COLOUR.matches(trimmed)) throw invalid("$field must be a hex colour like #1a2b3c")
        val hex = trimmed.substring(1).lowercase()
        return "#" + if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
    }

    private fun logo(mediaId: String): String {
        val (kind, status) = db.one("SELECT kind, status FROM media WHERE id = ?", mediaId) { it.getString(1) to it.getString(2) }
            ?: throw notFound("no such media: $mediaId")
        if (kind != "image") throw invalid("a logo must be an image")
        if (status != "ready") throw invalid("that image is still being processed")
        return mediaId
    }

    companion object {
        const val NAME_MAX = 200
        const val DESCRIPTION_MAX = 2_000
        const val COLUMNS_MAX = 6
        const val COLUMN_TITLE_MAX = 40
        private val THEME_COLOUR = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")
        private val TASK_STATES = listOf("todo", "claimed", "done", "cancelled")

        /** `assertBoard`: null for the default columns, else `{columns:[{title, states}]}`. */
        fun assertBoard(input: JsonElement): JsonObject? {
            if (input is JsonNull) return null
            if (input !is JsonObject) throw invalid("board must be an object, or null for the default columns")
            val columns = input["columns"] as? JsonArray ?: throw invalid("board.columns must be a list")
            if (columns.isEmpty()) throw invalid("a board needs at least one column")
            if (columns.size > COLUMNS_MAX) throw invalid("at most $COLUMNS_MAX columns")
            val seen = HashSet<String>()
            val checked = columns.mapIndexed { index, column ->
                if (column !is JsonObject) throw invalid("columns[$index] must be an object")
                val states = column["states"] as? JsonArray
                if (states == null || states.isEmpty()) throw invalid("columns[$index].states must name at least one task state")
                for (state in states) {
                    val name = state.jsString
                    if (name == null || name !in TASK_STATES) {
                        throw invalid("columns[$index].states must be any of: ${TASK_STATES.joinToString(", ")}")
                    }
                    if (!seen.add(name)) throw invalid("$name is already in another column")
                }
                val rawTitle = column["title"]
                val title = requiredText(
                    if (rawTitle == null || rawTitle is JsonNull) "" else JsText.string(rawTitle),
                    "columns[$index].title",
                    COLUMN_TITLE_MAX,
                )
                buildJsonObject { put("title", title); put("states", states) }
            }
            return buildJsonObject { put("columns", JsonArray(checked)) }
        }
    }
}

/** `optionalText`: trimmed, blank is null. */
fun optionalText(value: String?, field: String, max: Int): String? {
    val trimmed = value?.jsTrim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (trimmed.length > max) throw invalid("$field must be at most $max characters")
    return trimmed
}
