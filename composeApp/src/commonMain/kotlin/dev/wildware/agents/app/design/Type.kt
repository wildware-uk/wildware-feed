package dev.wildware.agents.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.wildware.agents.app.resources.Res
import dev.wildware.agents.app.resources.geist_400
import dev.wildware.agents.app.resources.geist_500
import dev.wildware.agents.app.resources.geist_600
import dev.wildware.agents.app.resources.geist_700
import dev.wildware.agents.app.resources.geist_mono_400
import dev.wildware.agents.app.resources.geist_mono_500
import org.jetbrains.compose.resources.Font

/**
 * The type scale. Every size is in sp, so all of it grows with the system text size
 * (Dynamic Type on iPhone, the font scale setting on desktop).
 */
@Immutable
data class AgentType(
    /** The big name at the top of a screen. */
    val display: TextStyle,
    /** A card's headline. */
    val title: TextStyle,
    /** Section labels like "Today" or "Projects". */
    val overline: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    /** Names, times and other small facts about a card. */
    val meta: TextStyle,
    val code: TextStyle,
)

@Composable
internal fun geistFamily() = FontFamily(
    Font(Res.font.geist_400, FontWeight.Normal),
    Font(Res.font.geist_500, FontWeight.Medium),
    Font(Res.font.geist_600, FontWeight.SemiBold),
    Font(Res.font.geist_700, FontWeight.Bold),
)

@Composable
internal fun geistMonoFamily() = FontFamily(
    Font(Res.font.geist_mono_400, FontWeight.Normal),
    Font(Res.font.geist_mono_500, FontWeight.Medium),
)

private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

internal fun style(
    family: FontFamily,
    size: TextUnit,
    weight: FontWeight,
    lineHeight: TextUnit,
    tracking: TextUnit = 0.em,
) = TextStyle(
    fontFamily = family,
    fontSize = size,
    fontWeight = weight,
    lineHeight = lineHeight,
    letterSpacing = tracking,
    lineHeightStyle = tight,
)

/** Geist for everything you read, Geist Mono for facts and code: small, dense and precise. */
@Composable
internal fun agentType(): AgentType {
    val sans = geistFamily()
    val mono = geistMonoFamily()
    return AgentType(
        display = style(sans, 22.sp, FontWeight.SemiBold, 28.sp, (-0.02).em),
        title = style(sans, 15.sp, FontWeight.SemiBold, 21.sp, (-0.01).em),
        overline = style(mono, 11.sp, FontWeight.Medium, 14.sp, 0.06.em),
        body = style(sans, 14.5.sp, FontWeight.Normal, 22.sp, (-0.005).em),
        bodyStrong = style(sans, 14.5.sp, FontWeight.Medium, 22.sp, (-0.005).em),
        label = style(sans, 13.sp, FontWeight.Medium, 18.sp),
        meta = style(mono, 11.5.sp, FontWeight.Normal, 15.sp),
        code = style(mono, 12.5.sp, FontWeight.Normal, 19.sp),
    )
}
