package dev.wildware.agents.app.desktop

import dev.wildware.agents.app.desktop.WindowsTitleBar.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Windows title bar follows the app's light or dark, and nothing is touched anywhere else. */
class WindowsTitleBarTest {
    @Test
    fun onlyWindowsGetsAnything() {
        assertTrue(WindowsTitleBar.plan("Mac OS X", dark = true, caption = 0xFF101114.toInt()).isEmpty())
        assertTrue(WindowsTitleBar.plan("Linux", dark = true, caption = 0xFF101114.toInt()).isEmpty())
    }

    @Test
    fun darkAsksForTheDarkFrameWithTheOldNumberAsAFallbackThenTheCaptionColour() {
        assertEquals(
            listOf(Step(attribute = 20, fallback = 19, value = 1), Step(attribute = 35, fallback = null, value = 0x00141110)),
            WindowsTitleBar.plan("Windows 11", dark = true, caption = 0xFF101114.toInt()),
        )
    }

    @Test
    fun lightTurnsTheDarkFrameOffAndWithoutAColourLeavesTheCaptionAlone() {
        assertEquals(listOf(Step(20, 19, 0)), WindowsTitleBar.plan("Windows 10", dark = false, caption = null))
    }

    @Test
    fun theCaptionColourIsWindowsBlueGreenRedOrder() {
        assertEquals(0x00332211, WindowsTitleBar.colorRef(0xFF112233.toInt()))
        assertEquals(0x00FFFFFF, WindowsTitleBar.colorRef(0xFFFFFFFF.toInt()))
    }
}
