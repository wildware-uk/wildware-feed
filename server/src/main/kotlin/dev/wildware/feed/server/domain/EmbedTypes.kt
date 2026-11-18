package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The looks an embed can be drawn in (TS `src/domain/embeds.ts`, spec/05 §7.8-7.9). The five
 * built-ins are seeded by migrations 027-029 with no agent; an agent registers its own. Checking a
 * spec is [Embeds.parseSpec]; putting an embed on a post is [FeedAttachments.checkedEmbeds].
 */
class EmbedTypes(private val feed: Feed) {
    private val db get() = feed.db

    /** `EmbedTypeView`, with the spec as parsed. */
    class TypeView(val slug: String, val name: String, val builtIn: Boolean, val spec: JsonObject, val updatedAt: Long)

    /** `registerEmbedType`: an upsert on the slug, refused when somebody else's. No event. */
    fun register(agentId: String, slug: String, name: String, spec: JsonElement?): TypeView = feed.op {
        val cleanSlug = slug.jsTrim().lowercase()
        if (cleanSlug.isEmpty() || cleanSlug.length > SLUG_MAX || !SLUG.matches(cleanSlug)) {
            throw invalid("slug must be lowercase words joined by hyphens, e.g. \"slack-message\", at most $SLUG_MAX characters")
        }

        val cleanName = name.jsTrim()
        if (cleanName.isEmpty() || cleanName.length > NAME_MAX) throw invalid("name is required, and at most $NAME_MAX characters")

        val parsed = Embeds.parseSpec(spec)

        val existing = db.one("SELECT agent_id FROM embed_types WHERE slug = ?", cleanSlug) { Given(it.getString(1)) }
        if (existing != null && existing.value != agentId) {
            throw conflict(
                if (existing.value == null) {
                    "\"$cleanSlug\" is one of the looks this dashboard ships, so it cannot be redefined. Register your own under a different slug."
                } else {
                    "\"$cleanSlug\" was registered by another agent. Pick a different slug."
                },
            )
        }

        val at = feed.now()
        db.one(
            "INSERT INTO embed_types (id, slug, name, agent_id, spec, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (slug) DO UPDATE SET name = excluded.name, spec = excluded.spec, updated_at = excluded.updated_at " +
                "RETURNING slug, name, agent_id, updated_at",
            Ulid.next(), cleanSlug, cleanName, agentId, Wire.encode(parsed), at, at,
        ) { TypeView(it.getString(1), it.getString(2), it.getString(3) == null, parsed, it.getLong(4)) }!!
    }

    /** `listEmbedTypeViews`: built-ins first, then by name; a spec that cannot be read is left out. */
    fun list(): List<TypeView> = db.query(
        "SELECT slug, name, agent_id, spec, updated_at FROM embed_types ORDER BY agent_id IS NOT NULL, name COLLATE NOCASE, slug",
    ) { rs ->
        Embeds.specOf(rs.getString(4))?.let { TypeView(rs.getString(1), rs.getString(2), rs.getString(3) == null, it, rs.getLong(5)) }
    }.filterNotNull()

    companion object {
        const val SLUG_MAX = 60
        const val NAME_MAX = 80
        private val SLUG = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

        /** `/\{\{\s*([A-Za-z0-9_-]+)\s*\}\}/g`, with JavaScript's `\s`. */
        private val PLACEHOLDER = Regex("\\{\\{$JS_SPACE*([A-Za-z0-9_-]+)$JS_SPACE*\\}\\}")

        /** JavaScript's `\s` as a character class. */
        private const val JS_SPACE =
            "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

        /** `placeholdersIn`: every `{{name}}` in the chrome, then the blocks, first seen, once each. */
        fun placeholders(spec: JsonObject): List<String> {
            val found = LinkedHashSet<String>()
            fun scan(value: JsonElement?) {
                when (value) {
                    is JsonPrimitive -> if (value.isString) PLACEHOLDER.findAll(value.content).forEach { found += it.groupValues[1] }
                    is JsonArray -> value.forEach(::scan)
                    is JsonObject -> value.values.forEach(::scan)
                    else -> {}
                }
            }
            scan(spec["chrome"])
            scan(spec["blocks"])
            return found.toList()
        }

        /** Each block's type, in order. */
        fun blockTypes(spec: JsonObject): List<String> =
            (spec["blocks"] as JsonArray).map { (it.jsonObject["type"] as JsonPrimitive).content }
    }
}
