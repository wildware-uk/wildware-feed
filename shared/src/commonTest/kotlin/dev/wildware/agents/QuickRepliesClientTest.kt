package dev.wildware.agents

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Quick replies (docs/QUICK_REPLIES.md) on the wire. */
class QuickRepliesClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun aMessageReadsItsButtonsAndWhoChoseWhat() {
        val message = json.decodeFromString(MessageView.serializer(), """{"id":"m1","author":"agent:a1","body":"Ship it?","createdAt":1,
            "replies":{"buttons":["Yes","No"],"answers":[{"userId":"founder","button":"Yes","messageId":"m2","at":2}]}}""")
        val replies = message.replies!!
        assertEquals(listOf("Yes", "No"), replies.buttons)
        assertNull(replies.fields)
        assertEquals("Yes", replies.answerOf(FounderId)?.button)
        assertNull(replies.answerOf("someone-else"))
    }

    @Test
    fun anAnswerSaysWhichMessageItAnswers() {
        val reply = json.decodeFromString(MessageView.serializer(), """{"id":"m2","author":"human","body":"Yes","createdAt":2,"replyFor":"m1","replyValue":{"button":"Yes"}}""")
        assertEquals("m1", reply.replyFor)
        assertEquals(JsonPrimitive("Yes"), reply.replyValue?.get("button"))
        assertNull(reply.replies)
    }

    @Test
    fun aTapPostsTheButtonValuesAndKey() = runTest {
        var path = ""
        var method: HttpMethod? = null
        var body = ""
        val client = DashboardClient("https://dash.test/", "device-key", MockEngine { request ->
            path = request.url.encodedPath
            method = request.method
            body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond(
                """{"message":{"id":"m2","author":"human","body":"- **Name:** Sam","createdAt":2,"replyFor":"m1"}}""",
                HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val values = json.parseToJsonElement("""{"name":"Sam"}""") as JsonObject
        val reply = client.quickReply("m1", "Send", values, sendKey = "k1")
        assertEquals("/api/messages/m1/reply", path)
        assertEquals(HttpMethod.Post, method)
        assertEquals("""{"button":"Send","values":{"name":"Sam"},"sendKey":"k1"}""", body)
        assertEquals("m1", reply.replyFor)
    }
}
