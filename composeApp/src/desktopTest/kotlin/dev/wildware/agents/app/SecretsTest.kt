package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.RequestConfig
import dev.wildware.agents.RequestView
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.DeviceAuth
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedSection
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.LocalDeviceAuth
import dev.wildware.agents.app.feed.QuestionKind
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.SecretAuditRow
import dev.wildware.agents.app.feed.SecretList
import dev.wildware.agents.app.feed.SecretRow
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Secrets sheet and the masked question card. Every value here is made up. */
@OptIn(ExperimentalTestApi::class)
class SecretsTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val events = mutableListOf<FeedEvent>()
    private val value = "sk-test-NOT-REAL-4f1c"

    private val list = SecretList(
        listOf(
            SecretRow("s1", "OPENAI_API_KEY", "For the test suite", "forge, scout", false, listOf("a1", "a2"), "Used 2h ago", "Added by you"),
            SecretRow("s2", "STRIPE_TEST_KEY", null, "All agents", true, emptyList(), "Never used", "Added by you"),
            SecretRow("s3", "DEPLOY_TOKEN", "Made by the deploy script", "relay", false, listOf("a4"), "Used 5m ago", "From relay", paused = true),
        ),
        loaded = true,
        audit = listOf(
            SecretAuditRow("e3", "Fetched by forge", "2h ago"),
            SecretAuditRow("e2", "Link given to forge", "2h ago"),
            SecretAuditRow("e4", "Refused: a URL that was already used was tried again", "2h ago", warning = true),
            SecretAuditRow("e1", "Added on Sam's iPhone", "3d ago"),
        ),
        auditFor = "s1",
    )

    private fun ComposeUiTest.screen(
        state: FeedState = FakeFeed.state.copy(secrets = list),
        preview: ScreenPreview = ScreenPreview(secretsOpen = true),
        auth: DeviceAuth = DeviceAuth { true },
        scale: Float = 1f,
        dark: Boolean = true,
    ) = setContent {
        CompositionLocalProvider(LocalDensity provides Density(scale), LocalDeviceAuth provides auth) {
            AgentTheme(dark = dark) {
                Box(Modifier.fillMaxSize()) {
                    FeedScreen(state, SafeArea(), preview = preview, onEvent = { events += it })
                }
            }
        }
    }

    @Test
    fun itListsTheSecretsByNameAndReadsThemWhenOpened() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen()
        onNodeWithText("OPENAI_API_KEY").assertExists()
        onNodeWithText("Paused", substring = true).assertExists()
        onNodeWithText("All agents · Never used").assertExists()
        assertTrue(events.any { it is FeedEvent.ReadSecrets })
    }

    @Test
    fun addingOneSendsItOnceAndNeverShowsOrLogsTheValue() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen(preview = ScreenPreview(secretsOpen = true, addingSecret = true))
        onNode(hasContentDescription("Name, like OPENAI_API_KEY")).performTextReplacement("NEW_KEY")
        onNode(hasContentDescription("Value, hidden as you type")).performTextReplacement(value)
        waitForIdle()
        assertMasked()
        onNodeWithText("Save").performClick()
        val added = events.filterIsInstance<FeedEvent.AddSecret>().single()
        assertEquals(listOf("NEW_KEY", value), listOf(added.name, added.value))
        assertTrue(added.allAgents)
        assertFalse(added.toString().contains(value), "an event's text never holds the value")
    }

    @Test
    fun aBadNameIsRefusedBeforeItIsSent() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen(preview = ScreenPreview(secretsOpen = true, addingSecret = true))
        onNode(hasContentDescription("Name, like OPENAI_API_KEY")).performTextReplacement("has space")
        onNode(hasContentDescription("Value, hidden as you type")).performTextReplacement(value)
        onNodeWithText("A name is letters", substring = true).assertExists()
        onNodeWithText("Save").performClick()
        assertTrue(events.none { it is FeedEvent.AddSecret })
    }

    @Test
    fun showingAValueAsksTheDeviceFirst() = runDesktopComposeUiTest(width = 1440, height = 900) {
        var allow = false
        screen(preview = ScreenPreview(secretsOpen = true, secretOpen = "s1"), auth = DeviceAuth { allow })
        onNodeWithText("Show").performClick()
        waitForIdle()
        onNodeWithText("Not confirmed, so it stays hidden.").assertExists()
        assertTrue(events.none { it is FeedEvent.RevealSecret }, "nothing is fetched until the owner is confirmed")

        allow = true
        onNodeWithText("Show").performClick()
        waitForIdle()
        val reveal = events.filterIsInstance<FeedEvent.RevealSecret>().single()
        assertEquals("s1" to false, reveal.secretId to reveal.copy)
        reveal.result(value, null)
        waitForIdle()
        onNodeWithText(value).assertExists()
        onNodeWithText("Hide").performClick()
        assertTrue(onAllNodesWithText(value).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theDetailShowsWhoUsedItAndAsksBeforeDeleting() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen(preview = ScreenPreview(secretsOpen = true, secretOpen = "s1"))
        onNodeWithText("Fetched by forge").assertExists()
        onNodeWithText("Refused", substring = true).assertExists()
        onAllNodes(hasText("Delete") and hasClickAction()).onFirst().performScrollTo().performClick()
        onNodeWithText("Delete OPENAI_API_KEY?", substring = true).assertExists()
        assertTrue(events.none { it is FeedEvent.DeleteSecret })
    }

    /** Nothing on screen spells the value out, and the box it was typed in is a password box. */
    private fun ComposeUiTest.assertMasked() {
        val shown = SemanticsMatcher("shows the value") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(value) } == true
        }
        assertTrue(onAllNodes(shown).fetchSemanticsNodes().isEmpty(), "the value is masked as typed")
        assertTrue(onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).fetchSemanticsNodes().isNotEmpty(), "a password box")
    }

    private fun secretQuestion(): FeedState {
        val base = FakeFeed.state.sections.first().items.filterIsInstance<FeedItem.Question>().first()
        val q = base.copy(
            id = "rq-secret",
            prompt = "Can I have the Stripe test key?",
            body = "The payment tests need it. I'll save it to .env.local, which git ignores.",
            choices = emptyList(),
            allowsText = false,
            kind = QuestionKind.Secret,
            secretName = "STRIPE_TEST_KEY",
            attachments = emptyList(),
        )
        return FakeFeed.state.copy(sections = listOf(FeedSection("Waiting on you", listOf(q))) + FakeFeed.state.sections.drop(1), openThread = null)
    }

    @Test
    fun aSecretQuestionTakesTheValueInAMaskedBoxAndSendsItAtOnce() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen(state = secretQuestion(), preview = ScreenPreview())
        onNodeWithText("STRIPE_TEST_KEY").assertExists()
        onAllNodes(hasContentDescription("Value of STRIPE_TEST_KEY", substring = true) and hasSetTextAction()).onFirst().performTextReplacement(value)
        waitForIdle()
        assertMasked()
        onNodeWithText("Save secret").performClick()
        val provided = events.filterIsInstance<FeedEvent.ProvideSecret>().single()
        assertEquals("rq-secret" to value, provided.questionId to provided.value)
        assertTrue(events.none { it is FeedEvent.AnswerText || it is FeedEvent.Answer }, "never the ordinary, queued answer")
        provided.done(null)
        waitForIdle()
        onNodeWithText("Saved.", substring = true).assertExists()
    }

    // --- Through the real engine --------------------------------------------------------------

    private val server = FakeServer()

    @AfterTest
    fun closeStreams() = server.close()

    private fun ComposeUiTest.app() {
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device", deviceId = "d1")))
        setContent {
            CompositionLocalProvider(LocalDeviceAuth provides DeviceAuth { true }) {
                Box(Modifier.size(1440.dp, 900.dp)) { AgentsApp(platform, dark = true, accessibility = AccessibilityPrefs()) }
            }
        }
    }

    private fun ComposeUiTest.waitForNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        try {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            throw AssertionError("never saw ${matcher.description}; the screen had:\n" + onRoot().printToString(), timeout)
        }
        return onAllNodes(matcher).onFirst()
    }

    private fun waitForWrite(method: String, path: String): String {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            server.wrote(method, path)?.let { return it }
            Thread.sleep(25)
        }
        error("no $method $path; writes were ${server.writes.map { it.first }}")
    }

    @Test
    fun theSheetReadsTheDashboardAndShowsAValueOnlyWhenAsked() = runDesktopComposeUiTest(width = 1440, height = 900) {
        app()
        waitForNode(hasContentDescription("Alerts on this device")).performClick()
        waitForNode(hasText("Secrets") and hasClickAction()).performClick()
        waitForNode(hasText("OPENAI_API_KEY")).performClick()
        waitForNode(hasText("Fetched by forge"))
        assertTrue(server.count("/api/secrets/audit") > 0)
        assertTrue(onAllNodesWithText("sk-test-NOT-REAL-0000").fetchSemanticsNodes().isEmpty())
        onNodeWithText("Show").performClick()
        waitForNode(hasText("sk-test-NOT-REAL-0000"))
        assertEquals("""{"purpose":"reveal"}""", server.wrote("POST", "/api/secrets/s1/reveal"))
    }

    @Test
    fun aDashboardFromBeforeSecretsHidesTheWayIn() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.secrets = null
        app()
        waitForNode(hasContentDescription("Alerts on this device")).performClick()
        waitForNode(hasText("Devices") and hasClickAction())
        waitUntil(timeoutMillis = 10_000) { server.count("/api/secrets") > 0 }
        waitForIdle()
        assertTrue(onAllNodes(hasText("Secrets") and hasClickAction()).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aSecretAnswerGoesStraightToItsOwnRouteAndNeverToTheOutbox() = runDesktopComposeUiTest(width = 1440, height = 900) {
        server.requests = listOf(
            RequestView(
                id = "rs", seq = 9, agentId = "a1", projectId = "p1", kind = "secret",
                question = "The Stripe test key?", expiresAt = FakeServer.now() + 3_600_000,
                config = RequestConfig(secretName = "STRIPE_TEST_KEY"),
            ),
        )
        app()
        waitForNode(hasText("STRIPE_TEST_KEY"))
        waitForNode(hasContentDescription("Value of STRIPE_TEST_KEY", substring = true) and hasSetTextAction()).performTextReplacement(value)
        onNodeWithText("Save secret").performClick()
        assertEquals("""{"value":"$value"}""", waitForWrite("POST", "/api/requests/rs/secret"))
        assertTrue(server.writes.none { it.first.endsWith("/answer") }, "never the ordinary answer route")
    }

    @Test
    fun screenshots() {
        for (scene in listOf("secrets", "secret-detail", "secret-request")) {
            for (dark in listOf(true, false)) {
                runDesktopComposeUiTest(2880, 1800) { shoot(scene, "desktop", 2f, dark) }
                runDesktopComposeUiTest(1170, 2532) { shoot(scene, "phone", 3f, dark) }
            }
        }
    }

    private fun ComposeUiTest.shoot(scene: String, name: String, scale: Float, dark: Boolean) {
        when (scene) {
            "secrets" -> screen(scale = scale, dark = dark)
            "secret-detail" -> screen(preview = ScreenPreview(secretsOpen = true, secretOpen = "s1"), scale = scale, dark = dark)
            else -> screen(state = secretQuestion(), preview = ScreenPreview(), scale = scale, dark = dark)
        }
        waitForIdle()
        val file = File(outDir, "$name-${if (dark) "dark" else "light"}-$scene.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }
}
