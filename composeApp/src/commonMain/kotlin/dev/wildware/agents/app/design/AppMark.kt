package dev.wildware.agents.app.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp

/**
 * The app icon as a picture, where the platform can read it (the desktop has
 * packaging/icons/agents.png on its classpath); null elsewhere.
 */
expect fun appIconBitmap(): ImageBitmap?

/**
 * The app's mark in the sidebar, drawer and sign-in screen: the app icon itself, so changing the
 * icon file changes the mark too. Where the icon can't be read, a plain tile with an "A".
 */
@Composable
fun AppMark(size: Dp, modifier: Modifier = Modifier) {
    val icon = appIconBitmap()
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = modifier.size(size).clearAndSetSemantics { })
        return
    }
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    Box(
        modifier.size(size).clip(shape).background(colors.surfaceSunken).border(Dp(1f), colors.borderStrong, shape).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text("A", color = colors.text, style = AgentTheme.type.title)
    }
}

