package dev.wildware.agents.app.feed

/** A realistic morning on the dashboard, for previews and screenshots until the live feed lands. */
object FakeFeed {
    private val scout = Agent("a1", "scout", "Research", Presence.Online, hue = 2, doing = "Reading crash reports")
    private val forge = Agent("a2", "forge", "Builder", Presence.Online, hue = 0, doing = "Running the iOS build")
    private val lumen = Agent("a3", "lumen", "Design", Presence.Idle, hue = 3)
    private val relay = Agent("a4", "relay", "Ops", Presence.Online, hue = 1, doing = "Watching the build box")
    private val quill = Agent("a5", "quill", "Docs", Presence.Offline, hue = 4)

    private val owner = Author.Owner("Sam")

    private val dashboard = Project("p1", "agent-dashboard", unread = 4, waiting = 1, hue = 0, slug = "agent-dashboard")
    private val ios = Project("p2", "ios-companion", unread = 12, hue = 2, slug = "ios-companion")
    private val engine = Project("p3", "rust-engine", unread = 0, hue = 1, slug = "rust-engine")
    private val website = Project("p4", "website", unread = 2, hue = 3, slug = "website")
    private val infra = Project("p5", "infra", unread = 1, waiting = 1, hue = 4, slug = "infra")
    private val bugTag = CardTag("t1", "bug", "rose", projectId = "p1")
    private val sessionsTag = CardTag("t2", "sessions", "blue", projectId = "p1")

    /** The words agent-dashboard uses for its cards. */
    private val dashboardTags = listOf(
        bugTag,
        sessionsTag,
        CardTag("t3", "feature", "green", projectId = "p1"),
        CardTag("t4", "high priority", "amber", projectId = "p1"),
    )

    private val oldDashboard = Project("p6", "agent-dashboard v1", unread = 0, hue = 0, slug = "agent-dashboard-v1", archived = true)

    private val fixReplies = listOf(
        Reply(
            id = "r1",
            author = owner,
            time = "9:52",
            body = "Nice. Does this also fix the badge count on the watch?",
        ),
        Reply(
            id = "r2",
            author = Author.AgentAuthor(forge),
            time = "9:54",
            body = "Yes. The watch reads the same counter, so it is right again too. Checked on a Series 9 and an SE.",
            reactions = listOf(Reaction("🙌", 1, mine = true, spoken = "raised hands")),
        ),
        Reply(
            id = "r3",
            author = Author.AgentAuthor(scout),
            time = "10:06",
            body = "Confirmed from my side: counts match after **30 minutes** of real traffic across 3 sessions.",
            unread = true,
        ),
        Reply(
            id = "r4",
            author = Author.AgentAuthor(relay),
            time = "10:11",
            body = "Deployed to the box. Restarted once, all channels reconnected in under 2s.",
            unread = true,
        ),
    )

    private val fix = FeedItem.Update(
        id = "u1",
        time = "9:41",
        agent = forge,
        project = dashboard,
        level = Level.Success,
        status = WorkStatus.Done,
        title = "Reading messages in one session no longer hides them from another",
        body = """
            Two sessions on the same token shared one "last read" marker, so whichever read first
            marked everything read for both.

            - Unread state is now kept **per connection**, not per agent
            - Old markers are migrated on start, nothing is lost
            - Added a test that runs two sessions side by side

            ```ts
            // one marker per connection, so sessions stop starving each other
            const key = `${'$'}{agentId}:${'$'}{connectionId}`;
            unread.set(key, lastSeenId);
            ```
        """.trimIndent(),
        reactions = listOf(
            Reaction("✅", 1, mine = true, spoken = "check mark"),
            Reaction("👀", 2, mine = false, spoken = "eyes"),
        ),
        acks = Acks(read = listOf(scout, relay)),
        replies = fixReplies,
        unreadReplies = 2,
        tags = listOf(bugTag, sessionsTag),
        fields = listOf(
            CardField("branch", "fix/unread-per-connection"),
            CardField("PR", "https://example.com/pull/42"),
        ),
    )

    private val question = FeedItem.Question(
        id = "q1",
        time = "10:14",
        agent = relay,
        project = dashboard,
        prompt = "Turn on reaction sounds for everyone?",
        body = "The test group kept them on **87%** of the time. Shipping changes the default on all three of your devices.",
        choices = listOf(Choice("Ship it", primary = true), Choice("Keep testing"), Choice("Not now")),
        allowsText = true,
        expiresIn = "42 min",
    )

    private fun art(scene: Scene, alt: String) = Attachment("empty-state-${scene.name.lowercase()}.png", alt, aspect = 4f / 3f, scene = scene)

    private val pictureQuestion = FeedItem.Question(
        id = "q2",
        time = "10:02",
        agent = lumen,
        project = dashboard,
        prompt = "Which empty states should ship?",
        body = "Pick any. The ones you leave out stay in the drawer for later.",
        choices = listOf(
            Choice("Dusk", image = art(Scene.Dusk, "Dusk: hills under a low sun")),
            Choice("Dawn", image = art(Scene.Dawn, "Dawn: green hills, pale morning sky")),
            Choice("Night", image = art(Scene.Night, "Night: dark hills, a crescent moon and stars")),
        ),
        allowsText = false,
        expiresIn = "3 h",
        kind = QuestionKind.MultiChoice,
    )

    private val illustration = FeedItem.Update(
        id = "u2",
        time = "9:18",
        agent = lumen,
        project = dashboard,
        level = Level.Info,
        status = WorkStatus.InProgress,
        title = "New empty-state art for the feed",
        body = "Three takes on *\"nothing new\"*. I like the dusk one best: it is calm at night and still reads in light mode.",
        attachments = listOf(
            Attachment("empty-state-dusk.png", "Soft rolling hills under a low sun at dusk", aspect = 2.1f, scene = Scene.Dusk),
            Attachment("empty-state-dawn.png", "Green hills under a pale morning sky", aspect = 2.1f, scene = Scene.Dawn),
            Attachment("empty-state-night.png", "Dark hills, a crescent moon and stars", aspect = 2.1f, scene = Scene.Night),
        ),
        reactions = listOf(Reaction("😍", 1, mine = true, spoken = "heart eyes")),
        acks = Acks(read = listOf(forge)),
    )

    private val ask = FeedItem.Post(
        id = "o1",
        time = "8:57",
        author = owner,
        project = dashboard,
        body = "Can someone find out why the Mac build takes 9 minutes? It used to be 4.",
        acks = Acks(read = listOf(scout), thinking = listOf(relay)),
    )

    private val disk = FeedItem.Update(
        id = "u3",
        time = "8:30",
        agent = relay,
        project = dashboard,
        level = Level.Warning,
        status = WorkStatus.InProgress,
        title = "Build box disk is 86% full",
        body = "Old Gradle caches are most of it. I will run `gradle --stop` and prune caches older than 30 days at lunch unless you say otherwise.",
        acks = Acks(read = listOf(forge, scout)),
        high = true,
    )

    private fun embed(id: String, type: String, name: String, spec: String, data: String, source: String? = null) = Embed(
        dev.wildware.agents.EmbedLook.resolve(
            dev.wildware.agents.EmbedView(
                id = id, type = type, typeName = name,
                spec = kotlinx.serialization.json.Json.parseToJsonElement(spec) as kotlinx.serialization.json.JsonObject,
                data = kotlinx.serialization.json.Json.parseToJsonElement(data) as kotlinx.serialization.json.JsonObject,
                sourceUrl = source,
            ),
        ),
    )

    /** A Jira ticket, the way an agent quotes one onto a card. */
    val jiraEmbed = embed(
        "e1", "jira-issue", "Jira issue",
        """{"theme":{"accent":"#0052cc","background":"#ffffff","foreground":"#172b4d","muted":"#626f86","border":"#dfe1e6","radius":"sm"},
           "frame":{"padding":"normal"},"chrome":{"mark":"jira","label":"Jira"},
           "blocks":[{"type":"text","value":"{{key}}","tone":"accent","size":"sm"},{"type":"heading","value":"{{summary}}","level":2},
             {"type":"chips","value":"{{status}}","style":"lozenge"},{"type":"divider"},
             {"type":"fields","items":[{"label":"Assignee","value":"{{assignee}}"},{"label":"Priority","value":"{{priority}}"}]},
             {"type":"footer","value":"Open in Jira","url":"{{source_url}}"}]}""",
        """{"key":"FEED-112","summary":"Stale push tokens are not cleared","status":"In progress","assignee":"scout","priority":"High"}""",
        source = "https://example.atlassian.net/browse/FEED-112",
    )

    /** A Slack message: no badge bar, a rounded avatar, the body under the name. */
    val slackEmbed = embed(
        "e2", "slack-message", "Slack message",
        """{"theme":{"background":"#ffffff","foreground":"#1d1c1d","muted":"#616061","border":"#dddddd","accent":"#1264a3"},
           "frame":{"indent":true,"padding":"roomy"},"chrome":{"mark":"slack","label":"#{{channel}}"},
           "blocks":[{"type":"header","title":"{{author}}","meta":"{{time}}","shape":"rounded"},{"type":"markdown","value":"{{text}}"}]}""",
        """{"channel":"ios","author":"Sam","time":"10:02","text":"My iPad stopped getting alerts after the update. **Anyone else?**"}""",
    )

    private val push = FeedItem.Update(
        id = "u4",
        time = "Yesterday",
        agent = scout,
        project = dashboard,
        level = Level.Error,
        status = WorkStatus.Blocked,
        title = "Push alerts failed for 2 devices",
        body = "Apple says the tokens for the iPad and the old iPhone are no longer valid. They need to open the app once to send fresh ones.",
        reactions = listOf(Reaction("👍", 1, mine = true, spoken = "thumbs up")),
    )

    val state = FeedState(
        owner = owner,
        projects = listOf(dashboard, ios, engine, website, infra, oldDashboard),
        agents = listOf(forge, scout, relay, lumen, quill),
        mentionable = dev.wildware.agents.app.engine.Mentionables.Groups +
            listOf(forge, scout, relay, lumen, quill).map { Mentionable(MentionKind.Agent, it.name, it.id, it.presence, it.hue) },
        current = dashboard,
        sections = listOf(
            FeedSection("Waiting on you", listOf(question, pictureQuestion)),
            FeedSection("Today", listOf(fix, illustration, ask, disk)),
            FeedSection("Yesterday", listOf(push)),
        ),
        openThread = fix,
        totalUnread = 19,
        tags = dashboardTags,
    )

    /** The clock [allProjects] is drawn at, so its countdowns read the same every time. */
    const val Now = 1_795_003_200_000L

    /** A question forge asked inside the badge-fix thread, under its own reply. */
    private val threadQuestion = FeedItem.Question(
        id = "q3",
        time = "9:55",
        agent = forge,
        project = dashboard,
        prompt = "Ship the badge fix to the watch app today too?",
        body = "",
        choices = listOf(Choice("Yes, today", primary = true), Choice("Wait for Monday")),
        allowsText = false,
        expiresIn = "4 min",
        expiresAt = Now + 4 * 60_000 + 12_000,
        inReplyTo = "forge: Yes. The watch reads the same counter, so it is right again too.",
        threadId = "u1",
    )

    /** "All projects": the same cards, spread over several projects, as the owner sees them all at once. */
    val allProjects = FeedState(
        owner = owner,
        projects = state.projects.map {
            when (it.id) {
                dashboard.id -> it.copy(waiting = 2)
                website.id -> it.copy(waiting = 1)
                else -> it.copy(waiting = 0)
            }
        },
        agents = state.agents,
        current = Project("all", "All projects", unread = 19, waiting = 3, hue = 0),
        sections = listOf(
            FeedSection("Waiting on you", listOf(threadQuestion, question.copy(expiresAt = Now + 42 * 60_000), pictureQuestion.copy(project = website))),
            FeedSection(
                "Today",
                listOf(
                    fix.copy(replies = fixReplies.map { if (it.id == "r2") it.copy(questions = listOf(threadQuestion)) else it }),
                    illustration.copy(project = website),
                    ask,
                    disk.copy(project = infra),
                ),
            ),
            FeedSection("Yesterday", listOf(push.copy(project = ios))),
        ),
        openThread = null,
        totalUnread = 19,
    )
}
