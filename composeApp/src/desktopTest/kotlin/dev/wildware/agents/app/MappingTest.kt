package dev.wildware.agents.app

import dev.wildware.agents.CardKinds
import dev.wildware.agents.CardRef
import dev.wildware.agents.MessageView
import dev.wildware.agents.MediaView
import dev.wildware.agents.RequestConfig
import dev.wildware.agents.ProjectSummary
import dev.wildware.agents.ProjectView
import dev.wildware.agents.ReactionView
import dev.wildware.agents.RequestCard
import dev.wildware.agents.RequestView
import dev.wildware.agents.StreamStatus
import dev.wildware.agents.ThreadLine
import dev.wildware.agents.UpdateCard
import dev.wildware.agents.UpdateView
import dev.wildware.agents.app.engine.FeedMapper
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.Level
import dev.wildware.agents.app.feed.LinkStatus
import dev.wildware.agents.app.feed.QuestionKind
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.wildware.agents.FeedState as EngineFeed

class MappingTest {
    // 2026-11-18 12:00 UTC.
    private val noon = 1_795_003_200_000L
    private val mapper = FeedMapper("https://dash.test/", now = { noon }, zone = TimeZone.UTC)
    private val mega = ProjectSummary(ProjectView(id = "p1", slug = "mega", name = "Mega"), waiting = 1, unread = 2)

    private fun line(id: String, author: String, at: Long) = ThreadLine(
        MessageView(id = id, author = author, body = "Line $id", createdAt = at, updateId = "u1"),
        authorName = if (author == "human") "You" else "scout",
        answersName = null, media = emptyList(), embeds = emptyList(), reactions = emptyList(),
        acks = emptyList(), deliveries = emptyList(), questions = emptyList(),
    )

    private fun update(id: String, at: Long, title: String?, body: String, replies: List<ThreadLine> = emptyList(), unread: Int = 0) = UpdateCard(
        update = UpdateView(
            id = id, seq = 1, projectId = "p1", agentId = "a1", title = title, body = body, level = "warn", createdAt = at,
            reactions = listOf(
                ReactionView("x1", card = CardRef(CardKinds.Update, id), actor = "human", emoji = "👍"),
                ReactionView("x2", card = CardRef(CardKinds.Update, id), actor = "agent:a2", emoji = "👍"),
            ),
        ),
        agentName = "forge", projectName = "Mega", isNew = false, activityAt = at,
        replies = replies, unread = unread, tags = emptyList(), fields = emptyList(), apps = emptyList(),
    )

    private fun feed(cards: List<UpdateCard>, waiting: List<RequestCard> = emptyList(), status: StreamStatus = StreamStatus.Live) =
        EngineFeed(null, null, status, true, false, false, waiting, emptyList(), cards, 0, false, emptyList())

    @Test
    fun anUntitledUpdateTakesItsFirstLineAsItsHeadline() {
        val state = mapper.map(feed(listOf(update("u1", noon, null, "**Disk** is full\n\nPrune the caches."))), listOf(mega), emptyList(), emptyList(), null)
        val item = state.sections.single().items.single() as FeedItem.Update
        assertEquals("Disk is full", item.title)
        assertEquals("Prune the caches.", item.body)
        assertEquals(Level.Warning, item.level)
    }

    @Test
    fun reactionsGroupByEmojiAndKnowTheOwnersOwn() {
        val item = mapper.map(feed(listOf(update("u1", noon, "T", "B"))), listOf(mega), emptyList(), emptyList(), null)
            .sections.single().items.single() as FeedItem.Update
        val thumbs = item.reactions.single()
        assertEquals(2, thumbs.count)
        assertTrue(thumbs.mine)
    }

    @Test
    fun theNewestAgentLinesAreTheUnreadOnes() {
        val replies = listOf(line("m1", "agent:a2", noon - 3000), line("m2", "human", noon - 2000), line("m3", "agent:a2", noon - 1000))
        val item = mapper.map(feed(listOf(update("u1", noon, "T", "B", replies, unread = 1))), listOf(mega), emptyList(), emptyList(), null)
            .sections.single().items.single() as FeedItem.Update
        assertEquals(listOf(false, false, true), item.replies.map { it.unread })
    }

    @Test
    fun cardsFallUnderTheirDay() {
        val day = 86_400_000L
        val state = mapper.map(
            feed(listOf(update("u1", noon, "T", "B"), update("u2", noon - day, "T", "B"), update("u3", noon - 3 * day, "T", "B"))),
            listOf(mega), emptyList(), emptyList(), null,
        )
        assertEquals(listOf("Today", "Yesterday", "Sunday"), state.sections.map { it.label })
    }

    @Test
    fun aConfirmQuestionBecomesYesAndNo() {
        val request = RequestCard(
            RequestView(id = "r1", agentId = "a1", kind = "confirm", question = "Ship?", expiresAt = noon + 42 * 60_000),
            agentName = "forge", projectName = null, discussion = emptyList(), unread = 0, sending = true, tags = emptyList(), fields = emptyList(),
        )
        val q = mapper.map(feed(emptyList(), listOf(request)), listOf(mega), emptyList(), emptyList(), null)
            .sections.single().items.single() as FeedItem.Question
        assertEquals(QuestionKind.Buttons, q.kind)
        assertEquals(listOf("Yes", "No"), q.choices.map { it.label })
        assertEquals("42 min", q.expiresIn)
        assertTrue(q.sending)
    }

    @Test
    fun picturesOnOptionsGoToTheirOptionsAndNotTheCard() {
        fun media(id: String) = MediaView(id = id, kind = "image", status = "ready", variants = listOf("original"), filename = "$id.png")
        val request = RequestCard(
            RequestView(
                id = "r2", agentId = "a1", kind = "multi_choice", question = "Which?", options = listOf("Dusk", "Dawn"),
                config = RequestConfig(optionMedia = mapOf("Dusk" to "md1", "Dawn" to "md2")),
                media = listOf(media("md1"), media("md2"), media("md3")),
            ),
            agentName = "lumen", projectName = null, discussion = emptyList(), unread = 0, sending = false, tags = emptyList(), fields = emptyList(),
        )
        val q = mapper.map(feed(emptyList(), listOf(request)), listOf(mega), emptyList(), emptyList(), null)
            .sections.single().items.single() as FeedItem.Question
        assertEquals(listOf("https://dash.test/media/md1/original", "https://dash.test/media/md2/original"), q.choices.map { it.image?.url })
        assertEquals(listOf("Dusk", "Dawn"), q.choices.map { it.image?.altText })
        assertEquals(listOf("https://dash.test/media/md3/original"), q.attachments.map { it.url })
        assertTrue(q.hasPictures)
    }

    @Test
    fun aDroppedConnectionReadsAsReconnecting() {
        val state = mapper.map(feed(emptyList(), status = StreamStatus.Offline), listOf(mega), emptyList(), emptyList(), "mega")
        assertEquals(LinkStatus.Reconnecting, state.link)
        assertEquals("Mega", state.current.name)
    }
}
