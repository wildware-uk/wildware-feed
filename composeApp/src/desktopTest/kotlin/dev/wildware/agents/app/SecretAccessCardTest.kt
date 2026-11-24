package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.RequestConfig
import dev.wildware.agents.RequestView
import dev.wildware.agents.SecretAccessView
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.engine.answerSaid
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An agent asks to use a secret another agent saved (request_secret_access): the card says so with
 * Allow and Deny instead of Yes and No, and each sends the yes or no the server shares it on.
 */
@OptIn(ExperimentalTestApi::class)
class SecretAccessCardTest {
    private val server = FakeServer()

    @AfterTest
    fun stop() = server.close()

    private fun asking() = RequestView(
        id = "r7", seq = 9, agentId = "a1", projectId = "p1", kind = "confirm",
        question = "forge asks to use secret TURNSTILE_SECRET: the signup form needs it",
        detail = "Note: Cloudflare Turnstile, live site\nSaved by scout.",
        config = RequestConfig(secretAccess = SecretAccessView("TURNSTILE_SECRET", "Cloudflare Turnstile, live site", "agent:a2", "scout")),
        expiresAt = System.currentTimeMillis() + 3_600_000,
    )

    private fun ComposeUiTest.app() {
        server.requests = listOf(asking())
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device")))
        setContent {
            Box(Modifier.size(1440.dp, 900.dp)) {
                AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs())
            }
        }
    }

    private fun ComposeUiTest.waitForNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        try {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher, true).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            throw AssertionError("never saw ${matcher.description}; the screen had:\n" + onRoot().printToString(), timeout)
        }
        return onAllNodes(matcher, true).onFirst()
    }

    private fun waitForWrite(method: String, path: String): String {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            server.wrote(method, path)?.let { return it }
            Thread.sleep(25)
        }
        error("no $method $path; writes were ${server.writes}")
    }

    @Test
    fun theCardOffersAllowAndDenyWithTheNoteAndAllowSendsYes() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitForNode(hasText("forge asks to use secret TURNSTILE_SECRET: the signup form needs it"))
        waitForNode(hasText("Saved by scout.", substring = true))
        assertTrue(onAllNodes(hasText("Yes"), true).fetchSemanticsNodes().isEmpty(), "an access card says Allow, not Yes")
        waitForNode(hasText("Deny"))
        waitForNode(hasText("Allow")).performClick()
        val body = waitForWrite("POST", "/api/requests/r7/answer")
        assertTrue("\"value\":true" in body, body)
    }

    @Test
    fun denySendsNo() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitForNode(hasText("Deny")).performClick()
        val body = waitForWrite("POST", "/api/requests/r7/answer")
        assertTrue("\"value\":false" in body, body)
    }

    @Test
    fun anAnsweredOneSaysAllowedOrDenied() {
        assertEquals("Allowed", answerSaid(buildJsonObject { put("kind", "confirm"); put("value", true) }, access = true))
        assertEquals("Denied", answerSaid(buildJsonObject { put("kind", "confirm"); put("value", false) }, access = true))
        assertEquals("Yes", answerSaid(buildJsonObject { put("kind", "confirm"); put("value", true) }))
    }
}
