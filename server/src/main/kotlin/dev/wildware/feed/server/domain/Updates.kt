package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Ulid
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Updates (spec/02 §3, spec/05 §7.4-7.5; TS `src/domain/updates.ts`). */
class Updates(private val feed: Feed) {
    private val db get() = feed.db

    class Post(
        val project: String,
        val agentId: String,
        val body: String,
        val title: String? = null,
        val level: String? = null,
        val priority: String? = null,
        val sessionId: String? = null,
        val mediaIds: List<String>? = null,
        val embeds: List<FeedAttachments.EmbedInput>? = null,
        val apps: List<FeedAttachments.AppInput>? = null,
    )

    class Edit(
        val title: Given<String?>? = null,
        val body: String? = null,
        val level: String? = null,
        val priority: String? = null,
    )

    fun find(id: String): UpdateRow? = db.one("SELECT ${Rows.UPDATE} FROM updates WHERE id = ?", id) { UpdateRow(Rows.update(it)) }

    fun post(input: Post): UpdateRow = feed.op {
        val project = feed.projects.resolve(input.project)
        val agentId = db.one("SELECT id FROM agents WHERE id = ?", input.agentId) { it.getString(1) }
            ?: throw notFound("no such agent: ${input.agentId}")
        val body = requiredText(input.body, "body", BODY_MAX)
        val title = optionalText(input.title, "title", TITLE_MAX)
        val sessionId = input.sessionId
        if (sessionId != null) {
            val owner = db.one("SELECT agent_id FROM sessions WHERE id = ?", sessionId) { it.getString(1) }
                ?: throw notFound("no such session: $sessionId")
            if (owner != agentId) throw invalid("session belongs to another agent")
        }
        val mediaIds = if (input.mediaIds.isNullOrEmpty()) emptyList() else FeedAttachments.assertAttachableToUpdate(db, input.mediaIds, agentId)
        val embeds = if (input.embeds.isNullOrEmpty()) emptyList() else FeedAttachments.checkedEmbeds(db, input.embeds)
        val apps = if (input.apps.isNullOrEmpty()) emptyList() else FeedAttachments.checkedApps(db, input.apps)
        val priority = input.priority ?: "medium"
        if (priority !in PRIORITIES) throw invalid("priority must be one of: ${PRIORITIES.joinToString(", ")}")

        val id = Ulid.next()
        db.update(
            "INSERT INTO updates (id, project_id, agent_id, session_id, title, body, level, priority, task_id, pinned, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 0, ?)",
            id, project.id, agentId, sessionId, title, body, input.level ?: "info", priority, feed.now(),
        )
        val update = find(id)!!
        val author = "agent:$agentId"
        if (mediaIds.isNotEmpty()) FeedAttachments.attachMedia(db, mediaIds, "update_id", id, "agent_id", agentId)
        if (embeds.isNotEmpty()) FeedAttachments.attachEmbeds(db, embeds, "update_id", id, agentId, author, feed.now())
        if (apps.isNotEmpty()) FeedAttachments.attachApps(db, apps, CardRef("update", id), agentId, author, feed.now())
        Subscriptions(db, feed.clock).run {
            followProject(Subscriptions.agent(agentId), project.id, afterSeq = 0)
            followThread(Subscriptions.agent(agentId), Subscriptions.Thread("update", id), project.id)
        }
        feed.publish("update.created", buildJsonObject {
            put("updateId", update.id); put("projectId", update.projectId); put("agentId", update.agentId)
        })
        update
    }

    /** Only the author may edit; `edited_at` is stamped whatever changed. */
    fun edit(updateId: String, agentId: String, edit: Edit): UpdateRow = feed.op {
        val update = find(updateId)
        if (update == null || update.deletedAt != null) throw notFound("no such update: $updateId")
        if (update.agentId != agentId) throw invalid("that update was posted by another agent")
        val sets = LinkedHashMap<String, Any?>()
        sets["edited_at"] = feed.now()
        edit.title?.let { sets["title"] = optionalText(it.value, "title", TITLE_MAX) }
        edit.body?.let { sets["body"] = requiredText(it, "body", BODY_MAX) }
        edit.level?.let {
            if (it !in LEVELS) throw invalid("level must be one of: ${LEVELS.joinToString(", ")}")
            sets["level"] = it
        }
        edit.priority?.let {
            if (it !in PRIORITIES) throw invalid("priority must be one of: ${PRIORITIES.joinToString(", ")}")
            sets["priority"] = it
        }
        if (sets.size == 1) throw invalid("an edit must change the title, the body, the level or the priority")
        db.update(
            "UPDATE updates SET ${sets.keys.joinToString(", ") { "$it = ?" }} WHERE id = ? AND deleted_at IS NULL",
            *sets.values.toTypedArray(), update.id,
        )
        val saved = find(update.id)!!
        feed.publish("update.updated", buildJsonObject {
            put("updateId", saved.id); put("projectId", saved.projectId); put("pinned", saved.pinned)
        })
        saved
    }

    /** Soft, and quiet when it already is. Nothing on the card goes with it. */
    fun delete(updateId: String): UpdateRow = feed.op {
        val update = find(updateId) ?: throw notFound("no such update: $updateId")
        if (update.deletedAt != null) return@op update
        db.update("UPDATE updates SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL", feed.now(), update.id)
        feed.publish("update.deleted", buildJsonObject { put("updateId", update.id); put("projectId", update.projectId) })
        find(update.id)!!
    }

    fun setPinned(updateId: String, pinned: Boolean): UpdateRow = feed.op {
        feed.cards.pin(CardRef("update", updateId), pinned)
        find(updateId)!!
    }

    companion object {
        const val BODY_MAX = 100_000
        const val TITLE_MAX = 200
        val LEVELS = listOf("info", "success", "warn", "error")
        /** `critical` pages the owner's phones through Do Not Disturb, on a phone that turned critical alerts on. */
        val PRIORITIES = listOf("low", "medium", "high", "critical")
    }
}
