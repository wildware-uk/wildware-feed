package dev.wildware.feed.parity

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * request_input end to end: the agent asks (and is held), the owner answers, dismisses, or lets it
 * expire; the agent withdraws or edits. Spec: server/spec/03-owner-api-requests-devices.md §1-2, 9.
 */
class RequestsTest : ParityTest() {
    private suspend fun HttpResponse.expectError(status: Int, error: String, message: String) {
        val text = bodyAsText()
        assertEquals(status, this.status.value, text)
        assertEquals(error, Parity.json.parseToJsonElement(text)["error"].s, text)
        assertEquals(message, Parity.json.parseToJsonElement(text)["message"].s, text)
    }

    private suspend fun pendingRequest(question: String): JsonObject? =
        owner.get("/api/snapshot/requests").obj()["requests"].arr.firstOrNull { it["question"].s == question }?.o

    /**
     * Ask [args] as [agent] while it is held, find the pending request, run [block] with its id and
     * the held call. Whatever [block] leaves pending is dismissed so the held call returns.
     */
    private suspend fun <T> asking(
        args: JsonObject,
        agent: Agent = alpha,
        block: suspend (id: String, held: Deferred<ToolResult>) -> T,
    ): T = coroutineScope {
        val question = args["question"].s
        val held = async { agent.tool("request_input", args) }
        val request = eventually(what = "request '$question' to be pending") { pendingRequest(question) }
        try {
            block(request["id"].s, held)
        } finally {
            if (!held.isCompleted) owner.delete("/api/requests/${request["id"].s}")
            withTimeout(70.seconds) { held.await() }
        }
    }

    private fun ask(kind: String, vararg extra: Pair<String, Any?>) =
        JsonObject(obj("kind" to kind, "question" to "${kind}? ${Parity.suffix()}", "project" to project.slug) + obj(*extra))

    private val project by lazy { kotlinx.coroutines.runBlocking { Fixtures.project() } }

    private suspend fun answer(id: String, value: Any?) = owner.post("/api/requests/$id/answer", obj("value" to value))

    @Test
    fun `confirm - the agent is woken by the answer`() = parity {
        asking(ask("confirm")) { id, held ->
            answer(id, "yes").expectError(400, "invalid_argument", "a confirm is answered true or false")
            owner.post("/api/requests/$id/answer", obj()).expectError(400, "invalid_argument", "an answer must carry a value")

            val ok = answer(id, true)
            assertEquals(200, ok.status.value)
            val request = ok.obj()["request"].o
            assertEquals("answered", request["state"].s)
            assertEquals(obj("kind" to "confirm", "value" to true), request["answer"])
            assertNotNull(request["answeredAt"].str)
            assertEquals(
                listOf("id", "seq", "agentId", "projectId", "updateId", "messageId", "kind", "question", "detail", "options", "config", "state", "expiresAt", "answeredAt", "answer", "editedAt", "pinned"),
                request.keys.toList(),
            )

            val result = withTimeout(10.seconds) { held.await() }
            assertTrue(!result.isError, result.raw.toString())
            assertEquals("answered", result.structured["state"].s)

            answer(id, true).expectError(409, "conflict", "request $id has already been answered")
        }
        answer("01ZZZZZZZZZZZZZZZZZZZZZZZZ", true).expectError(404, "not_found", "no such request: 01ZZZZZZZZZZZZZZZZZZZZZZZZ")
    }

    @Test
    fun `confirm false is still an answer`() = parity {
        asking(ask("confirm")) { id, held ->
            val request = answer(id, false).obj()["request"].o
            assertEquals("answered", request["state"].s)
            assertEquals(obj("kind" to "confirm", "value" to false), request["answer"])
            val awaited = alpha.ok("await_request", obj("request_id" to id))
            assertEquals("answered", awaited["state"].s)
            held.await()
        }
    }

    @Test
    fun `text - trimmed and held to min`() = parity {
        asking(ask("text", "min" to 3)) { id, _ ->
            answer(id, "  ab ").expectError(400, "invalid_argument", "the answer must be at least 3 characters")
            assertEquals(obj("kind" to "text", "value" to "abc"), answer(id, "  abc ").obj()["request"]["answer"])
        }
        asking(ask("text")) { id, _ ->
            answer(id, "").expectError(400, "invalid_argument", "the answer must be at least 1 characters")
            answer(id, 5).expectError(400, "invalid_argument", "a text request is answered with a string")
        }
    }

    @Test
    fun `buttons and choice - one of the options`() = parity {
        for (kind in listOf("buttons", "choice")) {
            asking(ask(kind, "options" to arr("Yes", "No"))) { id, _ ->
                answer(id, "Maybe").expectError(400, "invalid_argument", "\"Maybe\" is not one of the options offered")
                answer(id, 5).expectError(400, "invalid_argument", "5 is not one of the options offered")
                assertEquals(obj("kind" to kind, "value" to "No"), answer(id, "No").obj()["request"]["answer"])
            }
        }
    }

    @Test
    fun `multi_choice - a list, no repeats, within max`() = parity {
        asking(ask("multi_choice", "options" to arr("a", "b", "c"), "max" to 2)) { id, _ ->
            answer(id, arr("a", "a")).expectError(400, "invalid_argument", "an option was chosen twice")
            answer(id, arr("a", "b", "c")).expectError(400, "invalid_argument", "choose at most 2")
            answer(id, "a").expectError(400, "invalid_argument", "a multi_choice is answered with a list of options")
            assertEquals(obj("kind" to "multi_choice", "value" to arr()), answer(id, arr()).obj()["request"]["answer"])
        }
    }

    @Test
    fun `form - an action and a text`() = parity {
        asking(ask("form", "options" to arr("Send", "Cancel"))) { id, _ ->
            answer(id, obj("text" to "hi")).expectError(400, "invalid_argument", "undefined is not one of the actions offered")
            answer(id, obj("action" to "Send")).expectError(400, "invalid_argument", "a form\u2019s text is a string")
            assertEquals(
                obj("kind" to "form", "value" to obj("action" to "Send", "text" to "hi")),
                answer(id, obj("action" to "Send", "text" to "  hi  ")).obj()["request"]["answer"],
            )
        }
    }

    @Test
    fun `dismissing cancels, once`() = parity {
        asking(ask("confirm")) { id, held ->
            val dismissed = owner.delete("/api/requests/$id")
            assertEquals(200, dismissed.status.value)
            val request = dismissed.obj()["request"].o
            assertEquals("cancelled", request["state"].s)
            assertNotNull(request["answeredAt"].str)
            assertTrue(request["answer"].isNull)
            owner.delete("/api/requests/$id").expectError(409, "conflict", "request $id was dismissed")
            assertEquals("cancelled", held.await().structured["state"].s)
        }
    }

    @Test
    fun `a request expires on its own and is then refused`() = parity {
        val args = ask("confirm", "timeout_s" to 5)
        val started = alpha.tool("request_input", args)
        assertEquals("timeout", started.structured["state"].s, "a hold shorter than HOLD_S ends at the deadline")
        val id = started.structured["request_id"].s
        assertEquals(null, pendingRequest(args["question"].s))
        answer(id, true).expectError(409, "conflict", "request $id timed out")
        assertEquals("timeout", alpha.ok("await_request", obj("request_id" to id))["state"].s)

        val tooShort = alpha.tool("request_input", ask("confirm", "timeout_s" to 4))
        assertTrue(tooShort.isError)
        assertEquals("timeout_s must be a whole number of seconds between 5 and 86400", tooShort.structured["message"].s)
    }

    @Test
    fun `the asking agent can withdraw, nobody else can`() = parity {
        asking(ask("confirm")) { id, held ->
            val foreign = beta.tool("withdraw_request", obj("request_id" to id))
            assertTrue(foreign.isError)
            assertEquals("request belongs to another agent", foreign.structured["message"].s)

            val withdrawn = alpha.ok("withdraw_request", obj("request_id" to id))
            assertEquals("withdrawn", withdrawn["state"].s)
            answer(id, true).expectError(409, "conflict", "request $id was withdrawn")
            assertEquals("withdrawn", held.await().structured["state"].s)
        }
    }

    @Test
    fun `an edit keeps the default valid`() = parity {
        asking(ask("choice", "options" to arr("Red", "Blue"), "default" to "Blue")) { id, _ ->
            val bad = alpha.tool("edit_request", obj("request_id" to id, "options" to arr("Red", "Green")))
            assertTrue(bad.isError)
            assertEquals("the default must be one of the options", bad.structured["message"].s)

            val nothing = alpha.tool("edit_request", obj("request_id" to id))
            assertTrue(nothing.isError)
            assertEquals("an edit must change the question, the detail, the options or the default", nothing.structured["message"].s)

            val ok = alpha.tool("edit_request", obj("request_id" to id, "options" to arr("Red", "Green"), "default" to "Red"))
            assertTrue(!ok.isError, ok.raw.toString())
            val row = owner.get("/api/snapshot/requests").obj()["requests"].arr.single { it["id"].s == id }
            assertEquals(arr("Red", "Green"), row["options"])
            assertEquals("Red", row["config"]["default"].s)
            assertNotNull(row["editedAt"].str)
        }
    }

    @Test
    fun `option pictures ride on the request and fall away with their option`() = parity {
        val image = Media.uploadPng(alpha)
        val video = Media.upload(alpha, "clip.mp4", "video/mp4", Media.tinyMp4())
        fun refused(args: JsonObject, message: String) = parity {
            val result = alpha.tool("request_input", args)
            assertTrue(result.isError, result.raw.toString())
            assertEquals(message, result.structured["message"].s)
        }
        refused(ask("form", "options" to arr("Send"), "option_media" to obj("Send" to image)), "pictures on options are for a buttons, choice or multi_choice request")
        refused(ask("choice", "options" to arr("Red", "Blue"), "option_media" to obj("Green" to image)), "Green is not one of the options")
        refused(ask("choice", "options" to arr("Red", "Blue"), "option_media" to obj("Red" to video)), "media $video must be an image to go on an option")
        refused(ask("choice", "options" to arr("Red", "Blue"), "option_media" to obj("Red" to image), "media_ids" to arr(image)), "a picture on an option cannot also be in media_ids")

        asking(ask("choice", "options" to arr("Red", "Blue"), "option_media" to obj("Red" to image))) { id, _ ->
            val row = owner.get("/api/snapshot/requests").obj()["requests"].arr.single { it["id"].s == id }
            assertEquals(obj("Red" to image), row["config"]["optionMedia"])
            assertTrue(row["media"].arr.any { it["id"].s == image })

            alpha.ok("edit_request", obj("request_id" to id, "options" to arr("Blue", "Green")))
            val edited = owner.get("/api/snapshot/requests").obj()["requests"].arr.single { it["id"].s == id }
            assertTrue(edited["config"]["optionMedia"].isNull, "the picture's option is gone")
            assertTrue(edited["media"].arr.any { it["id"].s == image }, "spec surprise: the media stays attached")
        }
    }

    @Test
    fun `the summary lists what is waiting, for the phone`() = parity {
        val device = Device.signIn()
        asking(ask("confirm", "default" to "true")) { id, _ ->
            val summary = device.get("/api/summary")
            assertEquals(200, summary.status.value)
            val body = summary.obj()
            assertEquals(listOf("at", "onlineAgents", "requests", "work", "projects"), body.keys.toList())
            val request = body["requests"].arr.single { it["id"].s == id }.o
            assertEquals(
                listOf("id", "agentName", "projectSlug", "projectName", "question", "detail", "default", "kind", "options", "expiresAt"),
                request.keys.toList(),
            )
            assertEquals("true", request["default"].s)
            assertEquals("confirm", request["kind"].s)
            assertTrue(request["options"].isNull)
            assertTrue(Regex("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z$").matches(request["expiresAt"].s))
            assertEquals(alpha.name, request["agentName"].s)
            assertEquals(setOf("todo", "inProgress", "blocked"), body["work"].o.keys)
        }
    }

    @Test
    fun `a new request rings the bell`() = parity {
        asking(ask("confirm")) { id, _ ->
            val first = eventually(what = "a notification for $id") {
                owner.get("/api/notifications").obj()["notifications"]?.arr?.firstOrNull { it["path"].str?.endsWith("focus=$id") == true }
            }
            assertEquals("request", first["kind"].s)
            assertEquals("${alpha.name} is waiting on you", first["title"].s)
            assertEquals("/projects/${project.slug}?focus=$id", first["path"].s)
        }
    }

    @Test
    fun `a request discussion and replies reach the agent`() = parity {
        asking(ask("confirm")) { id, _ ->
            owner.post("/api/messages", obj("request" to id, "body" to "why?"))
            val messages = eventually(what = "the discussion message") {
                alpha.ok("get_messages", obj("project" to project.slug))["messages"].arr.firstOrNull { it["request_id"].str == id }
            }
            assertEquals("why?", messages["body"].s)
            delay(1)
        }
    }
}
