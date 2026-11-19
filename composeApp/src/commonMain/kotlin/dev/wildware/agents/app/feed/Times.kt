package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import dev.wildware.agents.app.design.AgentTheme
import kotlinx.coroutines.delay

/** How long the pointer rests on a time before its whole instant shows, as a browser's tooltip waits. */
private const val HintDelayMillis = 500L

/**
 * A card's or reply's time, "5m ago", with the whole instant behind it ([detail]) shown when the
 * pointer rests on it, as the website's tooltip does. Touch screens have no hover; there, and for
 * screen readers, the card reads [detail] out instead.
 */
@Composable
internal fun TimeText(label: String, detail: String, modifier: Modifier = Modifier) {
    if (detail.isEmpty()) {
        MetaText(label, modifier)
        return
    }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        if (hovered) delay(HintDelayMillis)
        shown = hovered
    }
    Box(modifier.hoverable(source)) {
        MetaText(label)
        if (shown) TimeHint(detail)
    }
}

@Composable
private fun TimeHint(text: String) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    val below = with(LocalDensity.current) { 22.dp.roundToPx() }
    Popup(alignment = Alignment.TopStart, offset = IntOffset(0, below)) {
        Text(
            text,
            Modifier
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.border, shape)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            color = colors.text,
            style = AgentTheme.type.meta,
        )
    }
}
