package dev.wildware.agents.app

import dev.wildware.agents.FeedCard
import dev.wildware.agents.FeedState
import dev.wildware.agents.Follows
import dev.wildware.agents.MessageView
import dev.wildware.agents.NotifyLevel
import dev.wildware.agents.PostCard
import dev.wildware.agents.StreamStatus
import dev.wildware.agents.ThreadLine
import dev.wildware.agents.UpdateCard
import dev.wildware.agents.UpdateView
import dev.wildware.agents.app.desktop.NotificationRules
import dev.wildware.agents.app.feed.PersonRow
import dev.wildware.agents.app.feed.TeamState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Local notices on a team (migration 055): "the owner" is this device's person. A teammate's line
 * notifies by what they follow, as an agent's does; their own never does.
 */
class TeamNoticesTest {
    private val me = PersonRow("m-me", "Alex", "alex", "owner", you = true)
    private val sam = PersonRow("m-sam", "Sam", "sam", "member")
    private val team = TeamState(me = me, people = listOf(me, sam), team = true, loaded = true)

    private fun human(id: String, who: String?, body: String, at: Long, updateId: String? = null, replyTo: String? = null, answers: String? = null) =
        MessageView(id = id, author = "human", body = body, createdAt = at, userId = who, updateId = updateId, replyTo = replyTo, answers = answers, projectId = "p1")

    private fun agent(id: String, body: String, at: Long, updateId: String? = null, replyTo: String? = null, answers: String? = null) =
        MessageView(id = id, author = "agent:a1", body = body, createdAt = at, updateId = updateId, replyTo = replyTo, answers = answers, projectId = "p1")

    private fun line(message: MessageView) =
        ThreadLine(message, if (message.isOwner) "You" else "Builder", null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    private fun update(id: String, vararg replies: MessageView) = UpdateCard(
        UpdateView(id = id, seq = 1, projectId = "p1", agentId = "a1", body = "Shipped", createdAt = 1_000),
        "Builder", "Mega", false, 1_000, replies.map(::line), 0, emptyList(), emptyList(), emptyList(),
    )

    private fun post(head: MessageView, vararg replies: MessageView) =
        PostCard(line(head), "Mega", head.createdAt, replies.map(::line), 0, emptyList(), emptyList(), emptyList())

    private fun feed(vararg cards: FeedCard) =
        FeedState(null, null, StreamStatus.Live, true, false, false, emptyList(), emptyList(), cards.toList(), 0, false, emptyList())

    /** Reads [before] first, so only what [after] adds can be announced. */
    private fun announced(before: FeedState, after: FeedState, follows: Follows = Follows(), team: TeamState = this.team): List<String> {
        val rules = NotificationRules()
        rules.next(true, emptyList(), before, follows = follows, team = team)
        return rules.next(true, emptyList(), after, follows = follows, team = team).map { "${it.title}: ${it.body}" }
    }

    @Test
    fun `a teammate's reply in a thread I wrote in notifies me with their name`() {
        val mine = human("m1", "m-me", "Can you check the logs?", 2_000, updateId = "u1")
        val samSays = human("m2", "m-sam", "I looked, they're clean", 3_000, updateId = "u1")
        assertEquals(listOf("Sam · Mega: I looked, they're clean"), announced(feed(update("u1", mine)), feed(update("u1", mine, samSays))))
    }

    @Test
    fun `my own line never notifies me, even in a thread I follow`() {
        val mine = human("m1", "m-me", "Shipping it", 2_000, updateId = "u1")
        val following = Follows(threads = mapOf("update:u1" to true), loaded = true)
        assertTrue(announced(feed(update("u1")), feed(update("u1", mine)), following).isEmpty())
    }

    @Test
    fun `a teammate's comment where I never spoke is quiet unless I follow it or am named`() {
        val samSays = human("m2", "m-sam", "Looks good to me", 3_000, updateId = "u1")
        assertTrue(announced(feed(update("u1")), feed(update("u1", samSays))).isEmpty())

        val following = Follows(threads = mapOf("update:u1" to true), loaded = true)
        assertEquals(listOf("Sam · Mega: Looks good to me"), announced(feed(update("u1")), feed(update("u1", samSays)), following))

        val naming = human("m3", "m-sam", "@alex can you look?", 3_000, updateId = "u1")
        assertEquals(listOf("Sam · Mega: @alex can you look?"), announced(feed(update("u1")), feed(update("u1", naming))))
    }

    @Test
    fun `an agent answering a teammate is not a reply to me`() {
        val samPost = human("p1", "m-sam", "Deploy the docs", 2_000)
        val answer = agent("r1", "On it", 3_000, replyTo = "p1")
        assertTrue(announced(feed(post(samPost)), feed(post(samPost, answer))).isEmpty())

        // The same reply under my own post is mine.
        val myPost = human("p2", "m-me", "Deploy the site", 2_000)
        val toMe = agent("r2", "On it", 3_000, replyTo = "p2")
        assertEquals(listOf("Builder · Mega: On it"), announced(feed(post(myPost)), feed(post(myPost, toMe))))
    }

    @Test
    fun `a teammate's line in a muted channel stays quiet`() {
        val mine = human("m1", "m-me", "Can you check?", 2_000, updateId = "u1")
        val samSays = human("m2", "m-sam", "Checked", 3_000, updateId = "u1")
        val muted = Follows(levels = mapOf("p1" to NotifyLevel.Muted), loaded = true)
        assertTrue(announced(feed(update("u1", mine)), feed(update("u1", mine, samSays)), muted).isEmpty())
    }

    @Test
    fun `on a team of one every person's line is the owner's, as before`() {
        val solo = TeamState(me = PersonRow("founder", "Alex", "alex", "owner", you = true, founder = true), team = false, loaded = true)
        val owner = human("m1", null, "Hello", 2_000, updateId = "u1")
        val reply = agent("m2", "Hi", 3_000, updateId = "u1")
        assertEquals(listOf("Builder · Mega: Hi"), announced(feed(update("u1", owner)), feed(update("u1", owner, reply)), team = solo))
        assertTrue(announced(feed(update("u1")), feed(update("u1", owner)), team = solo).isEmpty())
    }
}
