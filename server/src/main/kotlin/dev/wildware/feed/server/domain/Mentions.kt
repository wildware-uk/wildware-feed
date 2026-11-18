package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Ulid
import java.util.regex.Pattern

/**
 * `@name` in a message (spec/02 §7.7; TS `src/domain/mentions.ts`): code is ignored, names match
 * case-insensitively, and every live agent with a named name is mentioned.
 */
object Mentions {
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

    fun mentionedAgentIds(db: Database, body: String): List<String> {
        if ('@' !in body) return emptyList()
        val agents = db.query("SELECT id, name FROM agents WHERE revoked_at IS NULL ORDER BY seq") { it.getString(1) to it.getString(2) }
        val names = mentionedNames(body, agents.map { it.second }.distinct())
        return agents.filter { it.second.lowercase() in names }.map { it.first }
    }

    /** Replace the message's mentions with what its body says now. */
    fun record(db: Database, messageId: String, body: String) {
        val ids = mentionedAgentIds(db, body)
        db.transaction {
            db.update("DELETE FROM message_mentions WHERE message_id = ?", messageId)
            for (id in ids) {
                db.update(
                    "INSERT INTO message_mentions (id, message_id, agent_id) VALUES (?, ?, ?) ON CONFLICT (message_id, agent_id) DO NOTHING",
                    Ulid.next(), messageId, id,
                )
            }
        }
    }
}
