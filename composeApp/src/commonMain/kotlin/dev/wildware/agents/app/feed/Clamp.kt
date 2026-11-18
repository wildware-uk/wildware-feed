package dev.wildware.agents.app.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.pressable

/** The tallest a card's body may be before it folds: enough to read, not enough to own the screen. */
val ClampHeight = 420.dp

/** How much taller than [ClampHeight] a body must be to fold, so nothing folds to hide one line. */
private val ClampSlack = 8.dp

private val FadeHeight = 48.dp

/**
 * Long content, cut to [max] with a fade until the owner asks for the rest. Content that fits is
 * left exactly as it is: no fade, no button. It is measured rather than counted, so a wide window,
 * a code block or a picture are all judged by the room they really take.
 *
 * Whether it folds is settled while it is laid out, so a card never draws once at full height and
 * then jumps. Opened stays opened for [key] while the feed is on screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Clamp(
    key: String,
    /** What the button reveals: "Show the whole update". */
    noun: String,
    modifier: Modifier = Modifier,
    /** The colour behind the content, which the fade runs into. */
    fade: Color = AgentTheme.colors.surface,
    max: Dp = ClampHeight,
    content: @Composable () -> Unit,
) {
    val ui = LocalFeedUi.current
    var openHere by rememberSaveable(key) { mutableStateOf(false) }
    val open = ui?.isUnfolded(key) ?: openHere
    val flip = { if (ui != null) ui.toggleFold(key) else openHere = !openHere }
    // Written while measuring and read in the same pass; not state, so it causes no extra frame.
    val folded = remember { FoldMeasure() }
    // Folding a long card from its bottom would leave the screen far past it, with the card gone
    // off the top. Scroll to its start first, then fold.
    val bringBack = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val toggle: () -> Unit = {
        if (open) {
            scope.launch {
                runCatching { bringBack.bringIntoView(Rect(0f, 0f, 1f, 1f)) }
                flip()
            }
        } else flip()
    }

    Layout(
        content = {
            Box(
                // Clip and fade wrap the measured size, so what is cut off is really hidden.
                Modifier
                    .clipToBounds()
                    .drawWithContent {
                        drawContent()
                        if (folded.overflowing && !open) {
                            val h = FadeHeight.toPx().coerceAtMost(size.height)
                            drawRect(
                                Brush.verticalGradient(listOf(fade.copy(alpha = 0f), fade), startY = size.height - h, endY = size.height),
                                topLeft = Offset(0f, size.height - h),
                                size = Size(size.width, h),
                            )
                        }
                    }
                    .layout { measurable, constraints ->
                        val full = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
                        val limit = max.roundToPx()
                        folded.overflowing = full.height > limit + ClampSlack.roundToPx()
                        val height = if (folded.overflowing && !open) limit else full.height
                        folded.shown = height
                        layout(full.width, height) { full.place(0, 0) }
                    },
            ) { content() }
            FoldToggle(open, noun, toggle, shown = folded.foldsState)
        },
        modifier = modifier.bringIntoViewRequester(bringBack),
    ) { measurables, constraints ->
        val body = measurables[0].measure(constraints.copy(minHeight = 0))
        val button = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gap = 4.dp.roundToPx()
        val showButton = folded.overflowing
        val height = body.height + if (showButton) gap + button.height else 0
        layout(body.width, height) {
            body.place(0, 0)
            if (showButton) button.place(0, body.height + gap)
        }
    }
}

/**
 * What the last measure found. [overflowing] is a plain field, read in the same layout pass that
 * set it, so the fold costs no extra frame. [foldsState] follows it a frame later, only to keep a
 * button that isn't placed away from screen readers.
 */
private class FoldMeasure {
    var overflowing = false
        set(value) {
            field = value
            if (foldsState.value != value) foldsState.value = value
        }
    var shown = 0
    val foldsState = mutableStateOf(false)
}

@Composable
private fun FoldToggle(open: Boolean, noun: String, onClick: () -> Unit, shown: androidx.compose.runtime.State<Boolean>) {
    // Not placed when the content fits; then it must not be read out either.
    if (!shown.value) {
        Box(Modifier)
        return
    }
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    val label = if (open) "Show less" else "Show $noun"
    Text(
        label,
        Modifier
            .clip(shape)
            .pressable(label, shape, labelIsShown = true, onClick = onClick)
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        color = colors.textMuted,
        style = AgentTheme.type.label,
    )
}
