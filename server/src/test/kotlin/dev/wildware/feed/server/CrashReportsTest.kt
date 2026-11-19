package dev.wildware.feed.server

import dev.wildware.feed.server.CrashReports.forCrashReports
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.http.endpoint
import dev.wildware.feed.server.http.feed
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.sentry.Breadcrumb
import io.sentry.JsonSerializer
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.User
import java.io.StringWriter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashReportsTest {
    private val config = Config(
        dataDir = "unused", adminPasswordHash = "\$argon2id\$x", sessionSecret = "s".repeat(32), tokenSecret = "t".repeat(32),
        publicBaseUrl = "http://localhost:5173", maxImageBytes = 1, maxVideoBytes = 1, maxFileBytes = 1, holdSeconds = 1,
        apns = null, port = 0, host = "127.0.0.1", origin = "http://localhost:5173",
    )

    @AfterTest
    fun noReporter() {
        CrashReports.reporter = null
    }

    @Test
    fun `no SENTRY_DSN means no reports`() {
        assertFalse(CrashReports.start(emptyMap()))
        assertFalse(CrashReports.start(mapOf("SENTRY_DSN" to "  ")))
        assertNull(CrashReports.reporter)
    }

    @Test
    fun `a report keeps the error's type and stack and loses everything else`() {
        val secret = "the owner's card says launch plans; token=abcdef1234567890abcdef"
        val event = SentryEvent(IllegalStateException(secret)).apply {
            message = Message().apply { formatted = secret }
            user = User().apply { id = "owner"; ipAddress = "203.0.113.9" }
            request = Request().apply {
                url = "https://feed.example/api/cards/C1?token=abcdef1234567890abcdef"
                data = """{"body":"$secret"}"""
                headers = mapOf("Authorization" to "Bearer abcdef1234567890abcdef")
            }
            serverName = "dyno-host"
            setExtra("body", secret)
            breadcrumbs = mutableListOf(Breadcrumb(secret))
        }
        // What the SDK adds before beforeSend: the exception list from the throwable.
        event.exceptions = listOf(io.sentry.protocol.SentryException().apply { type = "IllegalStateException"; value = secret })

        val sent = CrashReports.scrub(event)
        val json = StringWriter().also { JsonSerializer(SentryOptions()).serialize(sent, it) }.toString()

        assertEquals("IllegalStateException", sent.exceptions!!.single().type)
        for (leak in listOf("launch plans", "abcdef1234567890abcdef", "feed.example", "203.0.113.9", "owner", "dyno-host")) {
            assertFalse(leak in json, "the report still carries $leak: $json")
        }
    }

    @Test
    fun `the options send no personal data and no traces or breadcrumbs`() {
        val options = SentryOptions().apply { forCrashReports("https://key@sentry.example/1", "production") }
        assertFalse(options.isSendDefaultPii)
        assertFalse(options.isAttachServerName)
        assertNull(options.tracesSampleRate)
        assertFalse(options.isTracingEnabled)
        assertNull(options.beforeBreadcrumb!!.execute(Breadcrumb("hello"), io.sentry.Hint()))
        assertTrue(options.release!!.startsWith("wildware-feed-server@"))
    }

    @Test
    fun `a request that throws is reported with its route pattern and not its path`() {
        val reported = mutableListOf<Triple<Throwable, String, String?>>()
        CrashReports.reporter = { error, method, route -> reported += Triple(error, method, route) }
        val app = App(config, Database.memory())
        val (_, key) = app.devices.mint("Phone")
        testApplication {
            application {
                feed(app)
                routing { endpoint("/api/boom/{id}", HttpMethod.Get to { error("card text") }) }
            }
            runCatching { client.get("/api/boom/C42") { header("Authorization", "Bearer $key") } }
        }
        app.close()
        val (error, method, route) = reported.single()
        assertEquals("card text", error.message)
        assertEquals("GET", method)
        assertEquals("/api/boom/{id}", route)
    }
}
