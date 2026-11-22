package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.engine.AppModel
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The hosted "Log in" in the whole app, at phone size: email, code, team, then the team's feed. */
@OptIn(ExperimentalTestApi::class)
class HostedLoginUiTest {
    private val server = FakeServer()
    private val account = FakeAccount(server).also { server.otherHost = it.handler }
    private val sessions = MemorySessionStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tidy() {
        server.close()
        scope.cancel()
    }

    private fun ComposeUiTest.app(linksToSignUp: Boolean = true) {
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = sessions, deviceName = "Ada's iPhone", linksToSignUp = linksToSignUp)
        val model = AppModel(platform, scope, accountService = "https://account.test")
        setContent {
            Box(Modifier.size(390.dp, 844.dp)) {
                AgentsApp(platform, model, dark = true, accessibility = AccessibilityPrefs(), safeArea = SafeArea(top = 54.dp, bottom = 34.dp))
            }
        }
    }

    private fun ComposeUiTest.waitFor(text: String, substring: Boolean = false) = try {
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
    } catch (timeout: Throwable) {
        throw AssertionError("never saw \"$text\"; the screen had:\n" + onRoot().printToString(), timeout)
    }

    private fun ComposeUiTest.absent(text: String) = onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty()

    private fun ComposeUiTest.click(text: String) = onNode(hasText(text) and hasClickAction()).performClick()

    private fun ComposeUiTest.emailACode() {
        waitFor("EMAIL")
        onNodeWithContentDescription("Email").performTextInput("ada@example.com")
        click("Email me a code")
        waitFor("CODE SENT TO")
    }

    private fun ComposeUiTest.typeCode(code: String) = onNodeWithContentDescription("Code").performTextInput(code)

    private fun ComposeUiTest.pick(team: String) =
        onNode(hasContentDescription(team, substring = true) and hasClickAction()).performClick()

    private fun ComposeUiTest.feedOpens() {
        waitFor("Fixed the unread counts")
        assertEquals("https://dash.test", sessions.load()?.server)
        assertEquals("key-1", sessions.load()?.token)
        // Signed in to the team the ordinary way; the account's own sign-in is over.
        waitUntil(timeoutMillis = 10_000) { account.open.isEmpty() }
        assertTrue(server.authorizations.isNotEmpty() && server.authorizations.all { it == "Bearer key-1" })
    }

    @Test
    fun logInComesFirstAndAPhoneHasNoThisComputer() = runDesktopComposeUiTest(width = 390, height = 844) {
        app()
        waitFor("Log in")
        waitFor("Connect to a host")
        assertTrue(absent("This computer"))
        waitFor("Email me a code")
        waitFor("Try a demo")
    }

    @Test
    fun emailCodeAndAPickedTeamOpenItsFeed() = runDesktopComposeUiTest(width = 390, height = 844) {
        app()
        emailACode()
        waitFor("ada@example.com")
        // A pasted code with a space in it is still the code, and the sixth digit sends it.
        typeCode("123 456")
        waitFor("CHOOSE A TEAM")
        waitFor("Acme")
        waitFor("Read-only: the subscription has lapsed. You can still sign in and read.")
        pick("Acme")
        feedOpens()
        val redeem = server.wrote("POST", "/api/devices/ticket")!!
        assertEquals("""{"ticket":"ticket-1","deviceName":"Ada's iPhone"}""", redeem)
        assertEquals(
            listOf("/api/app/sign-in", "/api/app/sign-in/verify", "/api/app/teams", "/api/app/teams/acme/ticket", "/api/app/sign-out"),
            account.calls.toList(),
        )
    }

    @Test
    fun oneOpenTeamSignsStraightIn() = runDesktopComposeUiTest(width = 390, height = 844) {
        account.teams = listOf(account.team("acme", "Acme"))
        app()
        emailACode()
        typeCode("123456")
        feedOpens()
    }

    @Test
    fun oneReadOnlyTeamStillShowsWhy() = runDesktopComposeUiTest(width = 390, height = 844) {
        account.teams = listOf(account.team("globex", "Globex", readOnly = true))
        app()
        emailACode()
        typeCode("123456")
        waitFor("YOUR TEAM")
        waitFor("Read-only", substring = true)
        pick("Globex")
        feedOpens()
    }

    @Test
    fun aWrongCodeSaysSoAndTheRightOneWorks() = runDesktopComposeUiTest(width = 390, height = 844) {
        app()
        emailACode()
        typeCode("000000")
        waitFor("That code isn't right. Check the email and try again.")
        typeCode("123456")
        waitFor("CHOOSE A TEAM")
    }

    @Test
    fun fiveWrongCodesAskForANewOne() = runDesktopComposeUiTest(width = 390, height = 844) {
        app()
        emailACode()
        repeat(4) {
            typeCode("000000")
            waitFor("That code isn't right. Check the email and try again.")
        }
        typeCode("000000")
        waitFor("Too many wrong codes. Ask for a new code, or try again tomorrow.")
        click("Send a new code")
        waitUntil(timeoutMillis = 10_000) { account.calls.count { it == "/api/app/sign-in" } == 2 }
        typeCode("123456")
        waitFor("CHOOSE A TEAM")
    }

    @Test
    fun tooManyEmailsSaysHowLongToWait() = runDesktopComposeUiTest(width = 390, height = 844) {
        account.refuseEmail = "rate_limited" to 429
        app()
        waitFor("EMAIL")
        onNodeWithContentDescription("Email").performTextInput("ada@example.com")
        click("Email me a code")
        waitFor("Too many tries. Wait 15 minutes, then try again.")
    }

    @Test
    fun noTeamsYetPointsAtTheWebsite() = runDesktopComposeUiTest(width = 390, height = 844) {
        account.teams = emptyList()
        app()
        emailACode()
        typeCode("123456")
        waitFor("This email isn't in a team yet. Make one on the website, or ask a team's owner to invite you.")
        waitFor("Make a team on the website")
        click("Use a different email")
        waitFor("Email me a code")
        waitUntil(timeoutMillis = 10_000) { account.open.isEmpty() }
    }

    @Test
    fun anAppleAppOffersNoWayToTheSignUpWebsite() = runDesktopComposeUiTest(width = 390, height = 844) {
        // App Store guideline 3.1.1: no link to buying a hosted team outside the App Store.
        account.teams = emptyList()
        app(linksToSignUp = false)
        waitFor("Email me a code")
        waitFor("Try a demo")
        assertTrue(absent("Make a team"))
        emailACode()
        typeCode("123456")
        waitFor("This email isn't in a team yet. Ask a team's owner to invite you.")
        assertTrue(absent("Make a team"))
        assertTrue(absent("website"))
        waitFor("Use a different email")
    }

    @Test
    fun theOtherAppsStillOfferTheSignUpWebsite() = runDesktopComposeUiTest(width = 390, height = 844) {
        app()
        waitFor("New here? Make a team")
    }

    @Test
    fun aTicketThatRanOutCanBePickedAgain() = runDesktopComposeUiTest(width = 390, height = 844) {
        account.spoilNextTicket = true
        app()
        emailACode()
        typeCode("123456")
        waitFor("CHOOSE A TEAM")
        pick("Acme")
        waitFor("That sign-in ran out before the team got it. Pick the team again.")
        pick("Acme")
        feedOpens()
    }

    @Test
    fun anEndedAccountSignInStartsAgain() = runDesktopComposeUiTest(width = 390, height = 844) {
        account.signedOut = true
        app()
        emailACode()
        typeCode("123456")
        waitFor("Your sign-in ran out. Start again with your email.")
        waitFor("Email me a code")
    }

    @Test
    fun aTeamThatHasGoneSaysSo() = runDesktopComposeUiTest(width = 390, height = 844) {
        account.teamGone = true
        app()
        emailACode()
        typeCode("123456")
        waitFor("CHOOSE A TEAM")
        pick("Acme")
        waitFor("That team isn't there any more, or you're no longer in it.")
        assertEquals(null, sessions.load())
    }

    @Test
    fun anEmptyEmailIsAskedFor() = runDesktopComposeUiTest(width = 390, height = 844) {
        app()
        waitFor("Email me a code")
        click("Email me a code")
        waitFor("Type your email.")
        assertTrue(account.calls.isEmpty())
    }
}
