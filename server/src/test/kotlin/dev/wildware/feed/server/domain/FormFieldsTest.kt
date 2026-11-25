package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.events.Bus
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A form with fields: what an agent may ask for, and what a person's answer must be. */
class FormFieldsTest {
    private var now = 1_763_474_000_000L
    private val db = Database.memory()
    private val bus = Bus(clock = { now })
    private val requests = Requests(db, bus, Reads(db) { now }, { now })

    init {
        db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'web', 'Web', 1, 1)")
        db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'claude', 'h1', 1)")
    }

    @AfterTest fun close() = db.close()

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun fields(text: String) = json(text).jsonArray

    private fun ask(fields: String, block: NewRequest.() -> NewRequest = { this }) =
        requests.create("A1", NewRequest(kind = "form", question = "Set it up?", project = "web", fields = fields(fields)).block()).first

    private fun refused(message: String, block: () -> Unit) {
        val e = assertFailsWith<DomainError> { block() }
        assertEquals(message, e.message)
    }

    private fun field(text: String) = ask("[$text]")

    private val everyType = """[
        {"id":"name","label":"Name","type":"text","required":true,"max":10,"placeholder":"Sam"},
        {"id":"notes","label":"Notes","type":"textarea","min":2},
        {"id":"seats","label":"Seats","type":"number","min":1,"max":50,"default":5},
        {"id":"mail","label":"Email","type":"email"},
        {"id":"site","label":"Site","type":"url"},
        {"id":"day","label":"Start","type":"date","min":"2026-01-01","max":"2026-12-31"},
        {"id":"plan","label":"Plan","type":"select","options":["Free","Team"],"default":"Free"},
        {"id":"size","label":"Size","type":"radio","options":["S","M","L"]},
        {"id":"agree","label":"I agree","type":"checkbox","required":true},
        {"id":"regions","label":"Regions","type":"checkboxes","options":["EU","US","Asia"],"min":1,"max":2},
        {"id":"notify","label":"Notify me","type":"toggle","hint":"By email"}
    ]"""

    @Test
    fun `every field type is kept as checked, with Submit and words for older apps`() {
        val request = ask(everyType)
        assertEquals(listOf("Submit"), request.options)
        val config = request.config!!
        val kept = config["fields"]!!.jsonArray
        assertEquals(11, kept.size)
        assertEquals(FormFields.TYPES, kept.map { it.jsonObject["type"].jsString })
        assertEquals("""{"id":"seats","label":"Seats","type":"number","min":1,"max":50,"default":5}""", Wire.encode(kept[2]))
        assertEquals(FormFields.OLD_APP_LABEL, config["label"].jsString)
        assertEquals(FormFields.OLD_APP_PLACEHOLDER, config["placeholder"].jsString)
    }

    @Test
    fun `the agent's own buttons stay its actions`() {
        val request = ask("""[{"id":"a","label":"A","type":"text"}]""") { copy(options = listOf("Create", "Cancel")) }
        assertEquals(listOf("Create", "Cancel"), request.options)
    }

    @Test
    fun `a form's fields are checked strictly`() {
        refused("fields are for a form request") {
            requests.create("A1", NewRequest(kind = "text", question = "Q?", project = "web", fields = fields("""[{"id":"a","label":"A","type":"text"}]""")))
        }
        refused("a form with fields sets label, default on each field, not on the request") {
            ask("""[{"id":"a","label":"A","type":"text"}]""") { copy(label = "x", default = "y") }
        }
        refused("fields needs at least one field") { ask("[]") }
        refused("a form has at most 20 fields") { ask((1..21).joinToString(",", "[", "]") { """{"id":"f$it","label":"F","type":"text"}""" }) }
        refused("fields[0] must be an object { id, label, type, ... }") { ask("""["name"]""") }
        refused("fields[0]: unknown key \"multiline\" (a field takes id, label, type, hint, required, default, options, min, max, placeholder)") {
            field("""{"id":"a","label":"A","type":"text","multiline":true}""")
        }
        refused("fields[0].type \"slider\" is not a field type: use one of text, textarea, number, email, url, date, select, radio, checkbox, checkboxes, toggle") {
            field("""{"id":"a","label":"A","type":"slider"}""")
        }
        refused("fields[0].type is required: one of text, textarea, number, email, url, date, select, radio, checkbox, checkboxes, toggle") { field("""{"id":"a","label":"A"}""") }
        refused("fields[0].id is required") { field("""{"label":"A","type":"text"}""") }
        refused("fields[0].id must be 1-64 letters, digits, _ . or -, starting with a letter or _") { field("""{"id":"9 lives","label":"A","type":"text"}""") }
        refused("fields[0].id must be 1-64 letters, digits, _ . or -, starting with a letter or _") { field("""{"id":"${"a".repeat(65)}","label":"A","type":"text"}""") }
        refused("fields[1].id a is used twice") { ask("""[{"id":"a","label":"A","type":"text"},{"id":"a","label":"B","type":"text"}]""") }
        refused("fields[0].label is required") { field("""{"id":"a","label":"  ","type":"text"}""") }
        refused("fields[0].label must be at most 100 characters") { field("""{"id":"a","label":"${"x".repeat(101)}","type":"text"}""") }
        refused("fields[0].hint must be at most 300 characters") { field("""{"id":"a","label":"A","type":"text","hint":"${"x".repeat(301)}"}""") }
        refused("fields[0].required must be true or false") { field("""{"id":"a","label":"A","type":"text","required":"yes"}""") }
        refused("fields[0]: a toggle is always on or off, so it can't be required") { field("""{"id":"a","label":"A","type":"toggle","required":true}""") }
        refused("fields[0]: placeholder is for a text, textarea, number, email or url field") { field("""{"id":"a","label":"A","type":"date","placeholder":"x"}""") }
        refused("fields[0]: options are for a select, radio or checkboxes field") { field("""{"id":"a","label":"A","type":"text","options":["x"]}""") }
        refused("fields[0]: a select field needs options") { field("""{"id":"a","label":"A","type":"select"}""") }
        refused("fields[0].options needs at least one option") { field("""{"id":"a","label":"A","type":"radio","options":[]}""") }
        refused("fields[0].options: at most 25 options") { field("""{"id":"a","label":"A","type":"radio","options":[${(1..26).joinToString(",") { "\"o$it\"" }}]}""") }
        refused("fields[0].options must be distinct") { field("""{"id":"a","label":"A","type":"radio","options":["x"," x"]}""") }
        refused("fields[0].options[0] must be at most 200 characters") { field("""{"id":"a","label":"A","type":"radio","options":["${"x".repeat(201)}"]}""") }
        refused("fields[0]: min and max are for a text, textarea, email, url, number, date or checkboxes field") { field("""{"id":"a","label":"A","type":"toggle","max":1}""") }
        refused("fields[0].min must be a whole number (a length in characters)") { field("""{"id":"a","label":"A","type":"text","min":1.5}""") }
        refused("fields[0].max must be at most 10000") { field("""{"id":"a","label":"A","type":"text","max":10001}""") }
        refused("fields[0].max must be at most 2") { field("""{"id":"a","label":"A","type":"checkboxes","options":["x","y"],"max":3}""") }
        refused("fields[0]: min must not exceed max") { field("""{"id":"a","label":"A","type":"number","min":5,"max":1}""") }
        refused("fields[0].min must be a date like \"2026-12-31\"") { field("""{"id":"a","label":"A","type":"date","min":"2026-02-30"}""") }
        refused("fields[0]: min must not be after max") { field("""{"id":"a","label":"A","type":"date","min":"2026-02-02","max":"2026-02-01"}""") }
    }

    @Test
    fun `a default must be a value its field takes`() {
        refused("\"Gold\" is not one of the options for fields[0].default") { field("""{"id":"a","label":"A","type":"select","options":["Free"],"default":"Gold"}""") }
        refused("fields[0].default must be at most 50") { field("""{"id":"a","label":"A","type":"number","max":50,"default":51}""") }
        refused("fields[0].default must be true or false") { field("""{"id":"a","label":"A","type":"toggle","default":"on"}""") }
        refused("fields[0].default must be an email address, like sam@example.com") { field("""{"id":"a","label":"A","type":"email","default":"sam"}""") }
        // It needn't fill a required field or reach a least length: the person finishes it.
        field("""{"id":"a","label":"A","type":"textarea","required":true,"min":5,"default":"hi"}""")
        field("""{"id":"a","label":"A","type":"checkboxes","options":["x","y"],"min":2,"default":["x"]}""")
    }

    private fun answer(request: OwnerRequest, values: String, action: String = "Submit") =
        requests.answer(request.id, json("""{"action":"$action","values":$values}"""))

    private val good = """{"name":" Sam ","seats":3,"mail":"sam@example.com","site":"https://example.com/x","day":"2026-06-01",
        "plan":"Team","size":"M","agree":true,"regions":["US","EU"],"notify":false}"""

    @Test
    fun `a good answer is kept, trimmed, in the agent's order, with empty optional fields left out`() {
        val request = ask(everyType)
        val answered = answer(request, good)
        assertEquals(
            """{"kind":"form","value":{"action":"Submit","values":{"name":"Sam","seats":3,"mail":"sam@example.com","site":"https://example.com/x",""" +
                """"day":"2026-06-01","plan":"Team","size":"M","agree":true,"regions":["EU","US"],"notify":false}}}""",
            Wire.encode(answered.answer!!),
        )
        assertEquals("Submit", db.one("SELECT decided_value FROM approvals WHERE id = ?", request.id) { it.getString(1) })
        // The same answer again from the same person is an app retrying: it already worked.
        assertEquals(answered.answer, answer(request, good).answer)
    }

    @Test
    fun `a missing tick or toggle reads as false and missing checkboxes as none`() {
        val request = ask("""[{"id":"t","label":"T","type":"toggle"},{"id":"c","label":"C","type":"checkbox"},{"id":"m","label":"M","type":"checkboxes","options":["x"]}]""")
        assertEquals("""{"action":"Submit","values":{"t":false,"c":false,"m":[]}}""", Wire.encode(answer(request, "{}").answer!!["value"]!!))
    }

    @Test
    fun `an answer is checked against the form that asked`() {
        val request = ask(everyType) { copy(options = listOf("Create", "Cancel")) }
        fun bad(message: String, values: String, action: String = "Create") = refused(message) { answer(request, values, action) }
        fun with(key: String, value: String): String {
            val o = json(good).jsonObject.toMutableMap()
            if (value == "null") o.remove(key) else o[key] = json(value)
            return Wire.encode(JsonObject(o))
        }
        bad("\"Submit\" is not one of the actions offered", good, action = "Submit")
        bad("this form has no field \"colour\"", with("colour", "\"red\""))
        bad("\"Name\" is required", with("name", "null"))
        bad("\"Name\" is required", with("name", "\"   \""))
        bad("\"Name\" must be a string", with("name", "3"))
        bad("\"Name\" must be at most 10 characters", with("name", "\"Samantha Jones\""))
        bad("\"Notes\" must be at least 2 characters", with("notes", "\"x\""))
        bad("\"Seats\" must be a number", with("seats", "\"3\""))
        bad("\"Seats\" must be at least 1", with("seats", "0"))
        bad("\"Seats\" must be at most 50", with("seats", "50.5"))
        bad("\"Email\" must be an email address, like sam@example.com", with("mail", "\"sam@example\""))
        bad("\"Site\" must be a web address starting http:// or https://", with("site", "\"example.com\""))
        bad("\"Site\" must be a web address starting http:// or https://", with("site", "\"ftp://example.com\""))
        bad("\"Start\" must be a date like 2026-12-31", with("day", "\"2026-13-01\""))
        bad("\"Start\" must be on or after 2026-01-01", with("day", "\"2025-12-31\""))
        bad("\"Start\" must be on or before 2026-12-31", with("day", "\"2027-01-01\""))
        bad("\"Gold\" is not one of the options for \"Plan\"", with("plan", "\"Gold\""))
        bad("\"XL\" is not one of the options for \"Size\"", with("size", "\"XL\""))
        bad("\"I agree\" must be ticked", with("agree", "false"))
        bad("\"I agree\" must be true or false", with("agree", "\"yes\""))
        bad("\"Regions\": tick at least 1", with("regions", "[]"))
        bad("\"Regions\": tick at most 2", with("regions", "[\"EU\",\"US\",\"Asia\"]"))
        bad("\"Mars\" is not one of the options for \"Regions\"", with("regions", "[\"Mars\"]"))
        bad("\"Regions\" has an option ticked twice", with("regions", "[\"EU\",\"EU\"]"))
        bad("\"Regions\" must be a list of its options", with("regions", "\"EU\""))
        bad("\"Notify me\" must be true or false", with("notify", "1"))
        refused("a form's values are an object, by field id") { requests.answer(request.id, json("""{"action":"Create","values":[]}""")) }
        refused("a form is answered with { action, values }") { requests.answer(request.id, json("\"Create\"")) }
        // Still waiting: no refusal settled it.
        assertEquals("pending", requests.find(request.id)!!.state)
        answer(request, good, action = "Cancel")
    }

    @Test
    fun `an older app's one box can't answer a form with fields`() {
        val request = ask(everyType)
        val update = "This form has 11 fields. Update the app, or answer it on the web, to fill them in."
        refused(update) { requests.answer(request.id, json("""{"action":"Submit","text":"hello"}""")) }
        refused(update) { requests.answer(request.id, json("""{"action":"Submit"}""")) }
        assertEquals("pending", requests.find(request.id)!!.state)
    }

    @Test
    fun `a form without fields answers as it always has`() {
        val form = requests.create("A1", NewRequest(kind = "form", question = "Send?", project = "web", options = listOf("Send"), label = "Msg", default = "draft")).first
        assertEquals("""{"label":"Msg","default":"draft"}""", Wire.encode(form.config!!))
        refused("a form’s text is a string") { requests.answer(form.id, json("""{"action":"Send","values":{}}""")) }
        val answered = requests.answer(form.id, json("""{"action":"Send","text":"  hi  "}"""))
        assertEquals("""{"kind":"form","value":{"action":"Send","text":"hi"}}""", Wire.encode(answered.answer!!))
    }

    @Test
    fun `a form with fields can't have a default edited onto it`() {
        val request = ask("""[{"id":"a","label":"A","type":"text"}]""")
        refused("a form with fields has a default on each field: withdraw it and ask again to change them") {
            requests.edit(request.id, "A1", RequestEdit(defaultGiven = true, default = "x"))
        }
        assertEquals(listOf("Go"), requests.edit(request.id, "A1", RequestEdit(options = listOf("Go"))).options)
    }

    @Test
    fun `the agent hears the action and which fields were filled`() {
        val value = json("""{"action":"Create","values":{"name":"Sam","regions":[],"notify":true}}""").jsonObject
        assertEquals("\"Create\" with values for name, notify. Read each in response.value.values, by field id.", FormFields.describe(value))
        assertEquals(JsonArray(emptyList()), FormFields.answer(fields("""[{"id":"m","label":"M","type":"checkboxes","options":["x"]}]"""), listOf("Go"), json("""{"action":"Go","values":{}}""")).jsonObject["values"]!!.jsonObject["m"])
    }
}
