package dev.wildware.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecentActivityTest {
    private val gardenGame = ProjectRef("garden-game", "Garden Game")
    private val fellas = ProjectRef("harbor-app", "Harbor App")
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun `markdown loses its marks but keeps its words`() {
        val markdown = """
            ## Release **ready**

            The `fee_rounding` branch is _merged_, see [the PR](https://example.com/1).
            Score is 2 * 3 * 4 and my_file_name stays.

            - first
            - [x] second
            1. one
            > quoted ~~old~~ new

            ```
            npm test   # *not* emphasis
            ```
            ---
            | a | b |
            |---|---|
            ![chart](https://example.com/c.png) and \*literal\*
        """.trimIndent()

        assertEquals(
            listOf(
                "Release ready",
                "The fee_rounding branch is merged, see the PR.",
                "Score is 2 * 3 * 4 and my_file_name stays.",
                "• first",
                "• second",
                "1. one",
                "quoted old new",
                "npm test   # *not* emphasis",
                "a · b",
                "chart and *literal*",
            ).joinToString("\n"),
            PlainText.fromMarkdown(markdown),
        )
    }

    @Test
    fun `sentences are counted across lines and clipped at a word`() {
        val text = "Merged the branch. Tests pass! Deploying next.\n• one more"
        assertEquals("Merged the branch. Tests pass!", PlainText.sentences(text, 2))
        assertEquals("Deploying next. one more.", PlainText.sentences(text, 2, skip = 2))
        assertEquals("Merged the…", PlainText.sentences(text, 1, maxLength = 14))
    }

    @Test
    fun `time ago is said in words`() {
        assertEquals("just now", TimeAgo.spoken(now - 10_000, now))
        assertEquals("a minute ago", TimeAgo.spoken(now - 70_000, now))
        assertEquals("five minutes ago", TimeAgo.spoken(now - 5 * minute, now))
        assertEquals("an hour ago", TimeAgo.spoken(now - 50 * minute, now))
        assertEquals("three hours ago", TimeAgo.spoken(now - 3 * 60 * minute, now))
        assertEquals("yesterday", TimeAgo.spoken(now - 26 * 60 * minute, now))
        assertEquals("four days ago", TimeAgo.spoken(now - 4 * 24 * 60 * minute, now))
        assertEquals("just now", TimeAgo.spoken(now + 5_000, now))
    }

    @Test
    fun `siri reads who posted last and when and the first two sentences`() {
        val activity = activity(
            update("u1", gardenGame, title = "Merged", text = "Merged\nAll tests pass. The fee bug is gone. Next I will deploy.", at = now - 5 * minute),
        )

        assertEquals(
            "Builder posted five minutes ago: Merged. All tests pass. The fee bug is gone.",
            Speech.latest(gardenGame, activity, now),
        )
    }

    @Test
    fun `an untitled update is read from its first sentences`() {
        val activity = activity(update("u1", gardenGame, title = null, text = "Started on the fee bug. It looks small. Will report.", at = now - 2 * minute))

        assertEquals("Builder posted two minutes ago: Started on the fee bug. It looks small.", Speech.latest(gardenGame, activity, now))
    }

    @Test
    fun `a newer message from an agent is the last word`() {
        val activity = RecentActivity(
            projects = listOf(gardenGame),
            updates = listOf(update("u1", gardenGame, title = "Merged", text = "All done.", at = now - 60 * minute)),
            messages = listOf(
                RecentMessage("m1", gardenGame, "Reviewer", "Found a typo in the readme. Fixed it.", now - 30_000),
                RecentMessage("m0", fellas, "Other", "Not this project.", now),
            ),
        )

        assertEquals(
            "Builder posted an hour ago: Merged. All done. Since then, Reviewer said just now: Found a typo in the readme. Fixed it.",
            Speech.latest(gardenGame, activity, now),
        )
    }

    @Test
    fun `a quiet project says so`() {
        assertEquals("Nothing from Garden Game yet.", Speech.latest(gardenGame, activity(), now))
    }

    @Test
    fun `a widget shows its project's news and only its questions`() {
        val summary = summary(
            request("r1", gardenGame, "Merge now?"),
            request("r2", fellas, "Ship it?"),
            request("r3", gardenGame, "Delete the old branch?"),
        )
        val activity = activity(
            update("u2", fellas, title = "Elsewhere", text = "x", at = now),
            update("u1", gardenGame, title = null, text = "Merged the branch. Tests pass. Deploying.", at = now - minute),
        )

        val medium = ProjectCard.build("garden-game", summary, activity, CardSize.Medium)!!
        assertEquals("Merged the branch.", medium.headline)
        assertEquals("Tests pass. Deploying.", medium.excerpt)
        assertEquals(listOf("r1"), medium.questions.map { it.id })
        assertEquals(2, medium.waiting)
        assertEquals("2 questions waiting", medium.statusLine)

        val large = ProjectCard.build("garden-game", summary, activity, CardSize.Large)!!
        assertEquals(listOf("r1", "r3"), large.questions.map { it.id })

        val titled = activity(update("u3", gardenGame, title = "Merged", text = "Merged\nTests pass.", at = now))
        assertEquals("Tests pass.", ProjectCard.build("garden-game", summary, titled, CardSize.Large)!!.excerpt)

        val accessory = ProjectCard.build("harbor-app", summary, activity, CardSize.Accessory)!!
        assertEquals("", accessory.excerpt)
        assertEquals("1 question waiting", accessory.statusLine)
    }

    @Test
    fun `a widget for a quiet project or a deleted one`() {
        val card = ProjectCard.build("garden-game", summary(), activity(), CardSize.Small)!!
        assertEquals("Nothing posted yet", card.headline)
        assertEquals("Nothing posted yet", card.statusLine)

        assertNull(ProjectCard.build("deleted", summary(), activity(), CardSize.Small))
    }

    @Test
    fun `spotlight finds projects and their updates and forgets what's gone`() {
        val gone = ProjectRef("archived", "Archived")
        val activity = activity(
            update("u1", gardenGame, title = "Merged", text = "Merged\nAll tests pass.", at = now),
            update("u2", gone, title = "Old news", text = "x", at = now - minute),
        )

        val entries = Spotlight.entries(activity)

        assertEquals(listOf("/projects/garden-game", "/projects/harbor-app", "/projects/garden-game?focus=u1"), entries.map { it.id })
        assertEquals("Builder: Merged", entries[0].text)
        assertEquals("A project in Wildware Feed", entries[1].text)
        assertEquals("Builder in Garden Game: Merged All tests pass.", entries[2].text)
        assertEquals(now, entries[2].createdAt)
        assertEquals(
            listOf("/projects/archived", "/projects/garden-game?focus=u0"),
            Spotlight.removed(listOf("/projects/archived", "/projects/garden-game", "/projects/garden-game?focus=u0"), entries),
        )
    }

    private fun activity(vararg updates: RecentUpdate) =
        RecentActivity(projects = listOf(gardenGame, fellas), updates = updates.toList(), messages = emptyList())

    private fun update(id: String, project: ProjectRef, title: String?, text: String, at: Long) =
        RecentUpdate(id, project, "Builder", title, text, at)

    private fun summary(vararg requests: PendingRequest) = DashboardSummary(
        at = "2026-11-16T12:00:00Z",
        onlineAgents = emptyList(),
        requests = requests.toList(),
        work = WorkCounts(0, 0, 0),
        projects = listOf(gardenGame, fellas),
    )

    private fun request(id: String, project: ProjectRef, question: String) = PendingRequest(
        id = id,
        agentName = "Builder",
        projectSlug = project.slug,
        projectName = project.name,
        question = question,
        kind = RequestKind.Confirm,
        expiresAt = "2026-11-16T13:00:00Z",
    )
}
