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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.DropdownMenu
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
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.CodeColors
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable

/**
 * Markdown parsed once per text, not once per time a card is drawn. A card that scrolls away and
 * back, or redraws because a reply arrived, finds its body here instead of parsing it again on the
 * UI thread. [warm] lets the feed parse what is coming before it is on screen, off the UI thread.
 */
@OptIn(ExperimentalAtomicApi::class)
object MarkdownCache {
    private const val Capacity = 600

    /** Copied on write: readers on the UI thread never wait for a parse on another. */
    private val parsed = AtomicReference<Map<String, State>>(emptyMap())

    fun get(markdown: String): State = parsed.load()[markdown] ?: parse(markdown)

    /** Parses what isn't held yet. Safe from any thread. */
    fun warm(texts: Iterable<String>) {
        for (text in texts) if (text !in parsed.load()) parse(text)
    }

    /** Told of every parse, for tests that check none happens on the UI thread. */
    internal var onParse: ((String) -> Unit)? = null

    private fun parse(markdown: String): State {
        onParse?.invoke(markdown)
        val state = parseMarkdown(markdown)
        // Code blocks are highlighted here too, so drawing one only applies colours.
        if (state is State.Success) runCatching { CodeHighlights.warm(state.node, state.content) }
        while (true) {
            val held = parsed.load()
            val next = LinkedHashMap<String, State>(held.size + 1)
            // Oldest out first, so a long session keeps what is on screen now.
            held.entries.drop(if (held.size >= Capacity) Capacity / 4 else 0).forEach { next[it.key] = it.value }
            next[markdown] = state
            if (parsed.compareAndSet(held, next)) return state
        }
    }
}

/**
 * A card's markdown body, drawn with the theme's type and colours. Parsed straight
 * away rather than in the background: bodies are short, and a card that changes height a frame
 * after it appears makes the feed jump. The parse is kept in [MarkdownCache], and the renderer's
 * styles are kept across redraws, so a card that redraws with the same text skips its body.
 */
/** Space around a code block's text, inside its frame. */
internal val CodeBlockPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 6.dp)

/** The widest a run of prose is set, so a wide window widens the card, not the lines. */
val ProseMaxWidth = 820.dp

@Composable
fun MarkdownBody(markdown: String, modifier: Modifier = Modifier, color: Color = AgentTheme.colors.text) {
    val colors = AgentTheme.colors
    val type = AgentTheme.type
    val state = remember(markdown) { MarkdownCache.get(markdown) }
    val code = colors.code
    val inlineCodeBackground = colors.text.copy(alpha = if (colors.isDark) 0.10f else 0.06f)
    val inlineCode = type.code.copy(fontSize = type.body.fontSize * 0.88f, color = colors.text)
    val copy = rememberCopyState()
    val annotator = rememberInlineCodeAnnotator(inlineCode, inlineCodeBackground, copy)
    SelectableText(modifier, copy) {
    Markdown(
        state = state,
        // Lines past about 90 characters are hard to read, however wide the window.
        modifier = Modifier.widthIn(max = ProseMaxWidth).fillMaxWidth(),
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
            codeBlock = CodeBlockPadding,
        ),
        // Each piece of text would otherwise animate its size: a cost on every card, for nothing.
        animations = markdownAnimations(animateTextSize = { this }),
        components = markdownComponents(
            codeFence = { model ->
                MarkdownCodeFence(model.content, model.node, model.typography.code) { text, language, style ->
                    CodeBlock(text, language) {
                        HighlightedCode(text, language, style, code, CodeBlockPadding)
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
        content()
    }
}


/**
 * Emoji reactions, plus a button to add one. Tapping a reaction adds the owner's own, or takes it
 * back if they already reacted with it. Each says who reacted when read aloud.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionRow(reactions: List<Reaction>, targetId: String?, modifier: Modifier = Modifier, showAdd: Boolean = true) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.pill
    val ui = LocalFeedUi.current
    val react = { emoji: String -> if (targetId != null) ui?.emit(FeedEvent.React(targetId, emoji)); Unit }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        reactions.forEach { r ->
            val bg = if (r.mine) colors.accent.copy(alpha = if (colors.isDark) 0.18f else 0.10f) else Color.Transparent
            val border = if (r.mine) colors.accent.copy(alpha = 0.45f) else colors.border
            val spoken = "${r.spoken}, ${r.count}" + if (r.mine) ", including you. Double tap to remove yours." else ". Double tap to add yours."
            Box(
                Modifier.clip(shape).pressable(spoken, shape, role = Role.Button) { react(r.emoji) },
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
        if (showAdd) AddReaction(react)
    }
}

/** The smile button: opens a row of emoji to react with. Escape or a tap outside closes it. */
@Composable
private fun AddReaction(react: (String) -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.pill
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.clip(shape).pressable("Add a reaction", shape) { open = true }, contentAlignment = Alignment.Center) {
            Box(
                Modifier.clip(shape).border(1.dp, if (open) colors.borderStrong else colors.border, shape).padding(horizontal = 9.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Smile, null, tint = if (open) colors.text else colors.textFaint, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = AgentTheme.shapes.pill,
            containerColor = colors.surfaceRaised,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
        ) {
            // The first emoji takes focus, so arrow keys, Enter and Escape work from the keyboard.
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
            Row(
                Modifier
                    .onKeyEvent { e -> if (e.key == Key.Escape) { open = false; true } else false }
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MenuReactions.forEachIndexed { i, emoji ->
                    Box(
                        Modifier
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .clip(CircleShape).pressable("React with $emoji", CircleShape) {
                            open = false
                            react(emoji)
                        },
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 20.sp) }
                }
            }
        }
    }
}

/**
 * "scout marked this done · relay has read this · delivered to lumen", with a live "relay is
 * thinking…" when an agent is replying. Each agent once, by the furthest thing it said.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AckLine(acks: Acks, modifier: Modifier = Modifier) {
    if (acks.isEmpty) return
    val colors = AgentTheme.colors
    val parts = remember(acks) { ackParts(acks) }
    val text = remember(parts) { parts.joinToString(" · ") { it.words } }
    FlowRow(
        modifier.semantics(mergeDescendants = true) { contentDescription = text },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-5).dp)) {
            (acks.thinking + acks.done + acks.read + acks.delivered).distinct().take(3).forEach { agent ->
                Box(Modifier.clip(CircleShape).background(colors.surface).padding(1.5.dp)) {
                    Avatar(Author.AgentAuthor(agent), 16.dp)
                }
            }
        }
        parts.forEach { part ->
            Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
                when (part.kind) {
                    AckKind.Thinking -> Box(Modifier.padding(end = 6.dp).size(6.dp).clip(CircleShape).background(colors.accent))
                    // A tick for done, in the success colour; a faint one for read: the same shape, so
                    // they read as steps of one thing. A paper plane for what the dashboard did, not the agent.
                    AckKind.Done -> Icon(Icons.Check, null, tint = colors.success, modifier = Modifier.padding(end = 4.dp).size(13.dp))
                    AckKind.Read -> Icon(Icons.Check, null, tint = colors.textFaint, modifier = Modifier.padding(end = 4.dp).size(12.dp))
                    AckKind.Delivered -> Icon(Icons.Delivered, null, tint = colors.textFaint, modifier = Modifier.padding(end = 4.dp).size(11.dp))
                }
                Text(
                    part.words,
                    color = if (part.kind == AckKind.Done) colors.textMuted else colors.textFaint,
                    style = AgentTheme.type.meta,
                    maxLines = 1,
                )
            }
        }
    }
}

private enum class AckKind { Thinking, Done, Read, Delivered }

private class AckPart(val kind: AckKind, val words: String)

/** Furthest along first: who is on it now, who finished, who read it, who has it. */
private fun ackParts(acks: Acks): List<AckPart> = buildList {
    if (acks.thinking.isNotEmpty()) add(AckPart(AckKind.Thinking, "${names(acks.thinking)} ${if (acks.thinking.size == 1) "is" else "are"} thinking…"))
    if (acks.done.isNotEmpty()) add(AckPart(AckKind.Done, "${names(acks.done)} marked this done"))
    if (acks.read.isNotEmpty()) add(AckPart(AckKind.Read, "${names(acks.read)} ${if (acks.read.size == 1) "has" else "have"} read this"))
    if (acks.delivered.isNotEmpty()) add(AckPart(AckKind.Delivered, "delivered to ${names(acks.delivered)}"))
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
    val inline = playingInline(attachment.video)
    Column(modifier) {
        Box(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .aspectRatio(attachment.aspect)
                .clip(shape)
                .border(1.dp, colors.border, shape)
                .then(
                    // A placeholder has nothing to open: it says what happened.
                    // Playing, the player's own controls take the taps.
                    if (attachment.isPlaceholder || inline) {
                        Modifier
                    } else {
                        Modifier.pressable(
                            if (attachment.video != null) tileLabel(attachment, 0, 1) else "Image: ${attachment.altText}. Open full size",
                            shape,
                            role = if (attachment.video != null) Role.Button else Role.Image,
                            onClick = onOpen,
                        )
                    },
                ),
        ) {
            val video = attachment.video?.takeUnless { attachment.isPlaceholder }
            when {
                video != null && inline -> InlineVideo(video, Modifier.fillMaxSize())
                video != null -> VideoFace(attachment, Modifier.fillMaxSize())
                else -> AttachmentImage(attachment, Modifier.fillMaxSize())
            }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (attachment.video != null) Icons.Play else Icons.Image, null, tint = colors.textFaint, modifier = Modifier.size(14.dp))
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
            val videos = images.count { it.video != null }
            MetaText(
                when (videos) {
                    0 -> "${images.size} pictures"
                    images.size -> "${images.size} videos"
                    else -> (images.size - videos).let { pictures ->
                        "$pictures ${if (pictures == 1) "picture" else "pictures"}, $videos ${if (videos == 1) "video" else "videos"}"
                    }
                },
                Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun GridTile(images: List<Attachment>, index: Int, modifier: Modifier, onOpen: (Int) -> Unit, more: Int = 0) {
    val image = images[index]
    val spoken = tileLabel(image, index, images.size) + if (more > 0) ", and $more more" else ""
    val inline = playingInline(image.video)
    // A placeholder opens nothing, unless it is the "+N more" one, which still opens the rest.
    val opens = (!image.isPlaceholder || more > 0) && !inline
    val press = if (opens) Modifier.pressable(spoken, RectangleShape, role = if (image.video != null) Role.Button else Role.Image) { onOpen(index) } else Modifier
    Box(modifier.then(press)) {
        val video = image.video?.takeUnless { image.isPlaceholder }
        when {
            video != null && inline -> InlineVideo(video, Modifier.fillMaxSize())
            video != null -> VideoFace(image, Modifier.fillMaxSize(), small = true)
            else -> AttachmentImage(image, Modifier.fillMaxSize())
        }
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
