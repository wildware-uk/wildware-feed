package dev.wildware.agents

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AllActionsTest {
    @Test
    fun `a question with more options than the lock screen fits gets a button for each`() {
        val options = listOf("A", "B", "C", "D", "E", "F")
        val actions = RequestActions.allActionsFor(RequestKind.Choice, options)

        assertEquals(options, actions.map { it.title })
        assertEquals(JsonPrimitive("F"), RequestActions.answerFor(RequestKind.Choice, options, actions[5].id, null))
    }

    @Test
    fun `other kinds get the same buttons as the lock screen`() {
        assertEquals(listOf("Yes", "No"), RequestActions.allActionsFor(RequestKind.Confirm, null).map { it.title })
        assertTrue(RequestActions.allActionsFor(RequestKind.Text, null).single().isTextInput)
        assertTrue(RequestActions.allActionsFor(RequestKind.Form, null).isEmpty())
        assertTrue(RequestActions.allActionsFor(RequestKind.MultiChoice, listOf("a")).isEmpty())
    }
}
