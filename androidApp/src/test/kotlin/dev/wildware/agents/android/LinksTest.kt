package dev.wildware.agents.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinksTest {
    @Test
    fun appLinksCarryAPagePath() {
        assertEquals("/projects/my-project?focus=abc", Links.pathOf("agents://open?path=%2Fprojects%2Fmy-project%3Ffocus%3Dabc"))
        assertEquals("/", Links.pathOf("agents://open?path=/"))
    }

    @Test
    fun otherLinksAreNotOurs() {
        // Website links are never the app's: it serves anyone's own dashboard.
        assertNull(Links.pathOf("https://dashboard.example.com/projects/x"))
        assertNull(Links.pathOf("agents://elsewhere?path=/"))
        assertNull(Links.pathOf("agents://open?path=projects"))
        assertNull(Links.pathOf("not a link at all"))
    }

    @Test
    fun openRoundTrips() {
        val path = "/projects/my project?focus=a&b"
        assertEquals(path, Links.pathOf(Links.open(path)))
    }

    @Test
    fun aSharedPageKeepsItsTitleAboveTheLink() {
        assertEquals("Release notes\nhttps://x.dev/notes", Links.sharedText("Release notes", "https://x.dev/notes"))
        assertEquals("Look: Release notes", Links.sharedText("Release notes", "Look: Release notes"))
        assertEquals("just text", Links.sharedText(null, "  just text "))
        assertEquals("Title", Links.sharedText("Title", null))
        assertEquals("", Links.sharedText(null, null))
    }
}
