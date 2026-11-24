package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Viewer
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A channel's Media tab: every picture, video and file on the cards in one channel, newest first,
 * a page at a time.
 *
 * What counts is what the feed would show there: media on the channel's updates, on its messages
 * (posts and replies) and on its requests. A deleted update or message takes its files with it, and
 * so does a reply in the thread of a deleted update. Files the media retention removed, files sent
 * with feedback and uploads on nothing are left out, and so is history older than the free plan
 * keeps. A channel this viewer can't read answers as missing, as its docs do.
 */
class ChannelMedia(private val feed: Feed) {
    private val db get() = feed.db

    /** Which media a page holds: everything, or just one sort. Files include sounds. */
    enum class Kind(val param: String, val sql: String) {
        ALL("all", ""),
        IMAGES("image", " AND m.kind = 'image'"),
        VIDEOS("video", " AND m.kind = 'video'"),
        FILES("file", " AND m.kind IN ('file', 'audio')"),
        ;

        companion object {
            /** "image", "video", "file", or "all" (or nothing) for everything. */
            fun of(raw: String?): Kind {
                val value = raw?.jsTrim()?.lowercase().orEmpty()
                if (value.isEmpty()) return ALL
                return entries.firstOrNull { it.param == value || it.param + "s" == value }
                    ?: throw invalid("kind must be one of all, image, video or file")
            }
        }
    }

    /** One page of [projectRef]'s media, newest first, after [cursor] (a cursor from the page before). */
    fun page(projectRef: String, kind: Kind, cursor: String?, limit: Int): JsonObject {
        val project = feed.projects.resolve(projectRef)
        val before = when {
            cursor.isNullOrEmpty() -> null
            else -> cursor.toLongOrNull()?.takeIf { it > 0 } ?: throw invalid("cursor is not one this server issued")
        }
        val take = limit.coerceIn(1, MAX_LIMIT)
        val rows = db.query(
            "SELECT m.seq, m.id, m.created_at, m.agent_id, m.update_id, m.message_id, m.request_id, x.user_id, x.author, a.decided_by " +
                "FROM media AS m " +
                "LEFT JOIN updates AS u ON u.team_id = m.team_id AND u.id = m.update_id " +
                "LEFT JOIN messages AS x ON x.team_id = m.team_id AND x.id = m.message_id " +
                "LEFT JOIN approvals AS a ON a.team_id = m.team_id AND a.id = m.request_id " +
                "WHERE m.team_id = :team AND m.sha256 <> '' AND m.removed_at IS NULL AND m.feedback_id IS NULL AND m.created_at >= :since " +
                "AND (? IS NULL OR m.seq < ?)${kind.sql} AND (" +
                "(u.project_id = ? AND u.deleted_at IS NULL) OR " +
                "(x.project_id = ? AND x.deleted_at IS NULL AND NOT EXISTS (" +
                "SELECT 1 FROM updates AS t WHERE t.team_id = :team AND t.id = x.update_id AND t.deleted_at IS NOT NULL)) OR " +
                "(a.project_id = ? AND NOT EXISTS (" +
                "SELECT 1 FROM updates AS t WHERE t.team_id = :team AND t.id = a.update_id AND t.deleted_at IS NOT NULL))" +
                ") ORDER BY m.seq DESC LIMIT ?",
            before, before, project.id, project.id, project.id, take + 1,
        ) { rs ->
            Row(
                seq = rs.getLong(1),
                id = rs.getString(2),
                at = rs.getLong(3),
                card = rs.getString(5)?.let { CardRef("update", it) }
                    ?: rs.getString(6)?.let { CardRef("message", it) }
                    ?: CardRef("request", rs.getString(7)),
                sender = sender(rs.getString(4), rs.getString(8), rs.getString(9), rs.getString(10)),
            )
        }
        val shown = rows.take(take)
        val media = Reads(db, feed.clock).mediaForMany("id", shown.map { it.id })
        val names = Docs(feed).names(shown.map { it.sender }.distinct())
        return buildJsonObject {
            put("projectId", project.id)
            put("kind", kind.param)
            put("items", JsonArray(shown.mapNotNull { row ->
                val json = media[row.id]?.firstOrNull() ?: return@mapNotNull null
                buildJsonObject {
                    put("media", json)
                    put("card", buildJsonObject { put("kind", row.card.kind); put("id", row.card.id) })
                    put("sentBy", names.getValue(row.sender).json)
                    put("at", Wire.isoOf(row.at))
                }
            }))
            put("nextCursor", if (rows.size > take) shown.last().seq.toString() else null)
        }
    }

    private class Row(val seq: Long, val id: String, val at: Long, val card: CardRef, val sender: String)

    /**
     * Who sent a file, as a principal: the agent that uploaded it, else the person who wrote the
     * message or answered the request it is on, else the team's founder (a team of one).
     */
    private fun sender(agentId: String?, messageUser: String?, messageAuthor: String?, decidedBy: String?): String = when {
        agentId != null -> Viewer.AGENT + agentId
        messageUser != null -> Viewer.MEMBER + messageUser
        messageAuthor != null && messageAuthor.startsWith(Viewer.AGENT) -> messageAuthor
        decidedBy != null -> Viewer.MEMBER + decidedBy
        else -> Viewer.MEMBER + People.FOUNDER
    }

    companion object {
        const val DEFAULT_LIMIT = 60
        const val MAX_LIMIT = 100
    }
}

val Feed.channelMedia: ChannelMedia get() = ChannelMedia(this)
