package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wildware.agents.EmbedBlock
import dev.wildware.agents.EmbedImage
import dev.wildware.agents.EmbedMark
import dev.wildware.agents.ResolvedEmbed
import dev.wildware.agents.app.design.AgentColors
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.pressable

/** One embed on a card: its look filled in, and the server its uploaded pictures come from. */
@Immutable
data class Embed(val look: ResolvedEmbed, val serverUrl: String = "")

/** What an embed is drawn in: the look's own colours where it set them, the app's where it didn't. */
private class EmbedPaint(
    val background: Color,
    val foreground: Color,
    val muted: Color,
    val border: Color,
    val accent: Color,
)

private fun hex(value: String?): Color? {
    val v = value?.removePrefix("#") ?: return null
    if (v.length != 6) return null
    return v.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

private fun paintFor(look: ResolvedEmbed, colors: AgentColors) = EmbedPaint(
    background = hex(look.style.background) ?: colors.surfaceSunken,
    foreground = hex(look.style.foreground) ?: colors.text,
    muted = hex(look.style.muted) ?: colors.textMuted,
    border = hex(look.style.border) ?: colors.border,
    accent = hex(look.style.accent) ?: colors.accent,
)

/** Every embed on a card, one under another. */
@Composable
fun Embeds(embeds: List<Embed>, modifier: Modifier = Modifier) {
    if (embeds.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        embeds.forEach { EmbedCard(it) }
    }
}

/**
 * A card shown in the look of where it came from (a Slack message, a GitHub comment, a Jira ticket),
 * as the agent described it. Colours, frame and blocks are the look's; links open in the browser.
 */
@Composable
fun EmbedCard(embed: Embed, modifier: Modifier = Modifier) {
    val look = embed.look
    val paint = paintFor(look, AgentTheme.colors)
    val radius = when (look.style.radius) {
        "none" -> 0.dp
        "sm" -> 4.dp
        "lg" -> 14.dp
        else -> 8.dp
    }
    val shape: Shape = if (radius == 0.dp) RectangleShape else RoundedCornerShape(radius)
    val pad = when (look.style.padding) {
        "tight" -> 8.dp
        "roomy" -> 16.dp
        else -> 12.dp
    }
    val family = when (look.style.font) {
        "serif" -> FontFamily.Serif
        "mono" -> FontFamily.Monospace
        else -> null
    }
    val spoken = listOfNotNull(look.badge ?: look.mark?.label ?: look.typeName, "embed").joinToString(" ")
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(paint.background)
            .then(if (look.style.bordered) Modifier.border(1.dp, paint.border, shape) else Modifier)
            .semantics(mergeDescendants = false) { contentDescription = spoken },
    ) {
        if (look.style.stripe) Box(Modifier.width(4.dp).fillMaxHeight().background(paint.accent))
        Column(Modifier.weight(1f).padding(pad)) {
            EmbedTop(look, paint)
            val header = look.blocks.firstOrNull() as? EmbedBlock.Header
            val indent = if (look.indent && header != null) avatarSize(header.size) + 8.dp else 0.dp
            look.blocks.forEachIndexed { index, block ->
                // A linked footer's tap area already has room above its words.
                val gap = when {
                    block == EmbedBlock.Divider -> 2.dp
                    block is EmbedBlock.Footer && block.url != null -> 0.dp
                    else -> 6.dp
                }
                if (index > 0) Spacer(Modifier.height(gap))
                val inner = if (block is EmbedBlock.Header) Modifier else Modifier.padding(start = indent)
                Box(inner) { Block(block, paint, family, embed.serverUrl) }
            }
        }
    }
}

private fun avatarSize(size: EmbedBlock.Size): Dp = when (size) {
    EmbedBlock.Size.Sm -> 20.dp
    EmbedBlock.Size.Md -> 36.dp
    EmbedBlock.Size.Lg -> 48.dp
}

/** The source's mark and badge along the top, when the look has either. */
@Composable
private fun EmbedTop(look: ResolvedEmbed, paint: EmbedPaint) {
    val mark = look.mark
    val badge = look.badge
    if (mark == null && badge == null) return
    Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        when (mark) {
            is EmbedMark.Drawn -> MarkGlyph(mark.name, paint)
            is EmbedMark.Image -> Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(paint.border))
            null -> {}
        }
        val words = badge ?: mark?.label?.replaceFirstChar { it.uppercase() }
        if (words != null) {
            Text(
                listOfNotNull(look.badgeIcon, words).joinToString(" "),
                Modifier.padding(start = if (mark != null) 6.dp else 0.dp),
                color = paint.muted,
                style = AgentTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A source's mark as a small square in its colour with its initial: the app ships no brand art. */
@Composable
private fun MarkGlyph(name: String, paint: EmbedPaint) {
    val fill = when (name) {
        "slack" -> Color(0xFF4A154B)
        "github" -> Color(0xFF1F2328)
        "gitlab" -> Color(0xFFFC6D26)
        "jira" -> Color(0xFF0052CC)
        "confluence" -> Color(0xFF1868DB)
        "linear" -> Color(0xFF5E6AD2)
        "notion" -> Color(0xFF000000)
        "figma" -> Color(0xFFA259FF)
        else -> paint.muted
    }
    Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(fill), contentAlignment = Alignment.Center) {
        Text(
            if (name == "link") "↗" else name.first().uppercase(),
            color = Color.White,
            style = AgentTheme.type.meta.copy(fontWeight = FontWeight.Bold, fontSize = AgentTheme.type.meta.fontSize * 0.8f),
        )
    }
}

private fun imageUrl(image: EmbedImage, serverUrl: String): String? = when (image) {
    is EmbedImage.Url -> image.url
    is EmbedImage.Media -> if (serverUrl.isEmpty()) null else serverUrl.trimEnd('/') + "/media/${image.id}/thumb-640"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Block(block: EmbedBlock, paint: EmbedPaint, family: FontFamily?, serverUrl: String) {
    val type = AgentTheme.type
    fun sized(size: EmbedBlock.Size) = when (size) {
        EmbedBlock.Size.Sm -> type.label
        EmbedBlock.Size.Md -> type.body
        EmbedBlock.Size.Lg -> type.title
    }.let { if (family != null) it.copy(fontFamily = family) else it }
    when (block) {
        is EmbedBlock.Header -> Header(block, paint, family, serverUrl)
        is EmbedBlock.Heading -> Text(
            block.text,
            Modifier.semantics { heading() },
            color = paint.foreground,
            style = (if (block.level == 1) type.title else type.body).copy(fontWeight = FontWeight.SemiBold)
                .let { if (family != null) it.copy(fontFamily = family) else it },
        )
        is EmbedBlock.Text -> Text(
            block.text,
            color = when (block.tone) {
                EmbedBlock.Tone.Normal -> paint.foreground
                EmbedBlock.Tone.Muted -> paint.muted
                EmbedBlock.Tone.Accent -> paint.accent
            },
            style = sized(block.size).let { if (block.tone == EmbedBlock.Tone.Accent) it.copy(fontWeight = FontWeight.SemiBold) else it },
        )
        is EmbedBlock.Markdown -> MarkdownBody(block.text, color = paint.foreground)
        is EmbedBlock.Code -> Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(paint.foreground.copy(alpha = 0.06f))
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            Text(block.text, color = paint.foreground, style = type.code)
        }
        is EmbedBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(paint.border))
            Text(block.text, Modifier.padding(start = 10.dp), color = paint.muted, style = sized(EmbedBlock.Size.Md))
        }
        is EmbedBlock.Fields -> if (block.inline) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEach { (label, value) ->
                    Column {
                        Text(label, color = paint.muted, style = type.meta)
                        Text(value, color = paint.foreground, style = sized(EmbedBlock.Size.Sm))
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                block.items.forEach { (label, value) ->
                    Row {
                        Text(label, Modifier.width(96.dp), color = paint.muted, style = sized(EmbedBlock.Size.Sm))
                        Text(value, Modifier.weight(1f), color = paint.foreground, style = sized(EmbedBlock.Size.Sm))
                    }
                }
            }
        }
        is EmbedBlock.Chips -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.chips.forEach { chip ->
                val shape = when (block.style) {
                    EmbedBlock.ChipStyle.Pill -> CircleShape
                    EmbedBlock.ChipStyle.Lozenge -> RoundedCornerShape(3.dp)
                    EmbedBlock.ChipStyle.Tag -> RoundedCornerShape(6.dp)
                }
                val lozenge = block.style == EmbedBlock.ChipStyle.Lozenge
                Text(
                    if (lozenge) chip.uppercase() else chip,
                    Modifier
                        .clip(shape)
                        .background(paint.accent.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    color = paint.foreground,
                    style = type.meta.copy(fontWeight = if (lozenge) FontWeight.Bold else FontWeight.Medium),
                )
            }
        }
        is EmbedBlock.Image -> {
            val url = imageUrl(block.image, serverUrl)
            val picture = remember(url) { Attachment(block.alt ?: "Picture", block.alt ?: "Picture", 16f / 9f, url = url) }
            AttachmentImage(
                picture,
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .height(180.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .semantics { contentDescription = block.alt ?: "Picture" },
            )
        }
        EmbedBlock.Divider -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(paint.border))
        is EmbedBlock.Footer -> {
            val uris = LocalUriHandler.current
            val url = block.url
            val words = block.text ?: url.orEmpty()
            if (url == null) {
                Text(words, color = paint.muted, style = type.meta)
            } else {
                // The tap area is full size; the words sit in its middle, so the card gains no gap.
                Box(
                    Modifier.pressable("Open $words", RoundedCornerShape(4.dp), role = Role.Button) { uris.openUri(url) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(words, color = paint.accent, style = type.meta.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

@Composable
private fun Header(block: EmbedBlock.Header, paint: EmbedPaint, family: FontFamily?, serverUrl: String) {
    val type = AgentTheme.type
    val avatarShape: Shape = when (block.shape) {
        EmbedBlock.Shape.Circle -> CircleShape
        EmbedBlock.Shape.Rounded -> RoundedCornerShape(6.dp)
        EmbedBlock.Shape.Square -> RectangleShape
    }
    val size = avatarSize(block.size)
    val title = type.body.copy(fontWeight = FontWeight.Bold).let { if (family != null) it.copy(fontFamily = family) else it }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (block.bar) Modifier.clip(RoundedCornerShape(6.dp)).background(paint.accent.copy(alpha = 0.08f)).padding(8.dp) else Modifier),
        verticalAlignment = if (block.stacked) Alignment.Top else Alignment.CenterVertically,
    ) {
        block.avatar?.let { avatar ->
            val url = imageUrl(avatar, serverUrl)
            val picture = remember(url) { Attachment("Avatar", "", 1f, url = url) }
            AttachmentImage(picture, Modifier.size(size).clip(avatarShape))
            Spacer(Modifier.width(8.dp))
        }
        if (block.stacked) {
            Column(Modifier.weight(1f)) {
                block.title?.let { Text(it, color = paint.foreground, style = title) }
                block.subtitle?.let { Text(it, color = paint.muted, style = type.meta) }
                block.meta?.let { Text(it, color = paint.muted, style = type.meta) }
            }
        } else {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                block.title?.let { Text(it, color = paint.foreground, style = title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                block.subtitle?.let { Text(it, Modifier.padding(start = 6.dp), color = paint.muted, style = type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                block.meta?.let { Text(it, Modifier.padding(start = 6.dp), color = paint.muted, style = type.meta, maxLines = 1) }
            }
        }
    }
}
