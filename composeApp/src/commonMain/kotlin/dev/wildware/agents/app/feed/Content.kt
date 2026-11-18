package dev.wildware.agents.app.feed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxTheme
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.CodeColors
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable

/**
 * A card's markdown body, drawn with the theme's type and colours. Parsed straight
 * away rather than in the background: bodies are short, and a card that changes height a frame
 * after it appears makes the feed jump.
 */
@Composable
fun MarkdownBody(markdown: String, modifier: Modifier = Modifier, color: Color = AgentTheme.colors.text) {
    val colors = AgentTheme.colors
    val type = AgentTheme.type
    val state = rememberMarkdownState(markdown, immediate = true)
    val code = colors.code
    val highlights = remember(code) { Highlights.Builder().theme(code.toSyntaxTheme()) }
    val inlineCodeBackground = colors.text.copy(alpha = if (colors.isDark) 0.10f else 0.06f)
    val inlineCode = type.code.copy(fontSize = type.body.fontSize * 0.88f, color = colors.text)
    val copy = rememberCopyState()
    val annotator = rememberInlineCodeAnnotator(inlineCode, inlineCodeBackground, copy)
    SelectableText(modifier, copy) {
    Markdown(
        markdownState = state,
        modifier = Modifier.fillMaxWidth(),
        colors = markdownColor(
            text = color,
            codeBackground = code.background,
            inlineCodeBackground = inlineCodeBackground,
            dividerColor = colors.border,
        ),
        annotator = annotator,
        typography = markdownTypography(
            text = type.body.copy(color = color),
            paragraph = type.body.copy(color = color),
            bullet = type.body,
            list = type.body,
            ordered = type.body,
            code = type.code.copy(color = code.text),
            inlineCode = inlineCode,
            h1 = type.title, h2 = type.title, h3 = type.bodyStrong, h4 = type.bodyStrong, h5 = type.bodyStrong, h6 = type.bodyStrong,
            quote = type.body.copy(color = colors.textMuted),
        ),
        padding = markdownPadding(
            block = 6.dp, list = 2.dp, listItemTop = 3.dp, listItemBottom = 0.dp,
            codeBlock = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 6.dp),
        ),
        components = markdownComponents(
            codeFence = { model ->
                MarkdownCodeFence(model.content, model.node, model.typography.code) { text, language, style ->
                    CodeBlock(text, language) {
                        MarkdownHighlightedCode(
                            code = text,
                            language = language,
                            style = style,
                            highlightsBuilder = highlights,
                            immediate = true,
                        )
                    }
                }
            },
        ),
    )
    }
}

/** Plain text, such as the owner's own post, selectable the same way a markdown body is. */
@Composable
fun PlainBody(text: String, modifier: Modifier = Modifier, color: Color = AgentTheme.colors.text) {
    SelectableText(modifier) { Text(text, color = color, style = AgentTheme.type.body) }
}

private fun CodeColors.toSyntaxTheme() = SyntaxTheme(
    key = "agents",
    code = text.toArgb() and 0xFFFFFF,
    keyword = keyword.toArgb() and 0xFFFFFF,
    string = string.toArgb() and 0xFFFFFF,
    literal = literal.toArgb() and 0xFFFFFF,
    comment = comment.toArgb() and 0xFFFFFF,
    metadata = literal.toArgb() and 0xFFFFFF,
    multilineComment = comment.toArgb() and 0xFFFFFF,
    punctuation = punctuation.toArgb() and 0xFFFFFF,
    mark = punctuation.toArgb() and 0xFFFFFF,
)

/** A code block with its language and a copy button above it. Long lines scroll sideways. */
@Composable
private fun CodeBlock(code: String, language: String?, content: @Composable () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val headerText = colors.code.text.copy(alpha = 0.55f)
    Column(
        Modifier
            .padding(vertical = 6.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.code.background)
            .border(1.dp, colors.border, shape),
    ) {
        // The label and button are not part of the code, so a drag selection skips them.
        DisableSelection {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.code.text.copy(alpha = 0.05f))
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (language ?: "code").lowercase(),
                    Modifier.weight(1f),
                    color = headerText,
                    style = AgentTheme.type.code.copy(fontSize = 11.sp),
                )
                // Exactly the code: no fences, no language, no trailing newline.
                CopyCodeButton(code.trimEnd('\n', '\r'), tint = headerText)
            }
        }
        Box(Modifier.trimVertical(8.dp)) {
            // The highlighter brings its own background; the block above already paints one.
            androidx.compose.runtime.CompositionLocalProvider(
                LocalMarkdownColors provides markdownColor(codeBackground = Color.Transparent),
            ) { content() }
        }
    }
}

/** Drops [amount] from the top and bottom of the child's layout, to undo padding it brings along. */
private fun Modifier.trimVertical(amount: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val trim = amount.roundToPx()
    layout(placeable.width, (placeable.height - 2 * trim).coerceAtLeast(0)) { placeable.place(0, -trim) }
}

/** Emoji reactions, plus a button to add one. Each says who reacted when read aloud. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionRow(reactions: List<Reaction>, modifier: Modifier = Modifier, showAdd: Boolean = true) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.pill
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        reactions.forEach { r ->
            val bg = if (r.mine) colors.accent.copy(alpha = if (colors.isDark) 0.18f else 0.10f) else Color.Transparent
            val border = if (r.mine) colors.accent.copy(alpha = 0.45f) else colors.border
            val spoken = "${r.spoken}, ${r.count}" + if (r.mine) ", including you. Double tap to remove yours." else ". Double tap to add yours."
            Box(
                Modifier.clip(shape).pressable(spoken, shape, role = Role.Button) {},
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.clip(shape).background(bg).border(1.dp, border, shape).padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(r.emoji, style = AgentTheme.type.label.copy(fontSize = 14.sp, lineHeight = 18.sp))
                    Text(
                        r.count.toString(),
                        Modifier.padding(start = 5.dp),
                        color = if (r.mine) colors.accent else colors.textMuted,
                        style = AgentTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
        if (showAdd) {
            Box(Modifier.clip(shape).pressable("Add a reaction", shape) {}, contentAlignment = Alignment.Center) {
                Box(
                    Modifier.clip(shape).border(1.dp, colors.border, shape).padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Smile, null, tint = colors.textFaint, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** "scout and relay have read this", with a live "relay is thinking" when an agent is replying. */
@Composable
fun AckLine(acks: Acks, modifier: Modifier = Modifier) {
    if (acks.read.isEmpty() && acks.thinking.isEmpty()) return
    val colors = AgentTheme.colors
    val parts = buildList {
        if (acks.thinking.isNotEmpty()) add("${names(acks.thinking)} ${if (acks.thinking.size == 1) "is" else "are"} thinking…")
        if (acks.read.isNotEmpty()) add("${names(acks.read)} ${if (acks.read.size == 1) "has" else "have"} read this")
    }
    val text = parts.joinToString(" · ")
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-5).dp)) {
            (acks.thinking + acks.read).distinct().take(3).forEach { agent ->
                Box(Modifier.clip(CircleShape).background(colors.surface).padding(1.5.dp)) {
                    Avatar(Author.AgentAuthor(agent), 16.dp)
                }
            }
        }
        if (acks.thinking.isNotEmpty()) {
            Box(Modifier.padding(start = 8.dp).size(6.dp).clip(CircleShape).background(colors.accent))
        }
        Text(
            text,
            Modifier.padding(start = 8.dp).clearAndSetSemantics { },
            color = colors.textFaint,
            style = AgentTheme.type.meta,
            maxLines = 1,
        )
    }
}

private fun names(agents: List<Agent>): String = when (agents.size) {
    1 -> agents[0].name
    2 -> "${agents[0].name} and ${agents[1].name}"
    else -> "${agents[0].name}, ${agents[1].name} and ${agents.size - 2} more"
}

/** One attached picture at its own shape, with its file name under it. */
@Composable
fun AttachmentView(attachment: Attachment, modifier: Modifier = Modifier, onOpen: () -> Unit = {}) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Column(modifier) {
        Box(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .aspectRatio(attachment.aspect)
                .clip(shape)
                .border(1.dp, colors.border, shape)
                .pressable("Image: ${attachment.altText}. Open full size", shape, role = Role.Image, onClick = onOpen),
        ) {
            AttachmentImage(attachment, Modifier.fillMaxSize())
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Image, null, tint = colors.textFaint, modifier = Modifier.size(14.dp))
            MetaText(attachment.title, Modifier.padding(start = 6.dp))
        }
    }
}

/** The colours of one stand-in picture. */
private data class ScenePalette(
    val sky: List<Color>,
    val glow: Color,
    val body: Color,
    val hills: List<Color>,
    /** Where the sun or moon sits, as fractions of the picture. */
    val bodyX: Float,
    val bodyY: Float,
    val stars: Boolean = false,
)

private fun Scene.palette() = when (this) {
    Scene.Dusk -> ScenePalette(
        sky = listOf(Color(0xFF2B2250), Color(0xFF7A4C8C), Color(0xFFF29A76), Color(0xFFFCD9A8)),
        glow = Color(0xFFFFF1D6), body = Color(0xFFFFF4DF),
        hills = listOf(Color(0xFF6B3F6E), Color(0xFF4A2B57), Color(0xFF2B1B3B)),
        bodyX = 0.68f, bodyY = 0.62f,
    )
    Scene.Dawn -> ScenePalette(
        sky = listOf(Color(0xFF8FB8DE), Color(0xFFC9DCEB), Color(0xFFF7D9B9), Color(0xFFFBE7C6)),
        glow = Color(0xFFFFF6E0), body = Color(0xFFFFE9A8),
        hills = listOf(Color(0xFF8DB58A), Color(0xFF5E8F66), Color(0xFF3B6B4B)),
        bodyX = 0.28f, bodyY = 0.66f,
    )
    Scene.Night -> ScenePalette(
        sky = listOf(Color(0xFF070B1E), Color(0xFF111A3A), Color(0xFF1F2B57), Color(0xFF2E3B6B)),
        glow = Color(0xFF9FB4FF), body = Color(0xFFE8EDFF),
        hills = listOf(Color(0xFF1B2448), Color(0xFF131A36), Color(0xFF0B1026)),
        bodyX = 0.72f, bodyY = 0.30f,
        stars = true,
    )
}

/**
 * A painted stand-in for an attached picture, until the image loader lands. Each [Scene] looks
 * clearly different, so a grid of them can be told apart.
 */
/**
 * The pictures on a card, laid out like the website's media grid: one at its own shape, two side
 * by side, three as one large and two small, four or more as two by two with "+N" on the last.
 * Each opens the viewer at that picture.
 */
@Composable
fun MediaGrid(images: List<Attachment>, modifier: Modifier = Modifier, onOpen: (Int) -> Unit) {
    if (images.size == 1) {
        AttachmentView(images[0], modifier) { onOpen(0) }
        return
    }
    val gap = 4.dp
    val shape = AgentTheme.shapes.medium
    Column(modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (images.size == 2) 2f else 4f / 3f).clip(shape)) {
            when (images.size) {
                2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    GridTile(images, 0, Modifier.weight(1f).fillMaxHeight(), onOpen)
                    GridTile(images, 1, Modifier.weight(1f).fillMaxHeight(), onOpen)
                }
                3 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    GridTile(images, 0, Modifier.weight(2f).fillMaxHeight(), onOpen)
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                        GridTile(images, 1, Modifier.weight(1f).fillMaxWidth(), onOpen)
                        GridTile(images, 2, Modifier.weight(1f).fillMaxWidth(), onOpen)
                    }
                }
                else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    for (row in 0..1) {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            for (col in 0..1) {
                                val i = row * 2 + col
                                GridTile(images, i, Modifier.weight(1f).fillMaxHeight(), onOpen, more = if (i == 3) images.size - 4 else 0)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Image, null, tint = AgentTheme.colors.textFaint, modifier = Modifier.size(14.dp))
            MetaText("${images.size} pictures", Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun GridTile(images: List<Attachment>, index: Int, modifier: Modifier, onOpen: (Int) -> Unit, more: Int = 0) {
    val image = images[index]
    val spoken = "Picture ${index + 1} of ${images.size}: ${image.altText}" + if (more > 0) ", and $more more" else ""
    Box(modifier.pressable(spoken, RectangleShape, role = Role.Image) { onOpen(index) }) {
        AttachmentImage(image, Modifier.fillMaxSize())
        if (more > 0) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Text("+$more", color = Color.White, style = AgentTheme.type.title)
            }
        }
    }
}

@Composable
internal fun SceneArt(scene: Scene, modifier: Modifier) {
    val p = scene.palette()
    Canvas(modifier.clipToBounds().semantics { role = Role.Image }) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(p.sky))
        val center = Offset(w * p.bodyX, h * p.bodyY)
        if (p.stars) {
            for (i in 0 until 40) {
                val x = ((i * 73) % 97) / 97f * w
                val y = ((i * 41) % 59) / 59f * h * 0.6f
                drawCircle(Color.White.copy(alpha = 0.35f + (i % 3) * 0.2f), radius = h * (0.004f + (i % 2) * 0.003f), center = Offset(x, y))
            }
        }
        drawCircle(
            Brush.radialGradient(listOf(p.glow.copy(alpha = if (p.stars) 0.35f else 1f), p.glow.copy(alpha = 0f)), center = center, radius = h * 0.55f),
            radius = h * 0.55f,
            center = center,
        )
        val r = h * 0.11f
        if (p.stars) {
            // The moon is a disc with a bite cut out of it, so the sky shows through the bite.
            val disc = Path().apply { addOval(Rect(center, r)) }
            val bite = Path().apply { addOval(Rect(center + Offset(r * 0.45f, -r * 0.3f), r * 0.9f)) }
            drawPath(Path.combine(PathOperation.Difference, disc, bite), p.body)
        } else {
            drawCircle(p.body, radius = r, center = center)
        }
        fun hill(base: Float, amp: Float, phase: Float, color: Color) {
            val path = Path().apply {
                moveTo(0f, h)
                lineTo(0f, h * base)
                val steps = 48
                for (i in 0..steps) {
                    val x = w * i / steps
                    val y = h * base - h * amp * kotlin.math.sin((i.toFloat() / steps) * 6.28f * 0.9f + phase)
                    lineTo(x, y)
                }
                lineTo(w, h)
                close()
            }
            drawPath(path, color)
        }
        hill(0.72f, 0.06f, 0.4f, p.hills[0])
        hill(0.80f, 0.07f, 2.1f, p.hills[1])
        hill(0.90f, 0.05f, 4.0f, p.hills[2])
    }
}
