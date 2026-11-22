package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Delete my account" in the settings sheet (App Store guideline 5.1.1(v)): typed confirmation and
 * the password, then the device signs out; an owner is told to hand the team on and gets no button.
 */
@OptIn(ExperimentalTestApi::class)
class DeleteAccountTest {
    private val server = FakeServer()
    private val store = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device", deviceId = "d1"))

    @AfterTest
    fun stop() = server.close()

    private fun asRole(role: String) {
        server.me = """{"person":{"id":"m-sam","name":"Sam","handle":"sam","role":"$role"},"team":true}"""
        server.people = """{"people":[{"id":"founder","name":"Alex","handle":"alex","role":"owner","founder":true},{"id":"m-sam","name":"Sam","handle":"sam","role":"$role"}]}"""
    }

    private fun ComposeUiTest.app() {
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = store)
        setContent {
            Box(Modifier.size(390.dp, 1400.dp)) {
                AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs(), safeArea = SafeArea(top = 54.dp, bottom = 34.dp))
            }
        }
    }

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher) = try {
        waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(matcher).onFirst()
    } catch (timeout: Throwable) {
        throw AssertionError("never saw ${matcher.description}; the screen had:\n" + onRoot().printToString(), timeout)
    }

    private fun ComposeUiTest.openDeleteAccount() {
        waitFor(hasContentDescription("Alerts on this device") and hasClickAction()).performClick()
        waitFor(hasText("Delete my account") and hasClickAction()).performScrollTo().performClick()
    }

    private fun ComposeUiTest.field(label: String) = waitFor(hasContentDescription(label) and hasSetTextAction())

    @Test
    fun theButtonStaysOffUntilDeleteIsTypedExactly() = runComposeUiTest {
        asRole("member")
        app()
        openDeleteAccount()
        waitFor(hasText("This can't be undone", substring = true))
        val delete = waitFor(hasText("Delete my account"))
        delete.assertIsNotEnabled()
        field("Your password").performTextInput("sam password 1")
        field("Type DELETE to confirm").performTextInput("delete")
        delete.assertIsNotEnabled()
        assertNull(server.wrote("DELETE", "/api/me"), "nothing is sent before DELETE is typed")
    }

    @Test
    fun theButtonSendsTheConfirmationAndPasswordThenSignsOut() = runComposeUiTest {
        asRole("member")
        app()
        openDeleteAccount()
        field("Your password").performTextInput("sam password 1")
        field("Type DELETE to confirm").performTextInput("DELETE")
        waitFor(hasText("Delete my account")).performScrollTo().assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 10_000) { server.wrote("DELETE", "/api/me") != null }
        assertEquals("""{"confirm":"DELETE","password":"sam password 1"}""", server.wrote("DELETE", "/api/me"))
        waitFor(hasText("Your account was deleted", substring = true))
        assertNull(store.load(), "the device's key is forgotten")
    }

    @Test
    fun aRefusalSaysWhyAndKeepsYouSignedIn() = runComposeUiTest {
        asRole("member")
        server.refuseDeleteAccount = Triple("wrong_password", 400, "That password is not right.")
        app()
        openDeleteAccount()
        field("Your password").performTextInput("not it")
        field("Type DELETE to confirm").performTextInput("DELETE")
        waitFor(hasText("Delete my account")).performScrollTo().performClick()
        waitFor(hasText("That password is not right.", substring = true))
        assertEquals("key-1", store.load()?.token)
    }

    @Test
    fun anOwnerIsToldToHandTheTeamOnAndHasNoDeleteButton() = runComposeUiTest {
        asRole("owner")
        app()
        openDeleteAccount()
        waitFor(hasText("You own this team", substring = true))
        waitFor(hasText("Open People") and hasClickAction())
        assertTrue(onAllNodes(hasContentDescription("Type DELETE to confirm")).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasText("Delete my account") and hasClickAction()).fetchSemanticsNodes().isEmpty())
    }
}
