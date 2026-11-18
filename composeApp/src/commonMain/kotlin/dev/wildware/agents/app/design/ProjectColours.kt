package dev.wildware.agents.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * A project's own colours over the app's, as the website draws them: the owner picks at most a
 * page colour and an accent, and everything else (cards, borders, text) is worked out from the page
 * colour, so any colour gives a readable screen. Text takes whichever ink reads better on it,
 * reaching 4.5:1 where any ink can.
 */
object ProjectColours {
    private val LightInk = Color(0xFFF6F8FA)
    private val DarkInk = Color(0xFF161A21)
    private const val Readable = 4.5f

    /** #rgb or #rrggbb as a colour; anything else is no colour at all. */
    fun parse(hex: String?): Color? {
        val digits = hex?.removePrefix("#")?.takeIf { hex.startsWith("#") } ?: return null
        val full = when (digits.length) {
            3 -> digits.map { "$it$it" }.joinToString("")
            6 -> digits
            else -> return null
        }
        val value = full.toLongOrNull(16) ?: return null
        return Color(0xFF000000 or value)
    }

    fun contrast(one: Color, other: Color): Float {
        val a = one.luminance()
        val b = other.luminance()
        return (max(a, b) + 0.05f) / (min(a, b) + 0.05f)
    }

    /** The text colour [background] can carry. */
    fun ink(background: Color): Color {
        val soft = if (contrast(background, LightInk) >= contrast(background, DarkInk)) LightInk else DarkInk
        if (contrast(background, soft) >= Readable) return soft
        val pure = if (contrast(background, Color.White) >= contrast(background, Color.Black)) Color.White else Color.Black
        return if (contrast(background, pure) > contrast(background, soft)) pure else soft
    }

    private fun mix(from: Color, to: Color, amount: Float) = Color(
        red = from.red + (to.red - from.red) * amount,
        green = from.green + (to.green - from.green) * amount,
        blue = from.blue + (to.blue - from.blue) * amount,
    )

    /** A visible step from [from] toward [toward]; away from it when already at the end. */
    private fun step(from: Color, toward: Color, amount: Float, fallback: Color): Color {
        val moved = mix(from, toward, amount)
        if (moved.toArgb8() != from.toArgb8()) return moved
        if (toward.toArgb8() != from.toArgb8()) return toward
        return mix(from, fallback, amount)
    }

    private fun Color.toArgb8() = listOf(red, green, blue).map { (it * 255).toInt() }

    /** [base] with a project's [background] and [accent] laid over it; [base] itself when it has neither. */
    fun over(base: AgentColors, background: String?, accent: String?): AgentColors {
        val page = parse(background)
        val mark = parse(accent)
        if (page == null && mark == null) return base
        var colors = base
        if (page != null) {
            val text = ink(page)
            colors = colors.copy(
                isDark = page.luminance() < 0.18f,
                background = page,
                glows = emptyList(),
                surface = step(page, Color.White, 0.05f, Color.Black),
                surfaceRaised = step(page, Color.White, 0.08f, Color.Black),
                surfaceSunken = step(page, Color.Black, 0.06f, Color.White),
                sidebar = step(page, Color.Black, 0.04f, Color.White),
                border = mix(page, text, 0.22f),
                borderStrong = mix(page, text, 0.34f),
                text = text,
                textMuted = mix(page, text, 0.62f),
                textFaint = mix(page, text, 0.45f),
            )
        }
        if (mark != null) {
            colors = colors.copy(accent = mark, onAccent = ink(mark), accentSoft = mix(colors.background, mark, 0.18f))
        }
        return colors
    }
}

/** Draws [content] in a project's own colours, or as it is when the project has none. */
@Composable
fun ProjectColours(background: String?, accent: String?, content: @Composable () -> Unit) {
    if (ProjectColours.parse(background) == null && ProjectColours.parse(accent) == null) return content()
    val base = AgentTheme.colors
    val colors = remember(base, background, accent) { ProjectColours.over(base, background, accent) }
    AgentColorsOverride(colors, content)
}
