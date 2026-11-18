package dev.wildware.agents.app.design

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The spacing scale. Layouts use these steps and nothing in between. */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 48.dp
}

/** The smallest thing a finger or pointer can be asked to hit: 44pt, Apple's minimum. */
val MinTouchTarget = 44.dp

@Immutable
data class AgentShapes(
    /** Chips, badges, small buttons. */
    val small: RoundedCornerShape,
    /** Inputs, code blocks, images. */
    val medium: RoundedCornerShape,
    /** Cards and panels. */
    val large: RoundedCornerShape,
    val pill: RoundedCornerShape = RoundedCornerShape(percent = 50),
)

/** One step of lift: how far the shadow falls and how dark it is. */
@Immutable
data class Lift(val blur: Dp, val offsetY: Dp, val color: Color)

@Immutable
data class AgentElevation(
    val flat: Lift,
    val card: Lift,
    val raised: Lift,
    val overlay: Lift,
)

/**
 * Timing for everything that moves. When the system asks for reduced motion, every spec snaps
 * straight to its end state instead of animating.
 */
@Immutable
data class AgentMotion(val reduced: Boolean) {
    val quick = 120
    val standard = 220
    val gentle = 360

    fun <T> fast(): AnimationSpec<T> = spec(quick)
    fun <T> normal(): AnimationSpec<T> = spec(standard)
    fun <T> slow(): AnimationSpec<T> = spec(gentle)

    private fun <T> spec(ms: Int): AnimationSpec<T> =
        if (reduced) snap() else tween(ms, easing = Emphasized)

    companion object {
        /** Starts briskly and settles softly. */
        val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
}
