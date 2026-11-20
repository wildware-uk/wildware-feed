package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.events.AppEvent
import dev.wildware.feed.server.events.Bus
import dev.wildware.feed.server.http.Wire
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestsTest {
    private var now = 1_763_474_000_000L
    private var holdMs = 200L
    private val db = Database.memory()
    private val bus = Bus(clock = { now })
    private val reads = Reads(db) { now }
    private val requests = Requests(db, bus, reads, { now }, { holdMs })
    private val events = mutableListOf<AppEvent>()

    init {
        bus.subscribe { events += it }
        db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'web', 'Web', 1, 1)")
        db.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P2', 'api', 'Api', 1, 1)")
        db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'claude', 'h1', 1)")
        db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A2', 'other', 'h2', 1)")
    }

    @AfterTest fun close() = db.close()

    private fun ask(kind: String = "confirm", agent: String = "A1", block: NewRequest.() -> NewRequest = { this }) =
        requests.create(agent, NewRequest(kind = kind, question = "Ship it?", project = "web").block()).first

    private fun refused(message: String, block: () -> Unit) {
        val e = assertFailsWith<DomainError> { block() }
        assertEquals(message, e.message)
    }

    private fun payload(type: String) = events.last { it.type == type }.payload

    @Test
    fun `a new request is stored, shaped and announced`() {
        val request = ask("choice") { copy(options = listOf(" Red ", "Blue"), default = "Red", detail = "  ") }
        assertEquals(
            listOf("id", "seq", "agentId", "projectId", "updateId", "messageId", "kind", "question", "detail", "options", "config", "state", "expiresAt", "answeredAt", "answer", "editedAt", "pinned"),
            request.json.keys.toList(),
        )
        assertEquals("""["Red","Blue"]""", Wire.encode(request.json["options"]!!))
        assertEquals("""{"default":"Red"}""", Wire.encode(request.json["config"]!!))
        assertEquals(JsonNull, request.json["detail"])
        assertEquals(now + 3_600_000, request.expiresAt)
        assertEquals("""{"requestId":"${request.id}","agentId":"A1","projectId":"P1","kind":"choice"}""", Wire.encode(payload("request.created")))
    }

    @Test
    fun `creation refuses in the TS order with its words`() {
        refused("kind must be one of text, confirm, buttons, choice, multi_choice, form") { ask("nope") }
        refused("question is required") { ask { copy(question = "  ") } }
        refused("a confirm request takes no options") { ask { copy(options = listOf("a")) } }
        refused("a choice request needs at least one option") { ask("choice") }
        refused("options[1] is required") { ask("choice") { copy(options = listOf("a", " ")) } }
        refused("options must be distinct") { ask("choice") { copy(options = listOf("a", " a")) } }
        refused("at most 25 options") { ask("choice") { copy(options = (1..26).map { "o$it" }) } }
        refused("placeholder is for a text or form request") { ask { copy(placeholder = "x") } }
        refused("multiline is for a text or form request") { ask { copy(multiline = true) } }
        refused("label names a form request’s field") { ask("text") { copy(label = "x") } }
        refused("a multi_choice takes no default") { ask("multi_choice") { copy(options = listOf("a"), default = "a") } }
        refused("the default for a confirm is \"true\" or \"false\"") { ask { copy(default = "yes") } }
        refused("the default must be one of the options") { ask("buttons") { copy(options = listOf("a"), default = "b") } }
        refused("min and max are for a text, form or multi_choice request") { ask { copy(min = 1.0) } }
        refused("min must be a whole number") { ask("text") { copy(min = -1.0) } }
        refused("max must be at most 2") { ask("multi_choice") { copy(options = listOf("a", "b"), max = 3.0) } }
        refused("min must not exceed max") { ask("text") { copy(min = 5.0, max = 4.0) } }
        refused("no such project: nope") { ask { copy(project = "nope") } }
        refused("no such update: U9") { ask { copy(update = "U9") } }
        refused("timeout_s must be a whole number of seconds between 5 and 86400") { ask { copy(timeoutS = 4.0) } }
        refused("not a media id: \"x\"") { ask { copy(mediaIds = listOf("x")) } }
        assertTrue(events.none { it.type == "request.created" })
    }

    @Test
    fun `config keeps the creation key order and a form's default is free text`() {
        val request = ask("form") { copy(options = listOf("Send"), placeholder = "p", multiline = true, label = "Msg", default = "draft", min = 0.0, max = 9.0) }
        assertEquals("""{"placeholder":"p","multiline":true,"label":"Msg","default":"draft","min":0,"max":9}""", Wire.encode(request.json["config"]!!))
    }

    @Test
    fun `a message anchor wins and carries its card`() {
        db.update("INSERT INTO updates (id, project_id, agent_id, body, created_at) VALUES ('U1', 'P1', 'A1', 'b', 1)")
        db.update("INSERT INTO messages (id, project_id, update_id, author, body, created_at) VALUES ('M1', 'P1', 'U1', 'human', 'm', 1)")
        val request = ask { copy(project = null, message = " M1 ", update = "ignored") }
        assertEquals(listOf("P1", "U1", "M1"), listOf(request.projectId, request.updateId, request.messageId))
        refused("the message named belongs to a different project") { ask { copy(project = "api", message = "M1") } }
        refused("the update named belongs to a different project") { ask { copy(project = "api", update = "U1") } }
    }

    @Test
    fun `an answer counts only for the question as it was shown`() {
        val request = ask()
        now += 1_000
        val edited = requests.edit(request.id, "A1", RequestEdit(question = "Delete the live database?"))
        val e = assertFailsWith<DomainError> { requests.answer(request.id, JsonPrimitive(true), shown = Requests.Shown(editedAt = null)) }
        assertEquals(409, e.status)
        assertEquals("pending", requests.find(request.id)!!.state, "the old Yes was not recorded")
        assertEquals("answered", requests.answer(request.id, JsonPrimitive(true), shown = Requests.Shown(edited.editedAt)).state)
        // An app that sends no version is answered as before.
        assertEquals("answered", requests.answer(ask().id, JsonPrimitive(true)).state)
    }

    @Test
    fun `answers are checked per kind`() {
        fun answerOf(kind: String, value: JsonElement, setup: NewRequest.() -> NewRequest = { this }) =
            Wire.encode(requests.answer(ask(kind, block = setup).id, value).answer!!)

        refused("a confirm is answered true or false") { requests.answer(ask().id, JsonPrimitive("yes")) }
        assertEquals("""{"kind":"text","value":"abc"}""", answerOf("text", JsonPrimitive("  abc ")))
        refused("the answer must be at least 1 characters") { requests.answer(ask("text").id, JsonPrimitive(" ")) }
        refused("the answer must be at least 3 characters") { requests.answer(ask("text") { copy(min = 3.0) }.id, JsonPrimitive("ab")) }
        val buttons = ask("buttons") { copy(options = listOf("Yes", "No")) }
        refused("\"Maybe\" is not one of the options offered") { requests.answer(buttons.id, JsonPrimitive("Maybe")) }
        refused("5 is not one of the options offered") { requests.answer(buttons.id, JsonPrimitive(5)) }
        refused("null is not one of the options offered") { requests.answer(buttons.id, JsonNull) }
        val multi = ask("multi_choice") { copy(options = listOf("a", "b", "c"), max = 2.0) }
        refused("an option was chosen twice") { requests.answer(multi.id, JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("a")))) }
        refused("choose at most 2") { requests.answer(multi.id, JsonArray(listOf("a", "b", "c").map(::JsonPrimitive))) }
        refused("1.5 is not one of the options offered") { requests.answer(multi.id, JsonArray(listOf(JsonPrimitive(1.5)))) }
        val form = ask("form") { copy(options = listOf("Send", "Cancel")) }
        refused("undefined is not one of the actions offered") { requests.answer(form.id, buildJsonObject { put("text", "hi") }) }
        refused("a form’s text is a string") { requests.answer(form.id, buildJsonObject { put("action", "Send") }) }
        refused("a form is answered with { action, text }") { requests.answer(form.id, JsonArray(emptyList())) }
        val answered = requests.answer(form.id, buildJsonObject { put("action", "Send"); put("text", "  hi  ") })
        assertEquals("""{"kind":"form","value":{"action":"Send","text":"hi"}}""", Wire.encode(answered.answer!!))
        assertEquals("Send", db.one("SELECT decided_value FROM approvals WHERE id = ?", form.id) { it.getString(1) })

        val no = requests.answer(ask().id, JsonPrimitive(false))
        assertEquals("answered", no.state)
        assertEquals("rejected" to "false", db.one("SELECT state, decided_value FROM approvals WHERE id = ?", no.id) { it.getString(1) to it.getString(2) })
        assertEquals("""{"requestId":"${no.id}","agentId":"A1","state":"answered","settledAt":"${Wire.isoOf(now)}"}""", Wire.encode(payload("request.answered")))
        refused("request ${no.id} has already been answered") { requests.answer(no.id, JsonPrimitive(true)) }
        refused("no such request: R9") { requests.answer("R9", JsonPrimitive(true)) }
    }

    @Test
    fun `dismiss, withdraw and the conflicts after them`() {
        val dismissed = requests.cancel(ask().id)
        assertEquals("cancelled", dismissed.state)
        assertEquals(now, dismissed.answeredAt)
        refused("request ${dismissed.id} was dismissed") { requests.cancel(dismissed.id) }

        val mine = ask()
        refused("request belongs to another agent") { requests.withdraw(mine.id, "A2") }
        assertEquals("withdrawn", requests.withdraw(mine.id, "A1").state)
        refused("request ${mine.id} was withdrawn") { requests.answer(mine.id, JsonPrimitive(true)) }
    }

    @Test
    fun `a due request times out where it is touched, then refuses (decided keep 4)`() {
        val request = ask { copy(timeoutS = 5.0) }
        now += 5_000
        assertTrue(reads.pendingRequests(now).isEmpty())
        refused("request ${request.id} timed out") { requests.answer(request.id, JsonPrimitive(true)) }
        assertEquals("timeout", payload("request.answered")["state"]!!.jsString)
        assertEquals(1, events.count { it.type == "request.answered" })
    }

    @Test
    fun `the sweeper times out every due request with one event each`() {
        ask { copy(timeoutS = 5.0) }
        ask { copy(timeoutS = 5.0) }
        ask()
        now += 6_000
        assertEquals(2, requests.expireDue().size)
        assertEquals(2, events.count { it.type == "request.answered" })
        assertTrue(requests.expireDue().isEmpty())
    }

    @Test
    fun `a hold returns pending after the hold, or the answer at once`() = runBlocking {
        val request = ask()
        val pending = requests.awaitRequest(request.id, "A1")
        assertEquals("pending", pending.state)

        holdMs = 10_000
        val waiting = async { requests.awaitRequest(request.id, "A1") }
        delay(100)
        requests.answer(request.id, JsonPrimitive(true))
        val result = waiting.await()
        assertEquals("answered", result.state)
        assertEquals("""{"kind":"confirm","value":true}""", Wire.encode(result.response!!))
        refused("request belongs to another agent") { runBlocking { requests.awaitRequest(request.id, "A2") } }
    }

    @Test
    fun `an answer that lands before the park still wakes it`() = runBlocking {
        holdMs = 10_000
        // Answer from inside the request.created dispatch, before requestInput parks.
        val stop = bus.subscribe { e ->
            if (e.type == "request.created") requests.answer(e.payload["requestId"]!!.jsString!!, JsonPrimitive(true))
        }
        val started = System.nanoTime()
        val result = requests.requestInput("A1", NewRequest(kind = "confirm", question = "Now?"))
        stop()
        assertEquals("answered", result.state)
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
    }

    @Test
    fun `a hold ends at the request's own deadline`() = runBlocking {
        holdMs = 10_000
        val request = ask { copy(timeoutS = 5.0) }
        now += 5_000
        assertEquals("timeout", requests.awaitRequest(request.id, "A1").state)
    }

    @Test
    fun `edits keep the default valid and drop pictures of removed options`() {
        val request = ask("choice") { copy(options = listOf("Red", "Blue"), default = "Blue") }
        refused("the default must be one of the options") { requests.edit(request.id, "A1", RequestEdit(options = listOf("Red", "Green"))) }
        refused("an edit must change the question, the detail, the options or the default") { requests.edit(request.id, "A1", RequestEdit()) }
        val edited = requests.edit(request.id, "A1", RequestEdit(options = listOf("Red", "Green"), defaultGiven = true, default = "Red"))
        assertEquals("""{"default":"Red"}""", Wire.encode(edited.json["config"]!!))
        assertEquals(now, edited.editedAt)
        assertEquals("""{"requestId":"${request.id}","agentId":"A1","projectId":"P1"}""", Wire.encode(payload("request.updated")))

        val cleared = requests.edit(request.id, "A1", RequestEdit(defaultGiven = true, default = null, detailGiven = true, detail = "why"))
        assertEquals(JsonNull, cleared.json["config"])
        assertEquals("why", cleared.detail)

        media("01ARZ3NDEKTSV4RRFFQ69G5FB1")
        media("01ARZ3NDEKTSV4RRFFQ69G5FB2")
        val pictured = ask("choice") { copy(options = listOf("Red", "Blue"), optionMedia = listOf("Red" to "01ARZ3NDEKTSV4RRFFQ69G5FB1", "Blue" to "01ARZ3NDEKTSV4RRFFQ69G5FB2")) }
        assertEquals("""{"optionMedia":{"Red":"01ARZ3NDEKTSV4RRFFQ69G5FB1","Blue":"01ARZ3NDEKTSV4RRFFQ69G5FB2"}}""", Wire.encode(pictured.json["config"]!!))
        assertEquals(pictured.id, db.one("SELECT request_id FROM media WHERE id = '01ARZ3NDEKTSV4RRFFQ69G5FB1'") { it.getString(1) })
        val dropped = requests.edit(pictured.id, "A1", RequestEdit(options = listOf("Blue", "Green")))
        assertEquals("""{"optionMedia":{"Blue":"01ARZ3NDEKTSV4RRFFQ69G5FB2"}}""", Wire.encode(dropped.json["config"]!!))
        assertEquals(pictured.id, db.one("SELECT request_id FROM media WHERE id = '01ARZ3NDEKTSV4RRFFQ69G5FB1'") { it.getString(1) }, "spec surprise: the media stays")
    }

    private fun media(id: String, kind: String = "image", author: String = "agent:A1", sha: String = "abc") = db.update(
        "INSERT INTO media (id, author, kind, mime, bytes, sha256, status, created_at) VALUES (?, ?, ?, 'image/png', 10, ?, 'ready', 1)",
        id, author, kind, sha,
    )

    @Test
    fun `option pictures are checked`() {
        val image = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val video = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
        val theirs = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
        val empty = "01ARZ3NDEKTSV4RRFFQ69G5FAY"
        media(image)
        media(video, kind = "video")
        media(theirs, author = "agent:A2")
        media(empty, sha = "")
        val choice: NewRequest.() -> NewRequest = { copy(options = listOf("Red", "Blue")) }
        refused("pictures on options are for a buttons, choice or multi_choice request") { ask("form") { copy(options = listOf("Send"), optionMedia = listOf("Send" to image)) } }
        refused("Green is not one of the options") { ask("choice") { choice().copy(optionMedia = listOf("Green" to image)) } }
        refused("each picture may be used once") { ask("choice") { choice().copy(optionMedia = listOf("Red" to image, "Blue" to image)) } }
        refused("media $video must be an image to go on an option") { ask("choice") { choice().copy(optionMedia = listOf("Red" to video)) } }
        refused("media $theirs was uploaded by somebody else") { ask("choice") { choice().copy(optionMedia = listOf("Red" to theirs)) } }
        refused("media $empty has no bytes yet: upload the file first") { ask("choice") { choice().copy(optionMedia = listOf("Red" to empty)) } }
        refused("no such media: 01ARZ3NDEKTSV4RRFFQ69G5FAZ") { ask("choice") { choice().copy(optionMedia = listOf("Red" to "01ARZ3NDEKTSV4RRFFQ69G5FAZ")) } }
        refused("a picture on an option cannot also be in media_ids") { ask("choice") { choice().copy(optionMedia = listOf("Red" to image), mediaIds = listOf(image)) } }
    }

    @Test
    fun `embeds are checked and stored on the request`() {
        db.update("INSERT INTO embed_types (id, slug, name, spec, created_at, updated_at) VALUES ('T1', 'note', 'Note', '{}', 1, 1)")
        refused("embeds[0]: no embed type \"nope\". Call list_embed_types to see what is registered, or register_embed_type to add it.") {
            ask { copy(embeds = listOf(NewEmbed("nope", null, null))) }
        }
        refused("embeds[0].source_url must be an absolute http or https URL") { ask { copy(embeds = listOf(NewEmbed("note", null, "ftp://x"))) } }
        val request = ask { copy(embeds = listOf(NewEmbed(" NOTE ", buildJsonObject { put("b", 2); put("1", "x") }, "HTTPS://Example.COM"))) }
        assertEquals(
            Triple("""{"1":"x","b":"2"}""", "https://example.com/", "agent:A1"),
            db.one("SELECT data, source_url, author FROM embeds WHERE request_id = ?", request.id) { Triple(it.getString(1), it.getString(2), it.getString(3)) },
        )
    }

    @Test
    fun `nothing to answer for a pending list once due`() {
        ask { copy(timeoutS = 5.0) }
        now += 5_000
        assertNull(reads.pendingRequests(now).firstOrNull())
    }

    @Test
    fun `a question is medium unless the agent says and may be critical`() {
        val plain = ask()
        assertEquals("medium", plain.priority)
        assertEquals(null, plain.json["priority"], "an ordinary question reads as it always did")
        val loud = ask { copy(priority = "critical") }
        assertEquals("critical", loud.priority)
        assertEquals("critical", (loud.json["priority"] as kotlinx.serialization.json.JsonPrimitive).content)
        refused("priority must be one of: low, medium, high, critical") { ask { copy(priority = "urgent") } }
    }
}
