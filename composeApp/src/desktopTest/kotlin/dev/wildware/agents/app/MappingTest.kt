package dev.wildware.agents.app

import dev.wildware.agents.AckView
import dev.wildware.agents.CardKinds
import dev.wildware.agents.LiveAgentView
import dev.wildware.agents.DeliveryView
import dev.wildware.agents.PostCard
import dev.wildware.agents.CardRef
import dev.wildware.agents.MessageView
import dev.wildware.agents.MediaView
import dev.wildware.agents.RequestConfig
import dev.wildware.agents.OutboxItem
import dev.wildware.agents.OutboxKind
import dev.wildware.agents.app.engine.unsent
import dev.wildware.agents.app.feed.Unsent
import dev.wildware.agents.app.feed.NotReady
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
import dev.wildware.agents.app.feed.FileAttachment
import dev.wildware.agents.app.feed.Sound
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.PickBounds
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
    fun updatesWaitingAboveAreCounted() {
        val state = mapper.map(feed(listOf(update("u1", noon, "T", "B"))).copy(newCount = 3), listOf(mega), emptyList(), emptyList(), null)
        assertEquals(3, state.newCount)
    }

    @Test
    fun cardTimesSayHowLongAgo() {
        val minute = 60_000L
        assertEquals("now", mapper.relativeLabel(noon - 5_000))
        assertEquals("45s ago", mapper.relativeLabel(noon - 45_000))
        assertEquals("5m ago", mapper.relativeLabel(noon - 5 * minute))
        assertEquals("3h ago", mapper.relativeLabel(noon - 3 * 60 * minute))
        assertEquals("2d ago", mapper.relativeLabel(noon - 2 * 24 * 60 * minute))
        // Past a month, the day itself.
        assertEquals("Saturday 4 October 2025", mapper.relativeLabel(noon - 410L * 24 * 60 * minute))
    }

    @Test
    fun aCardThatChangedSaysUpdated() {
        val minute = 60_000L
        val card = update("u1", noon - 60 * minute, "T", "B", replies = listOf(line("m1", "agent:a1", noon - 5 * minute)))
        val item = mapper.map(feed(listOf(card.copy(activityAt = noon - 5 * minute))), listOf(mega), emptyList(), emptyList(), null).sections.single().items.single() as FeedItem.Update
        assertEquals("updated 5m ago", item.time)
        assertEquals("Posted Wednesday 18 November 2026 at 11:00 · updated Wednesday 18 November 2026 at 11:55", item.timeDetail)
        assertEquals("5m ago", item.replies.single().time)
        assertEquals("Wednesday 18 November 2026 at 11:55", item.replies.single().timeDetail)
        val quiet = mapper.map(feed(listOf(update("u2", noon - 60 * minute, "T", "B"))), listOf(mega), emptyList(), emptyList(), null).sections.single().items.single() as FeedItem.Update
        assertEquals("1h ago", quiet.time)
        assertEquals("Posted Wednesday 18 November 2026 at 11:00", quiet.timeDetail)
    }

    @Test
    fun aDayFromAnotherYearSaysTheYear() {
        assertEquals("3 August", mapper.dayLabel(kotlinx.datetime.LocalDate(2026, 8, 3)))
        assertEquals("3 August 2025", mapper.dayLabel(kotlinx.datetime.LocalDate(2025, 8, 3)))
        assertEquals("Yesterday", mapper.dayLabel(kotlinx.datetime.LocalDate(2026, 11, 17)))
    }

    @Test
    fun anEditedUpdateAndReplySayWhen() {
        val edited = update("u1", noon, "Title", "Body", replies = listOf(line("m1", "agent:a1", noon).let { it.copy(message = it.message.copy(editedAt = noon - 90 * 60_000)) }))
        val card = edited.copy(update = edited.update.copy(editedAt = noon + 5 * 60_000))
        val item = mapper.map(feed(listOf(card)), listOf(mega), emptyList(), emptyList(), null).sections.single().items.single() as FeedItem.Update
        assertEquals("Edited Wednesday 18 November 2026 at 12:05", item.edited)
        assertEquals("Edited Wednesday 18 November 2026 at 10:30", item.replies.single().edited)
        // Never edited: nothing to say.
        val plain = mapper.map(feed(listOf(update("u2", noon, "T", "B"))), listOf(mega), emptyList(), emptyList(), null).sections.single().items.single() as FeedItem.Update
        assertEquals(null, plain.edited)
    }

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

    private fun textQuestion(config: RequestConfig?): FeedItem.Question {
        val request = RequestCard(
            RequestView(id = "r4", agentId = "a1", kind = "text", question = "Name it?", config = config),
            agentName = "lumen", projectName = null, discussion = emptyList(), unread = 0, sending = false, tags = emptyList(), fields = emptyList(),
        )
        return mapper.map(feed(emptyList(), listOf(request)), listOf(mega), emptyList(), emptyList(), null).sections.single().items.single() as FeedItem.Question
    }

    @Test
    fun aTextQuestionCarriesItsBoxSettings() {
        val q = textQuestion(RequestConfig(placeholder = "A short name", defaultValue = "garden", multiline = true, min = 3, max = 40))
        assertEquals(FormField(placeholder = "A short name", initial = "garden", multiline = true, min = 3, max = 40), q.text)
        // A text question exists because something must be typed: one character at least, as on the website.
        assertEquals(FormField(min = 1), textQuestion(null).text)
    }

    private fun multi(config: RequestConfig?, options: List<String> = listOf("A", "B", "C", "D")): FeedItem.Question {
        val request = RequestCard(
            RequestView(id = "r3", agentId = "a1", kind = "multi_choice", question = "Which?", options = options, config = config),
            agentName = "lumen", projectName = null, discussion = emptyList(), unread = 0, sending = false, tags = emptyList(), fields = emptyList(),
        )
        return mapper.map(feed(emptyList(), listOf(request)), listOf(mega), emptyList(), emptyList(), null).sections.single().items.single() as FeedItem.Question
    }

    @Test
    fun aMultiChoiceQuestionCarriesHowManyToPick() {
        assertEquals(PickBounds(2, 3, bounded = true), multi(RequestConfig(min = 2, max = 3)).picks)
        // No bounds: at least one, any number up to all of them, and nothing to say about it.
        assertEquals(PickBounds(1, 4, bounded = false), multi(null).picks)
        // An agent may let the owner pick none.
        assertEquals(PickBounds(0, 2, bounded = true), multi(RequestConfig(min = 0, max = 2)).picks)
        // A max past the options is the options.
        assertEquals(PickBounds(1, 4, bounded = false), multi(RequestConfig(max = 9)).picks)
    }

    private fun choice(config: RequestConfig?, kind: String = "choice"): FeedItem.Question {
        val request = RequestCard(
            RequestView(id = "r4", agentId = "a1", kind = kind, question = "Which?", options = listOf("A", "B", "C"), config = config),
            agentName = "lumen", projectName = null, discussion = emptyList(), unread = 0, sending = false, tags = emptyList(), fields = emptyList(),
        )
        return mapper.map(feed(emptyList(), listOf(request)), listOf(mega), emptyList(), emptyList(), null).sections.single().items.single() as FeedItem.Question
    }

    @Test
    fun aChoiceQuestionStartsOnTheAgentsDefault() {
        assertEquals("B", choice(RequestConfig(defaultValue = "B")).preset)
        assertEquals(null, choice(null).preset)
        // A default that is not one of the options picks nothing.
        assertEquals(null, choice(RequestConfig(defaultValue = "Z")).preset)
        // Only choice questions: a buttons question answers on the spot.
        assertEquals(null, choice(RequestConfig(defaultValue = "B"), kind = "buttons").preset)
    }

    private fun refused(kind: OutboxKind, value: String?, id: String = "o1") = OutboxItem(
        id = id, createdAtMillis = 1, kind = kind, label = "Answer lumen", card = CardRef(CardKinds.Request, "r5"),
        value = value, gaveUp = true, lastError = "That answer is too long.",
    )

    private fun questionWith(outbox: List<OutboxItem>): FeedItem.Question {
        val request = RequestCard(
            RequestView(id = "r5", agentId = "a1", kind = "text", question = "Why?"),
            agentName = "lumen", projectName = null, discussion = emptyList(), unread = 0, sending = false, tags = emptyList(), fields = emptyList(),
        )
        return mapper.map(feed(emptyList(), listOf(request)), listOf(mega), emptyList(), outbox, null).sections.single().items.single() as FeedItem.Question
    }

    @Test
    fun aRefusedAnswerShowsOnItsQuestion() {
        assertEquals(Unsent("o1", "That answer is too long.", "Because", "Because"), questionWith(listOf(refused(OutboxKind.RequestAnswer, "\"Because\""))).unsent)
        assertEquals(null, questionWith(emptyList()).unsent)
        // Still trying is not refused: the card says it is waiting to send instead.
        assertEquals(null, questionWith(listOf(refused(OutboxKind.RequestAnswer, "\"Because\"").copy(gaveUp = false))).unsent)
    }

    @Test
    fun aRefusedAnswerSaysWhatTheOwnerSaid() {
        assertEquals("Mac, Windows", unsent(refused(OutboxKind.RequestAnswer, """["Mac","Windows"]""")).answer)
        assertEquals("No", unsent(refused(OutboxKind.RequestAnswer, "false")).answer)
        assertEquals("Yes", unsent(refused(OutboxKind.RequestAnswer, "true")).answer)
        val form = unsent(refused(OutboxKind.RequestAnswer, """{"action":"Commit","text":"Fix the build"}"""))
        assertEquals("Commit: Fix the build", form.answer)
        assertEquals("Fix the build", form.typed)
        assertEquals(Unsent("o1", "That answer is too long.", "Not now", null), unsent(refused(OutboxKind.Dismiss, null)))
    }

    @Test
    fun aQuestionCarriesItsTagsFieldsReactionsAndPin() {
        val request = RequestCard(
            RequestView(
                id = "r6", agentId = "a1", kind = "confirm", question = "Ship?", pinned = true,
                reactions = listOf(ReactionView("x9", card = CardRef(CardKinds.Request, "r6"), actor = "human", emoji = "👀")),
            ),
            agentName = "lumen", projectName = null, discussion = emptyList(), unread = 0, sending = false,
            tags = listOf(dev.wildware.agents.TagView("t1", "p1", "release", "release")),
            fields = listOf(dev.wildware.agents.CardFieldView("f1", CardRef(CardKinds.Request, "r6"), "branch", "branch", "fix/sounds", "agent:a1")),
        )
        val q = mapper.map(feed(emptyList(), listOf(request)), listOf(mega), emptyList(), emptyList(), null).sections.single().items.single() as FeedItem.Question
        assertEquals(listOf("release"), q.tags.map { it.name })
        assertEquals(listOf(dev.wildware.agents.app.feed.CardField("branch", "fix/sounds")), q.fields)
        assertEquals(listOf("👀"), q.reactions.map { it.emoji })
        assertTrue(q.reactions.single().mine)
        assertTrue(q.pinned)
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

    private fun line(id: String, author: String, at: Long, acks: List<AckView> = emptyList(), deliveries: List<DeliveryView> = emptyList(), status: String? = null, sending: Boolean = false) =
        ThreadLine(
            message = MessageView(id = id, author = author, body = "Line $id", createdAt = at, projectId = "p1", status = status),
            authorName = if (author == "human") "You" else "forge", answersName = null, media = emptyList(), embeds = emptyList(),
            reactions = emptyList(), acks = acks, deliveries = deliveries, questions = emptyList(), sending = sending,
        )

    private fun postOf(head: ThreadLine, replies: List<ThreadLine> = emptyList()): FeedItem.Post {
        val card = PostCard(head, projectName = null, activityAt = head.message.createdAt, replies = replies, unread = 0, tags = emptyList(), fields = emptyList(), apps = emptyList())
        val engine = EngineFeed(null, null, StreamStatus.Live, true, false, false, emptyList(), emptyList(), listOf(card), 0, false, emptyList())
        return mapper.map(engine, listOf(mega), emptyList(), emptyList(), null).sections.single().items.single() as FeedItem.Post
    }

    @Test
    fun anOwnersPostNobodyHasAfterFifteenSecondsIsNotDelivered() {
        assertTrue(postOf(line("m1", "human", noon - 20_000)).undelivered)
        assertEquals(false, postOf(line("m1", "human", noon - 5_000)).undelivered, "still in flight")
        val delivered = DeliveryView("d1", messageId = "m1", agentId = "a1")
        assertEquals(false, postOf(line("m1", "human", noon - 20_000, deliveries = listOf(delivered))).undelivered)
        val read = AckView("k1", agentId = "a1", card = CardRef(CardKinds.Message, "m1"), messageId = "m1", state = "read")
        assertEquals(false, postOf(line("m1", "human", noon - 20_000, acks = listOf(read))).undelivered)
        assertEquals(false, postOf(line("m1", "human", noon - 20_000, status = "todo")).undelivered, "work says where it has got to")
        assertEquals(false, postOf(line("m1", "agent:a1", noon - 20_000)).undelivered, "an agent's post is not waiting for an agent")
        assertEquals(false, postOf(line("m1", "human", noon - 20_000, sending = true)).undelivered, "still in the outbox")
    }

    @Test
    fun doneShowsApartFromReadAndADeliveryOnlyWhileItsAgentIsSilent() {
        fun ack(agent: String, state: String, on: String = "m1") =
            AckView("k-$agent-$on", agentId = agent, card = CardRef(CardKinds.Message, on), messageId = on, state = state)
        fun handed(agent: String, on: String = "m1") = DeliveryView("d-$agent-$on", messageId = on, agentId = agent)
        val post = postOf(
            line(
                "m1", "human", noon - 20_000,
                acks = listOf(ack("a1", "done"), ack("a2", "read"), ack("a3", "thinking")),
                deliveries = listOf(handed("a1"), handed("a2"), handed("a4"), handed("a4")),
            ),
            replies = listOf(line("m2", "human", noon - 10_000, acks = listOf(ack("a2", "done", on = "m2")), deliveries = listOf(handed("a1", on = "m2")))),
        )
        assertEquals(listOf("a1"), post.acks.done.map { it.id })
        assertEquals(listOf("a2"), post.acks.read.map { it.id }, "done is no longer folded into read")
        assertEquals(listOf("a3"), post.acks.thinking.map { it.id })
        assertEquals(listOf("a4"), post.acks.delivered.map { it.id }, "an agent that said something is not also 'delivered to'")
        val reply = post.replies.single()
        assertEquals(listOf("a2"), reply.acks.done.map { it.id })
        assertEquals(listOf("a1"), reply.acks.delivered.map { it.id })
    }

    @Test
    fun anOwnersReplyNobodyHasIsNotDelivered() {
        val post = postOf(
            line("m1", "agent:a1", noon - 60_000),
            replies = listOf(line("m2", "human", noon - 30_000), line("m3", "agent:a1", noon - 25_000), line("m4", "human", noon - 2_000)),
        )
        assertEquals(listOf(true, false, false), post.replies.map { it.undelivered })
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
    fun videosJoinThePicturesWhileSoundsAndFilesGetTheirOwnRows() {
        fun media(id: String, kind: String, variants: List<String>, status: String = "ready", durationMs: Long? = null, filename: String? = null, bytes: Long = 0, mime: String = "") =
            MediaView(id = id, kind = kind, status = status, variants = variants, durationMs = durationMs, filename = filename, bytes = bytes, mime = mime, width = 1280, height = 720)
        val card = update("u1", noon, "T", "B").let {
            it.copy(update = it.update.copy(media = listOf(
                media("img", "image", listOf("original", "thumb-1600")),
                media("vid", "video", listOf("original", "poster", "video"), durationMs = 42_000),
                media("raw", "video", listOf("original")),
                media("snd", "audio", listOf("original"), durationMs = 9_000, mime = "audio/mpeg"),
                media("doc", "file", listOf("original"), filename = "build.log", bytes = 1536, mime = "text/plain"),
                media("wip", "video", emptyList(), status = "pending"),
                media("bad", "image", emptyList(), status = "failed"),
            )))
        }
        val item = mapper.map(feed(listOf(card)), listOf(mega), emptyList(), emptyList(), null)
            .sections.single().items.single() as FeedItem.Update

        assertEquals(listOf(null, "https://dash.test/media/vid/video", "https://dash.test/media/raw/original", null, null), item.attachments.map { it.video?.url })
        // Not made yet, or never will be: each keeps its place and says so, with nothing to load.
        assertEquals(listOf(null, null, null, NotReady.Processing, NotReady.Failed), item.attachments.map { it.notReady })
        assertEquals(listOf("Processing…", "Media unavailable"), item.attachments.drop(3).map { it.altText })
        assertEquals(listOf(null, null), item.attachments.drop(3).map { it.url })
        assertEquals("https://dash.test/media/vid/poster", item.attachments[1].url)
        assertEquals(null, item.attachments[2].url, "no poster yet: the tile draws its own")
        assertEquals("0:42", item.attachments[1].video?.duration)
        assertEquals(listOf(Sound("snd", "https://dash.test/media/snd/original", 9_000, "audio/mpeg")), item.sounds)
        assertEquals(listOf(FileAttachment("doc", "https://dash.test/media/doc/original", "build.log", "1.5 KB", "text/plain")), item.files)
    }

    @Test
    fun postsAndRepliesCarryTheirPicturesVideosSoundsAndFiles() {
        fun media(id: String, kind: String, variants: List<String>, filename: String? = null, mime: String = "") =
            MediaView(id = id, kind = kind, status = "ready", variants = variants, filename = filename, bytes = 2048, mime = mime, width = 1280, height = 720, durationMs = 5_000)
        val head = line("m1", "human", noon - 60_000).copy(media = listOf(
            media("img", "image", listOf("original")),
            media("vid", "video", listOf("original", "poster", "video")),
            media("snd", "audio", listOf("original"), mime = "audio/mpeg"),
            media("doc", "file", listOf("original"), filename = "crash.log", mime = "text/plain"),
        ))
        val reply = line("m2", "agent:a1", noon - 30_000).copy(media = listOf(media("clip", "video", listOf("original", "poster", "video"))))
        val post = postOf(head, listOf(reply))

        assertEquals(listOf(null, "https://dash.test/media/vid/video"), post.attachments.map { it.video?.url })
        assertEquals(listOf("snd"), post.sounds.map { it.id })
        assertEquals(listOf("crash.log"), post.files.map { it.name })
        val r = post.replies.single()
        assertEquals(listOf("https://dash.test/media/clip/video"), r.attachments.map { it.video?.url })
        assertTrue(r.sounds.isEmpty() && r.files.isEmpty())
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
    fun aQuestionCarriesTheTalkAboutItWithItsAcks() {
        val thinking = AckView("k1", agentId = "a1", card = CardRef(CardKinds.Message, "d2"), messageId = "d2", state = "thinking")
        val talk = listOf(line("d1", "agent:a1", noon - 9_000), line("d2", "human", noon - 4_000, acks = listOf(thinking)))
        val asked = question("q1").copy(discussion = talk, unread = 1)
        val q = waitingOf(mapper.map(feed(emptyList(), waiting = listOf(asked)), listOf(mega), emptyList(), emptyList(), null)).single()
        assertEquals(listOf("d1", "d2"), q.replies.map { it.id })
        assertEquals(1, q.unreadReplies)
        assertEquals(listOf(true, false), q.replies.map { it.unread })
        assertEquals(listOf("a1"), q.replies[1].acks.thinking.map { it.id })
        assertTrue(q.replies[0].acks.thinking.isEmpty())
    }

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

    @Test
    fun aHighPriorityUpdateIsMarkedHigh() {
        val high = update("u1", noon, "Disk", "").let { it.copy(update = it.update.copy(priority = "high")) }
        val low = update("u2", noon - 1, "Other", "").let { it.copy(update = it.update.copy(priority = "low")) }
        val items = mapper.map(feed(listOf(high, low)), listOf(mega), emptyList(), emptyList(), null).sections.flatMap { it.items }
        assertEquals(mapOf("u1" to true, "u2" to false), items.filterIsInstance<FeedItem.Update>().associate { it.id to it.high })
    }
}
