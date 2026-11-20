package dev.wildware.feed.server.http

import dev.wildware.feed.server.App
import dev.wildware.feed.server.Config
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.domain.Messages
import dev.wildware.feed.server.domain.Updates
import dev.wildware.feed.server.domain.feed
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.stream.Stream as Lines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The agent stream across a session restart: the new bridge (a new `?client=`) does not hear again
 * what the old one was handed, while two sessions on one token at once both hear every new line.
 * The server's clock is ours; nothing sleeps.
 */
class StreamBacklogTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://127.0.0.1", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://127.0.0.1",
    )

    @Volatile private var now = 1_763_474_000_000L

    /** One open agent stream: the bodies of its `message` frames as they arrive, and when its opening `work` frame came. */
    private class Stream(private val lines: Lines<String>) {
        val bodies = LinkedBlockingQueue<String>()
        private val opened = CountDownLatch(1)

        init {
            Thread {
                var event: String? = null
                runCatching {
                    for (line in lines.iterator()) {
                        when {
                            line.startsWith("event: ") -> event = line.removePrefix("event: ")
                            line.startsWith("data: ") && event == "work" -> opened.countDown()
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

        /** Waits for the opening `work` frame: the backlog, if any, is written right after it. */
        fun awaitOpen() = assertTrue(opened.await(5, TimeUnit.SECONDS), "the stream opened")

        fun next(ms: Long = 5_000): String? = bodies.poll(ms, TimeUnit.MILLISECONDS)

        fun close() = lines.close()
    }

    @Test
    fun `a restart hears no old line again, a quick reconnect does, and two sessions both hear a new one`() {
        val app = App(config, Database.memory(), clock = { now })
        val server = FeedServer(app).start()
        try {
            val (agent, token) = app.agents.mint("alpha")
            app.feed.projects.create("P", null, null)
            app.feed.updates.post(Updates.Post(project = "p", agentId = agent.id, body = "alpha's card"))
            fun say(body: String) = app.feed.messages.post(Messages.Post(author = "human", body = body, project = "p"))
            val client = HttpClient.newHttpClient()
            fun open(clientId: String) = Stream(
                client.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.port}/api/agent/stream?client=$clientId"))
                        .header("Authorization", "Bearer $token").build(),
                    HttpResponse.BodyHandlers.ofLines(),
                ).get(5, TimeUnit.SECONDS).also { assertEquals(200, it.statusCode()) }.body(),
            ).also { it.awaitOpen() }

            // Two sessions on one token at once, as when a second terminal starts with the same agent.
            val first = open("bridge-1")
            val second = open("bridge-2")
            say("hello both")
            assertEquals("hello both", first.next())
            assertEquals("hello both", second.next(), "a live line reaches every session, not only the first")

            // A day later both sessions restart, each with a new bridge and client id. The line is
            // still unread (no get_messages), but it is not heard again.
            first.close()
            second.close()
            now += 25 * 60 * 60_000L
            say("while nobody listened")
            val restarted = open("bridge-3")
            assertEquals("while nobody listened", restarted.next(), "a line no session was handed is caught up on")
            assertNull(restarted.next(1_000), "nothing handed over before comes again")
            assertEquals(2, app.work.countWork(agent.id).unreadMessages, "both lines are still unread")

            // It drops, and reconnects 30 s later: what it had then may never have reached the agent.
            restarted.close()
            now += 30_000
            val back = open("bridge-4")
            assertEquals("while nobody listened", back.next(), "handed over a moment ago, so handed over again")
            assertNull(back.next(1_000))

            // Three minutes on, yet another restart hears none of it.
            back.close()
            now += 3 * 60_000L
            val later = open("bridge-5")
            say("fresh")
            assertEquals("fresh", later.next(), "the first frame it gets is the new line")
            later.close()
        } finally {
            server.stop()
        }
    }
}
