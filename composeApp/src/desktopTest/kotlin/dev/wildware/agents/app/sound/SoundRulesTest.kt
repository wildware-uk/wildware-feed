package dev.wildware.agents.app.sound

import dev.wildware.agents.BlockView
import dev.wildware.agents.ChecklistItem
import dev.wildware.agents.FeedCard
import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.CardRef
import dev.wildware.agents.FeedState
import dev.wildware.agents.Follows
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.MessageView
import dev.wildware.agents.NotifyLevel
import dev.wildware.agents.OutboxFile
import dev.wildware.agents.OutboxItem
import dev.wildware.agents.OutboxKind
import dev.wildware.agents.PollOption
import dev.wildware.agents.PostCard
import dev.wildware.agents.ReactionView
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

    private fun line(message: MessageView, blocks: List<BlockView> = emptyList(), reactions: List<ReactionView> = emptyList()) =
        ThreadLine(message, "Builder", null, emptyList(), emptyList(), reactions, emptyList(), emptyList(), emptyList(), blocks = blocks)

    private fun reaction(id: String, messageId: String, actor: String = "agent:a1", at: Long = 3_000) =
        ReactionView(id, card = CardRef("message", "p1"), messageId = messageId, actor = actor, emoji = "👍", createdAt = at)

    private fun post(head: MessageView, at: Long = 1_000, replies: List<ThreadLine> = emptyList(), reactions: List<ReactionView> = emptyList()) =
        PostCard(line(head, reactions = reactions), "Mega", at, replies, 0, emptyList(), emptyList(), emptyList())

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

    private fun poll(votes: Int) = BlockView("b2", BlockView.Poll, options = listOf(PollOption("o1", "Yes", votes = votes)))

    private fun item(id: String, kind: OutboxKind = OutboxKind.Message, gaveUp: Boolean = false, files: Int = 0, claimed: Boolean = false) =
        OutboxItem(id, 0, kind, "To Mega", gaveUp = gaveUp, files = List(files) { OutboxFile("f$it", "f$it.png", "image/png") }, claimedUntilMillis = if (claimed) 99 else 0)

    /** A clock past the settling after the first read, so changes sound. */
    private var now = 0L

    private fun snapshot(
        feed: FeedState = feed(),
        waiting: List<RequestCard> = emptyList(),
        outbox: List<OutboxItem> = emptyList(),
        live: Boolean = true,
        follows: Follows = Follows(),
        prefs: AlertPrefs? = null,
    ) = SoundSnapshot(now, ready = true, live = live, waiting = waiting, feed = feed, outbox = outbox, prefs = prefs, follows = follows)

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
    fun `a new update sounds the grid sound for its priority and level`() {
        val rules = rules(snapshot(feed(update("u0"))))
        val cards = mutableListOf(update("u0"))
        var at = 2_000L
        for (priority in listOf("low", "medium", "high")) {
            for (level in listOf("info", "success", "warn", "error")) {
                cards += update("u${at}", at = at, level = level, priority = priority)
                at += 1_000
                val column = if (level == "warn") "warning" else level
                assertEquals(listOf(Sound.entries.single { it.file == "$priority-$column" }), rules.next(snapshot(feed(*cards.toTypedArray()))))
            }
        }
        // No priority is medium.
        cards += update("u-none", at = at, level = "success")
        assertEquals(listOf(Sound.MediumSuccess), rules.next(snapshot(feed(*cards.toTypedArray()))))
    }

    @Test
    fun `older history loaded later makes no sound`() {
        val rules = rules(snapshot(feed(update("u5", at = 5_000))))
        assertEquals(listOf(Sound.MediumInfo), rules.next(snapshot(feed(update("u5", at = 5_000), update("u6", at = 6_000)))))
        assertEquals(emptyList(), rules.next(snapshot(feed(update("u5", at = 5_000), update("u6", at = 6_000), update("u1", at = 1_000, level = "error")))))
    }

    @Test
    fun `an update the device's choice of alerts leaves out makes no sound`() {
        val onlyHigh = AlertPrefs(priorities = listOf("high"))
        val rules = rules(snapshot(feed(update("u0")), prefs = onlyHigh))
        assertEquals(emptyList(), rules.next(snapshot(feed(update("u0"), update("u1", at = 2_000, priority = "low")), prefs = onlyHigh)))
        assertEquals(listOf(Sound.HighError), rules.next(snapshot(feed(update("u0"), update("u1", at = 2_000, priority = "low"), update("u2", at = 3_000, level = "error", priority = "high")), prefs = onlyHigh)))
    }

    @Test
    fun `a project followed for replies only makes no sound for its updates`() {
        val replies = Follows(levels = mapOf("p1" to NotifyLevel.Replies))
        val rules = rules(snapshot(feed(update("u0")), follows = replies))
        assertEquals(emptyList(), rules.next(snapshot(feed(update("u0"), update("u1", at = 2_000, level = "error")), follows = replies)))
    }

    @Test
    fun `a critical update sounds the grid's critical row with its notice`() {
        val rules = rules(snapshot(feed(update("u0"))))
        assertEquals(listOf(Sound.CriticalWarning), rules.next(snapshot(feed(update("u0"), update("u1", at = 2_000, level = "warn", priority = "critical")))))
    }

    @Test
    fun `a card someone else marks done sounds but one the owner marked does not`() {
        val rules = rules(snapshot(feed(update("u1", status = "in_progress"), update("u2", status = "in_progress"))))
        assertEquals(listOf(Sound.Done), rules.next(snapshot(feed(update("u1", status = "done"), update("u2", status = "in_progress")))))
        rules.touched("u2", now)
        assertEquals(emptyList(), rules.next(snapshot(feed(update("u1", status = "done"), update("u2", status = "done")))))
    }

    @Test
    fun `a ticked checklist line or a vote makes no sound`() {
        val rules = rules(snapshot(feed(update("u1", blocks = listOf(checklist(0), poll(0))))))
        assertEquals(emptyList(), rules.next(snapshot(feed(update("u1", blocks = listOf(checklist(1), poll(0)))))))
        assertEquals(emptyList(), rules.next(snapshot(feed(update("u1", blocks = listOf(checklist(1), poll(1)))))))
    }

    @Test
    fun `a line not for the owner makes no sound`() {
        val rules = rules(snapshot(feed(update("u1"))))
        val agentReply = line(agent("m1", 2_000, updateId = "u1"))
        assertEquals(emptyList(), rules.next(snapshot(feed(update("u1", replies = listOf(agentReply))))))
    }

    @Test
    fun `a reply to the owner sounds the message sound`() {
        val head = owner("p1", 1_000)
        val rules = rules(snapshot(feed(post(head))))
        val answer = line(agent("m1", 2_000, replyTo = "p1"))
        assertEquals(listOf(Sound.Message), rules.next(snapshot(feed(post(head, 2_000, listOf(answer))))))
    }

    @Test
    fun `a line that names the owner sounds the mention sound`() {
        val rules = rules(snapshot(feed(update("u1"))))
        val named = line(agent("m1", 2_000, updateId = "u1").copy(body = "@you have a look"))
        assertEquals(listOf(Sound.Mention), rules.next(snapshot(feed(update("u1", replies = listOf(named))))))
    }

    @Test
    fun `an agent asking to join sounds the permission request`() {
        val rules = rules()
        val joining = dev.wildware.agents.PairingView(id = "pr1", name = "laptop", code = "K7Q-3MD")
        assertEquals(listOf(Sound.Permission), rules.next(snapshot().copy(joining = listOf(joining))))
    }

    @Test
    fun `someone else's reaction to the owner's line sounds once but the owner's own and others' lines do not`() {
        val head = owner("p1", 1_000)
        val theirs = agent("m1", 1_500, replyTo = "p1")
        val rules = rules(snapshot(feed(post(head, replies = listOf(line(theirs))))))
        assertEquals(listOf(Sound.Reaction), rules.next(snapshot(feed(post(head, reactions = listOf(reaction("x1", "p1")), replies = listOf(line(theirs)))))))
        assertEquals(emptyList(), rules.next(snapshot(feed(post(head, reactions = listOf(reaction("x1", "p1")), replies = listOf(line(theirs)))))))
        val own = reaction("x2", "p1", actor = "human", at = 4_000)
        val onTheirs = reaction("x3", "m1", at = 4_000)
        assertEquals(emptyList(), rules.next(snapshot(feed(post(head, reactions = listOf(reaction("x1", "p1"), own), replies = listOf(line(theirs, reactions = listOf(onTheirs))))))))
    }

    @Test
    fun `a sent message makes no sound but one refused for good sounds the error`() {
        val rules = rules(snapshot(outbox = listOf(item("o1"), item("o2"))))
        assertEquals(emptyList(), rules.next(snapshot(outbox = listOf(item("o2")))))
        assertEquals(listOf(Sound.Error), rules.next(snapshot(outbox = listOf(item("o2", gaveUp = true)))))
    }

    @Test
    fun `answering a question sounds the answer as it is given`() {
        val rules = rules()
        assertEquals(listOf(Sound.Answered), rules.next(snapshot(outbox = listOf(item("a1", OutboxKind.Answer)))))
        assertEquals(listOf(Sound.Answered), rules.next(snapshot(outbox = listOf(item("a1", OutboxKind.Answer), item("a2", OutboxKind.RequestAnswer)))))
        assertEquals(emptyList(), rules.next(snapshot(outbox = listOf(item("a1", OutboxKind.Answer), item("a2", OutboxKind.RequestAnswer), item("d1", OutboxKind.Dismiss)))))
    }

    @Test
    fun `files sent sound the upload once they reach the dashboard`() {
        val rules = rules(snapshot(outbox = listOf(item("o1", files = 1), item("o2", files = 1), item("o3"))))
        // Taken out before it was sent (an undo): no sound.
        assertEquals(emptyList(), rules.next(snapshot(outbox = listOf(item("o2", files = 1, claimed = true), item("o3", claimed = true)))))
        assertEquals(listOf(Sound.Uploaded), rules.next(snapshot(outbox = emptyList())))
    }

    @Test
    fun `what a reconnect replays is quiet but a refused send and a critical alert are not`() {
        val rules = rules(snapshot(outbox = listOf(item("o1"))))
        rules.next(snapshot(outbox = listOf(item("o1")), live = false))
        now += 100
        val replay = rules.next(snapshot(feed(update("u1", at = 2_000, level = "error")), waiting = listOf(question("r9", "critical")), outbox = listOf(item("o1", gaveUp = true))))
        assertEquals(listOf(Sound.Error, Sound.Critical), replay)
        now += 100
        assertEquals(emptyList(), rules.next(snapshot(feed(update("u1", at = 2_000, level = "error"), update("u2", at = 3_000, level = "error")), waiting = listOf(question("r9", "critical")))))
        now += 10_000
        assertEquals(listOf(Sound.MediumError), rules.next(snapshot(feed(update("u1", at = 2_000, level = "error"), update("u2", at = 3_000, level = "error"), update("u3", at = 4_000, level = "error")), waiting = listOf(question("r9", "critical")))))
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
        SoundBoard(player, MemoryTextStore(), clock = { now }).play(listOf(Sound.Done, Sound.Question, Sound.Error, Sound.HighError))
        assertEquals(listOf(Sound.Question), player.played)
    }

    @Test
    fun `the same sound plays at most once in 400 ms`() {
        val player = FakePlayer()
        val board = SoundBoard(player, MemoryTextStore(), clock = { now })
        board.play(Sound.Done)
        now += 399
        board.play(Sound.Done)
        board.play(Sound.Question)
        now += 1
        board.play(Sound.Done)
        assertEquals(listOf(Sound.Done, Sound.Question, Sound.Done), player.played)
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
    fun `a quiet device plays a critical update's sound but not the owner's own`() {
        val player = FakePlayer()
        val board = SoundBoard(player, MemoryTextStore(), quiet = { true }, clock = { now })
        board.play(Sound.Answered)
        board.play(Sound.HighError)
        board.play(Sound.CriticalError)
        assertEquals(listOf(Sound.CriticalError), player.played)
    }

    @Test
    fun `while the owner is using the app only critical sounds and their own play`() {
        val player = FakePlayer()
        val board = SoundBoard(player, MemoryTextStore(), clock = { now })
        board.activity.focus(true)
        board.play(Sound.Message)
        board.play(Sound.Question)
        board.play(Sound.MediumInfo)
        board.play(Sound.Critical)
        board.play(Sound.Answered)
        board.play(Sound.Uploaded)
        board.play(Sound.Error)
        assertEquals(listOf(Sound.Critical, Sound.Answered, Sound.Uploaded, Sound.Error), player.played)
    }

    @Test
    fun `after a minute without input the app sounds again`() {
        val player = FakePlayer()
        val board = SoundBoard(player, MemoryTextStore(), clock = { now })
        board.activity.focus(true)
        now += 59_999
        board.play(Sound.Message)
        board.activity.input()
        now += 59_999
        board.play(Sound.Message)
        now += 1
        board.play(Sound.Message)
        assertEquals(listOf(Sound.Message), player.played)
    }

    @Test
    fun `with the window in the background the app sounds`() {
        val player = FakePlayer()
        val board = SoundBoard(player, MemoryTextStore(), clock = { now })
        board.activity.focus(true)
        board.activity.input()
        board.activity.focus(false)
        board.play(Sound.Message)
        assertEquals(listOf(Sound.Message), player.played)
    }

    @Test
    fun `without a player it is silent and offers no switch`() {
        val board = SoundBoard(null, MemoryTextStore())
        assertFalse(board.available)
        board.play(Sound.Question)
    }
}
