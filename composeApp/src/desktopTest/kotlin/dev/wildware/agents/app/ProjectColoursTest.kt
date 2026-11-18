package dev.wildware.agents.app

import androidx.compose.ui.graphics.Color
import dev.wildware.agents.app.design.ProjectColours
import dev.wildware.agents.app.design.DarkColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A project's colours always give readable text, worked out as the website works it out. */
class ProjectColoursTest {
    @Test
    fun darkAndLightPagesEachGetTextThatReads() {
        listOf("#0f3d3e", "#14182b", "#f3ead8", "#e8eef3", "#ffffff", "#000000").forEach { hex ->
            val page = ProjectColours.parse(hex)!!
            assertTrue(ProjectColours.contrast(page, ProjectColours.ink(page)) >= 4.5f, hex)
        }
    }

    @Test
    fun shortAndLongHexAreReadAndAnythingElseIsNot() {
        assertEquals(Color(0xFFAABBCC), ProjectColours.parse("#abc"))
        assertEquals(Color(0xFF0F3D3E), ProjectColours.parse("#0f3d3e"))
        listOf(null, "", "0f3d3e", "#12", "#zzzzzz", "red").forEach { assertNull(ProjectColours.parse(it), it) }
    }

    @Test
    fun cardsStandOffThePageEvenAtWhite() {
        val colors = ProjectColours.over(DarkColors, "#ffffff", null)
        assertNotEquals(colors.background, colors.surfaceRaised)
        assertNotEquals(colors.background, colors.surfaceSunken)
    }

    @Test
    fun noColoursLeavesTheAppsOwn() {
        assertSame(DarkColors, ProjectColours.over(DarkColors, null, "not a colour"))
    }
}
