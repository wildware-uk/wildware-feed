package dev.wildware.agents.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.wildware.agents.HostCheck
import dev.wildware.agents.SignInException
import dev.wildware.agents.SignInFailure
import dev.wildware.agents.SignInMethod
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.signin.CouldNotReach
import dev.wildware.agents.app.signin.NotAFeedServer
import dev.wildware.agents.app.signin.SignInChoice
import dev.wildware.agents.app.signin.SignInScreen
import dev.wildware.agents.app.signin.signInChoices
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sign-in screen: the desktop's tabs, a phone's single choice, and "Connect to a host" in two steps. */
@OptIn(ExperimentalTestApi::class)
class SignInScreenTest {
    private val feed = HostCheck.Feed("https://dashboard.example.com", listOf(SignInMethod.Password))
    private val tabs = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun ComposeUiTest.phone(
        checkHost: suspend (String) -> HostCheck = { feed },
        signIn: suspend (String, String, String) -> Unit = { _, _, _ -> },
    ) = setContent { AgentTheme(false) { SignInScreen("Phone", null, SafeArea(), tryDemo = {}, checkHost = checkHost, signIn = signIn) } }

    private fun ComposeUiTest.shows(text: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun ComposeUiTest.absent(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()

    private fun ComposeUiTest.continueTo(address: String) {
        onNodeWithContentDescription("Host address").performTextInput(address)
        onNodeWithText("Continue").performClick()
    }

    @Test
    fun aPhoneGoesStraightToConnectToAHostWithNoTabs() = runComposeUiTest {
        phone()
        assertTrue(onAllNodes(tabs).fetchSemanticsNodes().isEmpty())
        assertTrue(absent("This computer"))
        onNodeWithText("HOST ADDRESS").assertExists()
        onNodeWithText("Try a demo").assertExists()
        assertTrue(absent("OWNER PASSWORD"))
    }

    @Test
    fun theDesktopShowsThisComputerAndConnectToAHost() = runComposeUiTest {
        setContent { AgentTheme(false) { SignInScreen("Laptop", null, SafeArea(), signInHere = {}, checkHost = { feed }) { _, _, _ -> } } }
        val labels = onAllNodes(tabs).fetchSemanticsNodes().map { node ->
            node.config[SemanticsProperties.Text].joinToString { it.text }
        }
        assertEquals(listOf("This computer", "Connect to a host"), labels)
        onNodeWithText("Connect to a host").performClick()
        onNodeWithText("HOST ADDRESS").assertExists()
    }

    @Test
    fun logInShowsOnlyWhereThereIsAHostedService() = runComposeUiTest {
        assertFalse(SignInChoice.LogIn in signInChoices(canHostLocally = true, hostedLogin = false))
        assertFalse(SignInChoice.LogIn in signInChoices(canHostLocally = false, hostedLogin = false))
        // A hosted service (not the web app) is what puts it there.
        assertEquals(SignInChoice.LogIn, signInChoices(canHostLocally = false, hostedLogin = true).first())

        var desktop by mutableStateOf(true)
        setContent {
            AgentTheme(false) {
                SignInScreen("Laptop", null, SafeArea(), signInHere = if (desktop) ({}) else null, checkHost = { feed }) { _, _, _ -> }
            }
        }
        assertTrue(absent("Log in"))
        desktop = false
        waitForIdle()
        assertTrue(absent("Log in"))
    }

    @Test
    fun theAddressIsCheckedThenTheHostsPasswordSignsIn() = runComposeUiTest {
        val checked = mutableListOf<String>()
        val signedIn = mutableListOf<Triple<String, String, String>>()
        phone(checkHost = { checked += it; feed }, signIn = { s, p, d -> signedIn += Triple(s, p, d) })

        continueTo("dashboard.example.com")
        shows("OWNER PASSWORD")
        assertEquals(listOf("dashboard.example.com"), checked)
        onNodeWithText("SIGNING IN TO").assertExists()
        onNodeWithText("dashboard.example.com").assertExists()
        assertTrue(absent("HOST ADDRESS"))
        assertTrue(absent("Try a demo"))

        onNodeWithContentDescription("Owner password").performTextInput("correct horse")
        onNode(hasText("Sign in") and hasClickAction()).performClick()
        waitUntil(timeoutMillis = 5_000) { signedIn.isNotEmpty() }
        assertEquals(listOf(Triple("https://dashboard.example.com", "correct horse", "Phone")), signedIn)
    }

    @Test
    fun changeGoesBackToTheAddressAsTyped() = runComposeUiTest {
        phone()
        continueTo("dashboard.example.com")
        shows("SIGNING IN TO")
        onNodeWithText("Change").performClick()
        shows("HOST ADDRESS")
        onNodeWithContentDescription("Host address").assert(hasText("dashboard.example.com"))
        assertTrue(absent("OWNER PASSWORD"))
    }

    @Test
    fun itSaysItIsCheckingWhileItChecks() = runComposeUiTest {
        val answer = CompletableDeferred<HostCheck>()
        phone(checkHost = { answer.await() })
        continueTo("dashboard.example.com")
        shows("Checking…")
        answer.complete(feed)
        shows("OWNER PASSWORD")
    }

    @Test
    fun anEmptyAddressAsksForOne() = runComposeUiTest {
        var asked = false
        phone(checkHost = { asked = true; feed })
        onNodeWithText("Continue").performClick()
        shows("Type the host's address.")
        assertFalse(asked)
    }

    @Test
    fun anAddressThatIsNotAFeedServerSaysSo() = runComposeUiTest {
        phone(checkHost = { HostCheck.NotFeed("https://example.com") })
        continueTo("example.com")
        shows(NotAFeedServer)
        assertEquals("That address isn't a Wildware Feed server. Check the address, or that it starts with https://", NotAFeedServer)
        assertTrue(absent("OWNER PASSWORD"))
    }

    @Test
    fun anAddressThatDoesNotAnswerSaysSo() = runComposeUiTest {
        phone(checkHost = { HostCheck.Unreachable("https://nowhere.example.com") })
        continueTo("nowhere.example.com")
        shows(CouldNotReach)
        assertEquals("Couldn't reach that address.", CouldNotReach)
    }

    @Test
    fun aCheckThatFailsOutrightCouldNotReachIt() = runComposeUiTest {
        phone(checkHost = { throw IllegalStateException("no network") })
        continueTo("dashboard.example.com")
        shows(CouldNotReach)
    }

    @Test
    fun aWrongPasswordIsShownAtStepTwo() = runComposeUiTest {
        phone(signIn = { _, _, _ -> throw SignInException(SignInFailure.WrongPassword, "wrong") })
        continueTo("dashboard.example.com")
        shows("OWNER PASSWORD")
        onNodeWithContentDescription("Owner password").performTextInput("nope")
        onNode(hasText("Sign in") and hasClickAction()).performClick()
        shows("That password isn't right. Try again.")
    }

    @Test
    fun aHostWithOnlyWaysThisAppDoesNotKnowSaysSo() = runComposeUiTest {
        phone(checkHost = { HostCheck.Feed("https://dashboard.example.com", emptyList()) })
        continueTo("dashboard.example.com")
        shows("This host signs in a way this app doesn't know yet. Update the app, then try again.")
        assertTrue(absent("OWNER PASSWORD"))
    }

    @Test
    fun theDesktopStartsOnThisComputerWithNoAddressOrPassword() = runComposeUiTest {
        val asked = mutableListOf<String>()
        setContent { AgentTheme(false) { SignInScreen("Laptop", null, SafeArea(), signInHere = { asked += it }, checkHost = { feed }) { _, _, _ -> } } }
        assertTrue(absent("HOST ADDRESS"))
        assertTrue(absent("OWNER PASSWORD"))
        onNodeWithText("Start on this computer").performClick()
        waitForIdle()
        assertEquals(listOf("Laptop"), asked)
    }

    @Test
    fun aFailureToStartIsShown() = runComposeUiTest {
        val why = "Another copy of Wildware Feed is already running the server on this computer."
        setContent {
            AgentTheme(false) {
                SignInScreen("Laptop", null, SafeArea(), signInHere = { throw IllegalStateException(why) }, checkHost = { feed }) { _, _, _ -> }
            }
        }
        onNodeWithText("Start on this computer").performClick()
        shows(why)
    }
}
