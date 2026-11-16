package dev.wildware.agents

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpokenAnswerTest {
    private fun request(kind: RequestKind, options: List<String>? = null) =
        PendingRequest("r1", "Builder", null, null, "Question?", kind, options, "2026-11-16T13:00:00Z")

    @Test
    fun `yes and no in everyday words answer a confirm`() {
        val confirm = request(RequestKind.Confirm)

        assertEquals(JsonPrimitive(true), SpokenAnswer.parse(confirm, "Yeah."))
        assertEquals(JsonPrimitive(true), SpokenAnswer.parse(confirm, "go ahead"))
        assertEquals(JsonPrimitive(false), SpokenAnswer.parse(confirm, "No, not yet"))
        assertNull(SpokenAnswer.parse(confirm, "maybe later"))
    }

    @Test
    fun `an option is picked by its words or its position`() {
        val choice = request(RequestKind.Choice, listOf("Ship it", "Hold", "Roll back"))

        assertEquals(JsonPrimitive("Roll back"), SpokenAnswer.parse(choice, "roll back"))
        assertEquals(JsonPrimitive("Hold"), SpokenAnswer.parse(choice, "the second one"))
        assertEquals(JsonPrimitive("Ship it"), SpokenAnswer.parse(choice, "ship"))
    }

    @Test
    fun `an unclear option sends nothing`() {
        val choice = request(RequestKind.Buttons, listOf("Merge main", "Merge dev"))

        assertNull(SpokenAnswer.parse(choice, "merge"))
        assertNull(SpokenAnswer.parse(choice, "pizza"))
    }

    @Test
    fun `a text question takes the words as said`() {
        assertEquals(JsonPrimitive("use the staging bucket"), SpokenAnswer.parse(request(RequestKind.Text), " use the staging bucket "))
    }
}
