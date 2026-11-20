package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.BlockActor
import dev.wildware.agents.BlockView
import dev.wildware.agents.CardKinds
import dev.wildware.agents.ChartSeries
import dev.wildware.agents.ChecklistItem
import dev.wildware.agents.DiffFileView
import dev.wildware.agents.DiffHunkView
import dev.wildware.agents.FounderId
import dev.wildware.agents.PollOption
import dev.wildware.agents.StepStage
import dev.wildware.agents.TableColumn
import dev.wildware.agents.withVote
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.CardBlock
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.LocalNow
import dev.wildware.agents.app.feed.SafeArea
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ticking a checklist line and voting in a poll (docs/BLOCKS.md): sent at once, and shown at once. */
@OptIn(ExperimentalTestApi::class)
class BlockInteractionTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    /** Noon on 20 November 2026, UTC: the clock every block is drawn against. */
    private val noon = 1_795_176_000_000L
    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()

    private val checklist = BlockView(
        id = "release", type = BlockView.Checklist, title = "Release 1.0.9", rev = 3,
        items = listOf(
            ChecklistItem("bump", "Bump the version to 1.0.9", done = true, doneBy = BlockActor("agent", "a2", "forge")),
            ChecklistItem("build", "TestFlight build", done = true, doneBy = BlockActor("person", FounderId, "Sam")),
            ChecklistItem("notes", "Write the release notes", owner = "forge"),
            ChecklistItem("store", "Approve the App Store text", owner = "you"),
            ChecklistItem("tag", "Tag the release"),
        ),
    )

    private val steps = BlockView(
        id = "ci", type = BlockView.Steps, title = "iOS build", rev = 7, progress = 0.45, eta = iso(noon + 4 * 60_000),
        stages = listOf(
            StepStage("checkout", "Check out", "done", startedAt = iso(noon - 200_000), endedAt = iso(noon - 188_000)),
            StepStage("compile", "Compile", "done", startedAt = iso(noon - 188_000), endedAt = iso(noon - 58_000)),
            StepStage("test", "Unit tests", "running", detail = "412 of 960 passed", startedAt = iso(noon - 58_000)),
            StepStage("archive", "Archive"),
            StepStage("upload", "Upload to TestFlight"),
        ),
    )

    private val failedSteps = steps.copy(
        id = "ci-failed", title = "Android build", progress = 0.6, eta = null,
        stages = listOf(
            StepStage("compile", "Compile", "done", startedAt = iso(noon - 300_000), endedAt = iso(noon - 120_000)),
            StepStage("lint", "Lint", "skipped"),
            StepStage("test", "Unit tests", "failed", detail = "2 failed: BadgeCountTest", startedAt = iso(noon - 120_000), endedAt = iso(noon - 30_000)),
        ),
    )

    private val diff = BlockView(
        id = "fix", type = BlockView.Diff, title = "Fix the badge count", rev = 1,
        files = listOf(
            DiffFileView(
                "shared/src/commonMain/kotlin/dev/wildware/agents/ReadMarks.kt", additions = 3, deletions = 2,
                hunks = listOf(DiffHunkView("@@ -41,7 +41,8 @@ class ReadMarks", listOf(
                    "     fun unread(cardId: String, lines: List<MessageView>): Int {",
                    "-        val seen = seenAt[cardId] ?: 0",
                    "-        return lines.count { it.createdAt > seen }",
                    "+        val seen = seenAt[cardId] ?: return lines.count { it.isFromAgent }",
                    "+        // Only agents' lines count: your own are read the moment you send them.",
                    "+        return lines.count { it.isFromAgent && it.createdAt > seen }",
                    "     }",
                ))),
            ),
            DiffFileView("iosApp/Widgets/StatusWidget.swift", additions = 1, deletions = 1, hunks = listOf(DiffHunkView("@@ -12 +12 @@", listOf("-        Text(\"\\(count)\")", "+        Text(count > 99 ? \"99+\" : \"\\(count)\")")))),
            DiffFileView("docs/RELEASE.md", status = "added", additions = 2, hunks = listOf(DiffHunkView("@@ -0,0 +1,2 @@", listOf("+# 1.0.9", "+The badge counts only agents' lines.")))),
        ),
    )

    private val chart = BlockView(
        id = "latency", type = BlockView.Chart, title = "p95 latency", kind = "line", unit = "ms", xLabel = "minutes since deploy", rev = 12,
        series = listOf(
            ChartSeries("p95", "p95", listOf(340, 352, 330, 298, 260, 231, 210, 205, 199, 201, 196, 192).mapIndexed { i, y -> listOf(JsonPrimitive(i * 5), JsonPrimitive(y)) }),
            ChartSeries("p50", "p50", listOf(120, 118, 117, 110, 102, 96, 92, 90, 88, 89, 87, 86).mapIndexed { i, y -> listOf(JsonPrimitive(i * 5), JsonPrimitive(y)) }),
        ),
    )

    private val bars = BlockView(
        id = "crashes", type = BlockView.Chart, title = "Crashes by day", kind = "bar", rev = 2,
        series = listOf(ChartSeries("crashes", "Crashes", listOf("Mon" to 14, "Tue" to 9, "Wed" to 11, "Thu" to 4, "Fri" to 2).map { (d, n) -> listOf(JsonPrimitive(d), JsonPrimitive(n)) })),
    )

    private val table = BlockView(
        id = "slow", type = BlockView.Table, title = "Slowest endpoints", rev = 1, limit = 5,
        columns = listOf(TableColumn("Endpoint"), TableColumn("Calls", "number"), TableColumn("p95 ms", "number")),
        rows = listOf(
            "/api/snapshot" to (12_400 to 412), "/api/messages" to (40_210 to 188), "/api/stream" to (2_100 to 95), "/api/media" to (3_300 to 730),
            "/api/summary" to (9_800 to 61), "/api/cards/update/x/blocks" to (1_204 to 22), "/api/notifications" to (7_700 to 140), "/mcp" to (55_000 to 310),
        ).map { (path, n) -> listOf(JsonPrimitive(path), JsonPrimitive(n.first), JsonPrimitive(n.second)) } + listOf(listOf(JsonPrimitive("/api/pair"), JsonPrimitive(3), JsonNull)),
    )

    private val log = BlockView(
        id = "gradle", type = BlockView.Log, title = "Gradle", rev = 40, tail = 5, dropped = 0,
        highlight = "e: ReadMarks.kt:44:31 Unresolved reference 'isFromAgent'.",
        lines = (1..36).map { "> Task :shared:compileKotlinJvm UP-TO-DATE ($it)" } + listOf(
            "e: ReadMarks.kt:44:31 Unresolved reference 'isFromAgent'.",
            "warning: the -Xjsr305 flag is deprecated",
            "> Task :shared:compileKotlinJvm FAILED",
            "BUILD FAILED in 41s",
        ),
    )

    private val poll = BlockView(
        id = "name", type = BlockView.Poll, title = "Naming", question = "What should the release be called?", rev = 4, totalVoters = 3,
        closesAt = iso(noon + 2 * 3_600_000),
        options = listOf(
            PollOption("atlas", "Atlas", 2, listOf(BlockActor("person", "m2", "Ann"), BlockActor("person", "m3", "Raj"))),
            PollOption("beacon", "Beacon", 1, listOf(BlockActor("person", FounderId, "Sam"))),
            PollOption("cobalt", "Cobalt"),
        ),
    )

    private fun cards(vararg blocks: BlockView, team: Boolean = true) = blocks.map { CardBlock(CardKinds.Update, "u1", it, FounderId, team) }

    /** The morning's feed with only the fix card, now carrying [blocks]. */
    private fun state(blocks: List<CardBlock>, title: String = "Release 1.0.9"): FeedState {
        val base = FakeFeed.state
        val fix = base.sections.flatMap { it.items }.first { it.id == "u1" } as FeedItem.Update
        val card = fix.copy(title = title, body = "Here is where it stands.", attachments = emptyList(), replies = emptyList(), unreadReplies = 0, refs = emptyList(), blocks = blocks)
        return base.copy(openThread = null, sections = listOf(base.sections[1].copy(items = listOf(card))))
    }

    /**
     * The feed on a phone, with a stand-in for the engine: a tick or a vote shows at once, as the
     * engine shows it (its refusals are tested in the shared engine's tests).
     */
    private fun ComposeUiTest.phone(state: FeedState, dark: Boolean = false, events: MutableList<FeedEvent>? = null) {
        val shown = mutableStateOf(state)
        setContent {
            CompositionLocalProvider(LocalNow provides { noon }) {
                AgentTheme(dark = dark) {
                    Box(Modifier.size(390.dp, 844.dp)) {
                        FeedScreen(shown, onEvent = { event ->
                            events?.add(event)
                            shown.value = engine(shown.value, event)
                        })
                    }
                }
            }
        }
    }

    private fun engine(state: FeedState, event: FeedEvent): FeedState {
        fun change(blockId: String, how: (BlockView) -> BlockView) = state.copy(sections = state.sections.map { section ->
            section.copy(items = section.items.map { item ->
                if (item !is FeedItem.Update) item else item.copy(blocks = item.blocks.map { if (it.block.id == blockId) it.copy(block = how(it.block)) else it })
            })
        })
        return when (event) {
            is FeedEvent.CheckBlockItem -> change(event.blockId) { b -> b.copy(items = b.items.map { if (it.id == event.itemId) it.copy(done = event.done) else it }) }
            is FeedEvent.VoteBlock -> change(event.blockId) { it.withVote(event.options, FounderId) }
            else -> state
        }
    }

    private fun ComposeUiTest.desktop(state: FeedState, events: MutableList<FeedEvent>? = null) = setContent {
        CompositionLocalProvider(LocalNow provides { noon }) {
            AgentTheme(dark = true) { FeedScreen(state, onEvent = { events?.add(it) }) }
        }
    }

    private fun ComposeUiTest.bringIn(matcher: SemanticsMatcher) {
        onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher)
        waitForIdle()
    }

    @Test
    fun tickingALineSendsItAndShowsItAtOnce() = runDesktopComposeUiTest {
        val events = mutableListOf<FeedEvent>()
        phone(state(cards(checklist)), events = events)
        val line = hasContentDescription("Write the release notes, waiting on forge")
        bringIn(line)
        val before = onNode(line).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)
        assertEquals(ToggleableState.Off, before)
        onNode(line).performClick()
        waitForIdle()
        assertEquals(FeedEvent.CheckBlockItem(CardKinds.Update, "u1", "release", "notes", true), events.single())
        assertEquals(ToggleableState.On, onNode(line).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
        onNode(hasText("3 of 5")).assertExists()

        // And back.
        onNode(line).performClick()
        waitForIdle()
        assertEquals(FeedEvent.CheckBlockItem(CardKinds.Update, "u1", "release", "notes", false), events.last())
        onNode(hasText("2 of 5")).assertExists()
    }

    @Test
    fun aTickedLineSaysWhoTickedIt() = runDesktopComposeUiTest(1440, 900) {
        desktop(state(cards(checklist)))
        onNode(hasContentDescription("TestFlight build, done by you")).assertExists()
        onNode(hasContentDescription("Bump the version to 1.0.9, done by forge")).assertExists()
        onNode(hasContentDescription("Approve the App Store text, waiting on you")).assertExists()
    }

    @Test
    fun votingSendsYourChoiceAndMovesYourVote() = runDesktopComposeUiTest {
        val events = mutableListOf<FeedEvent>()
        phone(state(cards(poll)), events = events)
        val atlas = hasContentDescription("Atlas, 2 votes")
        bringIn(atlas)
        onNode(hasContentDescription("Beacon, 1 vote")).assertExists()
        assertEquals(true, onNode(hasContentDescription("Beacon, 1 vote")).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        onNode(atlas).performClick()
        waitForIdle()
        assertEquals(FeedEvent.VoteBlock(CardKinds.Update, "u1", "name", listOf("atlas")), events.single())
        onNode(hasContentDescription("Atlas, 3 votes")).assertExists()
        onNode(hasContentDescription("Beacon, 0 votes")).assertExists()
        // Your choice again takes your vote back.
        onNode(hasContentDescription("Atlas, 3 votes")).performClick()
        waitForIdle()
        assertEquals(FeedEvent.VoteBlock(CardKinds.Update, "u1", "name", emptyList()), events.last())
        onNode(hasText("2 votes", substring = true)).assertExists()
    }

    @Test
    fun aClosedPollTakesNoVotes() = runDesktopComposeUiTest {
        phone(state(cards(poll.copy(closed = true))))
        bringIn(hasContentDescription("Atlas, 2 votes"))
        onNode(hasText("Closed")).assertExists()
        val node = onNode(hasContentDescription("Atlas, 2 votes")).fetchSemanticsNode()
        assertNull(node.config.getOrNull(SemanticsActions.OnClick), "nothing to press")
    }

}
