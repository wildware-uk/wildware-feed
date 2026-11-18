package dev.wildware.feed.server.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireTest {
    @Test
    fun `JSON is read as strictly as JSON parse reads it`() {
        assertNull(Wire.parseStrict("nope"))
        assertNull(Wire.parseStrict("{\"a\": nope}"))
        assertNull(Wire.parseStrict("{not json"))
        assertNotNull(Wire.parseStrict("[1, -2.5e3, true, null, \"x\"]"))
    }

    @Test
    fun `times are written as toISOString writes them`() {
        assertEquals("2023-11-14T22:13:20.000Z", Wire.isoOf(1_700_000_000_000))
        assertEquals("1970-01-01T00:00:00.001Z", Wire.isoOf(1))
    }

    @Test
    fun `the guard's redirects keep the way back only for owner pages`() {
        assertEquals("/login", Guard.loginRedirect("/"))
        assertEquals("/login?redirectTo=%2Fprojects%2Fdemo%3Fx%3D1", Guard.loginRedirect("/projects/demo?x=1"))
        assertEquals("/", Guard.safeRedirectTarget("//evil.com"))
        assertEquals("/", Guard.safeRedirectTarget("/mcp"))
        assertEquals("/", Guard.safeRedirectTarget("/logout"))
        assertEquals("/projects/a", Guard.safeRedirectTarget("/projects/a"))
        assertTrue(Guard.requiresSession("/mcp-docs"))
        assertFalse(Guard.requiresSession("/mcp/x"))
        assertFalse(Guard.requiresSession("/api/devices/sign-in"))
    }

    @Test
    fun `form actions answer JSON unless HTML is preferred`() {
        assertTrue(wantsActionJson(null))
        assertTrue(wantsActionJson("*/*"))
        assertTrue(wantsActionJson("application/json"))
        assertFalse(wantsActionJson("text/html"))
        assertFalse(wantsActionJson("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"))
    }
}
