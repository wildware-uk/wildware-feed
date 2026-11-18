package dev.wildware.agents.app

import dev.wildware.agents.CardKinds
import dev.wildware.agents.LiveAgentView
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
import dev.wildware.agents.app.feed.AgentDetails
import dev.wildware.agents.NotificationList
import dev.wildware.agents.NotificationView
import dev.wildware.agents.app.feed.BellItem
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.Level
import dev.wildware.agents.app.feed.LinkStatus
import dev.wildware.agents.app.feed.FormField
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
    fun aFormKeepsItsFieldAndItsOwnActions() {
        val request = RequestCard(
            RequestView(
                id = "r3", agentId = "a1", kind = "form", question = "Send this commit message?", options = listOf("Commit", "Discard"),
                config = RequestConfig(label = "Commit message", placeholder = "What changed", defaultValue = "Fix the counts", multiline = true, min = 3, max = 72),
                expiresAt = noon + 42 * 60_000,
            ),
            agentName = "forge", projectName = null, discussion = emptyList(), unread = 0, sending = false, tags = emptyList(), fields = emptyList(),
        )
        val q = mapper.map(feed(emptyList(), listOf(request)), listOf(mega), emptyList(), emptyList(), null)
            .sections.single().items.single() as FeedItem.Question
        assertEquals(QuestionKind.Form, q.kind)
        assertEquals(FormField(label = "Commit message", placeholder = "What changed", initial = "Fix the counts", multiline = true, min = 3, max = 72, actions = listOf("Commit", "Discard")), q.form)
        assertTrue(q.choices.isEmpty(), "the actions are the form's own buttons, not answer choices")
        assertEquals(false, q.allowsText)
    }

    @Test
    fun aFormWithNoConfigAllowsAnEmptyField() {
        val request = RequestCard(
            RequestView(id = "r4", agentId = "a1", kind = "form", question = "Go?", options = listOf("Go")),
            agentName = "forge", projectName = null, discussion = emptyList(), unread = 0, sending = false, tags = emptyList(), fields = emptyList(),
        )
        val q = mapper.map(feed(emptyList(), listOf(request)), listOf(mega), emptyList(), emptyList(), null)
            .sections.single().items.single() as FeedItem.Question
        assertEquals(FormField(actions = listOf("Go")), q.form)
        assertEquals(null, q.form!!.problem(""))
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
    fun aLiveAgentCarriesItsModelMachineFolderSessionsAndLastBeat() {
        fun live(id: String, beatAgo: Long, sessions: Int = 1) = LiveAgentView(
            agentId = id, name = "forge-$id", lastHeartbeatAt = noon - beatAgo, sessions = sessions,
            host = "build-box", cwd = "/work/garden-game", model = "claude-opus-5-5",
        )
        val state = mapper.map(feed(emptyList()), listOf(mega), listOf(live("a1", 2_000, sessions = 2), live("a2", 45_000), live("a3", 200_000)), emptyList(), null)

        assertEquals(
            listOf(
                AgentDetails(model = "claude-opus-5-5", host = "build-box", cwd = "/work/garden-game", sessions = 2, lastSeen = "just now"),
                AgentDetails(model = "claude-opus-5-5", host = "build-box", cwd = "/work/garden-game", sessions = 1, lastSeen = "45s ago"),
                AgentDetails(model = "claude-opus-5-5", host = "build-box", cwd = "/work/garden-game", sessions = 1, lastSeen = "3m ago"),
            ),
            state.agents.map { it.details },
        )
    }

    @Test
    fun blankDetailsAreLeftOut() {
        val bare = LiveAgentView(agentId = "a1", name = "forge", lastHeartbeatAt = noon, host = " ", cwd = "", model = null)
        val details = mapper.map(feed(emptyList()), listOf(mega), listOf(bare), emptyList(), null).agents.single().details
        assertEquals(AgentDetails(model = null, host = null, cwd = null, sessions = 1, lastSeen = "just now"), details)
    }

    @Test
    fun theBellKeepsItsOrderCountAndWhereEachLeads() {
        val list = NotificationList(
            items = listOf(
                NotificationView(id = "n2", title = "scout replied", body = "Looks good", projectName = "Mega", createdAt = noon, path = "/projects/mega?focus=u1"),
                NotificationView(id = "n1", title = "forge posted", body = "", createdAt = noon - 86_400_000L, seenAt = noon, path = "/"),
            ),
            unseen = 1,
        )
        val bell = mapper.bell(list)
        assertEquals(1, bell.unseen)
        assertEquals(listOf("n2", "n1"), bell.items.map { it.id })
        val first = bell.items.first()
        assertEquals(BellItem("n2", "scout replied", "Looks good", "Mega", mapper.timeLabel(noon), unread = true, path = "/projects/mega?focus=u1"), first)
        assertEquals(false, bell.items.last().unread)
        assertEquals("Yesterday", bell.items.last().time)
    }

    @Test
    fun aDroppedConnectionReadsAsReconnecting() {
        val state = mapper.map(feed(emptyList(), status = StreamStatus.Offline), listOf(mega), emptyList(), emptyList(), "mega")
        assertEquals(LinkStatus.Reconnecting, state.link)
        assertEquals("Mega", state.current.name)
    }

    private fun question(id: String, projectId: String? = "p1", messageId: String? = null, seq: Long = 1) = RequestCard(
        RequestView(id = id, seq = seq, agentId = "a1", projectId = projectId, messageId = messageId, kind = "buttons",
            question = "Question $id?", options = listOf("Yes", "No"), expiresAt = noon + 4 * 60_000),
        agentName = "forge", projectName = "Mega", discussion = emptyList(), unread = 0, sending = false, tags = emptyList(), fields = emptyList(),
    )

    private fun waitingOf(state: dev.wildware.agents.app.feed.FeedState) =
        state.sections.firstOrNull { it.label == "Waiting on you" }?.items.orEmpty().map { it as FeedItem.Question }

    @Test
    fun aQuestionAskedInAThreadIsWaitingOnYouWithWhatItAnswers() {
        val asked = question("q1", messageId = "m2")
        val replies = listOf(line("m1", "human", noon - 3_000), line("m2", "agent:a1", noon - 2_000).copy(questions = listOf(asked)))
        // The engine keeps a thread question out of the feed's own list; every question still reaches the app.
        val state = mapper.map(feed(listOf(update("u1", noon - 5_000, "Fix", "", replies))), listOf(mega), emptyList(), emptyList(), null, everyQuestion = listOf(asked))
        val q = waitingOf(state).single()
        assertEquals("q1", q.id)
        assertEquals("u1", q.threadId)
        assertEquals("scout: Line m2", q.inReplyTo)
        assertEquals(noon + 4 * 60_000, q.expiresAt)
    }

    @Test
    fun aThreadQuestionAlsoShowsUnderTheReplyItWasAskedUnder() {
        val asked = question("q1", messageId = "m2")
        val replies = listOf(line("m2", "agent:a1", noon - 2_000).copy(questions = listOf(asked)))
        val state = mapper.map(feed(listOf(update("u1", noon - 5_000, "Fix", "", replies))), listOf(mega), emptyList(), emptyList(), null, everyQuestion = listOf(asked))
        val card = state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().single()
        assertEquals(listOf("q1"), card.replies.single().questions.map { it.id })
    }

    @Test
    fun waitingOnYouKeepsTheServersOrderOldestFirst() {
        val all = listOf(question("old", seq = 1), question("mid", messageId = "mx", seq = 2), question("new", seq = 3))
        val state = mapper.map(feed(emptyList(), listOf(all[0], all[2])), listOf(mega), emptyList(), emptyList(), null, everyQuestion = all)
        assertEquals(listOf("old", "mid", "new"), waitingOf(state).map { it.id })
        // Asked in a thread that isn't loaded: still listed, said so, with nowhere to jump yet.
        assertEquals("Asked in a thread", waitingOf(state)[1].inReplyTo)
    }

    @Test
    fun insideAProjectOnlyThatProjectsQuestionsWait() {
        val other = ProjectSummary(ProjectView(id = "p2", slug = "other", name = "Other"), waiting = 1, unread = 0)
        val all = listOf(question("here", projectId = "p1"), question("there", projectId = "p2", messageId = "m9"))
        val state = mapper.map(feed(emptyList()), listOf(mega, other), emptyList(), emptyList(), "mega", everyQuestion = all)
        assertEquals(listOf("here"), waitingOf(state).map { it.id })
    }
}
