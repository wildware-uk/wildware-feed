package dev.wildware.agents.app.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Every colour the app draws with, named for what it is for rather than what it looks like.
 *
 * Filled in three times: light, dark, and a high contrast version of either.
 */
@Immutable
data class AgentColors(
    val isDark: Boolean,
    /** The window behind everything. */
    val background: Color,
    /** Soft colour washes painted over [background]; empty for a flat background. */
    val glows: List<Glow>,
    /** Cards and panels that sit on the background. */
    val surface: Color,
    /** Things lifted above a surface: menus, the composer, the selected card. */
    val surfaceRaised: Color,
    /** Wells set into a surface: code, text fields, quoted text. */
    val surfaceSunken: Color,
    val sidebar: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    /** The "waiting on you" colour, used only for questions the owner must answer. */
    val waiting: Color,
    val onWaiting: Color,
    val waitingSoft: Color,
    val info: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val online: Color,
    val focus: Color,
    val code: CodeColors,
    /** Agent identity colours. An agent keeps the same one everywhere. */
    val agents: List<Color>,
) {
    fun agent(index: Int): Color = agents[index.mod(agents.size)]
}

/** A soft radial wash of colour, placed by fractions of the window size. */
@Immutable
data class Glow(val color: Color, val x: Float, val y: Float, val radius: Float)

@Immutable
data class CodeColors(
    val background: Color,
    val text: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val literal: Color,
    val punctuation: Color,
)

/**
 * The high contrast version of a palette: text goes to full strength, quiet text and lines move
 * most of the way towards it, signal colours move away from the background, and washes are
 * dropped so nothing sits behind words.
 */
fun AgentColors.highContrast(): AgentColors {
    val ink = if (isDark) Color.White else Color.Black
    fun Color.firm() = lerp(this, ink, if (isDark) 0.2f else 0.3f)
    return copy(
        accent = accent.firm(),
        waiting = waiting.firm(),
        info = info.firm(),
        success = success.firm(),
        warning = warning.firm(),
        error = error.firm(),
        glows = emptyList(),
        border = lerp(border, text, 0.55f),
        borderStrong = lerp(borderStrong, ink, 0.7f),
        text = ink,
        textMuted = lerp(textMuted, ink, 0.6f),
        textFaint = lerp(textFaint, ink, 0.55f),
        focus = ink,
        code = run {
            // Some light themes keep a dark code block, so code text follows the block, not the page.
            val codeInk = if (code.background.luminance() < 0.5f) Color.White else Color.Black
            code.copy(text = codeInk, comment = lerp(code.comment, codeInk, 0.5f))
        },
    )
}
