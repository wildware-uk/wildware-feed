package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.CardRef
import dev.wildware.feed.server.domain.Feed
import dev.wildware.feed.server.domain.Updates
import dev.wildware.feed.server.domain.feed
import dev.wildware.feed.server.events.Bus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Blocks (migration 057, docs/BLOCKS.md) through the tools, the owner's routes and the agent stream. */
class BlocksTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    private class Ctx(val app: App, val base: String, val key: String, val alpha: Agents.Agent, val alphaToken: String, val beta: Agents.Agent)

    private fun withServer(block: Ctx.() -> Unit) {
        val app = App(config, Database.memory())
        app.feed.projects.create("Web", null, null)
        val (_, key) = app.devices.mint("Phone")
        val (alpha, alphaToken) = app.agents.mint("alpha")
        val (beta, _) = app.agents.mint("beta")
        val server = FeedServer(app).start()
        try {
            Ctx(app, "http://127.0.0.1:${server.port}", key, alpha, alphaToken, beta).block()
        } finally {
            server.stop()
            app.close()
        }
    }

    private fun j(text: String): JsonElement = Wire.json.parseToJsonElement(text)

    private fun Ctx.tool(name: String, args: String, by: Agents.Agent = alpha): JsonObject = runBlocking {
        app.tools.call(app, by, buildJsonObject { put("name", name); put("arguments", j(args)) }).json
    }

    private fun Ctx.ok(name: String, args: String, by: Agents.Agent = alpha): JsonObject {
        val result = tool(name, args, by)
        assertTrue(result["isError"] == null, "$name failed: $result")
        return result["structuredContent"]!!.jsonObject
    }

    private fun Ctx.refused(name: String, args: String, code: String, message: String, by: Agents.Agent = alpha) {
        val data = tool(name, args, by)["structuredContent"]!!.jsonObject
        assertEquals(code, data.s("error"), "$name: $data")
        assertEquals(message, data.s("message"))
    }

    private val http = HttpClient.newHttpClient()

    private fun Ctx.owner(method: String, path: String, body: String? = null): Pair<Int, JsonObject> {
        val request = HttpRequest.newBuilder(URI.create("$base$path")).header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to Wire.json.parseToJsonElement(response.body()).jsonObject
    }

    private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.o(key: String) = this[key]!!.jsonObject
    private fun JsonObject.a(key: String) = this[key]!!.jsonArray
    private fun JsonArray.o(i: Int) = this[i].jsonObject

    private val checklist = """{"id":"ship","type":"checklist","title":"Release","items":[{"id":"1","text":"Tests","owner":"you"},{"id":"2","text":"Notes"}]}"""
    private val poll = """{"id":"pick","type":"poll","question":"Which name?","options":[{"id":"a","label":"Atlas"},{"id":"b","label":"Beacon"}]}"""

    @Test
    fun `blocks ride on an update, a reply and a question, and the owner reads them`() = withServer {
        val posted = ok("post_update", """{"channel":"web","body":"Release","blocks":[$checklist,$poll]}""")
        val updateId = posted.o("update").s("id")!!
        assertEquals(listOf("ship", "pick"), posted.a("blocks").map { it.jsonObject.s("id") })
        assertEquals("1", posted.a("blocks").o(0).s("rev"))

        val reply = ok("post_message", """{"update_id":"$updateId","body":"Logs","blocks":[{"id":"log","type":"log","lines":["a","b"]}]}""")
        val replyId = reply.o("message").s("id")!!
        assertEquals("log", reply.a("blocks").o(0).s("type"))

        val (status, snapshot) = owner("GET", "/api/snapshot")
        assertEquals(200, status)
        val update = snapshot.o("updates").a("items").first { it.jsonObject.s("id") == updateId }.jsonObject
        assertEquals(listOf("checklist", "poll"), update.a("blocks").map { it.jsonObject.s("type") })
        val options = update.a("blocks").o(1).a("options")
        assertEquals("0", options.o(0).s("votes"))
        assertEquals("false", update.a("blocks").o(1).s("closed"))
        assertEquals("log", snapshot.o("messageBlocks").a(replyId).o(0).s("id"))
        val (_, page) = owner("GET", "/api/messages?update=$updateId")
        assertEquals("log", page.o("blocks").a(replyId).o(0).s("id"))

        // A question carries them too, read from the request banner.
        val app = app
        val created = app.requests.create(alpha.id, dev.wildware.feed.server.domain.NewRequest(
            kind = "confirm", question = "Ship it?", project = "web",
            blocks = listOf(j("""{"id":"diff","type":"diff","files":[{"path":"a.kt","hunks":["@@ -1 +1 @@\n-a\n+b"]}]}""")),
        )).first
        val (_, requests) = owner("GET", "/api/snapshot/requests")
        val asked = requests.a("requests").first { it.jsonObject.s("id") == created.id }.jsonObject
        assertEquals("a.kt", asked.a("blocks").o(0).a("files").o(0).s("path"))
        val (_, read) = owner("GET", "/api/cards/request/${created.id}/blocks")
        assertEquals("diff", read.a("blocks").o(0).s("id"))
    }

    @Test
    fun `a bad block refuses the whole post`() = withServer {
        refused(
            "post_update", """{"channel":"web","body":"x","blocks":[{"id":"a","type":"checklist","items":[{"id":"1"}]}]}""",
            "invalid_argument", "blocks[0].items[0].text is required",
        )
        assertEquals(0, app.db.one("SELECT count(*) FROM updates") { it.getLong(1) })
        refused(
            "post_message", """{"channel":"web","body":"x","blocks":[{"id":"a","type":"radar"}]}""",
            "invalid_argument", "blocks[0].type must be one of: checklist, steps, diff, chart, table, log, poll",
        )
        assertEquals(0, app.db.one("SELECT count(*) FROM messages") { it.getLong(1) })
    }

    @Test
    fun `update_block changes a block in place, for its own agent only`() = withServer {
        val updateId = ok("post_update", """{"channel":"web","body":"CI","blocks":[{"id":"ci","type":"steps","stages":[{"id":"build","label":"Build","state":"running"},{"id":"test","label":"Test"}]}]}""")
            .o("update").s("id")!!
        val changed = ok("update_block", """{"update_id":"$updateId","block_id":"ci","patch":{"stages":[{"id":"build","state":"done"},{"id":"test","state":"failed","detail":"2 failed"}],"progress":0.5}}""")
        assertEquals(setOf("id", "type", "rev", "updated_at"), changed.o("block").keys, "kept small: the whole block is get_blocks'")
        assertEquals("2", changed.o("block").s("rev"))
        val block = ok("get_blocks", """{"update_id":"$updateId","block_id":"ci"}""").a("blocks").o(0)
        assertEquals(listOf("done", "failed"), block.a("stages").map { it.jsonObject.s("state") })
        assertEquals(1L, app.db.one("SELECT count(*) FROM updates") { it.getLong(1) }, "nothing new is posted")

        refused("update_block", """{"update_id":"$updateId","block_id":"ci","patch":{"progress":1}}""", "invalid_argument",
            "only the agent that posted that update can change its blocks", by = beta)
        refused("update_block", """{"update_id":"$updateId","block_id":"nope","patch":{"title":"x"}}""", "not_found", "no block \"nope\" on that update")
        refused("update_block", """{"update_id":"$updateId","block_id":"ci"}""", "invalid_argument", "give one of patch, block or remove")
        refused("update_block", """{"update_id":"$updateId","block_id":"ci","patch":{"append":"x"}}""", "invalid_argument",
            "patch.append is not something a patch of a steps block has: it takes title, stages, remove, progress, eta")

        // A whole block adds one, or replaces one; remove takes it off.
        ok("update_block", """{"update_id":"$updateId","block_id":"log","block":{"id":"log","type":"log","lines":["one"]}}""")
        ok("update_block", """{"update_id":"$updateId","block_id":"log","patch":{"append":["two"]}}""")
        val blocks = ok("get_blocks", """{"update_id":"$updateId"}""").a("blocks")
        assertEquals(listOf("ci", "log"), blocks.map { it.jsonObject.s("id") })
        assertEquals(listOf("one", "two"), blocks.o(1).a("lines").map { it.jsonPrimitive.content })
        refused("update_block", """{"update_id":"$updateId","block_id":"log","block":{"id":"other","type":"log"}}""", "invalid_argument",
            "block.id must be \"log\", the block_id you named")
        assertEquals("true", ok("update_block", """{"update_id":"$updateId","block_id":"log","remove":true}""").s("removed"))
        assertEquals(1, ok("get_blocks", """{"update_id":"$updateId"}""").a("blocks").size)
        refused("get_blocks", """{"update_id":"$updateId","block_id":"log"}""", "not_found", "no block \"log\" on that update")
    }

    @Test
    fun `every change publishes block updated with the new rev`() = withServer {
        val seen = LinkedBlockingQueue<JsonObject>()
        app.bus.subscribe { if (it.type == "block.updated") seen += it.payload }
        val updateId = ok("post_update", """{"channel":"web","body":"x","blocks":[$checklist]}""").o("update").s("id")!!
        ok("update_block", """{"update_id":"$updateId","block_id":"ship","patch":{"items":[{"id":"2","done":true}]}}""")
        val event = seen.poll(2, TimeUnit.SECONDS)!!
        assertEquals("""{"kind":"update","id":"$updateId"}""", event["card"].toString())
        assertEquals("ship", event.s("blockId"))
        assertEquals("2", event.s("rev"))
        assertEquals("agent:${alpha.id}", event.s("actor"))
        assertEquals("patch", event.s("action"))
    }

    @Test
    fun `the owner ticks and votes, and the agent that posted the card hears it`() = withServer {
        val updateId = ok("post_update", """{"channel":"web","body":"Release","blocks":[$checklist,$poll]}""").o("update").s("id")!!
        val frames = LinkedBlockingQueue<JsonObject>()
        val betaFrames = LinkedBlockingQueue<JsonObject>()
        fun listen(token: String, into: LinkedBlockingQueue<JsonObject>) {
            val lines = http.sendAsync(
                HttpRequest.newBuilder(URI.create("$base/api/agent/stream")).header("Authorization", "Bearer $token").build(),
                HttpResponse.BodyHandlers.ofLines(),
            ).get(5, TimeUnit.SECONDS).body().iterator()
            thread(isDaemon = true) {
                var event: String? = null
                runCatching {
                    while (lines.hasNext()) {
                        val line = lines.next()
                        when {
                            line.startsWith("event: ") -> event = line.removePrefix("event: ")
                            line.startsWith("data: ") && event == "block" -> into += Wire.json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                            line.isEmpty() -> event = null
                        }
                    }
                }
            }
        }
        listen(alphaToken, frames)
        listen(app.agents.mint("gamma").second, betaFrames)
        Thread.sleep(300)

        val (status, ticked) = owner("POST", "/api/cards/update/$updateId/blocks/ship/check", """{"item":"1","done":true}""")
        assertEquals(200, status, ticked.toString())
        val line = ticked.o("block").a("items").o(0)
        assertEquals("true", line.s("done"))
        assertEquals("person", line.o("done_by").s("kind"))
        assertEquals("founder", line.o("done_by").s("id"))
        val frame = frames.poll(5, TimeUnit.SECONDS)!!
        assertEquals("check", frame.s("action"))
        assertEquals(updateId, frame.s("update_id"))
        assertEquals("ship", frame.s("block_id"))
        assertEquals("Release", frame.s("block_title"))
        assertEquals("Tests", frame.s("item_text"))
        assertEquals("true", frame.s("done"))
        assertEquals("web", frame.s("project"))

        // Ticking a ticked line again changes nothing and tells nobody.
        val (_, again) = owner("POST", "/api/cards/update/$updateId/blocks/ship/check", """{"item":"1","done":true}""")
        assertEquals(ticked.o("block").s("rev"), again.o("block").s("rev"), "nothing written")
        val (_, voted) = owner("POST", "/api/cards/update/$updateId/blocks/pick/vote", """{"options":["b"]}""")
        val options = voted.o("block").a("options")
        assertEquals("1", options.o(1).s("votes"))
        assertEquals("founder", options.o(1).a("voters").o(0).s("id"))
        val vote = frames.poll(5, TimeUnit.SECONDS)!!
        assertEquals("vote", vote.s("action"), "the repeated tick sent nothing")
        assertEquals("Beacon", vote.a("options").o(0).s("label"))
        val (_, same) = owner("POST", "/api/cards/update/$updateId/blocks/pick/vote", """{"options":["b"]}""")
        assertEquals(voted.o("block").s("rev"), same.o("block").s("rev"), "the same vote again writes nothing")
        assertNull(betaFrames.poll(500, TimeUnit.MILLISECONDS), "another agent hears nothing of it")

        // The agent's own change is not echoed back to it.
        ok("update_block", """{"update_id":"$updateId","block_id":"ship","patch":{"items":[{"id":"2","done":true}]}}""")
        assertNull(frames.poll(500, TimeUnit.MILLISECONDS))

        val (refusedStatus, refusal) = owner("POST", "/api/cards/update/$updateId/blocks/pick/vote", """{"options":["a","b"]}""")
        assertEquals(400, refusedStatus)
        assertEquals("that poll takes one choice", refusal.s("message"))
        val (missing, _) = owner("POST", "/api/cards/update/$updateId/blocks/nope/check", """{"item":"1","done":true}""")
        assertEquals(404, missing)
        val (wrongType, wrong) = owner("POST", "/api/cards/update/$updateId/blocks/pick/check", """{"item":"a","done":true}""")
        assertEquals(400, wrongType)
        assertEquals("only a checklist has lines to tick", wrong.s("message"))

        // The agent reads who ticked and who voted.
        val read = ok("get_blocks", """{"update_id":"$updateId","block_id":"pick"}""").a("blocks").o(0)
        assertEquals("1", read.s("total_voters"))
    }

    @Test
    fun `patches at once from many threads lose nothing`() = withServer {
        val updateId = ok("post_update", """{"channel":"web","body":"x","blocks":[{"id":"log","type":"log"},$checklist]}""").o("update").s("id")!!
        val start = CountDownLatch(1)
        val workers = (1..8).map { w ->
            thread {
                start.await()
                repeat(20) { i -> ok("update_block", """{"update_id":"$updateId","block_id":"log","patch":{"append":"w$w-$i"}}""") }
            }
        }
        val ticker = thread {
            start.await()
            repeat(20) { i -> owner("POST", "/api/cards/update/$updateId/blocks/ship/check", """{"item":"${1 + i % 2}","done":${i % 3 != 0}}""") }
        }
        start.countDown()
        (workers + ticker).forEach { it.join() }
        val log = ok("get_blocks", """{"update_id":"$updateId","block_id":"log"}""").a("blocks").o(0)
        assertEquals(160, log.a("lines").size)
        assertEquals((1..8).flatMap { w -> (0 until 20).map { "w$w-$it" } }.toSet(), log.a("lines").map { it.jsonPrimitive.content }.toSet())
        assertEquals("161", log.s("rev"))
    }

    @Test
    fun `two servers on one database never lose each other's patch`() {
        // Two connections, as two servers on one database would have: each write checks the rev it read.
        val dir = Files.createTempDirectory("blocks").toFile()
        try {
            val one = Database.open(dir.path)
            val two = Database.open(dir.path, migrate = false)
            val feeds = listOf(Feed(one, Bus(), System::currentTimeMillis), Feed(two, Bus(), System::currentTimeMillis))
            one.update("INSERT INTO projects (id, slug, name, created_at, updated_at) VALUES ('P1', 'web', 'Web', 1, 1)")
            one.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'alpha', 'h', 1)")
            val update = feeds[0].updates.post(Updates.Post(project = "web", agentId = "A1", body = "x", blocks = listOf(j("""{"id":"log","type":"log"}"""))))
            val card = CardRef("update", update.id)
            val start = CountDownLatch(1)
            val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val workers = feeds.flatMapIndexed { f, feed ->
                (1..3).map { w ->
                    thread {
                        start.await()
                        try {
                            repeat(15) { i -> feed.blocks.change(card, "log", "A1", j("""{"append":"s$f-w$w-$i"}"""), null, false) }
                        } catch (e: Throwable) {
                            failures += e
                        }
                    }
                }
            }
            start.countDown()
            workers.forEach { it.join() }
            assertEquals(emptyList(), failures.map { it.toString() })
            val lines = feeds[1].blocks.viewsOn(card)[0].a("lines")
            assertEquals(90, lines.size)
            assertEquals(90, lines.map { it.jsonPrimitive.content }.toSet().size)
            one.close(); two.close()
        } finally {
            dir.deleteRecursively()
        }
    }
}
