package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.CardFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.jetbrains.skia.Bitmap

/** The owner's own post has an accent rail down its left edge, as on the website; others have none. */
class MineRailTest {
    /** The colour just inside the card's left edge, halfway down, and the theme's accent. */
    private fun leftEdge(mine: Boolean): Pair<Int, Int> {
        var accent = Color.Unspecified
        val image = ImageComposeScene(200, 100, Density(1f)) {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                AgentTheme(dark = false) {
                    accent = AgentTheme.colors.accent
                    CardFrame(mine = mine) { Box(Modifier.height(60.dp)) }
                }
            }
        }.use { it.render() }
        val bitmap = Bitmap.makeFromImage(image)
        return bitmap.getColor(2, 50) to accent.toArgb()
    }

    @Test
    fun theOwnersPostHasTheRail() {
        val (edge, accent) = leftEdge(mine = true)
        assertEquals(accent, edge)
    }

    @Test
    fun othersHaveNone() {
        val (edge, accent) = leftEdge(mine = false)
        assertNotEquals(accent, edge)
    }
}
