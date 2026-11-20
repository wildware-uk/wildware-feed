package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.BlockShapes.Actor
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Blocks on cards (migration 057, docs/BLOCKS.md): stored, changed in place, ticked and voted on.
 *
 * Every write reads the block with its `rev` and writes only if the rev is still the one it read,
 * trying again otherwise. One server already runs writes one at a time ([Feed.op]); the rev is
 * what keeps two servers on one Postgres from losing each other's change.
 */
class Blocks(private val feed: Feed) {
    private val db get() = feed.db

    /** The blocks a post brings, checked; nothing is written. */
    fun check(raw: List<JsonElement>?, agentId: String?): List<JsonObject> {
        if (raw.isNullOrEmpty()) return emptyList()
        val actor = agentId?.let { Actor.agent(it) } ?: throw invalid("only an agent's post carries blocks")
        return BlockShapes.normalizeAll(raw, actor, feed.now())
    }

    /** Write [blocks] (from [check]) onto a new [card]. */
    fun attach(card: CardRef, blocks: List<JsonObject>) {
        val at = feed.now()
        for (block in blocks) insert(card, block, at)
    }

    private fun insert(card: CardRef, block: JsonObject, at: Long) {
        try {
            db.update(
                "INSERT INTO card_blocks (${card.anchor}, block_id, type, data, rev, created_at, updated_at) VALUES (?, ?, ?, ?, 1, ?, ?)",
                card.id, block["id"].jsString, block["type"].jsString, Wire.encode(block), at, at,
            )
        } catch (e: java.sql.SQLException) {
            // Another server added a block with this id a moment ago (the unique index says so).
            if (find(card, block["id"].jsString!!) != null) throw conflict("that card already has a block \"${block["id"].jsString}\"")
            throw e
        }
    }

    private val reads get() = BlockReads(db, feed.clock)

    fun on(card: CardRef): List<StoredBlock> = reads.on(card)

    fun viewsOn(card: CardRef): List<JsonObject> = reads.viewsOn(card)

    /**
     * An agent changes one of its own blocks: [patch] merges into it, [block] replaces it (or adds
     * it when the card has none with that id), [remove] takes it off. Returns the block as it now
     * reads, or null once removed.
     */
    fun change(card: CardRef, blockId: String, agentId: String, patch: JsonElement?, block: JsonElement?, remove: Boolean): JsonObject? = feed.op {
        val given = listOf(patch != null, block != null, remove).count { it }
        if (given != 1) throw invalid("give one of patch, block or remove")
        val row = feed.cards.resolve(card)
        if (author(card) != "agent:$agentId") throw invalid("only the agent that posted that ${card.kind} can change its blocks")
        val actor = Actor.agent(agentId)
        if (block != null) {
            val written = BlockShapes.normalize(block, "block", actor, feed.now(), previous = find(card, blockId)?.data)
            if (written["id"].jsString != blockId) throw invalid("block.id must be \"$blockId\", the block_id you named")
        }
        val stored = find(card, blockId)
        if (stored == null) {
            if (block == null) throw notFound("no block \"$blockId\" on that ${card.kind}")
            if (on(card).size >= BlockShapes.BLOCKS_MAX) throw invalid("a card may carry at most ${BlockShapes.BLOCKS_MAX} blocks")
            insert(card, BlockShapes.normalize(block, "block", actor, feed.now()), feed.now())
            val added = find(card, blockId)!!
            announce(card, row, added, "agent:$agentId", buildJsonObject { put("action", "add") })
            return@op viewsOn(card).first { it["id"].jsString == blockId }
        }
        if (remove) {
            db.update("DELETE FROM card_blocks WHERE seq = ?", stored.seq)
            announce(card, row, stored, "agent:$agentId", buildJsonObject { put("action", "remove") }, rev = stored.rev + 1)
            return@op null
        }
        val saved = write(card, blockId) { current ->
            if (patch != null) {
                BlockShapes.applyPatch(current.data, patch, actor, feed.now())
            } else {
                BlockShapes.normalize(block, "block", actor, feed.now(), previous = current.data)
            }
        }
        announce(card, row, saved, "agent:$agentId", buildJsonObject { put("action", if (patch != null) "patch" else "replace") })
        viewsOn(card).first { it["id"].jsString == blockId }
    }

    /** A person ticks or unticks a checklist line. */
    fun tick(card: CardRef, blockId: String, itemId: String, done: Boolean, personId: String): JsonObject = feed.op {
        val row = feed.cards.resolve(card)
        if (find(card, blockId) == null) throw notFound("no block \"$blockId\" on that ${card.kind}")
        val current = find(card, blockId)!!
        val was = (current.data["items"] as? JsonArray)?.firstOrNull { (it as JsonObject)["id"].jsString == itemId }?.let { (it as JsonObject)["done"].jsBoolean }
        // Already as asked: nothing to write, and nobody to tell.
        if (was == done) return@op viewsOn(card).first { it["id"].jsString == blockId }
        var changed = false
        val saved = write(card, blockId) { latest ->
            val now = (latest.data["items"] as? JsonArray)?.firstOrNull { (it as JsonObject)["id"].jsString == itemId }?.let { (it as JsonObject)["done"].jsBoolean }
            changed = now != done
            BlockShapes.check(latest.data, itemId, done, Actor.person(personId), feed.now())
        }
        if (changed) {
            val text = (saved.data["items"] as JsonArray).map { it as JsonObject }.first { it["id"].jsString == itemId }["text"]!!
            announce(card, row, saved, Messages.HUMAN, buildJsonObject {
                put("action", "check"); put("item", itemId); put("itemText", text); put("done", done); put("userId", personId)
            })
        }
        viewsOn(card).first { it["id"].jsString == blockId }
    }

    /** A person votes on a poll; [options] empty takes their vote back. */
    fun vote(card: CardRef, blockId: String, options: List<String>, personId: String): JsonObject = feed.op {
        val row = feed.cards.resolve(card)
        val current = find(card, blockId) ?: throw notFound("no block \"$blockId\" on that ${card.kind}")
        val mine = (current.data["votes"] as? JsonArray)?.map { it as JsonObject }?.firstOrNull { it["by"].jsString == "person:$personId" }
            ?.let { v -> (v["options"] as JsonArray).mapNotNull { it.jsString }.toSet() } ?: emptySet()
        // The same vote again: checked (a closed poll still says so), but nothing changes.
        if (mine == options.toSet()) {
            BlockShapes.vote(current.data, options, Actor.person(personId), feed.now())
            return@op viewsOn(card).first { it["id"].jsString == blockId }
        }
        val saved = write(card, blockId) { latest -> BlockShapes.vote(latest.data, options, Actor.person(personId), feed.now()) }
        val labels = (saved.data["options"] as JsonArray).map { it as JsonObject }.filter { it["id"].jsString in options }
        announce(card, row, saved, Messages.HUMAN, buildJsonObject {
            put("action", "vote")
            put("options", JsonArray(labels.map { o -> buildJsonObject { put("id", o["id"]!!); put("label", o["label"]!!) } }))
            put("userId", personId)
        })
        viewsOn(card).first { it["id"].jsString == blockId }
    }

    /** Who posted [card]: `agent:<id>`, or `human` for a person's message. */
    fun author(card: CardRef): String? = when (card.kind) {
        "update" -> db.one("SELECT agent_id FROM updates WHERE id = ?", card.id) { "agent:" + it.getString(1) }
        "request" -> db.one("SELECT agent_id FROM approvals WHERE id = ?", card.id) { "agent:" + it.getString(1) }
        else -> db.one("SELECT author FROM messages WHERE id = ?", card.id) { it.getString(1) }
    }

    private fun find(card: CardRef, blockId: String): StoredBlock? = db.one(
        "SELECT seq, block_id, data, rev, updated_at FROM card_blocks WHERE ${card.anchor} = ? AND block_id = ?", card.id, blockId,
    ) { StoredBlock(it.getLong(1), card, it.getString(2), Js.parse(it.getString(3)) as JsonObject, it.getLong(4), it.getLong(5)) }

    /** Read, change, write if nobody else wrote in between; otherwise read again. */
    private fun write(card: CardRef, blockId: String, change: (StoredBlock) -> JsonObject): StoredBlock {
        repeat(WRITE_TRIES) { attempt ->
            // Another server wrote in between: give it a moment, then read again.
            if (attempt > 0) Thread.sleep((attempt * 2L + (0..3).random()).coerceAtMost(25))
            val current = find(card, blockId) ?: throw notFound("no block \"$blockId\" on that ${card.kind}")
            val next = change(current)
            val at = feed.now()
            val written = db.update(
                "UPDATE card_blocks SET data = ?, type = ?, rev = rev + 1, updated_at = ? WHERE seq = ? AND rev = ?",
                Wire.encode(next), next["type"].jsString, at, current.seq, current.rev,
            )
            if (written == 1) return StoredBlock(current.seq, card, blockId, next, current.rev + 1, at)
        }
        throw conflict("that block is changing too fast; try again")
    }

    private fun announce(card: CardRef, row: CardRow, block: StoredBlock, actor: String, extra: JsonObject, rev: Long = block.rev) {
        feed.publish("block.updated", buildJsonObject {
            put("card", card.json)
            put("projectId", row.projectId)
            put("blockId", block.blockId)
            put("blockType", block.data["type"]!!)
            block.data["title"]?.let { put("blockTitle", it) }
            put("rev", rev)
            put("actor", actor)
            for ((k, v) in extra) put(k, v)
        })
    }

    companion object {
        private const val WRITE_TRIES = 40

    }
}

/** One stored block. */
class StoredBlock(val seq: Long, val card: CardRef, val blockId: String, val data: JsonObject, val rev: Long, val updatedAt: Long)

/** Blocks as everyone reads them: the stored JSON with names filled in, its rev and when it last changed. */
class BlockReads(private val db: Database, private val clock: () -> Long) {
    fun on(card: CardRef): List<StoredBlock> = onMany(card.kind, listOf(card.id))[card.id].orEmpty()

    /** The blocks on many cards of one kind, by card id, in the order they were given. */
    fun onMany(kind: String, ids: List<String>): Map<String, List<StoredBlock>> {
        if (ids.isEmpty()) return emptyMap()
        val anchor = CardRef(kind, "").anchor
        val out = LinkedHashMap<String, MutableList<StoredBlock>>()
        for (chunk in ids.distinct().chunked(CHUNK)) {
            db.query(
                "SELECT seq, $anchor, block_id, data, rev, updated_at FROM card_blocks WHERE $anchor IN (${marks(chunk)}) ORDER BY seq",
                *chunk.toTypedArray(),
            ) { StoredBlock(it.getLong(1), CardRef(kind, it.getString(2)), it.getString(3), Js.parse(it.getString(4)) as JsonObject, it.getLong(5), it.getLong(6)) }
                .forEach { out.getOrPut(it.card.id) { mutableListOf() } += it }
        }
        return out
    }

    /** What everyone reads: each card's blocks with names filled in, by card id. Cards without blocks are left out. */
    fun views(kind: String, ids: List<String>): Map<String, List<JsonObject>> {
        val byCard = onMany(kind, ids)
        if (byCard.isEmpty()) return emptyMap()
        val names = names(byCard.values.flatten().flatMap { actors(it.data) })
        val now = clock()
        return byCard.mapValues { (_, blocks) -> blocks.map { view(it, names, now) } }
    }

    fun viewsOn(card: CardRef): List<JsonObject> = views(card.kind, listOf(card.id))[card.id].orEmpty()

    // ---- reading --------------------------------------------------------------------------------

    private fun actors(block: JsonObject): List<String> = buildList {
        (block["items"] as? JsonArray)?.forEach { (it as JsonObject)["done_by"].jsString?.let(::add) }
        (block["votes"] as? JsonArray)?.forEach { (it as JsonObject)["by"].jsString?.let(::add) }
    }

    /** Each actor as `{kind, id, name}`. */
    private fun names(actors: List<String>): Map<String, JsonObject> {
        val distinct = actors.distinct()
        val agents = distinct.filter { it.startsWith(AGENT) }.map { it.removePrefix(AGENT) }
        val people = distinct.filter { it.startsWith(PERSON) }.map { it.removePrefix(PERSON) }
        val agentNames = agents.chunked(CHUNK).flatMap { chunk ->
            db.query("SELECT id, name FROM agents WHERE id IN (${marks(chunk)})", *chunk.toTypedArray()) { it.getString(1) to it.getString(2) }
        }.toMap()
        val personNames = people.chunked(CHUNK).flatMap { chunk ->
            db.query(
                "SELECT m.id, u.name FROM memberships AS m JOIN users AS u ON u.id = m.user_id WHERE m.id IN (${marks(chunk)})", *chunk.toTypedArray(),
            ) { it.getString(1) to it.getString(2) }
        }.toMap()
        return distinct.associateWith { actor ->
            val agent = actor.startsWith(AGENT)
            val id = actor.substringAfter(':')
            buildJsonObject {
                put("kind", if (agent) "agent" else "person")
                put("id", id)
                put("name", if (agent) agentNames[id] else personNames[id])
            }
        }
    }

    private fun view(stored: StoredBlock, names: Map<String, JsonObject>, now: Long): JsonObject {
        val data = stored.data
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in data) {
            when (key) {
                "items" -> out[key] = JsonArray((value as JsonArray).map { item ->
                    item as JsonObject
                    val by = item["done_by"].jsString
                    if (by == null) item else JsonObject(item + ("done_by" to (names[by] ?: JsonNull)))
                })
                "votes" -> {}
                "options" -> {
                    val votes = (data["votes"] as? JsonArray).orEmpty().map { it as JsonObject }
                    out[key] = JsonArray(value.let { it as JsonArray }.map { option ->
                        option as JsonObject
                        val id = option["id"].jsString
                        val voters = votes.filter { v -> (v["options"] as JsonArray).any { it.jsString == id } }.mapNotNull { names[it["by"].jsString] }
                        JsonObject(option + ("votes" to JsonPrimitive(voters.size)) + ("voters" to JsonArray(voters)))
                    })
                    out["total_voters"] = JsonPrimitive(votes.size)
                }
                "closed" -> out[key] = JsonPrimitive(BlockShapes.pollClosed(data, now))
                else -> out[key] = value
            }
        }
        out["rev"] = JsonPrimitive(stored.rev)
        out["updated_at"] = JsonPrimitive(Wire.isoOf(stored.updatedAt))
        return JsonObject(out)
    }

    private companion object {
        const val AGENT = "agent:"
        const val PERSON = "person:"
        const val CHUNK = 500

        fun marks(ids: List<*>) = ids.joinToString(", ") { "?" }
        fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}

