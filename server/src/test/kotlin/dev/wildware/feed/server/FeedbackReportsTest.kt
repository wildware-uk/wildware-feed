package dev.wildware.feed.server

import dev.wildware.feed.server.CrashReports.forCrashReports
import io.sentry.Hint
import io.sentry.ITransportFactory
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feedback as Sentry gets it (server/spec/14-feedback.md), through the real SDK with a transport that
 * keeps the envelope instead of sending it: one feedback event with the words, name and tags, the
 * pictures as attachments, and no address, request or machine name.
 */
class FeedbackReportsTest {
    private val envelopes = mutableListOf<SentryEnvelope>()

    private val keep = ITransportFactory { _, _ ->
        object : ITransport {
            override fun send(envelope: SentryEnvelope, hint: Hint) { synchronized(envelopes) { envelopes += envelope } }
            override fun flush(timeoutMillis: Long) = Unit
            override fun getRateLimiter(): RateLimiter? = null
            override fun close(isRestarting: Boolean) = Unit
            override fun close() = Unit
        }
    }

    @AfterTest
    fun stop() {
        Sentry.close()
        FeedbackReports.sender = null
        CrashReports.reporter = null
    }

    private fun start() {
        Sentry.init { options ->
            options.forCrashReports("https://key@sentry.example/1", "test")
            options.setTransportFactory(keep)
        }
        FeedbackReports.useSentry()
    }

    private val item = FeedbackReports.Outgoing(
        kind = "feature_request", area = "voice", title = "Voice notes in threads", body = "Reply by voice.",
        name = "Sam", email = null, source = "person", team = "0123456789abcdef", client = "wildware-feed@1.0.19",
        attachments = listOf(FeedbackReports.File("picture-1.png", "image/png", byteArrayOf(1, 2, 3))),
    )

    private fun sent(): Pair<String, List<String>> {
        Sentry.flush(2_000)
        val envelope = synchronized(envelopes) { envelopes.single() }
        val items = envelope.items.toList()
        val types = items.map { it.header.type.itemType }
        val event = items.first { it.header.type.itemType == "feedback" }.data.toString(Charsets.UTF_8)
        return event to types
    }

    @Test
    fun `no SENTRY_DSN means feedback goes nowhere`() {
        assertFalse(CrashReports.start(emptyMap()))
        assertFalse(FeedbackReports.sends)
        assertNull(FeedbackReports.send(item))
    }

    @Test
    fun `sent feedback is one Sentry feedback event with its words, name, tags and pictures`() {
        start()
        assertTrue(FeedbackReports.sends)
        val id = FeedbackReports.send(item)
        assertTrue(id != null && id.length == 32, "an event id: $id")
        val (event, types) = sent()
        assertEquals(listOf("feedback", "attachment"), types)
        for (part in listOf(
            "\"message\":\"Voice notes in threads\\n\\nReply by voice.\"", "\"name\":\"Sam\"",
            "\"feedback.kind\":\"feature_request\"", "\"feedback.area\":\"voice\"", "\"feedback.source\":\"person\"",
            "\"feedback.team\":\"0123456789abcdef\"", "\"client.release\":\"wildware-feed@1.0.19\"", "\"server.version\"",
        )) {
            assertTrue(part in event, "the event has $part: $event")
        }
        assertFalse("contact_email" in event, "no email unless the person chose to give it")
        assertTrue("\"ip_address\":\"0.0.0.0\"" in event, "an address that finds no town: $event")
        assertFalse("server_name" in event)
    }

    @Test
    fun `a person who ticks it sends their email too`() {
        start()
        FeedbackReports.send(item.copy(email = "sam@example.com", attachments = emptyList()))
        val (event, types) = sent()
        assertEquals(listOf("feedback"), types)
        assertTrue("\"contact_email\":\"sam@example.com\"" in event, event)
    }
}
