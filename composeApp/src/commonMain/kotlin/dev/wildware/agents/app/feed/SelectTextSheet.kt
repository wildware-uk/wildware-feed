package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.pressable

/**
 * "Select text" on a phone: the card's words, all of them selected, with the system's own handles
 * and Copy menu, so the owner can pick out a line, as in iMessage. Done (or a tap outside, or the
 * back swipe) puts the feed back.
 */
@Composable
fun SelectTextSheet(ui: FeedUi, selecting: SelectingText, safeArea: SafeArea) {
    val colors = AgentTheme.colors
    val close = { ui.selecting = null }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = close),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(AgentTheme.shapes.large)
                .background(colors.surfaceRaised)
                // Taps inside the sheet are the text's, not the scrim's.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = safeArea.bottom + 14.dp)
                .semantics { paneTitle = "Select text" },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Select text", Modifier.weight(1f), color = colors.text, style = AgentTheme.type.bodyStrong)
                val shape = AgentTheme.shapes.pill
                Text(
                    "Done",
                    Modifier.clip(shape).pressable("Done", shape, labelIsShown = true, onClick = close).padding(horizontal = 12.dp, vertical = 8.dp),
                    color = colors.accent,
                    style = AgentTheme.type.label,
                )
            }
            val state = rememberTextFieldState(selecting.text, TextRange(0, selecting.text.length))
            val focus = remember { FocusRequester() }
            BasicTextField(
                state = state,
                readOnly = true,
                textStyle = AgentTheme.type.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                lineLimits = TextFieldLineLimits.MultiLine(),
                modifier = Modifier
                    .padding(top = 6.dp, end = 8.dp)
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .focusRequester(focus),
            )
            // Focused, so the selection shows with its handles and the system's Copy menu.
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}
