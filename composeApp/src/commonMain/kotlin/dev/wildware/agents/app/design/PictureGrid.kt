package dev.wildware.agents.app.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** One option in a [PictureGrid]: its words and whether it is picked. */
@Immutable
data class PictureOption(val label: String, val picked: Boolean)

/** How picking works, which decides the mark on each tile. */
enum class PickMode {
    /** Tapping answers straight away; tiles carry no mark. */
    Instant,

    /** One at a time: a round mark, like a radio button. */
    One,

    /** Any number: a square mark that fills with a tick, like a checkbox. */
    Many,
}

private val MinTile = 96.dp
private val Gap = 10.dp

/**
 * Options you choose by picture: a grid of tiles, the image filling each tile and its label
 * under it. Tap a tile to pick it; long-press (or the corner button, or the screen reader's
 * "Look closer") to see the picture full size. The label is the picture's alt text.
 *
 * The tiles only listen for taps and long presses, so a sideways swipe across them still moves
 * the card they sit on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PictureGrid(
    options: List<PictureOption>,
    mode: PickMode,
    onPick: (Int) -> Unit,
    onLookCloser: (Int) -> Unit,
    modifier: Modifier = Modifier,
    picture: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // As many columns as fit tiles of at least MinTile, but never more than there are options.
        val fit = ((maxWidth + Gap) / (MinTile + Gap)).toInt()
        val columns = min(options.size, max(2, fit)).coerceAtLeast(1)
        val tile: Dp = (maxWidth - Gap * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(Gap)) {
            options.indices.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Gap)) {
                    row.forEach { i ->
                        PictureTile(
                            option = options[i],
                            mode = mode,
                            width = tile,
                            onPick = {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onPick(i)
                            },
                            onLookCloser = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLookCloser(i)
                            },
                        ) { m -> picture(i, m) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PictureTile(
    option: PictureOption,
    mode: PickMode,
    width: Dp,
    onPick: () -> Unit,
    onLookCloser: () -> Unit,
    picture: @Composable (Modifier) -> Unit,
) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val picked = option.picked
    val state = when {
        mode == PickMode.Instant -> null
        picked -> "Picked"
        else -> "Not picked"
    }
    Column(
        Modifier
            .width(width)
            .clip(shape)
            .touchTarget()
            .focusRing(shape)
            .semantics(mergeDescendants = true) {
                contentDescription = option.label
                role = when (mode) {
                    PickMode.Instant -> Role.Button
                    PickMode.One -> Role.RadioButton
                    PickMode.Many -> Role.Checkbox
                }
                if (state != null) stateDescription = state
                selected = picked
                customActions = listOf(CustomAccessibilityAction("Look closer at ${option.label}") { onLookCloser(); true })
            }
            .combinedClickable(
                onClickLabel = if (mode == PickMode.Instant) "Answer ${option.label}" else "Pick ${option.label}",
                onLongClickLabel = "Look closer",
                onLongClick = onLookCloser,
                onClick = onPick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(shape)
                .border(if (picked) 2.dp else 1.dp, if (picked) colors.accent else colors.border, shape),
        ) {
            picture(Modifier.fillMaxSize())
            if (picked) Box(Modifier.fillMaxSize().background(colors.accent.copy(alpha = 0.12f)))
            if (mode != PickMode.Instant) {
                PickMark(mode, picked, Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .pressable("Look closer at ${option.label}", CircleShape, onClick = onLookCloser),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.ZoomIn, null, tint = Color.White, modifier = Modifier.size(15.dp))
                }
            }
        }
        Text(
            option.label,
            Modifier.padding(top = 6.dp, bottom = 2.dp, start = 2.dp, end = 2.dp).clearAndSetSemantics { },
            color = if (picked) colors.text else colors.textMuted,
            style = AgentTheme.type.label,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The tick or dot in a tile's corner. A circle for pick-one, a square for pick-many. */
@Composable
private fun PickMark(mode: PickMode, picked: Boolean, modifier: Modifier) {
    val colors = AgentTheme.colors
    val shape = if (mode == PickMode.Many) AgentTheme.shapes.small else CircleShape
    Box(
        modifier
            .size(22.dp)
            .clip(shape)
            .background(if (picked) colors.accent else Color.Black.copy(alpha = 0.35f))
            .border(1.5.dp, if (picked) colors.accent else Color.White.copy(alpha = 0.85f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (picked) Icon(Icons.Check, null, tint = colors.onAccent, modifier = Modifier.size(14.dp))
    }
}
