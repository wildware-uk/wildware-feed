package dev.wildware.agents.app.crash

import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.JsonSerializer
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Device
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashScrubTest {
    private val token = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"

    @Test
    fun `addresses keys and emails come out of text`() {
        val text = CrashScrub.text(
            "GET https://feed.example/media/M1?token=$token failed for owner@example.com from 203.0.113.9 with Bearer $token and key=$token",
        )
        assertEquals("GET [url] failed for [email] from [ip] with Bearer [redacted] and key=[redacted]", text)
    }

    @Test
    fun `a long key-like run with a digit in it is redacted but a long word is not`() {
        assertEquals("id [redacted]", CrashScrub.text("id $token"))
        assertEquals("in MainViewControllerKtNativeWrapper", CrashScrub.text("in MainViewControllerKtNativeWrapper"))
    }

    @Test
    fun `text is cut short`() {
        val cut = CrashScrub.text("x ".repeat(400))
        assertEquals(CrashScrub.MaxText + 1, cut.length)
    }

    @Test
    fun `a Kotlin error's message is dropped and a system crash's kept`() {
        assertNull(CrashScrub.exceptionMessage("IllegalStateException", "card says launch plans"))
        assertNull(CrashScrub.exceptionMessage("kotlinx.serialization.json.internal.JsonDecodingException", "JSON input: {\"body\":\"secret\"}"))
        assertEquals("KERN_INVALID_ADDRESS at 0x0000000000000010", CrashScrub.exceptionMessage("EXC_BAD_ACCESS", "KERN_INVALID_ADDRESS at 0x0000000000000010"))
    }

    @Test
    fun `network taps and logs breadcrumbs are dropped whole`() {
        assertFalse(CrashScrub.keepBreadcrumb("http", "http"))
        assertFalse(CrashScrub.keepBreadcrumb("default", "http.client"))
        assertFalse(CrashScrub.keepBreadcrumb("user", "ui.click"))
        assertFalse(CrashScrub.keepBreadcrumb("debug", "console"))
        assertTrue(CrashScrub.keepBreadcrumb("navigation", "app.lifecycle"))
        assertTrue(CrashScrub.keepBreadcrumb(null, null))
    }

    @Test
    fun `a report keeps the error's type and stack and loses the owner's data`() {
        val secret = "launch plans for the garden game"
        val event = SentryEvent(IllegalStateException(secret)).apply {
            message = Message().apply { formatted = "loading https://feed.example/api/cards/C1?token=$token" }
            exceptions = listOf(SentryException().apply { type = "IllegalStateException"; value = secret })
            user = User().apply { id = "install-id"; ipAddress = "203.0.113.9"; email = "owner@example.com" }
            request = Request().apply {
                url = "https://feed.example/api/cards/C1"
                data = """{"body":"$secret"}"""
                headers = mapOf("Authorization" to "Bearer $token")
            }
            serverName = "owners-laptop"
            setExtra("card", secret)
            contexts.setDevice(Device().apply { id = "device-id"; name = "Owner's phone"; model = "Pixel 9" })
            breadcrumbs = mutableListOf(
                Breadcrumb.http("https://feed.example/api/cards?token=$token", "GET"),
                Breadcrumb.navigation("feed", "card $secret").apply { message = "opened https://feed.example/c/C1" },
            )
        }

        val sent = scrub(event)
        val json = StringWriter().also { JsonSerializer(SentryOptions()).serialize(sent, it) }.toString()

        assertEquals("IllegalStateException", sent.exceptions!!.single().type)
        assertEquals("loading [url]", sent.message!!.formatted)
        assertEquals("Pixel 9", sent.contexts.device!!.model)
        // A placeholder, so Sentry looks up no town from the connection; the project drops it.
        assertEquals(CrashScrub.NoAddress, sent.user!!.ipAddress)
        assertNull(sent.user!!.id)
        val crumb = sent.breadcrumbs!!.single()
        assertEquals("navigation", crumb.category)
        assertEquals("opened [url]", crumb.message)
        for (leak in listOf(secret, token, "feed.example", "203.0.113.9", "owner@example.com", "install-id", "device-id", "Owner's phone", "owners-laptop")) {
            assertFalse(leak in json, "the report still carries $leak: $json")
        }
    }

    @Test
    fun `the options report crashes only and nothing while the switch is off`() {
        var allowed = true
        val options = SentryOptions().apply {
            forCrashReports(CrashReportConfig("https://key@sentry.example/1", "wildware-feed@1.0.0", "production", null)) { allowed }
        }
        assertFalse(options.isSendDefaultPii)
        assertFalse(options.isAttachServerName)
        assertNull(options.tracesSampleRate)
        assertFalse(options.isTracingEnabled)
        assertFalse(options.isEnableAutoSessionTracking)
        assertEquals("wildware-feed@1.0.0", options.release)
        assertNotNull(options.beforeSend!!.execute(SentryEvent(), Hint()))
        allowed = false
        assertNull(options.beforeSend!!.execute(SentryEvent(), Hint()))
    }
}
