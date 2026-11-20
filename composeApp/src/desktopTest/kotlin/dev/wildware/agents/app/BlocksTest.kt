package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.test.performSemanticsAction
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

/**
 * Blocks (docs/BLOCKS.md) drawn natively on a card: every type at phone and desktop size, folding
 * and sorting, and the screenshots in docs/screenshots/blocks.
 */
@OptIn(ExperimentalTestApi::class)
class BlocksTest {
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

    private fun ComposeUiTest.phone(state: FeedState, dark: Boolean = false, events: MutableList<FeedEvent>? = null) = setContent {
        CompositionLocalProvider(LocalNow provides { noon }) {
            AgentTheme(dark = dark) { Box(Modifier.size(390.dp, 844.dp)) { FeedScreen(state, onEvent = { events?.add(it) }) } }
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

    // ---- each type, at both sizes -------------------------------------------------------------------

    private val shows: Map<BlockView, List<String>> = mapOf(
        checklist to listOf("Release 1.0.9", "2 of 5", "Write the release notes", "waiting on you", "Done by forge"),
        steps to listOf("iOS build", "45% · about 4 min left", "Unit tests", "412 of 960 passed", "2m 10s"),
        diff to listOf("Fix the badge count", "3 files", "+6  −3"),
        chart to listOf("p95 latency", "192 ms", "minutes since deploy"),
        table to listOf("Slowest endpoints", "9 rows", "Endpoint", "/api/snapshot", "Show all 9 rows"),
        log to listOf("Gradle", "40 lines", "BUILD FAILED in 41s", "Show all 40 lines"),
        poll to listOf("What should the release be called?", "Atlas", "Closes in 2 h", "3 votes · Ann, Raj, You"),
    )

    private fun ComposeUiTest.assertShows(block: BlockView) {
        for (text in shows.getValue(block)) {
            // A row a screen reader reads as one says its words in its description.
            val shown = hasText(text, substring = true) or hasContentDescription(text, substring = true, ignoreCase = true)
            bringIn(shown)
            onAllNodes(shown).onFirst().assertIsDisplayed()
        }
    }

    @Test
    fun everyTypeIsDrawnOnAPhone() {
        for (block in shows.keys) runDesktopComposeUiTest {
            phone(state(cards(block)))
            assertShows(block)
        }
    }

    @Test
    fun everyTypeIsDrawnOnDesktop() {
        for (block in shows.keys) runDesktopComposeUiTest(1440, 900) {
            desktop(state(cards(block)))
            assertShows(block)
        }
    }

    @Test
    fun aFailedStageAndAChartSayWhatTheyShow() = runDesktopComposeUiTest {
        phone(state(cards(failedSteps, bars)))
        bringIn(hasContentDescription("Step 3: Unit tests, failed", substring = true))
        onNode(hasContentDescription("Step 3: Unit tests, failed, 2 failed: BadgeCountTest, 1m 30s")).assertExists()
        onNode(hasContentDescription("Step 2: Lint, skipped")).assertExists()
        onNode(hasContentDescription("Bar chart, Crashes by day. Crashes: 5 points, from 2 to 14, last 2", substring = true)).assertExists()
    }

    // ---- folding: tables, logs, diffs ---------------------------------------------------------------

    @Test
    fun aTableSortsByAColumnAndUnfolds() = runDesktopComposeUiTest {
        phone(state(cards(table)))
        bringIn(hasContentDescription("Sort by Calls"))
        onNode(hasContentDescription("Sort by Calls")).performClick()
        waitForIdle()
        // Fewest calls first: /api/pair, then the blocks route.
        val first = onAllNodes(hasContentDescription("Endpoint:", substring = true)).fetchSemanticsNodes().first()
        assertTrue(first.config[SemanticsProperties.ContentDescription].single().startsWith("Endpoint: /api/pair,"))
        onNode(hasContentDescription("Sort by Calls")).performClick()
        waitForIdle()
        val most = onAllNodes(hasContentDescription("Endpoint:", substring = true)).fetchSemanticsNodes().first()
        assertTrue(most.config[SemanticsProperties.ContentDescription].single().startsWith("Endpoint: /mcp,"), "most calls first")
        assertEquals(5, onAllNodes(hasContentDescription("Endpoint:", substring = true)).fetchSemanticsNodes().size, "folded to its limit")
        onNode(hasText("Show all 9 rows")).performClick()
        waitForIdle()
        assertEquals(9, onAllNodes(hasContentDescription("Endpoint:", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun aLogShowsItsMarkedLineAndTailThenUnfolds() = runDesktopComposeUiTest {
        phone(state(cards(log)))
        bringIn(hasText("Show all 40 lines"))
        onNode(hasText("e: ReadMarks.kt:44:31 Unresolved reference 'isFromAgent'.")).assertExists()
        assertEquals(0, onAllNodes(hasText("(3)", substring = true)).fetchSemanticsNodes().size, "the early lines are folded away")
        onNode(hasText("Show all 40 lines")).performClick()
        waitForIdle()
        onNode(hasText("Fold")).assertExists()
        onAllNodes(hasText("BUILD FAILED in 41s")).onFirst().assertExists()
    }

    @Test
    fun aDiffOpensFileByFile() = runDesktopComposeUiTest {
        phone(state(cards(diff)))
        val file = hasContentDescription("iosApp/Widgets/StatusWidget.swift, 1 added, 1 removed")
        bringIn(file)
        assertEquals("Folded", onNode(file).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        onNode(file).performClick()
        waitForIdle()
        assertEquals("Open", onNode(file).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        onNode(hasContentDescription("The changes to iosApp/Widgets/StatusWidget.swift")).assertExists()
    }

    @Test
    fun aLongDiffUnfoldsIntoABoxOfItsOwn() = runDesktopComposeUiTest {
        val long = DiffFileView("big.kt", additions = 300, hunks = listOf(DiffHunkView("@@ -0,0 +1,300 @@", (1..300).map { "+val line$it = $it" })))
        phone(state(cards(diff.copy(id = "big", files = listOf(long)))))
        val file = hasContentDescription("big.kt, 300 added, 0 removed")
        bringIn(file)
        onNode(file).performClick()
        waitForIdle()
        bringIn(hasText("Show 181 more lines"))
        // By its action, not a tap: the reply box sits over the foot of the feed.
        onNode(hasText("Show 181 more lines")).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        bringIn(hasText("Fold"))
        onNode(hasText("Fold")).assertExists()
        // Only the lines in view are drawn, not all 300.
        assertTrue(onAllNodes(hasText("val line", substring = true)).fetchSemanticsNodes().size < 120)
    }

    // ---- screenshots --------------------------------------------------------------------------------

    private fun ComposeUiTest.save(name: String) {
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        val dir = File(outDir, "blocks").apply { mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    @Test
    fun screenshots() {
        val shots = linkedMapOf(
            "checklist" to (cards(checklist) to "Release 1.0.9"),
            "steps" to (cards(steps) to "Builds"),
            "steps-failed" to (cards(failedSteps) to "The Android build failed"),
            "diff" to (cards(diff.copy(files = diff.files.take(1))) to "The badge fix"),
            "diff-files" to (cards(diff) to "The badge fix, three files"),
            "chart" to (cards(chart) to "After the deploy"),
            "chart-bar" to (cards(bars) to "This week"),
            "table" to (cards(table) to "Slow endpoints"),
            "log" to (cards(log) to "The build failed"),
            "poll" to (cards(poll) to "A name for 1.0.9"),
        )
        for ((name, shot) in shots) for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            val (blocks, title) = shot
            runDesktopComposeUiTest(1170, 2532) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(3f), LocalNow provides { noon }) {
                        AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(state(blocks, title), SafeArea(top = 54.dp, bottom = 34.dp)) } }
                    }
                }
                save("phone-$mode-$name")
            }
            runDesktopComposeUiTest(2880, 1800) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2f), LocalNow provides { noon }) {
                        AgentTheme(dark = dark) { Box(Modifier.fillMaxSize()) { FeedScreen(state(blocks, title)) } }
                    }
                }
                save("desktop-$mode-$name")
            }
        }
    }
}
