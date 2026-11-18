package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme

/** True while the app runs on the built-in sample data ("Try a demo") rather than a dashboard. */
val LocalDemo = staticCompositionLocalOf { false }

/** A small "Demo" by the connection, so it is never mistaken for a real dashboard. */
@Composable
fun DemoBadge(modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.pill
    Text(
        "Demo",
        modifier
            .clip(shape)
            .background(colors.waiting.copy(alpha = if (colors.isDark) 0.18f else 0.12f))
            .border(1.dp, colors.waiting.copy(alpha = 0.5f), shape)
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .semantics { contentDescription = "Demo: sample data, nothing is sent" },
        color = colors.waiting,
        style = AgentTheme.type.meta,
    )
}
