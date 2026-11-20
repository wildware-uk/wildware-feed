package dev.wildware.agents.app.sound

import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.BlockView
import dev.wildware.agents.FeedCard
import dev.wildware.agents.FeedState
import dev.wildware.agents.Follows
import dev.wildware.agents.FounderId
import dev.wildware.agents.MessageView
import dev.wildware.agents.NotifyLevel
import dev.wildware.agents.OutboxItem
import dev.wildware.agents.OutboxKind
import dev.wildware.agents.PairingView
import dev.wildware.agents.PostCard
import dev.wildware.agents.RequestCard
import dev.wildware.agents.ThreadLine
import dev.wildware.agents.UpdateCard
import dev.wildware.agents.app.desktop.DesktopNotice
import dev.wildware.agents.app.desktop.NotificationRules
import dev.wildware.agents.app.feed.TeamState

/** Everything [SoundRules] looks at, each time any of it changes. */
data class SoundSnapshot(
    val now: Long,
    /** The dashboard has been read; before that nothing is new. */
    val ready: Boolean,
    /** The live connection is open. Coming back to it replays what was missed, quietly. */
    val live: Boolean,
    val waiting: List<RequestCard>,
    val feed: FeedState,
    val outbox: List<OutboxItem> = emptyList(),
    /** The device's choice of alerts; null is all. */
    val prefs: AlertPrefs? = null,
    val slugOf: (String?) -> String? = { null },
    val joining: List<PairingView> = emptyList(),
    val follows: Follows = Follows(),
    val team: TeamState = TeamState(),
    /** The card whose thread the owner last opened. */
    val viewing: String? = null,
)

/**
 * Which sounds a change makes. It follows the notification rules ([NotificationRules]), so a sound
 * goes with each question, reply to the owner, mention and critical update a notification would
 * announce, and adds what only a sound says:
 *
 * - [Sound.Reply] for anyone else's line in the thread the owner has open;
 * - [Sound.Done] when someone else marks a card done, or an agent posts a success update;
 * - [Sound.Error] when an agent posts an error update, or the dashboard refuses something sent for good;
 * - [Sound.Tick] when someone else ticks a checklist line or votes in a poll;
 * - [Sound.Sent] when something the owner wrote (a post, reply, ticket or answer) reached the dashboard.
 *
 * The owner's own doings make no sound but [Sound.Sent]. Nothing already there when the dashboard
 * is first read sounds, nor older history loaded later; for [settleMillis] after the first read, a
 * reconnect, or [hush] (the app back in front), changes are taken in quietly, as they are what
 * was missed rather than what is happening. A muted project makes no sound but a critical one.
 */
class SoundRules(private val settleMillis: Long = 3_000, private val touchMillis: Long = 15_000) {
    private val notices = NotificationRules()
    private var baselined = false
    private var quietUntil = Long.MIN_VALUE
    private var wasLive = false
    private var watermark = Long.MIN_VALUE
    private val seenMessages = HashSet<String>()
    private val seenUpdates = HashSet<String>()
    private val statuses = HashMap<String, String?>()
    private val ticks = HashMap<String, Int>()
    private var outbox: Map<String, OutboxItem>? = null
    private val dropped = HashSet<String>()
    private val touched = HashMap<String, Long>()

    /** Takes in what comes over the next [settleMillis] quietly: the app is back and catching up. */
    fun hush(now: Long) {
        quietUntil = maxOf(quietUntil, now + settleMillis)
    }

    /** The owner changed [cardId] themselves (its state, a tick, a vote): what follows on it is theirs. */
    fun touched(cardId: String, now: Long) {
        touched[cardId] = now
    }

    /** The owner deleted [outboxId] unsent: its going is not a send. */
    fun dropped(outboxId: String) {
        dropped += outboxId
    }

    /**
     * The sounds [snapshot] makes, given the last. [systemPlays] is whether the system's own
     * notification for a notice already makes its sound, so the app doesn't play it twice.
     */
    fun next(snapshot: SoundSnapshot, systemPlays: (DesktopNotice) -> Boolean = { false }): List<Sound> {
        // What the owner sent, or had refused: their own doing, so never held back.
        val own = outboxSounds(snapshot.outbox)
        val now = snapshot.now
        if (snapshot.live && !wasLive && baselined) hush(now)
        wasLive = snapshot.live
        touched.entries.removeAll { now - it.value > touchMillis }

        val noticed = notices.next(snapshot.ready, snapshot.waiting, snapshot.feed, snapshot.prefs, snapshot.slugOf, snapshot.joining, snapshot.follows, snapshot.team)
        if (!snapshot.ready) return own
        if (!baselined) {
            baselined = true
            hush(now)
            take(snapshot, now, sounds = null)
            return own
        }
        val heard = ArrayList<Sound>()
        noticed.forEach { notice -> if (!systemPlays(notice)) notice.sound?.let(heard::add) }
        take(snapshot, now, heard)
        // Catching up is quiet, but for anything critical: that is what matters most.
        return own + if (now < quietUntil) heard.filter { it == Sound.Critical } else heard
    }

    /** Notes what [snapshot] holds, and adds to [sounds] (null: only note) what it changed. */
    private fun take(snapshot: SoundSnapshot, now: Long, sounds: MutableList<Sound>?) {
        val feed = snapshot.feed
        val me = snapshot.team.me?.id ?: FounderId
        fun mine(message: MessageView) = message.isOwner && (!snapshot.team.team || message.personId == me)
        fun muted(projectId: String?) = snapshot.follows.level(projectId) == NotifyLevel.Muted
        val newest = watermark

        for (card in feed.allCards) {
            if (card is UpdateCard && seenUpdates.add(card.id) && card.createdAt >= newest && sounds != null && !muted(card.projectId)) {
                // A critical update sounds with its notice.
                if (card.update.priority != AlertPrefs.Critical) when (card.update.level) {
                    "error" -> sounds += Sound.Error
                    "success" -> sounds += Sound.Done
                }
            }
            val known = card.id in statuses
            val before = statuses[card.id]
            statuses[card.id] = card.status
            if (known && card.status == "done" && before != "done" && sounds != null && !mineNow(card.id, now) && !muted(card.projectId)) sounds += Sound.Done
            for ((on, block) in blocksOf(card)) {
                val count = tickCount(block)
                val was = ticks.put("$on/${block.id}", count)
                val theirs = !mineNow(card.id, now) && !mineNow(on, now)
                if (was != null && count > was && sounds != null && theirs && !muted(card.projectId)) sounds += Sound.Tick
            }
        }
        val lines = feed.allCards.flatMap { linesOf(it) } + (snapshot.waiting + feed.waiting).flatMap { it.discussion }
        val open = snapshot.viewing?.let { id -> threadOf(id, feed, snapshot.waiting) }.orEmpty().mapTo(HashSet()) { it.message.id }
        for (line in lines) {
            val message = line.message
            if (!seenMessages.add(message.id)) continue
            if (sounds == null || message.createdAt < newest || mine(message)) continue
            if (message.agentId == null && !message.isOwner) continue
            if (message.id in open) sounds += Sound.Reply
        }
        val latest = maxOf(
            lines.maxOfOrNull { it.message.createdAt } ?: Long.MIN_VALUE,
            feed.allCards.filterIsInstance<UpdateCard>().maxOfOrNull { it.createdAt } ?: Long.MIN_VALUE,
        )
        watermark = maxOf(watermark, latest)
    }

    private fun mineNow(cardId: String, now: Long): Boolean = touched[cardId]?.let { now - it <= touchMillis } == true

    /** A send that went (it left the outbox, not refused, not deleted), or one refused for good. */
    private fun outboxSounds(items: List<OutboxItem>): List<Sound> {
        val after = items.associateBy { it.id }
        val before = outbox
        outbox = after
        if (before == null) return emptyList()
        val sounds = mutableListOf<Sound>()
        for ((id, item) in before) {
            if (id in after) continue
            if (dropped.remove(id)) continue
            if (!item.gaveUp && item.kind in Spoken) sounds += Sound.Sent
        }
        for ((id, item) in after) {
            if (item.gaveUp && before[id]?.gaveUp == false) sounds += Sound.Error
        }
        return sounds
    }

    private fun linesOf(card: FeedCard): List<ThreadLine> = when (card) {
        is PostCard -> listOf(card.post) + card.replies
        is UpdateCard -> card.replies
    }

    private fun threadOf(id: String, feed: FeedState, waiting: List<RequestCard>): List<ThreadLine>? =
        feed.card(id)?.let(::linesOf) ?: (waiting + feed.waiting).firstOrNull { it.id == id }?.discussion

    /** Each block on [card], by the id of what it is drawn on: the card itself or one of its lines. */
    private fun blocksOf(card: FeedCard): List<Pair<String, BlockView>> {
        val own = if (card is UpdateCard) card.update.blocks.map { card.id to it } else emptyList()
        return own + linesOf(card).flatMap { line -> line.blocks.map { line.message.id to it } }
    }

    /** Lines ticked and votes cast: one more of either is a tick. */
    private fun tickCount(block: BlockView): Int = when (block.type) {
        BlockView.Checklist -> block.items.count { it.done }
        BlockView.Poll -> block.options.sumOf { it.votes }
        else -> 0
    }

    private companion object {
        /** What the owner says, rather than does: these make [Sound.Sent] when they go. */
        val Spoken = setOf(OutboxKind.Message, OutboxKind.Ticket, OutboxKind.Answer, OutboxKind.RequestAnswer)
    }
}
