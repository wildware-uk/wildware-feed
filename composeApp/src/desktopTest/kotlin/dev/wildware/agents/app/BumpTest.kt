package dev.wildware.agents.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.CardFrame
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.FeedSection
import dev.wildware.agents.app.feed.FeedUi
import dev.wildware.agents.app.feed.LocalFeedUi
import dev.wildware.agents.app.feed.ScreenPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.jetbrains.skia.Bitmap

/** A card whose activity moves on wears a fading ring for a moment, as on the website; a new card doesn't. */
class BumpTest {
    private val update = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().first()
    private fun sections(at: Long) = listOf(FeedSection("Today", listOf(update.copy(activityAt = at))))
    private fun ui() = FeedUi(ScreenPreview(), mutableStateOf(FakeFeed.state), {})

    @Test
    fun onlyActivityMovingOnIsABump() {
        val ui = ui()
        ui.noteActivity(sections(100))
        assertEquals(0, ui.bumpOf(update.id).intValue, "first seen is arriving, not a bump")
        ui.noteActivity(sections(100))
        assertEquals(0, ui.bumpOf(update.id).intValue, "nothing moved")
        ui.noteActivity(sections(200))
        assertNotEquals(0, ui.bumpOf(update.id).intValue, "a reply moved it on")
    }

    /** The colour just outside the card's left edge, halfway down, at [millis] into the ring. */
    private fun edgeAt(ui: FeedUi, millis: Long): Int {
        val scene = ImageComposeScene(200, 100, Density(1f)) {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalFeedUi provides ui) {
                AgentTheme(dark = false) {
                    Box(Modifier.padding(8.dp)) { CardFrame(bumpId = update.id) { Box(Modifier.height(60.dp)) } }
                }
            }
        }
        return scene.use {
            it.render(0)
            it.render(millis * 1_000_000)
            Bitmap.makeFromImage(it.render(millis * 1_000_000)).getColor(7, 50)
        }
    }

    @Test
    fun theRingShowsThenFades() {
        val quiet = edgeAt(ui(), 0)
        val bumped = ui().also { it.noteActivity(sections(100)); it.noteActivity(sections(200)) }
        assertNotEquals(quiet, edgeAt(bumped, 0), "ringed at first")
        val later = ui().also { it.noteActivity(sections(100)); it.noteActivity(sections(200)) }
        assertEquals(quiet, edgeAt(later, 3_000), "gone after a moment")
    }
}
