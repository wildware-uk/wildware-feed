package dev.wildware.agents

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestActionsTest {
    @Test
    fun `a yes or no question gets yes and no buttons that answer true and false`() {
        val actions = RequestActions.actionsFor(RequestKind.Confirm, null)

        assertEquals(listOf("Yes", "No"), actions.map { it.title })
        assertEquals(JsonPrimitive(true), RequestActions.answerFor(RequestKind.Confirm, null, actions[0].id, null))
        assertEquals(JsonPrimitive(false), RequestActions.answerFor(RequestKind.Confirm, null, actions[1].id, null))
    }

    @Test
    fun `each option becomes a button that answers with that option`() {
        val options = listOf("Ship it", "Hold", "Roll back")
        val actions = RequestActions.actionsFor(RequestKind.Buttons, options)

        assertEquals(options, actions.map { it.title })
        assertEquals(JsonPrimitive("Roll back"), RequestActions.answerFor(RequestKind.Buttons, options, actions[2].id, null))
    }

    @Test
    fun `too many options to fit on the lock screen get no buttons`() {
        val options = (1..5).map { "Option $it" }

        assertTrue(RequestActions.actionsFor(RequestKind.Choice, options).isEmpty())
    }

    @Test
    fun `a text question gets a reply field and sends the trimmed text`() {
        val actions = RequestActions.actionsFor(RequestKind.Text, null)

        assertTrue(actions.single().isTextInput)
        assertEquals(JsonPrimitive("main"), RequestActions.answerFor(RequestKind.Text, null, RequestActions.Reply, "  main "))
        assertNull(RequestActions.answerFor(RequestKind.Text, null, RequestActions.Reply, "   "))
    }

    @Test
    fun `multi select and form questions are left for the full card`() {
        assertTrue(RequestActions.actionsFor(RequestKind.MultiChoice, listOf("a", "b")).isEmpty())
        assertTrue(RequestActions.actionsFor(RequestKind.Form, listOf("Send")).isEmpty())
    }

    @Test
    fun `a button that no longer matches the request answers nothing`() {
        assertNull(RequestActions.answerFor(RequestKind.Buttons, listOf("a"), "option-3", null))
        assertNull(RequestActions.answerFor(RequestKind.Buttons, listOf("a"), RequestActions.Yes, null))
        assertNull(RequestActions.answerFor(RequestKind.Confirm, null, "option-0", null))
    }

    @Test
    fun `requests with the same number of buttons share a category from the fixed set`() {
        assertEquals(
            RequestActions.categoryId(RequestKind.Buttons, listOf("a", "b")),
            RequestActions.categoryId(RequestKind.Choice, listOf("c", "d")),
        )
        assertNotEquals(
            RequestActions.categoryId(RequestKind.Buttons, listOf("a", "b")),
            RequestActions.categoryId(RequestKind.Buttons, listOf("a", "b", "c")),
        )

        val optionCounts = (0..RequestActions.MaxOptionButtons + 1).map { n -> List(n) { "Option $it" } }
        val ids = RequestKind.entries.flatMap { kind -> optionCounts.mapNotNull { RequestActions.categoryId(kind, it) } }
        assertEquals(RequestActions.categoryIds.toSet(), ids.toSet())
    }

    @Test
    fun `a request with no buttons has no category`() {
        assertNull(RequestActions.categoryId(RequestKind.Form, listOf("Send")))
        assertNull(RequestActions.categoryId(RequestKind.Buttons, emptyList()))
    }
}
