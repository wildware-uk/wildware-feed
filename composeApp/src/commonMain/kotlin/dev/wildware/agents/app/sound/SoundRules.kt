package dev.wildware.agents.app.sound

import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.FeedState
import dev.wildware.agents.FounderId
import dev.wildware.agents.Follows
import dev.wildware.agents.NotifyLevel
import dev.wildware.agents.OutboxItem
import dev.wildware.agents.OutboxKind
import dev.wildware.agents.PostCard
import dev.wildware.agents.PairingView
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
)

/**
 * Which sounds a change makes. Only these moments sound:
 *
 * - [Sound.Question] for a question to the owner, [Sound.Critical] for one marked critical, and
 *   [Sound.Permission] for an agent asking to join (each with its notification);
 * - [Sound.Message] for a message a notification announces: a reply to the owner, or a line in a
 *   thread they follow; [Sound.Mention] when it names them;
 * - the grid sound ([Sound.grid]) for a new update, by its priority and level, where the device's
 *   choice of alerts lets it through and its project is followed in full ([NotifyLevel.All]); a
 *   critical update's comes with its notification;
 * - [Sound.Done] when someone else marks a card done;
 * - [Sound.Reaction] when someone else reacts to the owner's line;
 * - for what the owner did: [Sound.Answered] as they answer a question, [Sound.Uploaded] when what
 *   they sent with files reaches the dashboard, [Sound.Error] when the dashboard refuses it for good.
 *
 * Other lines arriving, ticks, votes and plain sends make no sound, and nothing but critical sounds
 * and the owner's own feedback plays while they use the app ([SoundBoard]). Nothing already there when the dashboard is first read sounds, nor older history loaded later; for [settleMillis] after the first read, a
 * reconnect, or [hush] (the app back in front), changes are taken in quietly, as they are what
 * was missed rather than what is happening. A muted project makes no sound but a critical one.
 */
class SoundRules(private val settleMillis: Long = 3_000, private val touchMillis: Long = 15_000) {
    private val notices = NotificationRules()
    private var baselined = false
    private var quietUntil = Long.MIN_VALUE
    private var wasLive = false
    private var watermark = Long.MIN_VALUE
    private val seenUpdates = HashSet<String>()
    private val seenReactions = HashSet<String>()
    private var reactionWatermark = Long.MIN_VALUE
    private val statuses = HashMap<String, String?>()
    private var outbox: Map<String, OutboxItem>? = null
    private val touched = HashMap<String, Long>()

    /** Takes in what comes over the next [settleMillis] quietly: the app is back and catching up. */
    fun hush(now: Long) {
        quietUntil = maxOf(quietUntil, now + settleMillis)
    }

    /** The owner changed [cardId]'s state themselves: its going done is theirs. */
    fun touched(cardId: String, now: Long) {
        touched[cardId] = now
    }

    /**
     * The sounds [snapshot] makes, given the last. [systemPlays] is whether the system's own
     * notification for a notice already makes its sound, so the app doesn't play it twice.
     */
    fun next(snapshot: SoundSnapshot, systemPlays: (DesktopNotice) -> Boolean = { false }): List<Sound> {
        // What the owner sent and had refused: their own doing, so never held back.
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
        fun muted(projectId: String?) = snapshot.follows.level(projectId) == NotifyLevel.Muted
        val newest = watermark
        val cards = snapshot.feed.allCards
        for (card in cards) {
            if (card is UpdateCard && seenUpdates.add(card.id) && card.createdAt >= newest && sounds != null && !muted(card.projectId)) {
                // A critical update sounds with its notice.
                val update = card.update
                if (update.priority != AlertPrefs.Critical &&
                    snapshot.follows.level(card.projectId) == NotifyLevel.All &&
                    AlertPrefs.allows(snapshot.prefs, AlertPrefs.Update, update.level, update.priority, update.agentId, snapshot.slugOf(card.projectId))
                ) {
                    sounds += Sound.grid(update.priority, update.level)
                }
            }
            val known = card.id in statuses
            val before = statuses[card.id]
            statuses[card.id] = card.status
            if (known && card.status == "done" && before != "done" && sounds != null && !mineNow(card.id, now) && !muted(card.projectId)) sounds += Sound.Done
        }
        watermark = maxOf(watermark, cards.filterIsInstance<UpdateCard>().maxOfOrNull { it.createdAt } ?: Long.MIN_VALUE)
        reactions(snapshot, sounds)
    }

    /** Someone else's reaction to a line the owner wrote: one each, never one from history loaded later. */
    private fun reactions(snapshot: SoundSnapshot, sounds: MutableList<Sound>?) {
        val team = snapshot.team
        val me = team.me?.id ?: FounderId
        fun mine(personId: String?, isOwner: Boolean) = isOwner && (!team.team || personId == me)
        val lines = snapshot.feed.allCards.flatMap { card -> (if (card is PostCard) listOf(card.post) else emptyList<ThreadLine>()) + card.replies } +
            snapshot.waiting.flatMap { it.discussion }
        val newest = reactionWatermark
        var latest = newest
        for (line in lines) {
            val message = line.message
            val ours = mine(message.personId, message.isOwner)
            for (reaction in line.reactions) {
                if (!seenReactions.add(reaction.id)) continue
                latest = maxOf(latest, reaction.createdAt)
                if (sounds == null || !ours || reaction.createdAt < newest) continue
                if (mine(reaction.personId, reaction.isOwner)) continue
                if (snapshot.follows.level(message.projectId) == NotifyLevel.Muted) continue
                sounds += Sound.Reaction
            }
        }
        reactionWatermark = latest
    }

    private fun mineNow(cardId: String, now: Long): Boolean = touched[cardId]?.let { now - it <= touchMillis } == true

    /**
     * What the owner sent, as it goes: an answer as they give it, files once they reach the
     * dashboard, and a send the dashboard refused for good.
     */
    private fun outboxSounds(items: List<OutboxItem>): List<Sound> {
        val after = items.associateBy { it.id }
        val before = outbox
        outbox = after
        if (before == null) return emptyList()
        val refused = after.filter { (id, item) -> item.gaveUp && before[id]?.gaveUp == false }.map { Sound.Error }
        val answered = after.filter { (id, item) -> id !in before && (item.kind == OutboxKind.Answer || item.kind == OutboxKind.RequestAnswer) }.map { Sound.Answered }
        // Sending it claims it, and a failed send gives the claim back: gone while claimed is sent.
        val uploaded = before.filter { (id, item) -> id !in after && item.files.isNotEmpty() && item.claimedUntilMillis > 0 && !item.gaveUp }.map { Sound.Uploaded }
        return refused + answered + uploaded
    }
}
