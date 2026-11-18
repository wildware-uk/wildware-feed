package dev.wildware.agents

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The server's JSON and SSE bytes, as they really arrive, read the way the engine reads them. */
class WireTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `a full snapshot as the server sends it`() {
        val snapshot = json.decodeFromString(FeedSnapshot.serializer(), SNAPSHOT)

        assertEquals(41, snapshot.seq)
        val project = snapshot.projects!!.single()
        assertEquals("#112233", project.theme?.background)
        val update = snapshot.updates.items.single()
        assertEquals("warn", update.level)
        assertEquals(listOf("original", "thumb-640"), update.media.single().variants)
        assertEquals("👍", update.reactions.single().emoji)
        assertEquals("github", update.embeds.single().type)
        assertEquals("bug", snapshot.tags!!.single().slug)
        assertEquals(CardRef("update", "u1"), snapshot.cardTags!!.single().card)
        assertEquals("Builder", snapshot.agentNames!!["a1"])
        assertEquals("c2", snapshot.updates.nextCursor)
    }

    @Test
    fun `threads and questions and presence as the server sends them`() {
        val messages = json.decodeFromString(MessagesSnapshot.serializer(), MESSAGES)
        val reply = messages.messages.single()
        assertEquals("u1", reply.updateId)
        assertEquals("m0", reply.answers)
        assertEquals("a1", reply.agentId)
        assertEquals("thinking", messages.acks.single().state)
        assertEquals("a2", messages.deliveries.single().agentId)
        assertEquals("image", messages.media["m1"]!!.single().kind)

        val request = json.decodeFromString(RequestsSnapshot.serializer(), REQUESTS).requests.single()
        assertEquals(RequestKind.Choice, request.requestKind)
        assertEquals("B", request.config?.defaultValue)
        assertNull(request.answer)

        val agents = json.decodeFromString(AgentsSnapshot.serializer(), """{"seq":3,"at":"x","agents":[{"agentId":"a1","name":"Builder","sessionId":"s1","startedAt":1,"lastHeartbeatAt":2,"sessions":2,"host":"box","cwd":"/w","model":null}]}""")
        assertEquals(2, agents.agents.single().sessions)
    }

    @Test
    fun `a picture question puts each picture on its option and out of the card's grid`() {
        val request = json.decodeFromString(RequestView.serializer(), """
            {"id":"r1","seq":3,"agentId":"a1","projectId":"p1","kind":"choice","question":"Which logo?","options":["Blue","Red","Plain"],
             "config":{"optionMedia":{"Blue":"md-blue","Red":"md-red"}},"state":"pending","expiresAt":9,
             "media":[
               {"id":"md-blue","requestId":"r1","kind":"image","mime":"image/png","status":"ready","variants":["original"]},
               {"id":"md-red","requestId":"r1","kind":"image","mime":"image/png","status":"ready","variants":["original"]},
               {"id":"md-context","requestId":"r1","kind":"image","mime":"image/png","status":"ready","variants":["original"]}]}
        """)

        assertEquals("md-blue", request.pictureFor("Blue")?.id)
        assertEquals("md-red", request.pictureFor("Red")?.id)
        assertNull(request.pictureFor("Plain"))
        assertNull(request.pictureFor("Green"))
        assertTrue(request.isOnOption(request.media[0]))
        assertEquals(listOf("md-context"), request.cardMedia.map { it.id })
        assertEquals(mapOf("Blue" to "md-blue", "Red" to "md-red"), request.config?.optionMedia)
    }

    @Test
    fun `a question without pictures keeps all its media on the card`() {
        val request = FakeDashboard.request("r1", 1).copy(media = listOf(MediaView("md1", kind = "image", mime = "image/png", status = "ready")))
        assertNull(request.pictureFor("Yes"))
        assertEquals(listOf("md1"), request.cardMedia.map { it.id })
    }

    @Test
    fun `a kind of question the app doesn't know still reads`() {
        val request = json.decodeFromString(RequestView.serializer(), """{"id":"r","agentId":"a","kind":"sketch","question":"Draw?","state":"pending","expiresAt":1}""")
        assertNull(request.requestKind)
    }

    @Test
    fun `a new post's body is what the server takes`() {
        val body = json.encodeToString(NewMessage.serializer(), NewMessage(body = "hi", update = "u1", answers = "m2"))
        assertEquals("""{"body":"hi","update":"u1","answers":"m2"}""", body)
    }

    @Test
    fun `SSE frames read fields and comments and many data lines and the retry hint`() = runTest {
        var retry = 0L
        val reader = SseReader(
            ByteReadChannel("retry: 2000\n\n: connected\n\nid: 7\nevent: update.created\ndata: {\"a\":1,\ndata: \"b\":2}\n\n: heartbeat\n\nevent: resync\ndata: {}\n\n"),
        ) { retry = it }

        val first = reader.next(1_000)!!
        assertEquals(SseFrame("update.created", "{\"a\":1,\n\"b\":2}", "7"), first)
        assertEquals(2000, retry)
        val second = reader.next(1_000)!!
        assertEquals("resync", second.event)
        assertNull(second.id)
        assertNull(reader.next(1_000))
    }

    @Test
    fun `a body that stops sending anything is a stall`() = runTest {
        val channel = io.ktor.utils.io.ByteChannel()
        val reader = SseReader(channel)
        assertFailsWith<StreamStalled> { reader.next(5_000) }
    }

    @Test
    fun `read marks survive being written and read back and are capped`() {
        val store = MemoryTextStore()
        val marks = ReadMarks(store)
        val comment = FakeDashboard.message("m", 1, "agent:a1", "x", 5_000)
        marks.seed("card", emptyList())
        assertEquals(1, ReadMarks(store).unread("card", listOf(comment)))
        repeat(ReadMarks.Max + 10) { marks.markRead("c$it", listOf(comment.copy(createdAt = it.toLong()))) }
        assertTrue(ReadMarks(store).size <= ReadMarks.Max)
    }

    private companion object {
        const val SNAPSHOT = """
        {"seq":41,"at":"2026-11-18T10:00:00.000Z",
         "projects":[{"id":"p1","seq":3,"slug":"mega","name":"Mega","description":null,"status":"active","pinned":true,
                      "createdAt":1,"updatedAt":2,"theme":{"background":"#112233","accent":"#445566"},"board":null}],
         "tags":[{"id":"t1","projectId":"p1","name":"Bug","slug":"bug","colour":"red","agentId":null,"createdAt":1}],
         "cardTags":[{"id":"ct1","tagId":"t1","card":{"kind":"update","id":"u1"},"author":"human","createdAt":2}],
         "cardFields":[{"id":"f1","card":{"kind":"update","id":"u1"},"name":"Branch","slug":"branch","value":"feat/x","author":"agent:a1","createdAt":1,"updatedAt":2}],
         "cardApps":[],
         "updates":{"items":[{"id":"u1","seq":40,"projectId":"p1","agentId":"a1","sessionId":"s1","title":"Shipped","body":"**done**",
             "level":"warn","priority":"high","pinned":false,"createdAt":100,"deletedAt":null,"editedAt":null,"taskId":null,
             "reactions":[{"id":"r1","seq":39,"card":{"kind":"update","id":"u1"},"messageId":null,"actor":"human","emoji":"👍","createdAt":101}],
             "status":"todo","statusAt":120,
             "media":[{"id":"md1","updateId":"u1","messageId":null,"requestId":null,"taskId":null,"kind":"image","mime":"image/png",
                       "status":"ready","bytes":1234,"width":640,"height":480,"durationMs":null,"filename":null,"variants":["original","thumb-640"]}],
             "embeds":[{"id":"e1","updateId":"u1","messageId":null,"requestId":null,"taskId":null,"type":"github","typeName":"GitHub",
                        "spec":{"theme":{},"chrome":{"mark":"github"},"frame":{},"blocks":[{"type":"divider"}]},"data":{"title":"PR"},"sourceUrl":null,"createdAt":1}],
             "share":{"views":3,"sharedAt":5}}],
           "nextCursor":"c2","hasMore":true},
         "agentNames":{"a1":"Builder"},
         "messages":[],"acks":[],"messageMedia":{},"messageEmbeds":{}}
        """

        const val MESSAGES = """
        {"seq":41,"at":"x",
         "messages":[{"id":"m1","seq":30,"projectId":"p1","updateId":"u1","taskId":null,"replyTo":null,"answers":"m0","requestId":null,
                      "author":"agent:a1","body":"On it","createdAt":200,"pinned":false,"status":null,"statusAt":null,"editedAt":null}],
         "acks":[{"id":"k1","seq":31,"agentId":"a1","card":{"kind":"message","id":"m1"},"messageId":"m1","taskId":null,"state":"thinking","createdAt":1,"updatedAt":2}],
         "media":{"m1":[{"id":"md2","updateId":null,"messageId":"m1","requestId":null,"taskId":null,"kind":"image","mime":"image/jpeg","status":"pending","bytes":1,"width":null,"height":null,"durationMs":null,"filename":null,"variants":[]}]},
         "embeds":{},
         "deliveries":[{"id":"d1","seq":32,"messageId":"m1","agentId":"a2","deliveredAt":3}],
         "reactions":[]}
        """

        const val REQUESTS = """
        {"seq":41,"at":"x","requests":[{"id":"r1","seq":9,"agentId":"a1","projectId":"p1","updateId":null,"messageId":null,"kind":"choice",
          "question":"Which?","detail":null,"options":["A","B"],"config":{"default":"B"},"state":"pending","expiresAt":999,"answeredAt":null,
          "answer":null,"createdAt":5,"media":[],"embeds":[],"reactions":[],"editedAt":null,"pinned":false}]}
        """
    }
}
