package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Ulid
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Named notes on a card (spec/02 §6.5, spec/05 §7.20-7.21; TS `src/domain/fields.ts`). */
class Fields(private val feed: Feed) {
    private val db get() = feed.db

    /** Every field on the card, oldest first. Unknown cards have none. */
    fun on(card: CardRef): List<FieldRow> =
        db.query("SELECT ${Rows.FIELD} FROM card_fields WHERE team_id = :team AND ${card.anchor} IN (?) ORDER BY seq", card.id) { FieldRow(Rows.field(it)) }

    /** Set (upsert by slug) or, with a null [value], remove. Returns the card's fields and whether one is set. */
    fun set(card: CardRef, name: String, value: String?, actor: String): Pair<List<FieldRow>, Boolean> = feed.op {
        val row = feed.cards.resolve(card)
        val clean = name.jsTrim()
        if (clean.isEmpty()) throw invalid("a field needs a name")
        if (clean.length > NAME_MAX) throw invalid("a field name may be at most $NAME_MAX characters")
        val slug = Slugs.field(clean)
        if (slug.isEmpty()) throw invalid("${JsText.quote(name)} has no letters or digits in it")

        if (value == null) {
            val removed = db.update("DELETE FROM card_fields WHERE team_id = :team AND ${card.anchor} = ? AND slug = ?", card.id, slug) > 0
            if (!removed) throw notFound("this card has no field called ${JsText.quote(clean)}")
            changed(card, row)
            return@op on(card) to false
        }

        val text = value.jsTrim()
        if (text.isEmpty()) throw invalid("a field needs a value, or null to remove it")
        if (text.length > VALUE_MAX) throw invalid("a field value may be at most $VALUE_MAX characters")
        val already = db.one("SELECT name, value FROM card_fields WHERE team_id = :team AND ${card.anchor} = ? AND slug = ?", card.id, slug) {
            it.getString(1) to it.getString(2)
        }
        if (already == null && on(card).size >= PER_CARD) throw invalid("a card may carry at most $PER_CARD fields")
        if (already != null && already.second == text && already.first == clean) return@op on(card) to true

        val now = feed.now()
        db.update(
            "INSERT INTO card_fields (team_id, id, ${card.anchor}, name, slug, value, author, created_at, updated_at) VALUES (:team, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (${card.anchor}, slug) WHERE ${card.anchor} IS NOT NULL " +
                "DO UPDATE SET name = excluded.name, value = excluded.value, author = excluded.author, updated_at = excluded.updated_at",
            Ulid.next(), card.id, clean, slug, text, actor, now, now,
        )
        changed(card, row)
        on(card) to true
    }

    private fun changed(card: CardRef, row: CardRow) {
        feed.cards.announce(card, row)
        feed.publish("fields.changed", buildJsonObject { put("projectId", row.projectId) })
    }

    companion object {
        const val NAME_MAX = 40
        const val VALUE_MAX = 500
        const val PER_CARD = 20
    }
}
