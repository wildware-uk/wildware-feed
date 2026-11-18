package dev.wildware.agents.app.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * How everything pressable answers a touch, in place of Android's ripple: it dims and, if small,
 * shrinks a little the moment a finger lands, and eases back when it lifts, as iOS buttons do.
 * Only drawing changes, so a press never redraws or re-lays-out anything. With reduced motion it
 * only dims.
 */
@Immutable
class PressIndication(private val tint: Color, private val reduced: Boolean) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, tint, reduced)
    override fun equals(other: Any?) = other is PressIndication && other.tint == tint && other.reduced == reduced
    override fun hashCode() = 31 * tint.hashCode() + reduced.hashCode()
}

/** How far a small pressed thing shrinks, and how much a pressed or hovered thing dims. */
internal const val PressedScale = 0.96f
internal const val PressedTint = 0.10f
internal const val HoveredTint = 0.05f

/** Things wider and taller than this (cards, rows) only dim: shrinking them would look like a glitch. */
private val ShrinkUpTo = 140.dp

private class PressNode(
    private val source: InteractionSource,
    private val tint: Color,
    private val reduced: Boolean,
) : Modifier.Node(), DrawModifierNode {
    private val pressed = Animatable(0f)
    private var hovered = false

    override fun onAttach() {
        coroutineScope.launch {
            var presses = 0
            var hovers = 0
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses++
                    is PressInteraction.Release, is PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
                    is HoverInteraction.Enter -> hovers++
                    is HoverInteraction.Exit -> hovers = (hovers - 1).coerceAtLeast(0)
                }
                if (hovered != hovers > 0) {
                    hovered = hovers > 0
                    invalidateDraw()
                }
                // Down shows in the very next frame; up eases off, so a quick tap still shows.
                if (presses > 0) launch { pressed.snapTo(1f) } else launch { pressed.animateTo(0f, tween(if (reduced) 0 else 200)) }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = pressed.value
        if (p == 0f && !hovered) {
            drawContent()
            return
        }
        val small = size.width <= ShrinkUpTo.toPx() && size.height <= ShrinkUpTo.toPx()
        val s = if (reduced || !small) 1f else 1f - (1f - PressedScale) * p
        val alpha = maxOf(PressedTint * p, if (hovered) HoveredTint else 0f)
        scale(s) {
            this@draw.drawContent()
            drawRect(tint.copy(alpha = tint.alpha * alpha))
        }
    }
}
