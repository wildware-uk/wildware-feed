package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Ulid
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A project's words and the cards that wear them (spec/02 §2.4, §6.4, spec/05 §7.18-7.19; TS `src/domain/tags.ts`). */
class Tags(private val feed: Feed) {
    private val db get() = feed.db

    fun byId(id: String): TagRow? = db.one("SELECT ${Rows.TAG} FROM tags WHERE team_id = :team AND id = ?", id) { TagRow(Rows.tag(it)) }?.takeIf { feed.access.visible(it.projectId) }

    fun bySlug(projectId: String, slug: String): TagRow? =
        db.one("SELECT ${Rows.TAG} FROM tags WHERE team_id = :team AND project_id = ? AND slug = ?", projectId, slug) { TagRow(Rows.tag(it)) }

    /** One project's tags (a non-empty reference is resolved) or every project's, oldest first. */
    fun list(project: String?): List<TagRow> {
        if (project.isNullOrEmpty()) return db.query("SELECT ${Rows.TAG} FROM tags WHERE team_id = :team ORDER BY seq") { TagRow(Rows.tag(it)) }
        val id = feed.projects.resolve(project).id
        return db.query("SELECT ${Rows.TAG} FROM tags WHERE team_id = :team AND project_id IN (?) ORDER BY seq", id) { TagRow(Rows.tag(it)) }
    }

    private fun checkedName(raw: String): Pair<String, String> {
        val name = raw.jsTrim()
        if (name.isEmpty()) throw invalid("a tag needs a word")
        if (name.length > NAME_MAX) throw invalid("a tag may be at most $NAME_MAX characters")
        val slug = Slugs.tag(name)
        if (slug.isEmpty()) throw invalid("${JsText.quote(raw)} has no letters or digits in it")
        return name to slug
    }

    private fun checkColour(colour: String?) {
        if (colour != null && colour !in COLOURS) throw invalid("colour must be one of ${COLOURS.joinToString(", ")}")
    }

    /** Idempotent on the word: an existing tag comes back as it is, colour and all. */
    fun define(project: String, name: String, colour: String?, agentId: String?): TagRow = feed.op {
        val projectId = feed.projects.resolve(project).id
        feed.access.requirePost(projectId) { notFound("no such project: $project") }
        val (clean, slug) = checkedName(name)
        checkColour(colour)
        bySlug(projectId, slug)?.let { return@op it }
        val count = db.one("SELECT count(*) FROM tags WHERE team_id = :team AND project_id = ?", projectId) { it.getLong(1) } ?: 0
        if (count >= PER_PROJECT) throw invalid("this project already has $PER_PROJECT tags, which is as many as a picker can show")
        val id = Ulid.next()
        db.update(
            "INSERT INTO tags (team_id, id, project_id, name, slug, colour, agent_id, created_at) VALUES (:team, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (project_id, slug) DO NOTHING",
            id, projectId, clean, slug, colour, agentId, feed.now(),
        )
        val tag = bySlug(projectId, slug)!!
        feed.publish("tags.changed", buildJsonObject { put("projectId", projectId) })
        tag
    }

    /** Rename or recolour; a word another tag already has is refused. Publishes even when nothing changed. */
    fun rename(id: String, name: String?, colour: Given<String?>?): TagRow = feed.op {
        val tag = byId(id) ?: throw notFound("no such tag: $id")
        feed.access.requirePost(tag.projectId) { notFound("no such tag: $id") }
        checkColour(colour?.value)
        var clean: Pair<String, String>? = null
        if (name != null) {
            clean = checkedName(name)
            val clash = bySlug(tag.projectId, clean.second)
            if (clash != null && clash.id != tag.id) throw invalid("this project already has a tag called ${JsText.quote(clash.name)}")
        }
        db.update(
            "UPDATE tags SET name = ?, slug = ?, colour = ? WHERE team_id = :team AND id = ?",
            clean?.first ?: tag.name,
            clean?.second ?: tag.slug,
            if (colour == null) tag.colour else colour.value,
            id,
        )
        feed.publish("tags.changed", buildJsonObject { put("projectId", tag.projectId) })
        byId(id)!!
    }

    /** A real delete, off every card too. Not idempotent: the second is a 404. */
    fun retire(id: String): Pair<String, String> = feed.op {
        val tag = byId(id) ?: throw notFound("no such tag: $id")
        feed.access.requirePost(tag.projectId) { notFound("no such tag: $id") }
        db.transaction {
            db.update("DELETE FROM card_tags WHERE team_id = :team AND tag_id = ?", id)
            db.update("DELETE FROM tags WHERE team_id = :team AND id = ?", id)
        }
        feed.publish("tags.changed", buildJsonObject { put("projectId", tag.projectId) })
        id to tag.projectId
    }

    /** `tagCard`: omitting [on] toggles; [create] defines a missing word first (and keeps it even if the tagging then fails). */
    fun tagCard(card: CardRef, tag: String, actor: String, on: Boolean?, create: Boolean): Pair<TagRow, Boolean> = feed.op {
        val row = feed.cards.resolveToWrite(card)
        val projectId = row.projectId?.takeIf { it.isNotEmpty() }
            ?: throw invalid("this card does not belong to a project, so it cannot be tagged")
        val found = resolveTag(projectId, tag, create, actor)
        val already = db.one("SELECT id FROM card_tags WHERE team_id = :team AND tag_id = ? AND ${card.anchor} = ?", found.id, card.id) { it.getString(1) } != null
        val wanted = on ?: !already
        if (wanted == already) return@op found to wanted

        if (wanted) {
            val worn = db.one("SELECT count(*) FROM card_tags WHERE team_id = :team AND ${card.anchor} IN (?)", card.id) { it.getLong(1) } ?: 0
            if (worn >= PER_CARD) throw invalid("a card may carry at most $PER_CARD tags")
            db.update(
                "INSERT INTO card_tags (team_id, id, tag_id, ${card.anchor}, author, created_at) VALUES (:team, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (tag_id, ${card.anchor}) WHERE ${card.anchor} IS NOT NULL DO NOTHING",
                Ulid.next(), found.id, card.id, actor, feed.now(),
            )
        } else {
            db.update("DELETE FROM card_tags WHERE team_id = :team AND tag_id = ? AND ${card.anchor} = ?", found.id, card.id)
        }
        feed.cards.announce(card, row)
        feed.publish("tags.changed", buildJsonObject { put("projectId", projectId) })
        found to wanted
    }

    /** By id first (it must be this project's), then by word, then made if asked. */
    private fun resolveTag(projectId: String, named: String, create: Boolean, actor: String): TagRow {
        byId(named)?.let {
            if (it.projectId != projectId) throw invalid("that tag belongs to another project")
            return it
        }
        val slug = Slugs.tag(named)
        if (slug.isNotEmpty()) bySlug(projectId, slug)?.let { return it }
        if (!create) throw notFound("this project has no tag called ${JsText.quote(named)}")
        val agentId = if (actor.startsWith(Messages.AGENT_PREFIX)) actor.removePrefix(Messages.AGENT_PREFIX) else null
        return define(projectId, named, null, agentId)
    }

    companion object {
        const val NAME_MAX = 32
        const val PER_PROJECT = 50
        const val PER_CARD = 8
        val COLOURS = listOf("grey", "rose", "amber", "green", "blue", "violet")
    }
}
