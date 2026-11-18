package dev.wildware.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which alerts a device wants, judged by the same rule the dashboard applies to its pushes. */
class AlertPrefsTest {
    @Test
    fun `no choice means every alert`() {
        assertTrue(AlertPrefs.allows(null, AlertPrefs.Request))
        assertTrue(AlertPrefs.allows(null, AlertPrefs.Update, level = "info", priority = "low"))
    }

    @Test
    fun `a list of kinds keeps only those kinds`() {
        val prefs = AlertPrefs(types = listOf(AlertPrefs.Request))
        assertTrue(AlertPrefs.allows(prefs, AlertPrefs.Request))
        assertFalse(AlertPrefs.allows(prefs, AlertPrefs.Message))
        assertFalse(AlertPrefs.allows(AlertPrefs(types = emptyList()), AlertPrefs.Request))
    }

    @Test
    fun `levels and priorities only narrow updates`() {
        val prefs = AlertPrefs(levels = listOf("error"), priorities = listOf("high"))
        assertTrue(AlertPrefs.allows(prefs, AlertPrefs.Update, level = "error", priority = "high"))
        assertFalse(AlertPrefs.allows(prefs, AlertPrefs.Update, level = "info", priority = "high"))
        assertFalse(AlertPrefs.allows(prefs, AlertPrefs.Update, level = "error", priority = "low"))
        assertTrue(AlertPrefs.allows(prefs, AlertPrefs.Request))
    }

    @Test
    fun `turning one choice off starts from everything`() {
        val off = AlertPrefs.toggle(null, AlertPrefs.Axis.Types, AlertPrefs.Comment)
        assertEquals(AlertPrefs(types = listOf("request", "message", "update")), off)
        assertEquals(null, AlertPrefs.toggle(off, AlertPrefs.Axis.Types, AlertPrefs.Comment), "back to everything is no choice at all")
        assertEquals(AlertPrefs(levels = listOf("error", "warn", "success")), AlertPrefs.toggle(null, AlertPrefs.Axis.Levels, "info"))
    }
}
