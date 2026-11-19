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

    @Test
    fun `critical alerts are off until turned on`() {
        assertFalse(AlertPrefs.pages(null, "critical", "a1", "fees"))
        assertFalse(AlertPrefs.pages(AlertPrefs(critical = false), "critical", "a1", "fees"))
        assertTrue(AlertPrefs.pages(AlertPrefs(critical = true), "critical", "a1", "fees"))
        assertFalse(AlertPrefs.pages(AlertPrefs(critical = true), "high", "a1", "fees"))
    }

    @Test
    fun `critical pages only for the agents and projects named`() {
        val some = AlertPrefs(critical = true, criticalAgents = listOf("a1"), criticalProjects = listOf("fees"))
        assertTrue(AlertPrefs.pages(some, "critical", "a1", "fees"))
        assertFalse(AlertPrefs.pages(some, "critical", "a2", "fees"))
        assertFalse(AlertPrefs.pages(some, "critical", "a1", "docs"))
        assertFalse(AlertPrefs.pages(some, "critical", "a1", null))
    }

    @Test
    fun `a page gets through every filter and critical counts as high`() {
        val questionsOnly = AlertPrefs(types = listOf(AlertPrefs.Request), critical = true)
        assertTrue(AlertPrefs.allows(questionsOnly, AlertPrefs.Update, "error", "critical", agentId = "a1", projectSlug = "fees"))
        assertFalse(AlertPrefs.allows(questionsOnly.copy(critical = false), AlertPrefs.Update, "error", "critical"))
        assertTrue(AlertPrefs.allows(AlertPrefs(priorities = listOf("high")), AlertPrefs.Update, "error", "critical"))
    }

    @Test
    fun `switching a filter keeps the critical choice`() {
        val prefs = AlertPrefs(critical = true)
        assertEquals(AlertPrefs(types = listOf(AlertPrefs.Request, AlertPrefs.Comment, AlertPrefs.Update), critical = true), AlertPrefs.toggle(prefs, AlertPrefs.Axis.Types, AlertPrefs.Message))
        assertEquals(prefs, AlertPrefs.toggle(AlertPrefs.toggle(prefs, AlertPrefs.Axis.Types, AlertPrefs.Message), AlertPrefs.Axis.Types, AlertPrefs.Message))
    }

    @Test
    fun `a question carries its priority and is medium when the server says nothing`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val plain = json.decodeFromString(RequestView.serializer(), """{"id":"r1","agentId":"a1","kind":"confirm","question":"ok?"}""")
        assertEquals("medium", plain.priority)
        val loud = json.decodeFromString(RequestView.serializer(), """{"id":"r1","agentId":"a1","kind":"confirm","question":"ok?","priority":"critical"}""")
        assertEquals("critical", loud.priority)
    }
}
