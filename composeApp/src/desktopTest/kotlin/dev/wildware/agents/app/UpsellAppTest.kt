package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.feed.UpsellMarks
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Team plan offered in the running app, against a fake dashboard: the popup and the files note
 * for a free team's owner, nothing for a member, and nothing at all, with a refusal in plain words,
 * where the app may not link to buying (an iPhone outside the US App Store).
 */
@OptIn(ExperimentalTestApi::class)
class UpsellAppTest {
    private val server = FakeServer().apply { requests = emptyList() }
    private val marks = MemoryTextStore()
    private val day = 24L * 60 * 60 * 1000

    @AfterTest
    fun stop() = server.close()

    private fun freeTeam(role: String, agents: Int = 1, madeDaysAgo: Int = 10) {
        server.me = """{"person":{"id":"m-ann","name":"Ann","handle":"ann","role":"$role"},"team":true}"""
        server.people = """{"people":[{"id":"m-ann","name":"Ann","handle":"ann","role":"$role"}]}"""
        server.plan = """{"plan":"free","limits":{"people":2,"agents":3,"storageBytes":524288000,"historyDays":30},""" +
            """"used":{"people":1,"agents":$agents,"storageBytes":${450L * 1024 * 1024}},"upgradeUrl":"https://billing.test/teams/acme",""" +
            """"createdAt":${System.currentTimeMillis() - madeDaysAgo * day}}"""
    }

    private fun ComposeUiTest.app(links: Boolean = true) {
        val platform = AppPlatform.inMemory(
            engine = { server.engine() },
            sessions = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device", deviceId = "d1")),
            buyingLinks = MutableStateFlow(links),
            upsellMarks = marks,
        )
        setContent { Box(Modifier.size(1440.dp, 900.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) } }
    }

    private fun ComposeUiTest.waitFor(text: String, substring: Boolean = false) = try {
        waitUntil(timeoutMillis = 20_000) { onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText(text, substring = substring)).onFirst()
    } catch (timeout: Throwable) {
        throw AssertionError("never saw \"$text\"; the screen had:\n" + onAllNodes(isRoot()).printToString(), timeout)
    }

    private fun ComposeUiTest.hasNo(matcher: SemanticsMatcher) = onAllNodes(matcher).fetchSemanticsNodes().isEmpty()

    /** The plan and the team have been read, and the screen has settled on them. */
    private fun ComposeUiTest.settled() {
        waitUntil(timeoutMillis = 20_000) { server.count("/api/plan") > 0 && server.count("/api/me") > 0 }
        waitFor("Fixed the unread counts")
        Thread.sleep(500)
        waitForIdle()
    }

    @Test
    fun aFreeTeamsOwnerSeesThePopupAndTheFilesNote() = runDesktopComposeUiTest(1440, 900) {
        freeTeam("owner")
        app()
        waitFor("Get more with Team")
        waitFor("450 MB of 500 MB used. Team gives you more room.")
        assertNotNull(UpsellMarks(marks).popupShown("https://dash.test"), "showing it starts the week's wait")
        waitFor("Not now").performClick()
        waitUntil(timeoutMillis = 10_000) { hasNo(hasText("Get more with Team")) }
    }

    @Test
    fun thePopupWaitsAWeekAfterItLastShowed() = runDesktopComposeUiTest(1440, 900) {
        freeTeam("owner")
        UpsellMarks(marks).markPopup("https://dash.test", System.currentTimeMillis() - 6 * day)
        app()
        waitFor("450 MB of 500 MB used. Team gives you more room.")
        settled()
        assertTrue(hasNo(hasText("Get more with Team")))
    }

    @Test
    fun aNewTeamIsLeftAloneForItsFirstDays() = runDesktopComposeUiTest(1440, 900) {
        freeTeam("owner", madeDaysAgo = 1)
        app()
        waitFor("450 MB of 500 MB used. Team gives you more room.")
        settled()
        assertTrue(hasNo(hasText("Get more with Team")))
    }

    @Test
    fun aMemberIsOfferedNothing() = runDesktopComposeUiTest(1440, 900) {
        freeTeam("member")
        app()
        settled()
        assertTrue(hasNo(hasText("Get more with Team")))
        assertTrue(hasNo(hasText("Team gives you more room", substring = true)))
        assertNull(UpsellMarks(marks).popupShown("https://dash.test"))
    }

    @Test
    fun whereTheAppMayNotLinkToBuyingNothingIsOfferedAndARefusalIsPlain() = runDesktopComposeUiTest(1440, 900) {
        // An iPhone outside the US App Store: the owner of a free team, with all 3 agents taken.
        freeTeam("owner", agents = 3)
        server.pairings = listOf(
            dev.wildware.agents.PairingView(
                id = "pr1", name = "laptop", host = "laptop", code = "K7Q-3MD",
                createdAt = System.currentTimeMillis(), expiresAt = System.currentTimeMillis() + 600_000,
            ),
        )
        server.refuseApproveBody = """{"error":"plan_limit","message":"The free plan has room for 3 agents. Remove one, or upgrade to Team to add more.",""" +
            """"limit":"agents","upgradeUrl":"https://billing.test/teams/acme"}"""
        app(links = false)
        waitFor("laptop wants to join")
        waitFor("3 of 3 agents in use. Remove one before you connect another.")
        assertTrue(hasNo(hasText("Upgrade")))
        waitFor("Allow").performClick()
        waitFor("This team has no room for more agents. Remove one to add another.")
        waitFor("No room for that")
        assertTrue(hasNo(hasText("upgrade to Team", substring = true)))
        assertTrue(hasNo(hasText("free plan", substring = true)))
        assertTrue(hasNo(hasText("Upgrade")))
        assertTrue(hasNo(hasText("Get more with Team")))
        assertTrue(hasNo(hasText("Team gives you more room", substring = true)))
    }
}
