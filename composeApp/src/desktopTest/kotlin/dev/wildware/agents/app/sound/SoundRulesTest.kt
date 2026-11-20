package dev.wildware.agents.app.sound

import dev.wildware.agents.BlockView
import dev.wildware.agents.ChecklistItem
import dev.wildware.agents.FeedCard
import dev.wildware.agents.FeedState
import dev.wildware.agents.Follows
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.MessageView
import dev.wildware.agents.NotifyLevel
import dev.wildware.agents.OutboxItem
import dev.wildware.agents.OutboxKind
import dev.wildware.agents.PostCard
import dev.wildware.agents.RequestCard
import dev.wildware.agents.RequestView
import dev.wildware.agents.StreamStatus
import dev.wildware.agents.ThreadLine
import dev.wildware.agents.UpdateCard
import dev.wildware.agents.UpdateView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which moment makes which sound, and what stays quiet. No audio: the player is a fake. */
class SoundRulesTest {
    private fun agent(id: String, at: Long, updateId: String? = null, replyTo: String? = null) =
        MessageView(id = id, author = "agent:a1", body = "hi", createdAt = at, updateId = updateId, replyTo = replyTo, projectId = "p1")

    private fun owner(id: String, at: Long, updateId: String? = null) =
        MessageView(id = id, author = "human", body = "mine", createdAt = at, updateId = updateId, projectId = "p1")

    private fun line(message: MessageView, blocks: List<BlockView> = emptyList()) =
        ThreadLine(message, "Builder", null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), blocks = blocks)

    private fun update(
        id: String,
        at: Long = 1_000,
        level: String = "info",
        priority: String? = null,
        status: String? = null,
        replies: List<ThreadLine> = emptyList(),
        blocks: List<BlockView> = emptyList(),
    ) = UpdateCard(
        UpdateView(id = id, seq = 1, projectId = "p1", agentId = "a1", body = "Shipped", level = level, priority = priority, status = status, createdAt = at, blocks = blocks),
        "Builder", "Mega", false, at, replies, 0, emptyList(), emptyList(), emptyList(),
    )

    private fun question(id: String, priority: String = "medium") = RequestCard(
        RequestView(id = id, agentId = "a1", projectId = "p1", kind = "confirm", question = "Ship it?", priority = priority),
        "Builder", "Mega", emptyList(), 0, false, emptyList(), emptyList(),
    )

    private fun feed(vararg cards: FeedCard) =
        FeedState(null, null, StreamStatus.Live, true, false, false, emptyList(), emptyList(), cards.toList(), 0, false, emptyList())

    private fun checklist(done: Int) = BlockView("b1", BlockView.Checklist, items = List(3) { ChecklistItem("i$it", "Step $it", done = it < done) })

    private fun item(id: String, kind: OutboxKind = OutboxKind.Message, gaveUp: Boolean = false) = OutboxItem(id, 0, kind, "To Mega", gaveUp = gaveUp)

    /** A clock past the settling after the first read, so changes sound. */
    private var now = 0L

    private fun snapshot(
        feed: FeedState = feed(),
        waiting: List<RequestCard> = emptyList(),
        outbox: List<OutboxItem> = emptyList(),
        live: Boolean = true,
        viewing: String? = null,
        follows: Follows = Follows(),
    ) = SoundSnapshot(now, ready = true, live = live, waiting = waiting, feed = feed, outbox = outbox, viewing = viewing, follows = follows)

    /** Reads [first] as the dashboard at sign-in, then lets the settling pass. */
    private fun rules(first: SoundSnapshot = snapshot()): SoundRules {
        val rules = SoundRules()
        assertEquals(emptyList(), rules.next(first))
        now += 10_000
        return rules
    }

    @Test
    fun `nothing there at the first read sounds`() {
        val rules = SoundRules()
        val busy = snapshot(feed(update("u1", level = "error"), update("u2", status = "done")), waiting = listOf(question("r1")))
        assertEquals(emptyList(), rules.next(busy))
        now += 10_000
        assertEquals(emptyList(), rules.next(busy.copy(now = now)))
    }

    @Test
    fun `a new question sounds the question and a critical one the critical sound`() {
        val rules = rules()
        assertEquals(listOf(Sound.Question), rules.next(snapshot(waiting = listOf(question("r1")))))
        assertEquals(listOf(Sound.Critical), rules.next(snapshot(waiting = listOf(question("r1"), question("r2", "critical")))))
    }

    @Test
    fun `an error update sounds the error and a success update the done sound`() {
        val rules = rules(snapshot(feed(update("u0"))))
        assertEquals(listOf(Sound.Error), rules.next(snapshot(feed(update("u0"), update("u1", at = 2_000, level = "error")))))
        assertEquals(listOf(Sound.Done), rules.next(snapshot(feed(update("u0"), update("u1", at = 2_000, level = "error"), update("u2", at = 3_000, level = "success")))))
    }

    @Test
    fun `an info update makes no sound and older history loaded later makes none`() {
        val rules = rules(snapshot(feed(update("u5", at = 5_000))))
        assertEquals(emptyList(), rules.next(snapshot(feed(update("u5", at = 5_000), update("u6", at = 6_000)))))
        assertEquals(emptyList(), rules.next(snapshot(feed(update("u5", at = 5_000), update("u6", at = 6_000), update("u1", at = 1_000, level = "error")))))
    }

    @Test
    fun `a card someone else marks done sounds but one the owner marked does not`() {
        val rules = rules(snapshot(feed(update("u1", status = "in_progress"), update("u2", status = "in_progress"))))
        assertEquals(listOf(Sound.Done), rules.next(snapshot(feed(update("u1", status = "done"), update("u2", status = "in_progress")))))
        rules.touched("u2", now)
        assertEquals(emptyList(), rules.next(snapshot(feed(update("u1", status = "done"), update("u2", status = "done")))))
    }

    @Test
    fun `a ticked checklist line sounds a tick unless the owner ticked it`() {
        val rules = rules(snapshot(feed(update("u1", blocks = listOf(checklist(0))))))
        assertEquals(listOf(Sound.Tick), rules.next(snapshot(feed(update("u1", blocks = listOf(checklist(1)))))))
        rules.touched("u1", now)
        assertEquals(emptyList(), rules.next(snapshot(feed(update("u1", blocks = listOf(checklist(2)))))))
    }

    @Test
    fun `a reply in the open thread sounds but the owner's own line does not`() {
        val rules = rules(snapshot(feed(update("u1"))))
        val agentReply = line(agent("m1", 2_000, updateId = "u1"))
        assertEquals(listOf(Sound.Reply), rules.next(snapshot(feed(update("u1", replies = listOf(agentReply))), viewing = "u1")))
        val mine = line(owner("m2", 3_000, updateId = "u1"))
        assertEquals(emptyList(), rules.next(snapshot(feed(update("u1", replies = listOf(agentReply, mine))), viewing = "u1")))
    }

    @Test
    fun `a reply to the owner sounds even with its thread closed`() {
        val head = owner("p1", 1_000)
        val rules = rules(snapshot(feed(PostCard(line(head), "Mega", 1_000, emptyList(), 0, emptyList(), emptyList(), emptyList()))))
        val answer = line(agent("m1", 2_000, replyTo = "p1"))
        assertEquals(listOf(Sound.Reply), rules.next(snapshot(feed(PostCard(line(head), "Mega", 2_000, listOf(answer), 0, emptyList(), emptyList(), emptyList())))))
    }

    @Test
    fun `a sent message sounds sent and one refused for good sounds the error`() {
        val rules = rules(snapshot(outbox = listOf(item("o1"), item("o2"))))
        assertEquals(listOf(Sound.Sent), rules.next(snapshot(outbox = listOf(item("o2")))))
        assertEquals(listOf(Sound.Error), rules.next(snapshot(outbox = listOf(item("o2", gaveUp = true)))))
    }

    @Test
    fun `a reaction going out or a message deleted unsent makes no sound`() {
        val rules = rules(snapshot(outbox = listOf(item("o1", OutboxKind.React), item("o2"))))
        rules.dropped("o2")
        assertEquals(emptyList(), rules.next(snapshot(outbox = emptyList())))
    }

    @Test
    fun `what a reconnect replays is quiet but a send and a critical alert are not`() {
        val rules = rules(snapshot(outbox = listOf(item("o1"))))
        rules.next(snapshot(outbox = listOf(item("o1")), live = false))
        now += 100
        val replay = rules.next(snapshot(feed(update("u1", at = 2_000, level = "error")), waiting = listOf(question("r9", "critical")), outbox = emptyList()))
        assertEquals(listOf(Sound.Sent, Sound.Critical), replay)
        now += 100
        assertEquals(emptyList(), rules.next(snapshot(feed(update("u1", at = 2_000, level = "error"), update("u2", at = 3_000, level = "error")), waiting = listOf(question("r9", "critical")))))
        now += 10_000
        assertEquals(listOf(Sound.Error), rules.next(snapshot(feed(update("u1", at = 2_000, level = "error"), update("u2", at = 3_000, level = "error"), update("u3", at = 4_000, level = "error")), waiting = listOf(question("r9", "critical")))))
    }

    @Test
    fun `coming back to the app catches up quietly`() {
        val rules = rules()
        rules.hush(now)
        assertEquals(emptyList(), rules.next(snapshot(waiting = listOf(question("r1")))))
    }

    @Test
    fun `a muted project makes no sound for its updates`() {
        val muted = Follows(levels = mapOf("p1" to NotifyLevel.Muted))
        val rules = rules(snapshot(feed(update("u0")), follows = muted))
        assertEquals(emptyList(), rules.next(snapshot(feed(update("u0"), update("u1", at = 2_000, level = "error")), follows = muted)))
    }

    @Test
    fun `a notice whose system notification sounds itself is not played twice`() {
        val rules = rules()
        assertEquals(emptyList(), rules.next(snapshot(waiting = listOf(question("r1"))), systemPlays = { true }))
    }
}

/** The switch, the gap between repeats and collapsing bursts. */
class SoundBoardTest {
    private class FakePlayer : SoundPlayer {
        val played = mutableListOf<Sound>()
        override fun play(sound: Sound) {
            played += sound
        }
    }

    private var now = 0L

    @Test
    fun `a burst plays only its most important sound`() {
        val player = FakePlayer()
        SoundBoard(player, MemoryTextStore(), clock = { now }).play(listOf(Sound.Reply, Sound.Question, Sound.Tick))
        assertEquals(listOf(Sound.Question), player.played)
    }

    @Test
    fun `the same sound plays at most once in 400 ms`() {
        val player = FakePlayer()
        val board = SoundBoard(player, MemoryTextStore(), clock = { now })
        board.play(Sound.Reply)
        now += 399
        board.play(Sound.Reply)
        board.play(Sound.Sent)
        now += 1
        board.play(Sound.Reply)
        assertEquals(listOf(Sound.Reply, Sound.Sent, Sound.Reply), player.played)
    }

    @Test
    fun `turned off it plays nothing and remembers it`() {
        val player = FakePlayer()
        val store = MemoryTextStore()
        val board = SoundBoard(player, store, clock = { now })
        assertTrue(board.enabled.value)
        board.set(false)
        board.play(Sound.Critical)
        assertEquals(emptyList(), player.played)
        assertFalse(SoundBoard(player, store).enabled.value)
    }

    @Test
    fun `a quiet device plays only critical sounds`() {
        val player = FakePlayer()
        val board = SoundBoard(player, MemoryTextStore(), quiet = { true }, clock = { now })
        board.play(Sound.Question)
        board.play(Sound.Critical)
        assertEquals(listOf(Sound.Critical), player.played)
    }

    @Test
    fun `without a player it is silent and offers no switch`() {
        val board = SoundBoard(null, MemoryTextStore())
        assertFalse(board.available)
        board.play(Sound.Question)
    }
}
