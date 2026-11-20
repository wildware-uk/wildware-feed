package dev.wildware.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavHistoryTest {
    @Test
    fun `it starts at its first place with nowhere to go`() {
        val history = NavHistory("home")
        assertEquals("home", history.current)
        assertFalse(history.canGoBack)
        assertFalse(history.canGoForward)
        assertNull(history.back())
        assertNull(history.forward())
    }

    @Test
    fun `back and forward walk the places in order`() {
        val history = NavHistory("a")
        history.visit("b")
        history.visit("c")
        assertEquals("b", history.back())
        assertEquals("a", history.back())
        assertNull(history.back())
        assertEquals("b", history.forward())
        assertEquals("c", history.forward())
        assertNull(history.forward())
    }

    @Test
    fun `a new place drops what was ahead`() {
        val history = NavHistory("a")
        history.visit("b")
        history.visit("c")
        history.back()
        history.back()
        assertEquals(NavHistory.Move.Added, history.visit("d"))
        assertFalse(history.canGoForward)
        assertEquals("a", history.back())
        assertEquals(2, history.size)
    }

    @Test
    fun `visiting the place showing changes nothing`() {
        val history = NavHistory("a")
        assertEquals(NavHistory.Move.Stayed, history.visit("a"))
        assertEquals(1, history.size)
    }

    @Test
    fun `visiting the place behind is going back`() {
        // Opening a thread then closing it by hand: Back then goes where the owner was before.
        val history = NavHistory("channel")
        history.visit("channel/thread")
        assertEquals(NavHistory.Move.Back, history.visit("channel"))
        assertEquals("channel", history.current)
        assertTrue(history.canGoForward)
        assertFalse(history.canGoBack)
    }

    @Test
    fun `visiting the place ahead is going forward`() {
        val history = NavHistory("a")
        history.visit("b")
        history.back()
        assertEquals(NavHistory.Move.Forward, history.visit("b"))
        assertEquals("b", history.current)
        assertFalse(history.canGoForward)
    }

    @Test
    fun `it keeps only the newest places`() {
        val history = NavHistory(0, limit = 50)
        for (i in 1..120) history.visit(i)
        assertEquals(50, history.size)
        assertEquals(120, history.current)
        var steps = 0
        while (history.back() != null) steps++
        assertEquals(49, steps)
        assertEquals(71, history.current)
    }

    @Test
    fun `replace changes the place showing without adding one`() {
        val history = NavHistory("starting")
        history.replace("settled")
        assertEquals("settled", history.current)
        assertEquals(1, history.size)
        assertFalse(history.canGoBack)
    }
}
