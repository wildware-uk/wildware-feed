package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Agents
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.ThreadReads
import dev.wildware.feed.server.domain.Updates
import dev.wildware.feed.server.domain.feed
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The agent stream with no `?project=`: what the agent follows on the server, live. */
class SubscriptionStreamTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    /** The bodies of the `message` frames on a stream, as they arrive. */
    private class Stream(lines: Iterator<String>) {
        val bodies = LinkedBlockingQueue<String>()

        init {
            Thread {
                var event: String? = null
                runCatching {
                    while (lines.hasNext()) {
                        val line = lines.next()
                        when {
                            line.startsWith("event: ") -> event = line.removePrefix("event: ")
                            line.startsWith("data: ") && event == "message" -> {
                                val frame = Wire.json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                                frame["messages"]!!.jsonArray.forEach { bodies += it.jsonObject["body"]!!.jsonPrimitive.content }
                            }
                            line.isEmpty() -> event = null
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        fun next(ms: Long = 5_000): String? = bodies.poll(ms, TimeUnit.MILLISECONDS)
    }

    private fun tool(app: App, agent: Agents.Agent, name: String, args: JsonObject): JsonObject = runBlocking {
        app.tools.call(app, agent, buildJsonObject { put("name", JsonPrimitive(name)); put("arguments", args) }).json
    }

    private fun structured(result: JsonObject) = result["structuredContent"]!!.jsonObject

    @Test
    fun `with no project list the stream hears followed threads anywhere, and a new subscription is live`() {
        val app = App(config, Database.memory())
        val server = FeedServer(app).start()
        try {
            val (alpha, _) = app.agents.mint("alpha")
            val (beta, betaToken) = app.agents.mint("beta")
            val feed = app.feed
            feed.projects.create("P", null, null)
            feed.projects.create("Q", null, null)
            feed.updates.post(Updates.Post(project = "q", agentId = beta.id, body = "beta works in q"))
            val inP = feed.updates.post(Updates.Post(project = "p", agentId = alpha.id, body = "alpha works in p"))
            val other = feed.updates.post(Updates.Post(project = "p", agentId = alpha.id, body = "another card in p"))

            val client = HttpClient.newHttpClient()
            fun open(query: String) = client.sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}/api/agent/stream$query"))
                    .header("Authorization", "Bearer $betaToken").build(),
                HttpResponse.BodyHandlers.ofLines(),
            ).get(5, TimeUnit.SECONDS).also { assertEquals(200, it.statusCode()) }.body().iterator()
            val stream = Stream(open(""))
            val old = Stream(open("?project=q"))
            Thread.sleep(300)

            fun say(author: String, body: String, updateId: String) =
                feed.messages.post(Messages.Post(author = author, body = body, updateId = updateId))

            say("human", "not for beta", inP.id)
            val mention = say("agent:${alpha.id}", "@beta can you check this?", inP.id)
            // New to the thread, so it also says how to read what came before (spec/04 §4.9).
            val joined = "@beta can you check this?\n\n" + ThreadReads.threadHint(mention.id)
            assertEquals(joined, stream.next())
            assertEquals(joined, old.next(), "a mention reaches the agent anywhere")
            say("human", "Thanks, beta, go ahead", inP.id)
            assertEquals("Thanks, beta, go ahead", stream.next(), "a reply with no mention reaches the follower")
            assertNull(old.next(500), "a bridge that named only q hears no followed thread in p")

            say("human", "before beta follows", other.id)
            assertNull(stream.next(500))
            val subscribed = structured(tool(app, beta, "subscribe", buildJsonObject { put("update_id", JsonPrimitive(other.id)) }))
            assertEquals("update", subscribed["subscribed"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
            assertEquals("p", subscribed["subscribed"]!!.jsonObject["project"]!!.jsonPrimitive.content)
            say("human", "after beta follows", other.id)
            assertEquals("after beta follows", stream.next(), "the subscription took effect without reconnecting")

            tool(app, beta, "unsubscribe", buildJsonObject { put("update_id", JsonPrimitive(inP.id)) })
            say("human", "beta has left", inP.id)
            say("human", "still following this one", other.id)
            assertEquals("still following this one", stream.next())

            val list = structured(tool(app, beta, "list_subscriptions", JsonObject(emptyMap())))
            assertEquals(false, list["all_projects"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(listOf("q"), list["projects"]!!.jsonArray.map { it.jsonObject["slug"]!!.jsonPrimitive.content })
            assertTrue(list["threads"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == other.id })
            assertTrue(list["threads"]!!.jsonArray.none { it.jsonObject["id"]!!.jsonPrimitive.content == inP.id })

            val twice = tool(app, beta, "subscribe", buildJsonObject { put("project", JsonPrimitive("q")) })
            assertEquals(true, structured(twice)["already"]!!.jsonPrimitive.content.toBoolean())
            val both = tool(app, beta, "subscribe", buildJsonObject { put("project", JsonPrimitive("q")); put("update_id", JsonPrimitive(other.id)) })
            assertEquals("invalid_argument", structured(both)["error"]!!.jsonPrimitive.content)
        } finally {
            server.stop()
        }
    }

    /** The names of the channels in the `message` frames, as they arrive, with the bodies. */
    private fun open(server: FeedServer, token: String, query: String): Stream = Stream(
        HttpClient.newHttpClient().sendAsync(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}/api/agent/stream$query"))
                .header("Authorization", "Bearer $token").build(),
            HttpResponse.BodyHandlers.ofLines(),
        ).get(5, TimeUnit.SECONDS).also { assertEquals(200, it.statusCode()) }.body().iterator(),
    ).also { Thread.sleep(300) }

    @Test
    fun `a bridge that names a channel never hears another, even one the agent follows`() {
        val app = App(config, Database.memory())
        val server = FeedServer(app).start()
        try {
            val (agent, token) = app.agents.mint("worker")
            val feed = app.feed
            feed.projects.create("Home", null, null)
            feed.projects.create("Work", null, null)
            tool(app, agent, "subscribe", buildJsonObject { put("channel", JsonPrimitive("home")) })
            tool(app, agent, "subscribe", buildJsonObject { put("channel", JsonPrimitive("work")) })
            val bound = open(server, token, "?project=home")
            val newer = open(server, token, "?channel=home")
            val free = open(server, token, "")

            feed.messages.post(Messages.Post(author = "human", body = "work secret", project = "work"))
            feed.messages.post(Messages.Post(author = "human", body = "@worker work secret for you", project = "work"))
            feed.messages.post(Messages.Post(author = "human", body = "home news", project = "home"))
            assertEquals("@worker work secret for you", bound.next(), "a mention reaches the agent anywhere, as always")
            assertEquals("home news", bound.next(), "the named channel still comes through")
            assertEquals("@worker work secret for you", newer.next())
            assertEquals("home news", newer.next())
            assertEquals(listOf("work secret", "@worker work secret for you", "home news"), listOf(free.next(), free.next(), free.next()))
            assertNull(bound.next(500), "nothing else from work reaches a bridge set up for home")
            assertNull(newer.next(100))

            // An unsubscribe still applies at once, inside the bound too.
            tool(app, agent, "unsubscribe", buildJsonObject { put("channel", JsonPrimitive("home")) })
            feed.messages.post(Messages.Post(author = "human", body = "home after leaving", project = "home"))
            assertNull(bound.next(500))

            // A name that matches no channel narrows to nothing, never to everything.
            val unknown = open(server, token, "?project=no-such-channel")
            assertEquals("@worker work secret for you", unknown.next(), "only the unread mention, from the backlog")
            feed.messages.post(Messages.Post(author = "human", body = "more work", project = "work"))
            assertEquals("more work", free.next())
            assertNull(unknown.next(500))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `on connect the backlog keeps to the same reach as live messages`() {
        val app = App(config, Database.memory())
        val server = FeedServer(app).start()
        try {
            val (agent, token) = app.agents.mint("late")
            val feed = app.feed
            feed.projects.create("Home", null, null)
            feed.projects.create("Work", null, null)
            // Days of history it never followed, before and after it starts.
            feed.messages.post(Messages.Post(author = "human", body = "old work", project = "work"))
            val thread = feed.messages.post(Messages.Post(author = "human", body = "old home thread", project = "home"))
            feed.messages.post(Messages.Post(author = "human", body = "old reply", replyTo = thread.id))
            // It replies in the home thread: that follows the thread, not the channel.
            feed.messages.post(Messages.Post(author = "agent:${agent.id}", body = "my reply", replyTo = thread.id))
            feed.messages.post(Messages.Post(author = "human", body = "home channel, not the thread", project = "home"))
            feed.messages.post(Messages.Post(author = "human", body = "reply after mine", replyTo = thread.id))
            feed.messages.post(Messages.Post(author = "human", body = "@late look here", project = "work"))
            tool(app, agent, "subscribe", buildJsonObject { put("channel", JsonPrimitive("work")) })
            feed.messages.post(Messages.Post(author = "human", body = "work news", project = "work"))
            assertEquals(3, app.work.countWork(agent.id).unreadMessages)
            assertEquals(2, app.work.countWork(agent.id, listOf(feed.projects.resolve("home").id)).unreadMessages)

            val first = open(server, token, "")
            assertEquals(listOf("reply after mine", "@late look here", "work news"), listOf(first.next(), first.next(), first.next()))
            assertNull(first.next(500), "no old history, and nothing from channels it does not follow")
            val bound = open(server, token, "?project=home&client=other")
            assertEquals(listOf("reply after mine", "@late look here"), listOf(bound.next(), bound.next()))
            assertNull(bound.next(500), "the bound applies to the backlog too: no work news")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `the owner sees who follows a thread`() {
        val app = App(config, Database.memory())
        val (_, key) = app.devices.mint("Phone")
        val server = FeedServer(app).start()
        try {
            val (alpha, _) = app.agents.mint("alpha")
            app.agents.mint("beta")
            app.feed.projects.create("P", null, null)
            val update = app.feed.updates.post(Updates.Post(project = "p", agentId = alpha.id, body = "u"))
            val reply = app.feed.messages.post(Messages.Post(author = "human", body = "@beta look", updateId = update.id))
            val client = HttpClient.newHttpClient()
            val res = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}/api/cards/message/${reply.id}/followers"))
                    .header("Authorization", "Bearer $key").build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, res.statusCode())
            val body = Wire.json.parseToJsonElement(res.body()).jsonObject
            assertEquals(update.id, body["thread"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals(listOf("alpha", "beta"), body["followers"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
            assertEquals("""{"following":true,"chosen":true}""", Wire.encode(body["owner"]!!), "the owner replied, so follows it")

            fun send(method: String, path: String, json: String): JsonObject {
                val r = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}$path")).header("Authorization", "Bearer $key")
                        .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                return Wire.json.parseToJsonElement(r.body()).jsonObject.also { assertEquals(200, r.statusCode(), r.body()) }
            }
            val off = send("PUT", "/api/cards/update/${update.id}/follow", """{"following":false}""")
            assertEquals("""{"following":false,"chosen":true}""", Wire.encode(off["owner"]!!))
            assertEquals("""{"projectId":"${update.projectId}","level":"muted","inherited":false}""",
                Wire.encode(send("PUT", "/api/projects/p/subscription", """{"level":"muted"}""")))
            val mine = JsonObject(send("GET", "/api/subscriptions", "").filterKeys { it != "seq" && it != "at" })
            assertEquals("""{"projects":[{"projectId":"${update.projectId}","level":"muted"}],"groups":[],"threads":[{"kind":"update","id":"${update.id}","following":false}]}""",
                Wire.encode(mine))
        } finally {
            server.stop()
        }
    }
}
