package dev.wildware.agents.app

import dev.wildware.agents.app.design.AgentColors
import dev.wildware.agents.app.design.DarkColors
import dev.wildware.agents.app.design.LightColors
import dev.wildware.agents.app.feed.replySurface
import kotlin.test.Test
import kotlin.test.assertNotEquals

/** Each reply sits on its own surface, so one ends visibly before the next starts; the owner's differ from agents'. */
class ReplySurfaceTest {
    private fun check(colors: AgentColors) {
        val mine = replySurface(colors, own = true)
        val theirs = replySurface(colors, own = false)
        assertNotEquals(mine, theirs, "the owner's replies look different from agents'")
        assertNotEquals(colors.surface, theirs, "an agent's reply stands out from the card it is in")
        assertNotEquals(colors.surface, mine, "the owner's reply stands out from the card it is in")
        assertNotEquals(colors.background, theirs, "and from the phone thread's background")
        assertNotEquals(colors.background, mine)
    }

    @Test
    fun repliesStandOutInLightMode() = check(LightColors)

    @Test
    fun repliesStandOutInDarkMode() = check(DarkColors)
}
