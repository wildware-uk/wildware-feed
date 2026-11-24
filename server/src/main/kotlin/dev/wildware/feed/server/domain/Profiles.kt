package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database

/**
 * What each member of a team says about themselves (migration 077, spec/13 "Profiles"): a short
 * role line ("Development", "Marketing"), a description (who they are, what they look after,
 * when to contact them) and a picture. Agents read them in `list_members` to pick whom to @mention.
 *
 * A person's is on their membership, so it is per team, and only they change it. An agent sets
 * its own with `set_profile`; owners and admins may change any agent's too.
 *
 * A picture is an image uploaded as any other (`POST /api/media`, or `create_upload`), not on any
 * card, at most [AVATAR_MAX_BYTES]. It counts toward the team's storage like any upload. While it
 * is someone's picture the sweeper and retention keep it; once nobody's, the sweeper takes it.
 */
class Profiles(private val db: Database) {
    data class Profile(val role: String?, val description: String?, val avatarMediaId: String? = null)

    /** A change: null leaves that part as it is; a [Given] of null (or of blank text) clears it. */
    class Change(
        val role: Given<String?>? = null,
        val description: Given<String?>? = null,
        val avatar: Given<String?>? = null,
    ) {
        val empty get() = role == null && description == null && avatar == null
    }

    /** Who is setting a picture, which says whose uploads it may be. */
    sealed interface Setter {
        /** A person, for themselves: one of the people's uploads. */
        data object Person : Setter

        /** An agent, for itself: one of its own uploads. */
        data class Agent(val agentId: String) : Setter

        /** An owner or admin, for an agent: a person's upload or that agent's. */
        data class ForAgent(val agentId: String) : Setter
    }

    /** Makes the 256 px square of a new picture ([dev.wildware.feed.server.media.Derive.avatar]); set by the app. */
    @Volatile var makeAvatar: (mediaId: String) -> Unit = {}

    fun ofMember(memberId: String): Profile =
        db.one("SELECT profile_role, profile_description, avatar_media_id FROM memberships WHERE team_id = :team AND id = ?", memberId) {
            Profile(it.getString(1), it.getString(2), it.getString(3))
        } ?: Profile(null, null)

    fun ofAgent(agentId: String): Profile =
        db.one("SELECT profile_role, profile_description, avatar_media_id FROM agents WHERE team_id = :team AND id = ?", agentId) {
            Profile(it.getString(1), it.getString(2), it.getString(3))
        } ?: Profile(null, null)

    /** A person changes their own. Removed people can't. */
    fun setMember(memberId: String, change: Change): Profile {
        val clean = check(change, Setter.Person)
        val live = db.one("SELECT 1 FROM memberships WHERE team_id = :team AND id = ? AND removed_at IS NULL", memberId) { true } == true
        if (!live) throw forbidden("you are no longer in this team")
        write("memberships", memberId, clean)
        return ofMember(memberId)
    }

    /** An agent's: by itself ([Setter.Agent]), or by an owner or admin ([Setter.ForAgent]; the route checks who). */
    fun setAgent(agentId: String, change: Change, setter: Setter = Setter.Agent(agentId)): Profile {
        val clean = check(change, setter)
        val live = db.one("SELECT 1 FROM agents WHERE team_id = :team AND id = ? AND revoked_at IS NULL", agentId) { true } == true
        if (!live) throw notFound("no such agent: $agentId")
        write("agents", agentId, clean)
        return ofAgent(agentId)
    }

    private fun write(table: String, id: String, change: Change) {
        change.role?.let { db.update("UPDATE $table SET profile_role = ? WHERE team_id = :team AND id = ?", it.value, id) }
        change.description?.let { db.update("UPDATE $table SET profile_description = ? WHERE team_id = :team AND id = ?", it.value, id) }
        change.avatar?.let { picture ->
            db.update("UPDATE $table SET avatar_media_id = ? WHERE team_id = :team AND id = ?", picture.value, id)
            picture.value?.let { runCatching { makeAvatar(it) } }
        }
    }

    /**
     * [change], cleaned and checked, without writing anything: routes call it first, so a bad
     * profile changes nothing else in the same request.
     */
    fun check(change: Change, setter: Setter) = Change(
        role = change.role?.let { Given(role(it.value)) },
        description = change.description?.let { Given(description(it.value)) },
        avatar = change.avatar?.let { Given(it.value?.jsTrim()?.ifEmpty { null }?.let { id -> picture(id, setter) }) },
    )

    /** [mediaId] as a picture [setter] may use: an image, uploaded, on no card, small enough, and theirs to use. */
    private fun picture(mediaId: String, setter: Setter): String {
        class Row(val kind: String, val bytes: Long, val uploaded: Boolean, val removed: Boolean, val attached: Boolean, val agentId: String?, val status: String)
        val row = db.one(
            "SELECT kind, bytes, sha256, removed_at, update_id, message_id, request_id, task_id, feedback_id, agent_id, status " +
                "FROM media WHERE team_id = :team AND id = ?",
            mediaId,
        ) {
            Row(
                kind = it.getString(1), bytes = it.getLong(2), uploaded = it.getString(3).isNotEmpty(), removed = it.getObject(4) != null,
                attached = (5..9).any { i -> it.getString(i) != null }, agentId = it.getString(10), status = it.getString(11),
            )
        }
        val mine = row != null && when (setter) {
            Setter.Person -> row.agentId == null
            is Setter.Agent -> row.agentId == setter.agentId
            is Setter.ForAgent -> row.agentId == null || row.agentId == setter.agentId
        }
        // Someone else's upload, or one on a card, is missing as far as a picture goes.
        if (row == null || !mine || row.attached || row.removed) throw notFound("no such upload: $mediaId")
        if (row.kind != "image") throw invalid("a picture must be an image (png, jpeg, webp or gif)")
        if (!row.uploaded || row.status == "failed") throw invalid("upload the picture's bytes first")
        if (row.bytes > AVATAR_MAX_BYTES) throw invalid("a picture may be at most $AVATAR_MAX_BYTES bytes (2 MB)")
        return mediaId
    }

    companion object {
        const val ROLE_MAX = 60
        const val DESCRIPTION_MAX = 500

        /** The largest picture taken in; it is shown as a 256 px square. */
        const val AVATAR_MAX_BYTES = 2L * 1024 * 1024

        /** Everyone's picture in this team, for SQL `NOT IN (...)`: the sweeper and retention keep them. */
        const val AVATAR_IDS =
            "SELECT avatar_media_id FROM memberships WHERE team_id = :team AND avatar_media_id IS NOT NULL " +
                "UNION SELECT avatar_media_id FROM agents WHERE team_id = :team AND avatar_media_id IS NOT NULL"

        /** Invisible format characters that could hide or reorder text; the joiner in emoji is fine. */
        private fun hidden(c: Char) = Character.getType(c) == Character.FORMAT.toInt() && c != '‍'

        /** A role line: one line, trimmed, at most [ROLE_MAX] characters; blank is none. */
        fun role(text: String?): String? {
            val clean = optionalText(text, "role", ROLE_MAX) ?: return null
            if (clean.any { it.isISOControl() || hidden(it) }) {
                throw invalid("role must be one line, with no control characters")
            }
            return clean
        }

        /** A description: trimmed, at most [DESCRIPTION_MAX] characters, line breaks allowed; blank is none. */
        fun description(text: String?): String? {
            val clean = optionalText(text?.replace("\r\n", "\n"), "description", DESCRIPTION_MAX) ?: return null
            if (clean.any { (it.isISOControl() && it != '\n' && it != '\t') || hidden(it) }) {
                throw invalid("description can't hold control characters")
            }
            return clean
        }
    }
}
