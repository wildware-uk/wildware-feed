package dev.wildware.agents.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.signin.SignInScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sign-in screen's two choices on the desktop, and the plain form on a phone. */
@OptIn(ExperimentalTestApi::class)
class SignInChoiceTest {
    @Test
    fun aPhoneSeesOnlyTheServerForm() = runComposeUiTest {
        setContent { AgentTheme(false) { SignInScreen("Phone", null, SafeArea()) { _, _, _ -> } } }
        assertTrue(onAllNodes(hasText("This computer")).fetchSemanticsNodes().isEmpty())
        onNodeWithText("DASHBOARD ADDRESS").assertExists()
    }

    @Test
    fun theDesktopStartsOnThisComputerWithNoAddressOrPassword() = runComposeUiTest {
        val asked = mutableListOf<String>()
        setContent { AgentTheme(false) { SignInScreen("Laptop", null, SafeArea(), signInHere = { asked += it }) { _, _, _ -> } } }
        assertTrue(onAllNodes(hasText("DASHBOARD ADDRESS")).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasText("OWNER PASSWORD")).fetchSemanticsNodes().isEmpty())
        onNodeWithText("Start on this computer").performClick()
        waitForIdle()
        assertEquals(listOf("Laptop"), asked)
    }

    @Test
    fun aServerShowsTodaysForm() = runComposeUiTest {
        setContent { AgentTheme(false) { SignInScreen("Laptop", null, SafeArea(), signInHere = {}) { _, _, _ -> } } }
        onNodeWithText("A server").performClick()
        onNodeWithText("DASHBOARD ADDRESS").assertExists()
        onNodeWithText("OWNER PASSWORD").assertExists()
    }

    @Test
    fun aFailureToStartIsShown() = runComposeUiTest {
        val why = "Another copy of Wildware Feed is already running the server on this computer."
        setContent {
            AgentTheme(false) {
                SignInScreen("Laptop", null, SafeArea(), signInHere = { throw IllegalStateException(why) }) { _, _, _ -> }
            }
        }
        onNodeWithText("Start on this computer").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(why)).fetchSemanticsNodes().isNotEmpty() }
    }
}
