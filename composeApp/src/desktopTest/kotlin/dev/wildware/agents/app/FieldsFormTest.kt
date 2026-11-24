package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.wildware.agents.FormFieldView
import dev.wildware.agents.RequestCard
import dev.wildware.agents.RequestConfig
import dev.wildware.agents.RequestView
import dev.wildware.agents.ProjectSummary
import dev.wildware.agents.ProjectView
import dev.wildware.agents.StreamStatus
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.engine.FeedMapper
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedEvent
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedScreen
import dev.wildware.agents.app.feed.FieldsForm
import dev.wildware.agents.app.feed.QuestionKind
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.wildware.agents.FeedState as EngineFeed

/** A form with fields on a card: every type drawn, Submit only once it would be taken, and what was sent once it is answered. */
@OptIn(ExperimentalTestApi::class)
class FieldsFormTest {
    private val events = mutableListOf<FeedEvent>()
    private val base = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first { it.id == "q1" }

    private val fields = listOf(
        FormFieldView("name", "Project name", "text", hint = "Lower case", required = true),
        FormFieldView("notes", "Notes", "textarea"),
        FormFieldView("seats", "Seats", "number", min = JsonPrimitive(1), max = JsonPrimitive(9)),
        FormFieldView("mail", "Email", "email"),
        FormFieldView("site", "Site", "url"),
        FormFieldView("day", "Start", "date"),
        FormFieldView("plan", "Plan", "select", options = listOf("Free", "Team"), defaultValue = JsonPrimitive("Free")),
        FormFieldView("size", "Size", "radio", options = listOf("Small", "Large")),
        FormFieldView("agree", "I agree to the terms", "checkbox", required = true),
        FormFieldView("regions", "Regions", "checkboxes", options = listOf("EU", "US")),
        FormFieldView("notify", "Email me when it is ready", "toggle"),
    )

    private val form = base.copy(
        prompt = "Set up the project?", body = "", kind = QuestionKind.Form, choices = emptyList(), allowsText = false,
        fieldsForm = FieldsForm(fields, listOf("Create", "Cancel")),
    )

    private fun ComposeUiTest.show(q: FeedItem.Question) {
        val state = FakeFeed.state.copy(sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(q))))
        setContent { AgentTheme(dark = false) { Box(Modifier.size(390.dp, 3000.dp)) { FeedScreen(state) { events += it } } } }
    }

    private fun ComposeUiTest.box(label: String) = onNode(hasSetTextAction() and hasContentDescription(label, substring = true))

    @Test
    fun everyFieldTypeIsDrawnWithItsLabel() = runDesktopComposeUiTest(390, 3000) {
        show(form)
        // Boxes say their label, and when they must be filled.
        box("Project name, required").assertExists()
        for (label in listOf("Notes", "Seats", "Email", "Site", "Start")) box(label).assertExists()
        onNodeWithText("Lower case").assertExists()
        // The dropdown shows its default; the radios, ticks and switch each their own option.
        onNodeWithContentDescription("Plan").assertExists()
        onNodeWithText("Free").assertExists()
        onNodeWithText("Small").assertExists()
        onNodeWithText("I agree to the terms").assertExists()
        onNodeWithText("EU").assertExists()
        onNodeWithText("Email me when it is ready").assertExists()
    }

    @Test
    fun submitStaysOffUntilTheFormWouldBeTaken() = runDesktopComposeUiTest(390, 3000) {
        show(form)
        onNodeWithText("Create").assertIsNotEnabled()
        onNodeWithText("Still to do: Project name, I agree to the terms").assertExists()

        box("Project name").performScrollTo().performTextInput("garden")
        onNodeWithText("Still to do: I agree to the terms").assertExists()
        onNodeWithText("Create").assertIsNotEnabled()

        // A bad email is said under its box, and stops it too.
        box("Email").performScrollTo().performTextInput("sam@")
        onNodeWithText("Write an email address, like sam@example.com.").assertExists()
        onNodeWithText("I agree to the terms").performScrollTo().performClick()
        onNodeWithText("Create").assertIsNotEnabled()
        box("Email").performTextInput("example.com")
        onNodeWithText("Create").assertIsEnabled()

        onNodeWithText("Large").performScrollTo().performClick()
        onNodeWithText("US").performScrollTo().performClick()
        onNodeWithText("Email me when it is ready").performScrollTo().performClick()
        onNodeWithText("Create").performScrollTo().performClick()
        waitForIdle()
        val sent = events.filterIsInstance<FeedEvent.AnswerFields>().single()
        assertEquals(form.id, sent.questionId)
        assertEquals(
            """{"action":"Create","values":{"name":"garden","mail":"sam@example.com","plan":"Free","size":"Large","agree":true,"regions":["US"],"notify":true}}""",
            sent.value.toString(),
        )
    }

    @Test
    fun aFieldThisAppCannotDrawSaysToUpdate() = runDesktopComposeUiTest(390, 1200) {
        show(form.copy(fieldsForm = FieldsForm(listOf(FormFieldView("x", "X", "slider")), listOf("Go"), understood = false)))
        onNodeWithText("This form has a field this version of $ProductName can't show. Update the app to fill it in.").assertExists()
        assertTrue(onAllNodes(hasText("Go")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun onceAnsweredTheCardShowsWhatWasSentAndWho() = runDesktopComposeUiTest(390, 1200) {
        show(form.copy(answered = FeedItem.Answered("you", "Create", values = listOf("Project name" to "garden", "Regions" to "EU, US"))))
        onNodeWithText("Answered by you · Create").assertExists()
        onNodeWithText("garden").assertExists()
        onNodeWithText("EU, US").assertExists()
        // Read-only: no boxes, no buttons (the composer's own box aside).
        assertTrue(onAllNodes(hasSetTextAction() and hasContentDescription("Project name", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasText("Cancel")).fetchSemanticsNodes().isEmpty())
    }

    private val noon = 1_795_003_200_000L
    private val mapper = FeedMapper("https://dash.test/", now = { noon }, zone = TimeZone.UTC)
    private val mega = ProjectSummary(ProjectView(id = "p1", slug = "mega", name = "Mega"), waiting = 1, unread = 2)

    private fun card(request: RequestView) = RequestCard(
        request, agentName = "forge", projectName = null, discussion = emptyList(), unread = 0, sending = false, tags = emptyList(), fields = emptyList(),
    )

    private val asked = RequestView(
        id = "r9", agentId = "a1", kind = "form", question = "Set it up?", options = listOf("Submit"),
        config = RequestConfig(label = "This form needs a newer app", placeholder = "Update the app", fields = fields.take(2)),
    )

    @Test
    fun aFormWithFieldsMapsToItsFieldsNotTheOldBox() {
        val q = mapper.map(EngineFeed(null, null, StreamStatus.Live, true, false, false, listOf(card(asked)), emptyList(), emptyList(), 0, false, emptyList()), listOf(mega), emptyList(), emptyList(), null)
            .sections.single().items.single() as FeedItem.Question
        assertNull(q.form, "the words for older apps are not shown here")
        assertEquals(FieldsForm(fields.take(2), listOf("Submit"), understood = true), q.fieldsForm)
    }

    @Test
    fun anAnsweredFormStaysForATeamOfOneWithWhatWasSent() {
        val answer = Json.parseToJsonElement("""{"kind":"form","value":{"action":"Submit","values":{"name":"garden"}}}""")
        val done = card(asked.copy(state = "answered", answeredAt = noon - 60_000, answer = answer))
        val state = mapper.map(
            EngineFeed(null, null, StreamStatus.Live, true, false, false, emptyList(), emptyList(), emptyList(), 0, false, emptyList(), answered = listOf(done)),
            listOf(mega), emptyList(), emptyList(), null,
        )
        val q = state.sections.flatMap { it.items }.single() as FeedItem.Question
        assertEquals("you", q.answered?.by)
        assertEquals("Submit", q.answered?.said)
        assertEquals(listOf("Project name" to "garden", "Notes" to "—"), q.answered?.values)
    }
}
