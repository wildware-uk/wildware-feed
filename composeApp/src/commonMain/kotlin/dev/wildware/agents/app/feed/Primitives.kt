package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import dev.wildware.agents.app.design.touchTarget
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wildware.agents.app.design.AgentColors
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Lift
import dev.wildware.agents.app.design.Space
import dev.wildware.agents.app.design.pressable
import androidx.compose.ui.draw.dropShadow

/** The window background: a flat colour plus a faint wash of light at the top. */
fun Modifier.windowBackground(colors: AgentColors): Modifier = this
    .background(colors.background)
    .drawBehind {
        colors.glows.forEach { glow ->
            val center = Offset(size.width * glow.x, size.height * glow.y)
            val radius = size.maxDimension * glow.radius
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glow.color, glow.color.copy(alpha = 0f)),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }

/** A soft shadow under a shape, for things lifted above the page. */
fun Modifier.lift(lift: Lift, shape: Shape): Modifier =
    if (lift.blur == 0.dp) this
    else dropShadow(shape, Shadow(radius = lift.blur, color = lift.color, offset = DpOffset(0.dp, lift.offsetY)))

fun AgentColors.level(level: Level): Color = when (level) {
    Level.Info -> info
    Level.Success -> success
    Level.Warning -> warning
    Level.Error -> error
}

fun AgentColors.status(status: WorkStatus): Color = when (status) {
    WorkStatus.Todo -> textMuted
    WorkStatus.InProgress -> warning
    WorkStatus.Blocked -> error
    WorkStatus.Done -> success
    WorkStatus.Cancelled -> textFaint
}

/** A round badge with the agent's initial on its identity colour. */
@Composable
fun Avatar(author: Author, size: Dp, modifier: Modifier = Modifier, showPresence: Boolean = false) {
    val colors = AgentTheme.colors
    val hue = when (author) {
        is Author.AgentAuthor -> colors.agent(author.agent.hue)
        is Author.Owner -> colors.text
    }
    val shape = RoundedCornerShape(size * 0.26f)
    val fill = Brush.linearGradient(listOf(hue.copy(alpha = 0.22f), hue.copy(alpha = 0.12f)))
    val letterColor = if (author is Author.Owner) colors.background else hue
    val ownerFill = Brush.linearGradient(listOf(colors.text, colors.text))
    Box(modifier.size(size).clearAndSetSemantics { }) {
        Box(
            Modifier
                .size(size)
                .clip(shape)
                .background(if (author is Author.Owner) ownerFill else fill)
                .then(
                    if (author !is Author.Owner)
                        Modifier.border(1.dp, hue.copy(alpha = 0.35f), shape) else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = author.name.first().uppercase(),
                color = letterColor,
                style = AgentTheme.type.label.copy(
                    fontSize = (size.value * 0.42f).sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = (size.value * 0.42f).sp,
                ),
            )
        }
        if (showPresence && author is Author.AgentAuthor) {
            PresenceDot(
                author.agent.presence,
                Modifier.align(Alignment.BottomEnd).padding(0.dp),
                ring = colors.surface,
                size = (size * 0.32f).coerceAtLeast(9.dp),
            )
        }
    }
}

@Composable
fun PresenceDot(presence: Presence, modifier: Modifier = Modifier, ring: Color, size: Dp = 9.dp) {
    val colors = AgentTheme.colors
    val fill = when (presence) {
        Presence.Online -> colors.online
        Presence.Idle -> colors.warning
        Presence.Offline -> colors.textFaint.copy(alpha = 0.5f)
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(ring)
            .padding(size * 0.2f)
            .clip(CircleShape)
            .background(fill),
    )
}

/** The level as a word with its colour, never colour alone. */
@Composable
fun LevelTag(level: Level, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val c = colors.level(level)
    Row(
        modifier
            .clip(AgentTheme.shapes.small)
            .border(1.dp, c.copy(alpha = 0.35f), AgentTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c))
        Text(level.label.uppercase(), Modifier.padding(start = 5.dp), color = c, style = AgentTheme.type.overline)
    }
}

/**
 * A solid red "Critical" chip, louder than [HighChip]: the agent marked this critical, which pages
 * the owner's phones that allow it. Read aloud with the card's header.
 */
@Composable
fun CriticalChip(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(AgentTheme.shapes.pill)
            .background(CriticalRed)
            .padding(horizontal = 9.dp, vertical = 3.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Critical" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(dev.wildware.agents.app.design.Icons.Bell, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        Text("CRITICAL", Modifier.padding(start = 4.dp), color = Color.White, style = AgentTheme.type.overline)
    }
}

/** The one red behind white for anything critical, in either theme: white on it is over 5:1. */
val CriticalRed = Color(0xFFC62828)

/** A red "High" chip: the agent marked this high priority. Read aloud with the card's header. */
@Composable
fun HighChip(modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val c = colors.error
    Row(
        modifier
            .clip(AgentTheme.shapes.pill)
            .background(c.copy(alpha = if (colors.isDark) 0.18f else 0.10f))
            .border(1.dp, c.copy(alpha = 0.45f), AgentTheme.shapes.pill)
            .padding(horizontal = 9.dp, vertical = 3.dp)
            .semantics(mergeDescendants = true) { contentDescription = "High priority" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(dev.wildware.agents.app.design.Icons.ArrowUp, contentDescription = null, tint = c, modifier = Modifier.size(12.dp))
        Text("HIGH", Modifier.padding(start = 4.dp), color = c, style = AgentTheme.type.overline)
    }
}

@Composable
fun StatusChip(status: WorkStatus, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val c = colors.status(status)
    Row(
        modifier
            .clip(AgentTheme.shapes.pill)
            .background(c.copy(alpha = if (colors.isDark) 0.16f else 0.10f))
            .padding(horizontal = 9.dp, vertical = 3.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Status: ${status.label}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusGlyph(status, c)
        Text(status.label.uppercase(), Modifier.padding(start = 5.dp), color = c, style = AgentTheme.type.overline)
    }
}

/** A tiny shape per status, so status reads without colour. */
@Composable
private fun StatusGlyph(status: WorkStatus, color: Color) {
    val icon = when (status) {
        WorkStatus.Done -> dev.wildware.agents.app.design.Icons.Check
        WorkStatus.Blocked, WorkStatus.Cancelled -> dev.wildware.agents.app.design.Icons.Close
        WorkStatus.InProgress -> dev.wildware.agents.app.design.Icons.Clock
        WorkStatus.Todo -> dev.wildware.agents.app.design.Icons.Plus
    }
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
}

/** A count of unread things. Spoken as words, not just a number. */
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier, emphasised: Boolean = false, what: String = "unread") {
    if (count <= 0) return
    val colors = AgentTheme.colors
    val bg = if (emphasised) colors.accent else colors.text.copy(alpha = if (colors.isDark) 0.12f else 0.07f)
    val fg = if (emphasised) colors.onAccent else colors.textMuted
    Box(
        modifier
            .sizeIn(minWidth = 20.dp, minHeight = 20.dp)
            .clip(AgentTheme.shapes.pill)
            .background(bg)
            .padding(horizontal = 6.dp)
            .clearAndSetSemantics { contentDescription = "$count $what" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = fg,
            style = AgentTheme.type.meta.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, lineHeight = 14.sp),
        )
    }
}

/** A heading for a run of cards ("Today"). Marked as a heading so screen readers can jump between them. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = AgentTheme.colors.textFaint) {
    Text(
        text.uppercase(),
        modifier.semantics { heading() },
        color = color,
        style = AgentTheme.type.overline,
    )
}

/** An icon-only button: always a spoken label and a 44pt target, however small it looks. */
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = AgentTheme.colors.textMuted,
    iconSize: Dp = 18.dp,
    onClick: () -> Unit = {},
) {
    val shape = AgentTheme.shapes.small
    Box(
        modifier.clip(shape).pressable(label, shape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** [Destructive] is for what can't be undone, such as Delete. */
enum class ButtonTone { Primary, Secondary, Quiet, Waiting, Destructive }

@Composable
fun AppButton(
    text: String,
    tone: ButtonTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val (bg, fg, border) = when (tone) {
        ButtonTone.Primary -> Triple(colors.accent, colors.onAccent, Color.Transparent)
        ButtonTone.Waiting -> Triple(colors.waiting, colors.onWaiting, Color.Transparent)
        ButtonTone.Secondary -> Triple(Color.Transparent, colors.text, colors.borderStrong)
        ButtonTone.Quiet -> Triple(Color.Transparent, colors.textMuted, Color.Transparent)
        ButtonTone.Destructive -> Triple(colors.error, Color.White, Color.Transparent)
    }
    Row(
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .then(
                if (enabled) Modifier.pressable(text, shape, labelIsShown = true, onClick = onClick)
                else Modifier.touchTarget().semantics(mergeDescendants = true) { disabled() }.alpha(0.45f),
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.padding(end = 6.dp).size(16.dp))
        }
        Text(text, color = fg, style = AgentTheme.type.label, maxLines = 1)
        if (trailing != null) {
            Text(
                trailing,
                Modifier.padding(start = 8.dp),
                color = fg.copy(alpha = 0.6f),
                style = AgentTheme.type.meta,
            )
        }
    }
}

/** A small filter or tag chip. */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    count: Int? = null,
    onClick: () -> Unit = {},
) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    val bg = if (selected) colors.text.copy(alpha = if (colors.isDark) 0.12f else 0.08f) else Color.Transparent
    val fg = if (selected) colors.text else colors.textMuted
    val border = if (selected) Color.Transparent else colors.border
    Box(
        modifier
            .clip(shape)
            .pressable(text, shape, role = Role.Tab, labelIsShown = true, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(shape)
                .background(bg)
                .border(1.dp, border, shape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, color = fg, style = AgentTheme.type.label, maxLines = 1, softWrap = false)
            if (count != null) {
                Text(
                    count.toString(),
                    Modifier.padding(start = 6.dp),
                    color = fg.copy(alpha = 0.6f),
                    style = AgentTheme.type.meta,
                )
            }
        }
    }
}

@Composable
fun MetaText(text: String, modifier: Modifier = Modifier, color: Color = AgentTheme.colors.textFaint, style: TextStyle = AgentTheme.type.meta) {
    Text(text, modifier, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
