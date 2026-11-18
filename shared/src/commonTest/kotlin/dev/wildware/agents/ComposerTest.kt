package dev.wildware.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposerTest {
    private val mega = ProjectRef("garden-game", "Garden Game")
    private val dash = ProjectRef("agent-dashboard", "Agent Dashboard")
    private val projects = listOf(mega, dash)
    private val box = ComposerTarget.PageTextBox("Reply to Builder")
    private val reply = ComposerTarget.Reply("r1", "Builder")

    @Test
    fun `by default it goes to the project on screen`() {
        assertEquals(ComposerTarget.Project(dash), Composer.target(null, null, "/projects/agent-dashboard", "garden-game", projects))
    }

    @Test
    fun `off a project page it goes to the last project used`() {
        assertEquals(ComposerTarget.Project(dash), Composer.target(null, null, "/", "agent-dashboard", projects))
    }

    @Test
    fun `the page's text box wins over the project`() {
        assertEquals(box, Composer.target(null, box, "/projects/agent-dashboard", null, projects))
    }

    @Test
    fun `an inbox reply wins over everything`() {
        assertEquals(reply, Composer.target(reply, box, "/projects/agent-dashboard", null, projects))
    }

    @Test
    fun `a reply pressed on the page wins over the page's text box`() {
        val pageReply = ComposerTarget.PageReply("reply-1", "Reply to scout")
        assertEquals(pageReply, Composer.target(pageReply, box, "/projects/agent-dashboard", null, projects))
        assertEquals("Reply to scout", pageReply.label)
        assertNull(pageReply.thread)
    }

    @Test
    fun `a reply on the page takes words but not photos`() {
        val pageReply = ComposerTarget.PageReply("reply-1", "Reply to scout")
        assertTrue(Composer.canSend("yes", 0, pageReply))
        assertFalse(Composer.canSend("", 1, pageReply))
        assertFalse(Composer.acceptsPhotos(pageReply))
    }

    @Test
    fun `a project picked on the chip wins over the page's text box`() {
        assertEquals(ComposerTarget.Project(mega), Composer.target(ComposerTarget.Project(mega), box, "/projects/agent-dashboard", null, projects))
    }

    @Test
    fun `a picked project that was deleted falls through`() {
        val gone = ComposerTarget.Project(ProjectRef("gone", "Gone"))
        assertEquals(ComposerTarget.Project(dash), Composer.target(gone, null, "/projects/agent-dashboard", null, projects))
    }

    @Test
    fun `choosing the text box once it has gone falls back to the project`() {
        assertEquals(ComposerTarget.Project(mega), Composer.target(box, null, "/projects/garden-game", null, projects))
    }

    @Test
    fun `with no projects there is nowhere to send`() {
        assertNull(Composer.target(null, null, "/", null, emptyList()))
        assertFalse(Composer.canSend("hello", 0, null))
    }

    @Test
    fun `labels say where it goes`() {
        assertEquals("To Garden Game", ComposerTarget.Project(mega).label)
        assertEquals("Reply to Builder", reply.label)
        assertEquals("Into “Reply to Builder”", box.label)
        assertEquals("Into the page's text box", ComposerTarget.PageTextBox(null).label)
        assertEquals("Into “Write something for the age…”", ComposerTarget.PageTextBox("Write something for the agents to do").label)
    }

    @Test
    fun `each target posts to its own thread`() {
        assertEquals(mapOf("project" to "garden-game"), ComposerTarget.Project(mega).thread)
        assertEquals(mapOf("request" to "r1"), reply.thread)
        assertNull(box.thread)
    }

    @Test
    fun `photos alone can be sent but not into the page`() {
        assertTrue(Composer.canSend("", 1, ComposerTarget.Project(mega)))
        assertFalse(Composer.canSend("  ", 0, ComposerTarget.Project(mega)))
        assertFalse(Composer.canSend("", 1, box))
        assertTrue(Composer.canSend("words", 0, box))
        assertFalse(Composer.acceptsPhotos(box))
    }

    @Test
    fun `dictation joins what is typed`() {
        assertEquals("hello there", Composer.appendDictation("hello", "there"))
        assertEquals("hello there", Composer.appendDictation("hello ", " there "))
        assertEquals("there", Composer.appendDictation("", "there"))
        assertEquals("hello", Composer.appendDictation("hello", "  "))
    }

    @Test
    fun `the bar stays off the sign-in page`() {
        assertFalse(Composer.showsOn("/login"))
        assertFalse(Composer.showsOn("/login?next=/projects/x"))
        assertFalse(Composer.showsOn("/logout"))
        assertTrue(Composer.showsOn("/"))
        assertTrue(Composer.showsOn("/projects/login-page"))
        assertTrue(Composer.showsOn(null))
    }
}
