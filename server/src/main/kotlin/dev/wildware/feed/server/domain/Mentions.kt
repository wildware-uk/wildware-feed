package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import java.util.regex.Pattern

/**
 * `@name` in a message (spec/02 §7.7; TS `src/domain/mentions.ts`): code is ignored, names match
 * case-insensitively, and every live agent with a named name is mentioned. Two groups on top:
 * `@everyone` mentions every agent, and `@here` every agent online now ([Sessions.PRESENCE_WINDOW_MS]).
 * A group never mentions the agent that wrote the message.
 */
object Mentions {
    const val EVERYONE = "everyone"
    const val HERE = "here"
    private const val AGENT = "agent:"

    /**
     * Whether a message [author] wrote is directed by its @names: an agent's @name reaches only the
     * agents it names, while a person's @name reaches that agent as well as everyone in the channel.
     */
    fun directs(author: String?): Boolean = author?.startsWith(AGENT) == true

    /** [directs] in SQL, for a message's author column [column]: true when its @names narrow it. */
    fun directsSql(column: String = "m.author"): String = "$column LIKE '$AGENT%'"

    /**
     * Whether [reader] (`human`, or `agent:<id>`) may read the words of a message [author] wrote: a
     * message an agent wrote that @names agents reaches only them and its author, wherever its text
     * would show (a reply's quote, a work summary, a thread's title); the owner reads everything.
     */
    fun readable(db: Database, messageId: String, author: String?, reader: String): Boolean {
        if (reader == "human" || reader == author || !directs(author)) return true
        val named = db.query("SELECT agent_id FROM message_mentions WHERE team_id = :team AND message_id = ?", messageId) { it.getString(1) }
        return named.isEmpty() || reader.removePrefix("agent:") in named
    }

    /** JavaScript's `$` without the `m` flag is the very end, hence `\z`. */
    private val CODE = Regex("```[\\s\\S]*?(?:```|\\z)|~~~[\\s\\S]*?(?:~~~|\\z)|`[^`\\n]*`")

    /** JavaScript's `\w` under the `iu` flags: ASCII word characters plus long s and the Kelvin sign. */
    private const val WORD = "A-Za-z0-9_\\u017F\\u212A"

    /** The lowercased names that [body] mentions. */
    fun mentionedNames(body: String, names: Iterable<String>): Set<String> {
        val prose = CODE.replace(body, " ")
        val found = LinkedHashSet<String>()
        for (name in names) {
            if (name.jsTrim().isEmpty()) continue
            val pattern = Pattern.compile(
                "(?<![$WORD@])@${Pattern.quote(name)}(?![$WORD-])",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
            )
            if (pattern.matcher(prose).find()) found += name.lowercase()
        }
        return found
    }

    fun mentionedAgentIds(db: Database, body: String, author: String? = null, now: Long = System.currentTimeMillis()): List<String> {
        if ('@' !in body) return emptyList()
        val agents = db.query("SELECT id, name FROM agents WHERE team_id = :team AND revoked_at IS NULL ORDER BY seq") { it.getString(1) to it.getString(2) }
        val names = mentionedNames(body, agents.map { it.second }.distinct() + listOf(EVERYONE, HERE))
        val named = agents.filter { it.second.lowercase() in names }.map { it.first }
        val sender = author?.takeIf { it.startsWith("agent:") }?.removePrefix("agent:")
        val group = when {
            EVERYONE in names -> agents.map { it.first }
            HERE in names -> db.query(
                "SELECT DISTINCT agent_id FROM sessions WHERE team_id = :team AND ended_at IS NULL AND last_heartbeat_at >= ?", now - Sessions.PRESENCE_WINDOW_MS,
            ) { it.getString(1) }.toSet().let { live -> agents.map { it.first }.filter { it in live } }
            else -> emptyList()
        }
        return (named + group.filter { it != sender }).distinct()
    }

    /** Replace the message's mentions with what its body says now, and answer who they are. [author] is never mentioned by a group. */
    fun record(db: Database, messageId: String, body: String, author: String? = null, now: Long = System.currentTimeMillis()): List<String> {
        val ids = mentionedAgentIds(db, body, author, now)
        db.transaction {
            db.update("DELETE FROM message_mentions WHERE team_id = :team AND message_id = ?", messageId)
            for (id in ids) {
                db.update(
                    "INSERT INTO message_mentions (team_id, id, message_id, agent_id) VALUES (:team, ?, ?, ?) ON CONFLICT (message_id, agent_id) DO NOTHING",
                    Ulid.next(), messageId, id,
                )
            }
        }
        return ids
    }

    /**
     * The people (membership ids) [body] names by their @handle (migration 055). Only on a team:
     * a team of one has nobody else to name, and its owner's `@owner` keeps meaning what it did.
     */
    fun mentionedPeople(db: Database, body: String): List<String> {
        if ('@' !in body) return emptyList()
        if ((db.one("SELECT count(*) FROM memberships WHERE team_id = :team") { it.getLong(1) } ?: 0) < 2) return emptyList()
        val people = db.query("SELECT id, handle FROM memberships WHERE team_id = :team AND removed_at IS NULL ORDER BY seq") { it.getString(1) to it.getString(2) }
        val names = mentionedNames(body, people.map { it.second })
        return people.filter { it.second.lowercase() in names }.map { it.first }
    }
}
