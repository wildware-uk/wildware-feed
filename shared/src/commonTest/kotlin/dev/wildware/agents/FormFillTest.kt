package dev.wildware.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Filling in a form with fields: the same checks the server makes, before anything is sent. */
class FormFillTest {
    private fun field(type: String, required: Boolean = false, options: List<String> = emptyList(), min: Double? = null, max: Double? = null, default: String? = null) =
        FormFieldView(
            id = type, label = type.replaceFirstChar { it.uppercase() }, type = type, required = required, options = options,
            min = min?.let(::JsonPrimitive), max = max?.let(::JsonPrimitive), defaultValue = default?.let(Json::parseToJsonElement),
        )

    private fun typed(text: String) = FieldInput.Typed(text)

    @Test
    fun everyTypeIsKnownAndANewerOneIsNot() {
        assertTrue(FormFill.understands(FieldType.entries.map { field(it.wire) }))
        assertFalse(FormFill.understands(listOf(field("slider"))))
    }

    @Test
    fun fieldsStartOnTheAgentsDefault() {
        assertEquals(typed("5"), FormFill.start(field("number", default = "5")))
        assertEquals(typed("2.5"), FormFill.start(field("number", default = "2.5")))
        assertEquals(FieldInput.Ticked(true), FormFill.start(field("toggle", default = "true")))
        assertEquals(FieldInput.Picked(setOf("EU")), FormFill.start(field("checkboxes", options = listOf("EU", "US"), default = """["EU","Mars"]""")))
        assertEquals(typed("Free"), FormFill.start(field("select", options = listOf("Free"), default = "\"Free\"")))
        assertEquals(typed(""), FormFill.start(field("text")))
    }

    @Test
    fun anEmptyRequiredFieldIsOnlyUnfilled() {
        assertEquals(FormFill.Problem("Required.", empty = true), FormFill.problem(field("text", required = true), typed("  ")))
        assertNull(FormFill.problem(field("text"), typed("")))
        assertEquals(FormFill.Problem("Tick this to send.", empty = true), FormFill.problem(field("checkbox", required = true), FieldInput.Ticked(false)))
        assertNull(FormFill.problem(field("toggle"), FieldInput.Ticked(false)))
    }

    @Test
    fun eachTypeChecksItsShape() {
        assertEquals("Write an email address, like sam@example.com.", FormFill.problem(field("email"), typed("sam@example"))?.text)
        assertNull(FormFill.problem(field("email"), typed(" sam@example.com ")))
        assertEquals("Write a web address starting http:// or https://.", FormFill.problem(field("url"), typed("example.com"))?.text)
        assertNull(FormFill.problem(field("url"), typed("https://example.com/a?b")))
        assertEquals("Write a date as year-month-day, like 2026-12-31.", FormFill.problem(field("date"), typed("2026-02-30"))?.text)
        assertNull(FormFill.problem(field("date"), typed("2028-02-29")))
        assertEquals("Write a number.", FormFill.problem(field("number"), typed("12abc"))?.text)
        assertEquals("At least 1.", FormFill.problem(field("number", min = 1.0, max = 9.0), typed("0"))?.text)
        assertEquals("At most 9.", FormFill.problem(field("number", min = 1.0, max = 9.0), typed("9.5"))?.text)
        assertEquals("Pick one of the options.", FormFill.problem(field("radio", options = listOf("S")), typed("XL"))?.text)
        assertEquals("At least 3 characters (2 so far).", FormFill.problem(field("textarea", min = 3.0), typed("hi"))?.text)
        assertEquals("2 characters too long (at most 3).", FormFill.problem(field("text", max = 3.0), typed("hello"))?.text)
    }

    @Test
    fun checkboxesCountWithinTheirBounds() {
        val f = field("checkboxes", options = listOf("A", "B", "C"), min = 2.0, max = 2.0)
        assertEquals(FormFill.Problem("Tick at least 2 (0 so far).", empty = true), FormFill.problem(f, FieldInput.Picked(emptySet())))
        assertEquals(FormFill.Problem("Tick at least 2 (1 so far)."), FormFill.problem(f, FieldInput.Picked(setOf("A"))))
        assertEquals("Tick at most 2.", FormFill.problem(f, FieldInput.Picked(setOf("A", "B", "C")))?.text)
        assertNull(FormFill.problem(f, FieldInput.Picked(setOf("A", "C"))))
        assertEquals("Tick at least one.", FormFill.problem(field("checkboxes", required = true, options = listOf("A")), FieldInput.Picked(emptySet()))?.text)
    }

    @Test
    fun theAnswerIsWhatTheServerReads() {
        val fields = listOf(
            field("text", required = true), field("number"), field("checkbox"), field("checkboxes", options = listOf("A", "B")), field("date"),
        )
        val inputs = mapOf("text" to typed(" Sam "), "number" to typed("3"), "checkboxes" to FieldInput.Picked(setOf("B", "A")), "date" to typed(""))
        assertFalse(FormFill.ready(fields, inputs - "text"))
        assertEquals(listOf("text"), FormFill.blocking(fields, inputs - "text").map { it.id })
        assertTrue(FormFill.ready(fields, inputs))
        assertEquals(
            """{"action":"Go","values":{"text":"Sam","number":3,"checkbox":false,"checkboxes":["A","B"]}}""",
            FormFill.answer(fields, "Go", inputs).toString(),
        )
        assertEquals("2.5", FormFill.answer(listOf(field("number")), "Go", mapOf("number" to typed("2.5")))["values"].toString().substringAfter(":").trimEnd('}'))
    }

    @Test
    fun whatWasSentReadsInWords() {
        val fields = listOf(field("text"), field("toggle"), field("checkboxes", options = listOf("A", "B")), field("number"), field("email"))
        val values = Json.parseToJsonElement("""{"text":"Sam","toggle":true,"checkboxes":["A","B"],"number":3}""") as kotlinx.serialization.json.JsonObject
        assertEquals(
            listOf("Text" to "Sam", "Toggle" to "Yes", "Checkboxes" to "A, B", "Number" to "3", "Email" to "—"),
            FormFill.said(fields, values),
        )
        val answer = Json.parseToJsonElement("""{"kind":"form","value":{"action":"Go","values":{"a":1}}}""")
        assertEquals("""{"a":1}""", FormFill.valuesOf(answer).toString())
        assertNull(FormFill.valuesOf(Json.parseToJsonElement("""{"kind":"form","value":{"action":"Go","text":"hi"}}""")))
        assertEquals(JsonArray(emptyList()), FormFill.answer(listOf(field("checkboxes", options = listOf("A"))), "Go", emptyMap())["values"]!!.let { (it as kotlinx.serialization.json.JsonObject)["checkboxes"] })
    }
}
