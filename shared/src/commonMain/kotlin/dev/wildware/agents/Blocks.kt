package dev.wildware.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Blocks (docs/BLOCKS.md): typed pieces of a card the apps draw themselves, changed in place.
 * The server sends each block as one object; which fields it fills depends on its [BlockView.type].
 */

/** Who ticked a line or voted: a person of the team, or an agent. */
@Serializable
data class BlockActor(val kind: String, val id: String, val name: String? = null)

/** One block on a card. Only the fields of its [type] are filled; the rest keep their defaults. */
@Serializable
data class BlockView(
    val id: String,
    /** checklist, steps, diff, chart, table, log or poll; anything else is drawn as unsupported. */
    val type: String,
    val title: String? = null,
    /** Counts its writes: a higher one is newer. */
    val rev: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
    // checklist
    val items: List<ChecklistItem> = emptyList(),
    // steps
    val stages: List<StepStage> = emptyList(),
    val progress: Double? = null,
    val eta: String? = null,
    // diff
    val files: List<DiffFileView> = emptyList(),
    // chart
    val kind: String? = null,
    val series: List<ChartSeries> = emptyList(),
    @SerialName("x_label") val xLabel: String? = null,
    @SerialName("y_label") val yLabel: String? = null,
    val unit: String? = null,
    // table
    val columns: List<TableColumn> = emptyList(),
    val rows: List<List<JsonElement>> = emptyList(),
    val limit: Int = 10,
    // log
    val lines: List<String> = emptyList(),
    val dropped: Long = 0,
    val highlight: String? = null,
    val tail: Int = 5,
    // poll
    val question: String? = null,
    val options: List<PollOption> = emptyList(),
    val multi: Boolean = false,
    @SerialName("closes_at") val closesAt: String? = null,
    val closed: Boolean = false,
    @SerialName("total_voters") val totalVoters: Int = 0,
) {
    companion object {
        const val Checklist = "checklist"
        const val Steps = "steps"
        const val Diff = "diff"
        const val Chart = "chart"
        const val Table = "table"
        const val Log = "log"
        const val Poll = "poll"
    }
}

/** This poll with [me]'s vote moved to [chosen], before the server says so. */
fun BlockView.withVote(chosen: List<String>, me: String?): BlockView {
    val who = me ?: FounderId
    val wasVoting = options.any { o -> o.voters.any { it.kind == "person" && it.id == who } }
    val mine = BlockActor("person", who)
    val next = options.map { option ->
        val had = option.voters.any { it.kind == "person" && it.id == who }
        val wants = option.id in chosen
        when {
            had && !wants -> option.copy(votes = option.votes - 1, voters = option.voters.filterNot { it.kind == "person" && it.id == who })
            !had && wants -> option.copy(votes = option.votes + 1, voters = option.voters + mine)
            else -> option
        }
    }
    val total = totalVoters + (if (chosen.isNotEmpty() && !wasVoting) 1 else 0) - (if (chosen.isEmpty() && wasVoting) 1 else 0)
    return copy(options = next, totalVoters = total)
}

@Serializable
data class ChecklistItem(
    val id: String,
    val text: String,
    val done: Boolean = false,
    /** Who the line waits on: "you", an agent's name or a person's. */
    val owner: String? = null,
    @SerialName("done_by") val doneBy: BlockActor? = null,
    @SerialName("done_at") val doneAt: String? = null,
)

@Serializable
data class StepStage(
    val id: String,
    val label: String,
    /** pending, running, done, failed or skipped. */
    val state: String = "pending",
    val detail: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
)

@Serializable
data class DiffFileView(
    val path: String,
    @SerialName("old_path") val oldPath: String? = null,
    /** added, deleted, modified or renamed. */
    val status: String = "modified",
    val additions: Int = 0,
    val deletions: Int = 0,
    val binary: Boolean = false,
    val hunks: List<DiffHunkView> = emptyList(),
)

/** One hunk: its `@@` line, then lines whose first character says what they are (space, + or -). */
@Serializable
data class DiffHunkView(val header: String, val lines: List<String> = emptyList())

/** A line of a chart: each point is `[x, y]`, x a number or a label. */
@Serializable
data class ChartSeries(val id: String, val label: String? = null, val points: List<List<JsonPrimitive>> = emptyList())

@Serializable
data class TableColumn(val label: String, val kind: String = "text")

@Serializable
data class PollOption(val id: String, val label: String, val votes: Int = 0, val voters: List<BlockActor> = emptyList())

@Serializable
internal data class BlocksBody(val blocks: List<BlockView> = emptyList())

@Serializable
internal data class BlockBody(val block: BlockView)

/**
 * The newest blocks of each card that changed since its card was read. A `block.updated` event
 * re-reads only that card's blocks, small, rather than the whole page; a tick or a vote puts the
 * server's answer here straight away. What is here wins over the blocks on the card as read, since
 * it is always the newest: every change to a card's blocks comes with a `block.updated`.
 */
internal class BlocksOverlay(
    private val client: DashboardClient,
    private val scope: CoroutineScope,
    private val onChange: () -> Unit,
    /** The blocks on a card as the open feeds and questions hold it, or null when nothing shows it. */
    private val held: (CardRef) -> List<BlockView>? = { null },
) {
    private val newest = LinkedHashMap<CardRef, List<BlockView>>()
    private val reading = HashSet<CardRef>()
    private val again = HashSet<CardRef>()
    private val waiting = HashSet<CardRef>()

    /** What a person did and is not yet answered: drawn as done straight away. */
    private val pending = LinkedHashMap<Pending, (BlockView) -> BlockView>()

    /** One thing a person did to a block: a line ticked ("item:<id>"), or their vote ("vote"). */
    data class Pending(val card: CardRef, val blockId: String, val part: String)

    fun receive(event: StreamEvent) {
        if (event.type == StreamEvent.Resync) {
            // Every reader reads again, and its blocks with it.
            newest.clear()
            onChange()
            return
        }
        if (event.type != EventType) return
        val payload = event.payload ?: return
        val card = payload["card"] as? JsonObject ?: return
        val kind = card.string("kind") ?: return
        val id = card.string("id") ?: return
        val ref = CardRef(kind, id)
        // A card nothing on screen shows is read when it is, with its blocks.
        val current = newest[ref] ?: held(ref) ?: return
        // Already as new as the event: a replay, or the answer to our own tick.
        val rev = payload.string("rev")?.toLongOrNull()
        val known = current.firstOrNull { it.id == payload.string("blockId") }
        if (rev != null && known != null && known.rev >= rev) return
        soon(ref)
    }

    /** One read for a burst of changes on a card: an agent appending a line a second costs one read, not ten. */
    private fun soon(card: CardRef) {
        if (!waiting.add(card)) return
        scope.launch {
            delay(BurstMillis)
            waiting -= card
            read(card)
        }
    }

    /** One read per card at a time; an event during it reads once more after. */
    private fun read(card: CardRef) {
        if (!reading.add(card)) {
            again += card
            return
        }
        scope.launch {
            try {
                val blocks = client.blocks(card)
                put(card, blocks)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Keep what is on screen; the next event or read catches up.
            } finally {
                reading -= card
                if (again.remove(card)) read(card)
            }
        }
    }

    private fun put(card: CardRef, blocks: List<BlockView>) {
        newest.remove(card)
        newest[card] = blocks
        while (newest.size > Memory) newest.remove(newest.keys.first())
        onChange()
    }

    /** One block, as the server answered a tick or vote with it. */
    fun answered(card: CardRef, block: BlockView, base: List<BlockView>) {
        val current = newest[card] ?: base
        val known = current.firstOrNull { it.id == block.id }
        if (known != null && known.rev > block.rev) return
        put(card, if (known == null) current + block else current.map { if (it.id == block.id) block else it })
    }

    fun expect(card: CardRef, blockId: String, part: String, change: (BlockView) -> BlockView) {
        // Two quick ticks on one checklist are two things waiting: each settles only itself.
        pending.remove(Pending(card, blockId, part))
        pending[Pending(card, blockId, part)] = change
        onChange()
    }

    fun settle(card: CardRef, blockId: String, part: String) {
        pending.remove(Pending(card, blockId, part))
        onChange()
    }

    /** [read] (the card's blocks as read with it), with anything newer put over it. */
    fun on(card: CardRef, read: List<BlockView>): List<BlockView> {
        val base = newest[card] ?: read
        if (pending.isEmpty()) return base
        return base.map { block ->
            pending.entries.fold(block) { shown, (key, change) -> if (key.card == card && key.blockId == block.id) change(shown) else shown }
        }
    }

    companion object {
        const val EventType = "block.updated"
        private const val Memory = 500
        /** How long a card's changes are gathered before its blocks are read again. */
        const val BurstMillis = 300L
    }
}
