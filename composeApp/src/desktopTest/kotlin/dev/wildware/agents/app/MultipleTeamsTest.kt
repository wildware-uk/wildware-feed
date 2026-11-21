package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.wildware.agents.MemoryOutboxStorage
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.Outbox
import dev.wildware.agents.RequestView
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.engine.AppModel
import dev.wildware.agents.app.engine.AppScreen
import dev.wildware.agents.app.engine.TeamEntry
import dev.wildware.agents.app.feed.AppNavigator
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One device in two teams: signing in to a second adds it, the switcher opens either, the one
 * that isn't open shows its questions and raises its own notices, an alert opens its own team,
 * and signing out of one leaves the other.
 */
@OptIn(ExperimentalTestApi::class)
class MultipleTeamsTest {
    private val a = FakeServer()
    private val b = FakeServer().apply {
        requests = listOf(
            RequestView(id = "rb1", seq = 8, agentId = "a1", projectId = "p1", kind = "buttons", question = "Deploy to staging?", options = listOf("Yes", "No"), expiresAt = FakeServer.now() + 3_600_000),
        )
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = MemorySessionStore(SavedSession("https://a.test", "key-1", "Test device", deviceId = "d1"))
    private val outbox = MemoryOutboxStorage()
    private val platform: AppPlatform

    init {
        // Two dashboards behind one engine, by address.
        a.otherHost = { request -> if (request.url.host == "b.test") with(b) { route(request) } else null }
        platform = AppPlatform(sessions, MemoryTextStore(), outbox, { a.engine() }, "Test device", imageCacheDir = null, teamList = MemoryTextStore())
    }

    @AfterTest
    fun tidy() {
        a.close()
        b.close()
        scope.cancel()
    }

    private fun model() = AppModel(platform, scope, accountService = "", watchIntervalMillis = 200)

    private suspend fun AppModel.openOn(server: String): AppScreen.Signed = withTimeout(10_000) {
        screen.first { (it as? AppScreen.Signed)?.session?.saved?.server == server } as AppScreen.Signed
    }

    private suspend fun AppModel.teamsWhere(check: (List<TeamEntry>) -> Boolean): List<TeamEntry> = withTimeout(10_000) { teams.first(check) }

    /** Signed in to team A, then team B added through sign-in, as "Add a team" does. */
    private suspend fun twoTeams(): AppModel {
        val model = model()
        model.openOn("https://a.test")
        model.startAddingTeam()
        assertEquals(AppScreen.SigningIn(adding = true), model.screen.value)
        model.signIn("https://b.test", "right", "Test device")
        model.openOn("https://b.test")
        return model
    }

    @Test
    fun `adding a team keeps the first and switching opens either`() = runBlocking {
        val model = twoTeams()
        val teams = model.teams.value
        assertEquals(listOf("a.test", "b.test"), teams.map { it.name })
        assertEquals(listOf(false, true), teams.map { it.active })
        assertEquals("https://b.test", sessions.load()?.server, "the open team's key is where the app's other parts read it")
        model.switchTeam(teams[0].key)
        model.openOn("https://a.test")
        assertEquals("https://a.test", sessions.load()?.server)
        assertEquals(2, model.teams.value.size, "switching signs nothing out")
        assertEquals(null, a.wrote("POST", "/api/devices/sign-out"))
    }

    @Test
    fun `a team that isn't open shows how many questions wait there`() = runBlocking {
        val model = twoTeams()
        val first = model.teamsWhere { list -> list.firstOrNull { !it.active }?.waiting == 1 }
        assertEquals("a.test", first.first { !it.active }.name)
        // Only the open team keeps a live feed: the other is asked for its small summary.
        assertTrue(a.count("/api/summary") > 0)
        assertEquals(0, b.count("/api/summary"), "the open team is never polled")
    }

    @Test
    fun `a new question in a team that isn't open is told about with that team`() = runBlocking {
        val model = twoTeams()
        model.teamsWhere { list -> list.firstOrNull { !it.active }?.waiting == 1 }
        val heard = scope.async { withTimeout(10_000) { model.otherTeamQuestions.first() } }
        delay(50)
        a.requests = a.requests + RequestView(id = "r2", seq = 9, agentId = "a1", projectId = "p1", kind = "buttons", question = "Merge the fix?", options = listOf("Yes"), expiresAt = FakeServer.now() + 3_600_000)
        val question = heard.await()
        assertEquals("https://a.test", question.server)
        assertEquals("a.test", question.teamName)
        assertEquals("Merge the fix?", question.request.question)
        assertEquals("mega", question.request.projectSlug)
        val notice = dev.wildware.agents.app.desktop.teamQuestionNotice(question)
        assertEquals("forge · a.test · Mega", notice.title)
        assertEquals("r2", notice.focusId)
        model.teamsWhere { list -> list.firstOrNull { !it.active }?.waiting == 2 }
        Unit
    }

    @Test
    fun `signing out of a team that isn't open ends its device and keeps the open one`() = runBlocking {
        val model = twoTeams()
        val other = model.teams.value.first { !it.active }
        model.signOutOf(other.key)
        withTimeout(10_000) { while (a.wrote("POST", "/api/devices/sign-out") == null) delay(20) }
        assertEquals(listOf("b.test"), model.teams.value.map { it.name })
        assertEquals("https://b.test", (model.screen.value as AppScreen.Signed).session.saved.server)
        assertEquals(null, b.wrote("POST", "/api/devices/sign-out"))
    }

    @Test
    fun `signing out of the open team opens the one left`() = runBlocking {
        val model = twoTeams()
        model.signOut()
        model.openOn("https://a.test")
        withTimeout(10_000) { while (b.wrote("POST", "/api/devices/sign-out") == null) delay(20) }
        assertEquals(listOf("a.test"), model.teams.value.map { it.name })
        assertEquals("https://a.test", sessions.load()?.server)
    }

    @Test
    fun `a team whose dashboard signed this device out is forgotten`() = runBlocking {
        val model = twoTeams()
        a.summaryRefused = true
        model.teamsWhere { list -> list.map { it.name } == listOf("b.test") }
        assertEquals("https://b.test", (model.screen.value as AppScreen.Signed).session.saved.server)
        Unit
    }

    @Test
    fun `what waits to send stays with its own team`() = runBlocking {
        a.failWrites = true
        outbox.save(Outbox.encode(listOf(Outbox.message("o1", 1L, "Reply", mapOf("updateId" to "u1"), "for team A", emptyList()))))
        val model = twoTeams()
        // Team B opened: team A's waiting reply is put by for A, never sent to B.
        assertTrue(Outbox.decode(outbox.load()).isEmpty())
        assertEquals(listOf("o1"), Outbox.decode(platform.teamFiles("").outbox.load()).map { it.id })
        delay(300)
        assertEquals(null, b.wrote("POST", "/api/messages"))
        // Back on A, it is A's to send again.
        model.switchTeam("")
        model.openOn("https://a.test")
        assertEquals(listOf("o1"), Outbox.decode(outbox.load()).map { it.id })
    }

    @Test
    fun `an alert from the team that isn't open opens that team at its card`() = runDesktopComposeUiTest(1280, 800) {
        val model = runBlocking { twoTeams() }
        val navigator = AppNavigator()
        setContent {
            Box(Modifier.size(1280.dp, 800.dp)) { AgentsApp(platform, model, dark = true, accessibility = AccessibilityPrefs(), navigator = navigator) }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Deploy to staging?").fetchSemanticsNodes().isNotEmpty() }
        // A tapped iPhone alert hands over its whole address; Android and the desktop hand over the team.
        navigator.openUrl("https://a.test/projects/mega?focus=u1")
        waitUntil(timeoutMillis = 10_000) { (model.screen.value as? AppScreen.Signed)?.session?.saved?.server == "https://a.test" }
        waitUntil(timeoutMillis = 10_000) { navigator.requests.value == null }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Fixed the unread counts").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `the sidebar switcher lists both teams with their questions and opens either`() = runDesktopComposeUiTest(1280, 800) {
        val model = runBlocking { twoTeams() }
        runBlocking { model.teamsWhere { list -> list.firstOrNull { !it.active }?.waiting == 1 } }
        setContent {
            Box(Modifier.size(1280.dp, 800.dp)) { AgentsApp(platform, model, dark = true, accessibility = AccessibilityPrefs()) }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("b.test").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("b.test").onFirst().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("a.test").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("Add a team").onFirst().assertExists()
        onAllNodesWithText("Sign out of b.test").onFirst().assertExists()
        onAllNodesWithText("a.test").onFirst().performClick()
        waitUntil(timeoutMillis = 10_000) { (model.screen.value as? AppScreen.Signed)?.session?.saved?.server == "https://a.test" }
        // Ctrl+Shift+2: the second team.
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Ship the new sounds?").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        onRoot().performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Two) } }
        }
        waitUntil(timeoutMillis = 10_000) { (model.screen.value as? AppScreen.Signed)?.session?.saved?.server == "https://b.test" }
    }

    @Test
    fun `the command bar offers each other team and adding one`() = runDesktopComposeUiTest(1280, 800) {
        val model = runBlocking { twoTeams() }
        setContent {
            Box(Modifier.size(1280.dp, 800.dp)) { AgentsApp(platform, model, dark = true, accessibility = AccessibilityPrefs()) }
        }
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Deploy to staging?").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } }
        onNodeWithContentDescription("Search or run a command").performTextInput("team")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Switch to a.test").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText("Add a team").onFirst().assertExists()
        onNodeWithText("Switch to a.test").performClick()
        waitUntil(timeoutMillis = 10_000) { (model.screen.value as? AppScreen.Signed)?.session?.saved?.server == "https://a.test" }
    }
}
