package dev.wildware.agents.app

import dev.wildware.agents.app.feed.FormField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a form's field accepts, counted as the server counts it: trimmed. */
class FormFieldTest {
    @Test
    fun anEmptyFieldIsFineUnlessTheAgentSetAMinimum() {
        assertNull(FormField().problem(""))
        assertNull(FormField().problem("   "))
    }

    @Test
    fun tooShortSaysHowShort() {
        val field = FormField(min = 5)
        assertEquals("At least 5 characters (2 so far).", field.problem(" hi "))
        assertNull(field.problem("hello"))
    }

    @Test
    fun oneCharacterMinimumAsksForSomething() {
        assertEquals("Write something first.", FormField(min = 1).problem("  "))
    }

    @Test
    fun tooLongSaysByHowMuch() {
        val field = FormField(max = 5)
        assertEquals("3 characters too long (at most 5).", field.problem("whatever"))
        assertEquals("1 character too long (at most 5).", field.problem("sixsix"))
        assertNull(field.problem("  five   "))
    }

    @Test
    fun withNoMaximumTheServersOwnLimitStillApplies() {
        assertNull(FormField().problem("a".repeat(10_000)))
        assertEquals("1 character too long (at most 10000).", FormField().problem("a".repeat(10_001)))
    }
}
