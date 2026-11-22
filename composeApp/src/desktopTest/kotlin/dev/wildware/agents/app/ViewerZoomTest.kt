package dev.wildware.agents.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.wildware.agents.app.feed.ViewerZoom
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The viewer's zoom and pan sums. */
class ViewerZoomTest {
    private fun zoom(viewport: Size = Size(1000f, 500f), image: Size = Size(4000f, 1000f)) = ViewerZoom().apply { setSizes(viewport, image) }

    private fun near(a: Float, b: Float) = abs(a - b) < 0.01f

    @Test
    fun aBigPictureOpensFittedToTheWindow() {
        val z = zoom()
        assertTrue(near(z.scale, 0.25f))
        assertTrue(z.isFitted)
        assertFalse(z.canPan)
        assertEquals(25, z.percent)
    }

    @Test
    fun aSmallPictureOpensAtActualSizeNeverBlownUp() {
        val z = zoom(image = Size(200f, 100f))
        assertEquals(1f, z.scale)
        assertEquals(200f, z.imageRect.width)
    }

    @Test
    fun zoomingKeepsThePointUnderThePointer() {
        val z = zoom()
        val at = Offset(100f, 250f)
        val before = z.imageRect
        val fx = (at.x - before.left) / before.width
        z.zoomTo(1f, at)
        val after = z.imageRect
        assertTrue(near((at.x - after.left) / after.width, fx), "$before then $after")
    }

    @Test
    fun panningStopsAtTheEdges() {
        val z = zoom()
        z.zoomTo(1f)
        z.panBy(Offset(99_999f, 99_999f))
        assertEquals(0f, z.imageRect.left)
        assertEquals(0f, z.imageRect.top)
        z.panBy(Offset(-99_999f, -99_999f))
        assertEquals(1000f, z.imageRect.right)
        assertEquals(500f, z.imageRect.bottom)
    }

    @Test
    fun aPictureSmallerThanTheWindowStaysInTheMiddle() {
        val z = zoom()
        z.zoomTo(0.1f)
        z.panBy(Offset(300f, 300f))
        assertEquals(Offset.Zero, z.offset)
    }

    @Test
    fun zoomStaysBetweenFivePercentAndEightHundred() {
        val z = zoom()
        z.zoomTo(100f)
        assertEquals(8f, z.scale)
        z.zoomTo(0.0001f)
        assertEquals(0.05f, z.scale)
    }

    @Test
    fun aFittedPictureFollowsTheWindowAndAZoomedOneStays() {
        val z = zoom()
        z.setSizes(Size(2000f, 1000f), Size(4000f, 1000f))
        assertTrue(near(z.scale, 0.5f))
        z.zoomTo(2f)
        z.setSizes(Size(1000f, 500f), Size(4000f, 1000f))
        assertEquals(2f, z.scale)
    }

    @Test
    fun toggleGoesToActualSizeAndBack() {
        val z = zoom()
        z.toggle(null)
        assertEquals(1f, z.scale)
        z.toggle(null)
        assertTrue(z.isFitted)
    }
}
