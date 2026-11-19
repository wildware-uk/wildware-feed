package dev.wildware.agents.app.design

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import dev.wildware.agents.app.design.gesture.LocalSwipeGuard
import dev.wildware.agents.app.design.gesture.SwipeGuard

/** What the person has asked the system for. Read from the platform, overridable in settings. */
@Immutable
data class AccessibilityPrefs(
    val reduceMotion: Boolean = false,
    val highContrast: Boolean = false,
)

/** Everything a screen needs to draw itself. */
@Immutable
data class AgentThemeValues(
    val colors: AgentColors,
    val type: AgentType,
    val shapes: AgentShapes,
    val elevation: AgentElevation,
    val motion: AgentMotion,
)

private val LocalAgentTheme = staticCompositionLocalOf<AgentThemeValues> {
    error("AgentTheme is missing: wrap the screen in AgentTheme { }")
}

/** Reads the current theme, in the same style as [MaterialTheme]. */
object AgentTheme {
    val current: AgentThemeValues
        @Composable @ReadOnlyComposable get() = LocalAgentTheme.current
    val colors: AgentColors
        @Composable @ReadOnlyComposable get() = LocalAgentTheme.current.colors
    val type: AgentType
        @Composable @ReadOnlyComposable get() = LocalAgentTheme.current.type
    val shapes: AgentShapes
        @Composable @ReadOnlyComposable get() = LocalAgentTheme.current.shapes
    val elevation: AgentElevation
        @Composable @ReadOnlyComposable get() = LocalAgentTheme.current.elevation
    val motion: AgentMotion
        @Composable @ReadOnlyComposable get() = LocalAgentTheme.current.motion
}

@Composable
fun AgentTheme(
    dark: Boolean,
    accessibility: AccessibilityPrefs = AccessibilityPrefs(),
    content: @Composable () -> Unit,
) {
    val base = colorsFor(dark)
    val colors = if (accessibility.highContrast) base.highContrast() else base
    val type = agentType()
    val values = remember(colors, type, dark, accessibility.reduceMotion) {
        AgentThemeValues(
            colors = colors,
            type = type,
            shapes = Shapes,
            elevation = elevationFor(dark),
            motion = AgentMotion(reduced = accessibility.reduceMotion),
        )
    }
    // One per window: a theme nested inside keeps the outer one, so its boxes guard the same rows.
    val swipeGuard = LocalSwipeGuard.current ?: remember { SwipeGuard() }
    // Material components (text fields, menus) still read MaterialTheme, so it gets the same values.
    MaterialTheme(
        colorScheme = colors.toMaterial(),
        typography = type.toMaterial(),
    ) {
        CompositionLocalProvider(
            LocalAgentTheme provides values,
            LocalSwipeGuard provides swipeGuard,
            LocalContentColor provides colors.text,
            // iOS-style: dim (and shrink, if small) on touch, rather than Android's ripple.
            LocalIndication provides PressIndication(colors.text, reduced = accessibility.reduceMotion),
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = colors.accent,
                backgroundColor = colors.accent.copy(alpha = 0.28f),
            ),
            content = content,
        )
    }
}

/**
 * Draws [content] in other [colors], keeping the rest of the theme: how a project's own colours
 * reach the part of the screen that is that project's.
 */
@Composable
fun AgentColorsOverride(colors: AgentColors, content: @Composable () -> Unit) {
    val current = LocalAgentTheme.current
    val values = remember(current, colors) { current.copy(colors = colors) }
    MaterialTheme(colorScheme = colors.toMaterial(), typography = MaterialTheme.typography) {
        CompositionLocalProvider(
            LocalAgentTheme provides values,
            LocalContentColor provides colors.text,
            LocalIndication provides PressIndication(colors.text, reduced = current.motion.reduced),
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = colors.accent,
                backgroundColor = colors.accent.copy(alpha = 0.28f),
            ),
            content = content,
        )
    }
}

private fun AgentColors.toMaterial(): ColorScheme {
    val scheme = if (isDark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = text,
        secondary = textMuted,
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceSunken,
        onSurfaceVariant = textMuted,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceRaised,
        surfaceContainerLow = surface,
        outline = borderStrong,
        outlineVariant = border,
        error = error,
    )
}

private fun AgentType.toMaterial(): Typography {
    val t = Typography()
    fun TextStyle.like(other: TextStyle) = other.merge(this)
    return t.copy(
        displayLarge = display, displayMedium = display, displaySmall = display,
        headlineLarge = display, headlineMedium = title, headlineSmall = title,
        titleLarge = title, titleMedium = bodyStrong, titleSmall = label,
        bodyLarge = body, bodyMedium = body, bodySmall = meta,
        labelLarge = label, labelMedium = label.like(meta), labelSmall = meta,
    )
}
