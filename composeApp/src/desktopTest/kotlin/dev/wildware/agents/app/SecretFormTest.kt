package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.SecretReveal
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
import dev.wildware.agents.app.feed.SecretAsk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A question that asks for several secrets on one card, and the eye on every secret box that
 * shows what was typed for a moment. Every value here is made up.
 */
@OptIn(ExperimentalTestApi::class)
class SecretFormTest {
    private val events = mutableListOf<FeedEvent>()
    private val keyId = "AKIA-NOT-REAL-form-01"
    private val keySecret = "not-real-form-secret-02"

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = Owner()

    private fun question(fields: List<SecretAsk>): FeedState {
        val base = FakeFeed.state.sections.first().items.filterIsInstance<FeedItem.Question>().first()
        val q = base.copy(
            id = "rq-keys",
            prompt = "Can I have the AWS keys for the deploy?",
            body = "Both go in .env.local, which git ignores.",
            choices = emptyList(),
            allowsText = false,
            kind = QuestionKind.Secret,
            secretName = fields.first().name,
            secretFields = fields,
            attachments = emptyList(),
        )
        return FakeFeed.state.copy(sections = listOf(FeedSection("Waiting on you", listOf(q))) + FakeFeed.state.sections.drop(1), openThread = null)
    }

    private val three = listOf(
        SecretAsk("AWS_ACCESS_KEY_ID", label = "Access key id"),
        SecretAsk("AWS_SECRET_ACCESS_KEY", label = "Secret access key", hint = "Shown once when you make the key"),
        SecretAsk("AWS_SESSION_TOKEN", optional = true),
    )

    private fun ComposeUiTest.screen(state: FeedState) = setContent {
        CompositionLocalProvider(LocalDeviceAuth provides DeviceAuth { true }, LocalLifecycleOwner provides owner) {
            AgentTheme(dark = true) {
                Box(Modifier.fillMaxSize()) {
                    FeedScreen(state, SafeArea(), preview = ScreenPreview(), onEvent = { events += it })
                }
            }
        }
    }

    private fun ComposeUiTest.box(name: String): SemanticsNodeInteraction =
        onNode(hasContentDescription("Value of $name", substring = true) and hasSetTextAction())

    /** The eye on [name]'s box, in the unmerged tree so the tap lands on the eye and not the box. */
    private fun ComposeUiTest.eye(name: String): SemanticsNodeInteraction {
        val boxes = onAllNodes(hasContentDescription("Value of", substring = true) and hasSetTextAction()).fetchSemanticsNodes()
        val index = boxes.indexOfFirst { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Value of $name,") } == true }
        check(index >= 0) { "no box for $name" }
        val eyes = (hasContentDescription(SecretReveal.ShowLabel) or hasContentDescription(SecretReveal.HideLabel)) and hasClickAction()
        return onAllNodes(eyes, useUnmergedTree = true)[index]
    }

    /**
     * Whether anything on screen, merged or not, spells [value] out: in its text, or in the text it
     * lays out and draws. (A password box's EditableText is always the value itself; it is marked
     * Password, so screen readers say only that it is a secret field. What it draws is the test.)
     */
    private fun ComposeUiTest.spelled(value: String): Boolean {
        val shows = SemanticsMatcher("spells the value") { node ->
            if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(value) } == true) return@SemanticsMatcher true
            val layout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: return@SemanticsMatcher false
            val drawn = mutableListOf<TextLayoutResult>()
            layout.action?.invoke(drawn)
            drawn.any { it.layoutInput.text.text.contains(value) }
        }
        return onAllNodes(shows, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun theCardDrawsABoxForEachSecretAndSavesThemAllAtOnce() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen(question(three))
        for (name in three.map { it.name }) onNodeWithText(name).assertExists()
        onNodeWithText("Access key id").assertExists()
        onNodeWithText("Shown once when you make the key").assertExists()
        onNodeWithText("optional", substring = true).assertExists()
        assertEquals(3, onAllNodes(hasContentDescription("Value of", substring = true) and hasSetTextAction()).fetchSemanticsNodes().size)

        // Save all waits for every box that isn't optional.
        onNodeWithText("Save all").assertIsNotEnabled()
        box("AWS_ACCESS_KEY_ID").performTextReplacement(keyId)
        waitForIdle()
        onNodeWithText("Save all").assertIsNotEnabled()
        box("AWS_SECRET_ACCESS_KEY").performTextReplacement(keySecret)
        waitForIdle()
        assertFalse(spelled(keyId) || spelled(keySecret), "masked as typed")
        onNodeWithText("Save all").assertIsEnabled().performClick()

        val sent = events.filterIsInstance<FeedEvent.ProvideSecrets>().single()
        assertEquals("rq-keys", sent.questionId)
        assertEquals(mapOf("AWS_ACCESS_KEY_ID" to keyId, "AWS_SECRET_ACCESS_KEY" to keySecret), sent.values, "the optional one left empty is left out")
        assertFalse(sent.toString().contains(keyId) || sent.toString().contains(keySecret), "an event's text never holds a value")
        assertTrue(events.none { it is FeedEvent.ProvideSecret || it is FeedEvent.AnswerText || it is FeedEvent.Answer }, "one save, never the queued answer")
        sent.done(null)
        waitForIdle()
        onNodeWithText("Saved.", substring = true).assertExists()
        assertFalse(spelled(keyId) || spelled(keySecret))
    }

    @Test
    fun aRefusedSaveKeepsTheBoxesAndSaysWhy() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen(question(three.take(2)))
        box("AWS_ACCESS_KEY_ID").performTextReplacement(keyId)
        box("AWS_SECRET_ACCESS_KEY").performTextReplacement(keySecret)
        waitForIdle()
        onNodeWithText("Save all").performClick()
        events.filterIsInstance<FeedEvent.ProvideSecrets>().single().done("AWS_SECRET_ACCESS_KEY is taken")
        waitForIdle()
        onNodeWithText("AWS_SECRET_ACCESS_KEY is taken").assertExists()
        onNodeWithText("Save all").assertIsEnabled()
    }

    @Test
    fun showTurnsOnForOneBoxOnlyAndOffWhenItLosesTheCursor() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen(question(three.take(2)))
        box("AWS_ACCESS_KEY_ID").performTextReplacement(keyId)
        box("AWS_SECRET_ACCESS_KEY").performTextReplacement(keySecret)
        waitForIdle()
        assertFalse(spelled(keyId) || spelled(keySecret), "off by default")

        box("AWS_SECRET_ACCESS_KEY").requestFocus()
        eye("AWS_SECRET_ACCESS_KEY").performClick()
        waitForIdle()
        assertTrue(spelled(keySecret), "shown when asked")
        assertFalse(spelled(keyId), "only for that box")
        onNode(hasContentDescription(SecretReveal.HideLabel) and hasClickAction(), useUnmergedTree = true).assertExists()
        // Still a password box: a screen reader is told it is a secret field, not what is in it.
        assertTrue(box("AWS_SECRET_ACCESS_KEY").fetchSemanticsNode().config.contains(SemanticsProperties.Password))

        box("AWS_ACCESS_KEY_ID").requestFocus()
        waitForIdle()
        assertFalse(spelled(keySecret), "hidden again once the cursor leaves")
    }

    @Test
    fun showTurnsItselfOffAfterThirtySeconds() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen(question(three.take(1)))
        box("AWS_ACCESS_KEY_ID").performTextReplacement(keyId)
        box("AWS_ACCESS_KEY_ID").requestFocus()
        eye("AWS_ACCESS_KEY_ID").performClick()
        waitForIdle()
        assertTrue(spelled(keyId))
        mainClock.advanceTimeBy(SecretReveal.ShownForMillis - 1_000)
        assertTrue(spelled(keyId), "still shown just before")
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        assertFalse(spelled(keyId), "hidden after 30 seconds")
    }

    @Test
    fun showTurnsOffWhenTheAppLeavesTheFront() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen(question(three.take(1)))
        box("AWS_ACCESS_KEY_ID").performTextReplacement(keyId)
        box("AWS_ACCESS_KEY_ID").requestFocus()
        eye("AWS_ACCESS_KEY_ID").performClick()
        waitForIdle()
        assertTrue(spelled(keyId))
        runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        waitForIdle()
        assertFalse(spelled(keyId), "hidden when the app goes to the background")
        runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        waitForIdle()
        assertFalse(spelled(keyId), "and it stays hidden when the app comes back")
    }

    @Test
    fun theSecretsSheetBoxesHaveTheEyeToo() = runDesktopComposeUiTest(width = 1440, height = 900) {
        setContent {
            CompositionLocalProvider(LocalDeviceAuth provides DeviceAuth { true }, LocalLifecycleOwner provides owner) {
                AgentTheme(dark = true) {
                    Box(Modifier.fillMaxSize()) {
                        FeedScreen(FakeFeed.state, SafeArea(), preview = ScreenPreview(secretsOpen = true, addingSecret = true), onEvent = { events += it })
                    }
                }
            }
        }
        val valueBox = onNode(hasContentDescription("Value, hidden as you type") and hasSetTextAction())
        valueBox.performTextReplacement(keySecret)
        waitForIdle()
        assertFalse(spelled(keySecret))
        valueBox.requestFocus()
        onNode(hasContentDescription(SecretReveal.ShowLabel) and hasClickAction(), useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(spelled(keySecret))
        onNode(hasContentDescription(SecretReveal.HideLabel) and hasClickAction(), useUnmergedTree = true).performClick()
        waitForIdle()
        assertFalse(spelled(keySecret))
    }
}
