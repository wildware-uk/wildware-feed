package dev.wildware.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceNoteTest {
    private val projects = listOf(
        ProjectRef("garden-game", "Garden Game"),
        ProjectRef("agent-dashboard", "Agent Dashboard"),
    )

    @Test
    fun `a project page names its project`() {
        assertEquals("garden-game", VoiceNote.projectSlug("/projects/garden-game"))
        assertEquals("garden-game", VoiceNote.projectSlug("/projects/garden-game/?focus=abc"))
        assertEquals("garden-game", VoiceNote.projectSlug("/projects/garden-game#feed"))
    }

    @Test
    fun `other pages name no project`() {
        assertNull(VoiceNote.projectSlug("/"))
        assertNull(VoiceNote.projectSlug("/projects"))
        assertNull(VoiceNote.projectSlug("/s/token/media/1"))
    }

    @Test
    fun `a hand-picked project wins over the page and the last one used`() {
        val chosen = VoiceNote.destination("agent-dashboard", "/projects/garden-game", "garden-game", projects)

        assertEquals("agent-dashboard", chosen?.slug)
    }

    @Test
    fun `the page on screen wins over the last one used`() {
        assertEquals("garden-game", VoiceNote.destination(null, "/projects/garden-game", "agent-dashboard", projects)?.slug)
    }

    @Test
    fun `off a project page the last one used is chosen`() {
        assertEquals("agent-dashboard", VoiceNote.destination(null, "/", "agent-dashboard", projects)?.slug)
    }

    @Test
    fun `projects that no longer exist fall through to the first project`() {
        assertEquals("garden-game", VoiceNote.destination("gone", "/projects/also-gone", "deleted", projects)?.slug)
        assertNull(VoiceNote.destination(null, null, null, emptyList()))
    }

    @Test
    fun `the transcript is tidied into one paragraph`() {
        assertEquals(
            "Merge the branch. Then deploy.",
            VoiceNote.messageBody(listOf("  Merge the branch.", "\nThen   deploy. ")),
        )
    }

    @Test
    fun `a note with no words gets a caption`() {
        assertEquals(VoiceNote.NoWordsCaption, VoiceNote.messageBody(emptyList()))
        assertEquals(VoiceNote.NoWordsCaption, VoiceNote.messageBody(listOf(" ", "")))
    }

    @Test
    fun `a very long transcript is cut to fit the dashboard`() {
        val body = VoiceNote.messageBody(listOf("word ".repeat(5_000)))

        assertEquals(VoiceNote.MaxBodyLength, body.length)
        assertTrue(body.endsWith("…"))
    }

    @Test
    fun `a tap is too short to send`() {
        assertFalse(VoiceNote.isLongEnough(0.2))
        assertTrue(VoiceNote.isLongEnough(1.5))
    }
}
