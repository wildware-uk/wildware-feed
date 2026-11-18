package dev.wildware.agents.app.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme

/**
 * Shows [content] for [value] while it is set, animating in and out with [enter] and [exit]. It
 * keeps drawing the last value while it leaves, so a sheet or viewer closing slides away with what
 * it showed rather than vanishing. Reduced motion shows and hides at once.
 */
@Composable
fun <T : Any> Presence(
    value: T?,
    enter: EnterTransition,
    exit: ExitTransition,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.(T) -> Unit,
) {
    val reduced = AgentTheme.motion.reduced
    val held = remember { Held<T>() }
    if (value != null) held.value = value
    AnimatedVisibility(
        visible = value != null,
        modifier = modifier,
        enter = if (reduced) EnterTransition.None else enter,
        exit = if (reduced) ExitTransition.None else exit,
    ) {
        held.value?.let { content(it) }
    }
}

/** The last value shown. Not state: it only changes in the same pass that shows it. */
private class Held<T> {
    var value: T? = null
}

/**
 * Grey stand-ins for cards while the feed loads for the first time, so the screen has its shape
 * at once and the cards fade in over it instead of popping in. A soft light passes over them;
 * with reduced motion they stay still. Animated while drawing only, so nothing recomposes.
 */
@Composable
fun SkeletonFeed(modifier: Modifier = Modifier, cards: Int = 4) {
    val colors = AgentTheme.colors
    val reduced = AgentTheme.motion.reduced
    val shimmer = if (reduced) null else rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "skeleton glow",
    )
    val bone = colors.text.copy(alpha = if (colors.isDark) 0.08f else 0.07f)
    Column(
        modifier
            .semantics { contentDescription = "Loading the feed" }
            .drawWithContent {
                val glow = shimmer?.value ?: 1f
                drawContent()
                // Dims the bones in and out together: one cheap pass over the whole stand-in.
                drawRect(colors.background.copy(alpha = (1f - glow) * 0.6f))
            },
    ) {
        repeat(cards) { i ->
            Row(Modifier.fillMaxWidth().padding(bottom = 22.dp).clearAndSetSemantics { }, verticalAlignment = Alignment.Top) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(bone))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Bone(bone, width = 120.dp, height = 12.dp)
                    Spacer(Modifier.height(10.dp))
                    Bone(bone, fraction = 0.92f, height = 14.dp)
                    Spacer(Modifier.height(8.dp))
                    Bone(bone, fraction = if (i % 2 == 0) 0.74f else 0.58f, height = 14.dp)
                    if (i % 3 == 0) {
                        Spacer(Modifier.height(8.dp))
                        Bone(bone, fraction = 0.4f, height = 14.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bone(color: Color, height: Dp, width: Dp? = null, fraction: Float = 1f) {
    val shape = AgentTheme.shapes.small
    Box(
        (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth(fraction))
            .height(height)
            .clip(shape)
            .background(color),
    )
}
