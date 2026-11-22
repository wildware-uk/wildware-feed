package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.FeedbackCard
import dev.wildware.agents.FeedbackKinds
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FeedState
import dev.wildware.agents.app.feed.FeedUi
import dev.wildware.agents.app.feed.FeedbackCompose
import dev.wildware.agents.app.feed.FeedbackRow
import dev.wildware.agents.app.feed.FeedbackState
import dev.wildware.agents.app.feed.FormField
import dev.wildware.agents.app.feed.MenuTarget
import dev.wildware.agents.app.feed.QuestionKind
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.ScreenPreview
import dev.wildware.agents.app.feed.menuActionsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feedback (server/spec/14-feedback.md): the form, the team's list with each one's status, and an agent's item waiting on its owner. */
@OptIn(ExperimentalTestApi::class)
class FeedbackSheetTest {
    private val events = mutableListOf<FeedEvent>()

    private val sent = FeedbackState(
        items = listOf(
            FeedbackRow("f3", FeedbackKinds.Feedback, "get_messages pages slowly", "Paging takes three calls.", "agents", "pending", "Waiting for approval", "scout (agent)", "2m"),
            FeedbackRow("f2", FeedbackKinds.FeatureRequest, "Voice notes in threads", "Reply by voice.", "voice", "sent", "Sent", "Sam", "1d"),
            FeedbackRow("f1", FeedbackKinds.Feedback, "Too many tools", "Hard to choose.", null, "discarded", "Discarded", "forge (agent)", "3d"),
        ),
        recipient = "Wildware",
        sends = true,
        areas = listOf("feed", "threads", "voice", "secrets", "apps", "agents", "billing", "other"),
        loaded = true,
    )

    private val inSheet = hasAnyAncestor(SemanticsMatcher("in the Send feedback sheet") { it.config.getOrNull(SemanticsProperties.PaneTitle) == "Send feedback" })

    private fun ComposeUiTest.sendButton() = onNode(hasText("Send to Wildware") and hasClickAction() and inSheet)

    private fun ComposeUiTest.screen(state: FeedState = FakeFeed.state.copy(feedback = sent), preview: ScreenPreview = ScreenPreview(feedbackOpen = true)) = setContent {
        AgentTheme(dark = true) {
            Box(Modifier.fillMaxSize()) { FeedScreen(state, SafeArea(), preview = preview, onEvent = { events += it }) }
        }
    }

    @Test
    fun theListShowsEachItemsStatus() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen()
        onNodeWithText("Voice notes in threads").assertExists()
        onNodeWithText("Sent").assertExists()
        onNodeWithText("Waiting for approval").assertExists()
        onNodeWithText("Discarded").assertExists()
        onNodeWithText("What your team has sent Wildware", substring = true).assertExists()
        assertTrue(events.any { it is FeedEvent.ReadFeedback }, "opening the sheet reads the list")
    }

    @Test
    fun theFormSendsWhatWasWrittenOnce() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen(preview = ScreenPreview(feedbackOpen = true, feedbackCompose = FeedbackCompose()))
        onNodeWithText("A feature you'd like").performClick()
        onNodeWithText("Voice").performClick()
        onNode(hasSetTextAction() and hasContentDescription("Title, in a line")).performTextReplacement("  Voice notes in threads ")
        onNode(hasSetTextAction() and hasContentDescription("Details: what you tried, what happened, what would help")).performTextReplacement("Reply by voice.")
        onNodeWithText("Goes to Wildware", substring = true).assertExists()
        sendButton().performScrollTo().performClick()
        val send = events.filterIsInstance<FeedEvent.SendFeedback>().single()
        assertEquals(FeedbackKinds.FeatureRequest, send.draft.kind)
        assertEquals("voice", send.draft.area)
        assertEquals("Voice notes in threads", send.draft.title)
        assertEquals("Reply by voice.", send.draft.body)
        assertEquals(null, send.draft.card)
        assertEquals(false, send.draft.includeEmail)
    }

    @Test
    fun theEmailGoesOnlyWhenTicked() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen(preview = ScreenPreview(feedbackOpen = true, feedbackCompose = FeedbackCompose()))
        onNodeWithText("Include my email, so they can write back").performScrollTo().performClick()
        onNode(hasSetTextAction() and hasContentDescription("Title, in a line")).performTextReplacement("Reach me")
        onNode(hasSetTextAction() and hasContentDescription("Details: what you tried, what happened, what would help")).performTextReplacement("Write back.")
        sendButton().performScrollTo().performClick()
        assertEquals(true, events.filterIsInstance<FeedEvent.SendFeedback>().single().draft.includeEmail)
    }

    @Test
    fun whereFeedbackGoesNowhereItIsSavedAndSaysSo() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen(
            state = FakeFeed.state.copy(feedback = sent.copy(sends = false)),
            preview = ScreenPreview(feedbackOpen = true, feedbackCompose = FeedbackCompose()),
        )
        onNodeWithText("Saved on this dashboard", substring = true).assertExists()
        onNode(hasText("Save it") and inSheet).assertExists()
        assertTrue(onAllNodes(hasText("Include my email, so they can write back")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun nothingIsSentWithoutATitleAndDetails() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen(preview = ScreenPreview(feedbackOpen = true, feedbackCompose = FeedbackCompose()))
        onNode(hasSetTextAction() and hasContentDescription("Title, in a line")).performTextReplacement("Only a title")
        onNode(hasText("Send to Wildware") and inSheet).performScrollTo().assertIsNotEnabled()
        assertTrue(events.none { it is FeedEvent.SendFeedback })
    }

    @Test
    fun aCardsMenuOffersFeedbackAboutThatCard() {
        val update = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().first()
        val target = MenuTarget(update.id, update.agent.name, update.body, own = false)
        assertTrue(menuActionsFor(target, null).any { it.label == "Feedback…" })
        val ui = FeedUi(ScreenPreview(), mutableStateOf(FakeFeed.state)) { events += it }
        ui.emit(FeedEvent.FeedbackAbout(update.id))
        assertTrue(ui.feedbackOpen)
        assertEquals(FeedbackCard("update", update.id), ui.feedbackCompose?.card)
        assertTrue(events.none { it is FeedEvent.FeedbackAbout }, "opening the form is the screen's own job")
    }

    @Test
    fun theCardsFormOpensWithTheCardNamed() = runDesktopComposeUiTest(width = 1440, height = 900) {
        screen(preview = ScreenPreview(feedbackOpen = true, feedbackCompose = FeedbackCompose(FeedbackCard("update", "u1"), "Fixed the counts")))
        onNodeWithText("About the card “Fixed the counts”").assertExists()
        onNode(hasSetTextAction() and hasContentDescription("Title, in a line")).performTextReplacement("Counts jump")
        onNode(hasSetTextAction() and hasContentDescription("Details: what you tried, what happened, what would help")).performTextReplacement("They jump.")
        sendButton().performScrollTo().performClick()
        assertEquals(FeedbackCard("update", "u1"), events.filterIsInstance<FeedEvent.SendFeedback>().single().draft.card)
    }

    /** An agent's feedback waits as a form question: the owner edits the text, then sends it or discards it. */
    @Test
    fun anAgentsFeedbackIsAnApprovalCardTheOwnerCanEditAndSend() = runDesktopComposeUiTest(width = 390, height = 844) {
        val base = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first()
        val q = base.copy(
            prompt = "Send this feedback to Wildware? “get_messages pages slowly”",
            body = "scout would like to send this feedback to Wildware. Edit the text if you like, then send it, or discard it.",
            kind = QuestionKind.Form, choices = emptyList(), allowsText = false,
            form = FormField(label = "What gets sent", initial = "Paging takes three calls.", multiline = true, min = 1, max = 5000, actions = listOf("Send to Wildware", "Discard")),
        )
        val state = FakeFeed.state.copy(sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(q))))
        setContent { AgentTheme(dark = false) { Box(Modifier.size(390.dp, 844.dp)) { FeedScreen(state) { events += it } } } }
        onNode(hasSetTextAction() and hasContentDescription("What gets sent")).performScrollTo().performTextReplacement("Paging takes three calls; one would do.")
        onNodeWithText("Send to Wildware").performScrollTo().performClick()
        val answer = events.filterIsInstance<FeedEvent.AnswerForm>().single()
        assertEquals("Send to Wildware", answer.action)
        assertEquals("Paging takes three calls; one would do.", answer.text)
    }
}
