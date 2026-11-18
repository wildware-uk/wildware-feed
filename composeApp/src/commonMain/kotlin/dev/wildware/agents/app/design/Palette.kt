package dev.wildware.agents.app.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * The app's look: dark-first and precise. Near-black panels with hairline edges, one lime
 * highlight, facts set in mono. Light mode keeps the same structure on cool white with ink accents.
 */

internal val Shapes = AgentShapes(
    small = RoundedCornerShape(5.dp),
    medium = RoundedCornerShape(7.dp),
    large = RoundedCornerShape(10.dp),
)

internal fun elevationFor(dark: Boolean): AgentElevation {
    val none = Lift(0.dp, 0.dp, Color.Transparent)
    return AgentElevation(
        flat = none,
        card = none,
        raised = Lift(20.dp, 6.dp, Color.Black.copy(alpha = if (dark) 0.6f else 0.08f)),
        overlay = Lift(48.dp, 16.dp, Color.Black.copy(alpha = if (dark) 0.7f else 0.16f)),
    )
}

internal fun colorsFor(dark: Boolean): AgentColors = if (dark) DarkColors else LightColors

internal val DarkColors = AgentColors(
    isDark = true,
    background = Color(0xFF0A0B0D),
    glows = listOf(Glow(Color(0xFFC6F169).copy(alpha = 0.035f), x = 0.55f, y = 0.0f, radius = 0.6f)),
    surface = Color(0xFF111215),
    surfaceRaised = Color(0xFF16181C),
    surfaceSunken = Color(0xFF0D0E10),
    sidebar = Color(0xFF0D0E10),
    border = Color(0xFF1F2126),
    borderStrong = Color(0xFF2C2F36),
    text = Color(0xFFECEDEF),
    textMuted = Color(0xFF9A9EA7),
    textFaint = Color(0xFF656973),
    accent = Color(0xFFC6F169),
    onAccent = Color(0xFF0B0D06),
    accentSoft = Color(0xFF1C2410),
    waiting = Color(0xFFF5B84A),
    onWaiting = Color(0xFF1A1103),
    waitingSoft = Color(0xFF231B0C),
    info = Color(0xFF7DA9FF),
    success = Color(0xFF5BD69A),
    warning = Color(0xFFF5B84A),
    error = Color(0xFFFF6B6B),
    online = Color(0xFF5BD69A),
    focus = Color(0xFFC6F169),
    code = CodeColors(
        background = Color(0xFF0D0E10),
        text = Color(0xFFD9DBDF),
        keyword = Color(0xFFC6F169),
        string = Color(0xFF7DD3C0),
        comment = Color(0xFF5E636D),
        literal = Color(0xFFF5B84A),
        punctuation = Color(0xFF8D919A),
    ),
    agents = listOf(
        Color(0xFF7DA9FF), Color(0xFFC6F169), Color(0xFFF5B84A),
        Color(0xFFFF8FA3), Color(0xFF6EE7D6), Color(0xFFB794F6),
    ),
)

internal val LightColors = AgentColors(
    isDark = false,
    background = Color(0xFFF6F6F7),
    glows = emptyList(),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFF2F2F4),
    sidebar = Color(0xFFEFEFF1),
    border = Color(0xFFE3E4E8),
    borderStrong = Color(0xFFCBCDD3),
    text = Color(0xFF0E0F12),
    textMuted = Color(0xFF5B5F68),
    textFaint = Color(0xFF8A8E97),
    accent = Color(0xFF0E0F12),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFEAF6D3),
    waiting = Color(0xFFB86E00),
    onWaiting = Color(0xFFFFFFFF),
    waitingSoft = Color(0xFFFFF4E0),
    info = Color(0xFF2F62D9),
    success = Color(0xFF168454),
    warning = Color(0xFFB86E00),
    error = Color(0xFFD12C2C),
    online = Color(0xFF1DA162),
    focus = Color(0xFF2F62D9),
    code = CodeColors(
        background = Color(0xFF0E0F12),
        text = Color(0xFFD9DBDF),
        keyword = Color(0xFFC6F169),
        string = Color(0xFF7DD3C0),
        comment = Color(0xFF6B707A),
        literal = Color(0xFFF5B84A),
        punctuation = Color(0xFF8D919A),
    ),
    agents = listOf(
        Color(0xFF2F62D9), Color(0xFF4F8A10), Color(0xFFB86E00),
        Color(0xFFD1456A), Color(0xFF0F8F80), Color(0xFF7A4FD6),
    ),
)
