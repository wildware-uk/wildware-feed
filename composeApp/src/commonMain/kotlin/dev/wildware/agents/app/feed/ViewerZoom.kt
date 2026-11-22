package dev.wildware.agents.app.feed

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How big a picture in the viewer is drawn and where. [scale] is screen pixels per picture pixel,
 * so 1 is "100%": one pixel of the picture on one pixel of the screen. [offset] is how far the
 * picture's middle sits from the middle of the window.
 *
 * It opens fitted (the whole picture showing, never cropped, and never blown up past 100%), and
 * stays fitted as the window changes size until someone zooms. Panning is clamped so the picture
 * can't be lost off the screen: a picture smaller than the window stays in its middle.
 */
@Stable
class ViewerZoom {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** The window the picture is shown in, in pixels. */
    var viewport by mutableStateOf(Size.Zero)
        private set

    /** The picture's own size in pixels. */
    var image by mutableStateOf(Size.Zero)
        private set

    /** Whether it follows the window as fitted: true until someone zooms or pans. */
    private var following = true

    /** The scale that shows the whole picture, at most 100%. */
    val fitScale: Float
        get() {
            if (viewport.isEmptySize() || image.isEmptySize()) return 1f
            return min(1f, min(viewport.width / image.width, viewport.height / image.height))
        }

    /** Whether it is showing the whole picture at its fitted size. */
    val isFitted: Boolean get() = abs(scale - fitScale) <= fitScale * 0.001f && offset == Offset.Zero

    /** Bigger than fitted: a drag then pans it, and swiping to the next picture waits. */
    val isZoomedIn: Boolean get() = scale > fitScale * 1.001f

    /** Whether a drag can move it: some of it is off the screen. */
    val canPan: Boolean get() = image.width * scale > viewport.width + 0.5f || image.height * scale > viewport.height + 0.5f

    /** "100" for 100%. */
    val percent: Int get() = (scale * 100).roundToInt()

    /** Where the picture is drawn, in the window's pixels. */
    val imageRect: Rect
        get() {
            val size = Size(image.width * scale, image.height * scale)
            val center = Offset(viewport.width / 2 + offset.x, viewport.height / 2 + offset.y)
            return Rect(center - Offset(size.width / 2, size.height / 2), size)
        }

    /** The window or the picture changed size: a fitted picture fits again; a zoomed one stays put but in bounds. */
    fun setSizes(viewport: Size, image: Size) {
        if (viewport == this.viewport && image == this.image) return
        this.viewport = viewport
        this.image = image
        if (following) {
            scale = fitScale
            offset = Offset.Zero
        } else {
            clamp()
        }
    }

    /** The whole picture, in the middle. */
    fun fit() {
        following = true
        scale = fitScale
        offset = Offset.Zero
    }

    /** Zooms to [target] keeping the picture's point under [pivot] (window pixels; null for the middle) where it is. */
    fun zoomTo(target: Float, pivot: Offset? = null) {
        val next = target.coerceIn(MinScale, MaxScale)
        if (viewport.isEmptySize()) {
            scale = next
            return
        }
        val center = Offset(viewport.width / 2, viewport.height / 2)
        val at = (pivot ?: center) - center
        // The picture's point under the pointer, from its middle, in its own pixels.
        val point = (at - offset) / scale
        following = false
        scale = next
        offset = at - point * next
        clamp()
    }

    fun zoomBy(factor: Float, pivot: Offset? = null) = zoomTo(scale * factor, pivot)

    /** One step in or out, for a key or a button. */
    fun stepIn() = zoomBy(Step)

    fun stepOut() = zoomBy(1f / Step)

    /** Moves it by [delta] window pixels, as far as it may go. */
    fun panBy(delta: Offset) {
        following = false
        offset += delta
        clamp()
    }

    /** Double-click or double-tap: fitted goes to 100% (or twice fitted, for a small picture) at [at]; anything else fits. */
    fun toggle(at: Offset?) {
        if (isZoomedIn) {
            fit()
        } else {
            zoomTo(if (fitScale < 0.999f) 1f else max(2f, fitScale * 2), at)
        }
    }

    private fun clamp() {
        val maxX = max(0f, (image.width * scale - viewport.width) / 2)
        val maxY = max(0f, (image.height * scale - viewport.height) / 2)
        offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    private fun Size.isEmptySize() = width <= 0f || height <= 0f

    companion object {
        const val MinScale = 0.05f
        const val MaxScale = 8f
        const val Step = 1.25f
    }
}
