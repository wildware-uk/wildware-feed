package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable

/**
 * Where a card's work has got to, and the way to move it: the five states, and "Not work" for a
 * card that is work. Nothing changes on screen until the dashboard says so, so two devices never
 * disagree about what is being worked on.
 */
@Composable
fun StatusPicker(ui: FeedUi, pick: StatusPick, safeArea: SafeArea) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val close = { ui.statusPick = null }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = close),
        )
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = safeArea.top + 96.dp, start = 12.dp, end = 12.dp)
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .lift(AgentTheme.elevation.overlay, shape)
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.borderStrong, shape)
                .semantics { paneTitle = "Work state" }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                        close()
                        true
                    } else {
                        false
                    }
                }
                .padding(vertical = 6.dp),
        ) {
            Text(
                if (pick.current == null) "Mark as work" else "Work state",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
                color = colors.text,
                style = AgentTheme.type.label,
            )
            WorkStatus.entries.forEach { status ->
                StatusRow(status, chosen = status == pick.current) { ui.emit(FeedEvent.SetStatus(pick.id, status)) }
            }
            if (pick.current != null) {
                Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().heightIn(max = 1.dp).background(colors.border))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable("Not work", AgentTheme.shapes.small, labelIsShown = true) { ui.emit(FeedEvent.SetStatus(pick.id, null)) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(26.dp))
                    Text("Not work", color = colors.textMuted, style = AgentTheme.type.body)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(status: WorkStatus, chosen: Boolean, onPick: () -> Unit) {
    val colors = AgentTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { selected = chosen }
            .pressable(status.label, AgentTheme.shapes.small, role = Role.RadioButton, labelIsShown = true, onClick = onPick)
            .background(if (chosen) colors.accent.copy(alpha = if (colors.isDark) 0.10f else 0.06f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(AgentTheme.shapes.pill).background(colors.status(status)))
        Spacer(Modifier.width(16.dp))
        Text(status.label, Modifier.weight(1f), color = colors.text, style = AgentTheme.type.body)
        if (chosen) Icon(Icons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
    }
}
