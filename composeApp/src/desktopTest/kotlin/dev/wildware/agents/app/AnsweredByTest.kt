package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wildware.agents.RequestView
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.engine.answerSaid
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.session.AppPlatform
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * On a team (migration 055), a question someone answered stays in the feed, settled, saying who
 * answered and what: "Answered by Sam · No". A team of one looks as before: answered ones go.
 */
@OptIn(ExperimentalTestApi::class)
class AnsweredByTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
    private val server = FakeServer()

    @AfterTest
    fun stop() = server.close()

    private fun teamOfTwo() {
        server.me = """{"person":{"id":"founder","name":"Alex","handle":"alex","role":"owner","founder":true},"team":true}"""
        server.people = """{"people":[{"id":"founder","name":"Alex","handle":"alex","role":"owner","founder":true},{"id":"m-sam","name":"Sam","handle":"sam","role":"member"}]}"""
        server.answered = listOf(
            RequestView(
                id = "r9", seq = 9, agentId = "a1", projectId = "p1", kind = "confirm", question = "Deploy the docs to production?",
                state = "answered", answeredAt = System.currentTimeMillis() - 5_000, answeredBy = "m-sam",
                answer = buildJsonObject { put("kind", "confirm"); put("value", false) },
            ),
            RequestView(
                id = "r8", seq = 4, agentId = "a1", projectId = "p1", kind = "buttons", question = "Which icon set?",
                options = listOf("Rounded", "Sharp"), state = "answered", answeredAt = System.currentTimeMillis() - 10_000, answeredBy = null,
                answer = buildJsonObject { put("kind", "buttons"); put("value", "Rounded") },
            ),
        )
    }

    private fun ComposeUiTest.app(width: Int = 390, height: Int = 1200, dark: Boolean = true, scale: Float = 1f) {
        val store = MemorySessionStore(SavedSession("https://dash.test", "key-1", "Test device", deviceId = "d1"))
        val platform = AppPlatform.inMemory(engine = { server.engine() }, sessions = store)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                Box(Modifier.size((width / scale).dp, (height / scale).dp)) {
                    AgentsApp(platform, dark = dark, accessibility = AccessibilityPrefs(), safeArea = if (width < 600 * scale) SafeArea(top = 54.dp, bottom = 34.dp) else SafeArea())
                }
            }
        }
    }

    @Test
    fun anAnswerSaysWhatWasSaidInWords() {
        assertEquals("No", answerSaid(buildJsonObject { put("kind", "confirm"); put("value", false) }))
        assertEquals("Rounded", answerSaid(buildJsonObject { put("kind", "buttons"); put("value", "Rounded") }))
        assertEquals("a, b", answerSaid(buildJsonObject { put("kind", "multi_choice"); put("value", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))) }))
        assertEquals("Send: Hi all", answerSaid(buildJsonObject { put("kind", "form"); put("value", buildJsonObject { put("action", "Send"); put("text", "Hi all") }) }))
        assertNull(answerSaid(buildJsonObject { put("kind", "secret"); put("value", "OPENAI_API_KEY") }))
        assertNull(answerSaid(null))
    }

    @Test
    fun onATeamAnAnsweredQuestionSaysWhoAnswered() = runDesktopComposeUiTest(390, 1200) {
        teamOfTwo()
        app()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Answered by Sam · No").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Deploy the docs to production?").assertExists()
        onNodeWithText("Answered by you · Rounded").assertExists()
        // Still waiting is still waiting, with its controls.
        onNodeWithText("Ship the new sounds?").assertExists()
        onNodeWithText("Ship it").assertExists()
        // Settled: nothing on it to answer.
        assertTrue(onAllNodesWithText("Sharp").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aTeamOfOneLooksAsBefore() = runDesktopComposeUiTest(390, 1200) {
        teamOfTwo()
        server.me = null
        app()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Ship the new sounds?").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        assertTrue(onAllNodesWithText("Deploy the docs to production?").fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodesWithText("Answered by", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun screenshots() {
        for (dark in listOf(true, false)) {
            val theme = if (dark) "dark" else "light"
            runDesktopComposeUiTest(1170, 2532) { shoot(1170, 2532, 3f, dark); save("phone-$theme-people-answered-by") }
            runDesktopComposeUiTest(2880, 1800) { shoot(2880, 1800, 2f, dark); save("desktop-$theme-people-answered-by") }
        }
    }

    private fun ComposeUiTest.shoot(width: Int, height: Int, scale: Float, dark: Boolean) {
        teamOfTwo()
        app(width, height, dark, scale)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Answered by Sam · No").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.save(name: String) {
        waitForIdle()
        val file = File(outDir, "$name.png")
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", file)
        println("screenshot: ${file.absolutePath}")
    }
}
