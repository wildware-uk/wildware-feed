package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable
import kotlinx.coroutines.delay
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

/** How long "Copied" stays up after a copy. */
private const val CopiedMillis = 1600L

/**
 * Puts text on the clipboard and remembers that it just did, so the screen can say "Copied".
 * [copied] goes back to false a moment later.
 */
@Stable
class CopyState internal constructor(private val write: (String) -> Unit) {
    var copied by mutableStateOf(false)
        private set
    internal var serial by mutableIntStateOf(0)
        private set

    /** Where the copy was asked for, in the text's own coordinates; null from a key or button. */
    var at: Offset? by mutableStateOf(null)
        internal set

    fun copy(text: String) {
        write(text)
        copied = true
        serial++
    }

    internal fun clear() {
        copied = false
    }
}

@Suppress("DEPRECATION")
@Composable
fun rememberCopyState(): CopyState {
    val clipboard = LocalClipboardManager.current
    val state = remember(clipboard) { CopyState { clipboard.setText(AnnotatedString(it)) } }
    LaunchedEffect(state, state.serial) {
        if (state.copied) {
            delay(CopiedMillis)
            state.clear()
        }
    }
    return state
}

/**
 * Text the owner reads and may want to copy: a message, post or request body.
 *
 * Everything inside is one selection, so a drag can run across paragraphs, lists and code.
 * Desktop: drag, double-click a word, triple-click a paragraph, Ctrl/Cmd+C, right-click for Copy.
 * Phones: hold on the text for the system's handles and Copy menu.
 *
 * The text keeps its own gestures. A press that lands on it is not passed on to the card around
 * it, so holding text selects rather than opening the card's menu, a double-click picks a word
 * rather than reacting, and a right-click offers Copy rather than the card's actions. The card's
 * own gestures still work everywhere else on it (its header, title and footer), and from the
 * keyboard and screen readers. Horizontal swipes are left alone: the card still swipes.
 */
@Composable
fun SelectableText(
    modifier: Modifier = Modifier,
    copy: CopyState = rememberCopyState(),
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth().keepPressesForText(copy)) {
        SelectionContainer { content() }
        CopiedNotice(copy)
    }
}

/**
 * Takes the presses that land on text for the text alone. It runs after the text has seen them
 * (children see a press first) and before the card does; the card's tap, long-press and
 * right-click handlers ignore a press already taken. Moves are never taken, so scrolling and the
 * card's sideways swipe still begin on text.
 */
private fun Modifier.keepPressesForText(copy: CopyState) = pointerInput(copy) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            event.changes.forEach { change ->
                if (change.changedToDownIgnoreConsumed()) {
                    copy.at = change.position
                    change.consume()
                }
            }
        }
    }
}

/** A small "Copied" just above where the copy was asked for, read out by screen readers. */
@Composable
private fun BoxScope.CopiedNotice(copy: CopyState) {
    if (!copy.copied) return
    val colors = AgentTheme.colors
    val at = copy.at
    val gap = 6.dp
    val place = if (at == null) {
        Modifier.align(Alignment.TopEnd)
    } else {
        Modifier.layout { measurable, constraints ->
            val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(0, 0) {
                val x = (at.x.toInt() - p.width / 2).coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
                p.place(x, at.y.toInt() - p.height - gap.roundToPx())
            }
        }
    }
    Row(
        place
            .clip(AgentTheme.shapes.pill)
            .background(colors.text)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Check, null, tint = colors.background, modifier = Modifier.size(12.dp))
        Text(
            "Copied",
            Modifier.padding(start = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = colors.background,
            style = AgentTheme.type.meta,
        )
    }
}

/**
 * Draws `inline code` as before, and makes it copy itself when tapped, clicked or activated from
 * the keyboard. It is still ordinary text to a drag selection.
 */
@Composable
fun rememberInlineCodeAnnotator(style: TextStyle, background: Color, copy: CopyState): MarkdownAnnotator {
    val onCopy by rememberUpdatedState<(String) -> Unit>(copy::copy)
    val hover = AgentTheme.colors.accent.copy(alpha = 0.22f)
    return remember(style, background, hover) {
        val span = style.toSpanStyle().copy(background = background)
        val links = TextLinkStyles(style = span, hoveredStyle = SpanStyle(background = hover), pressedStyle = SpanStyle(background = hover))
        markdownAnnotator { content, node ->
            if (node.type != MarkdownElementTypes.CODE_SPAN) return@markdownAnnotator false
            val code = codeSpanText(content, node)
            withLink(LinkAnnotation.Clickable("copy-code", links) { onCopy(code) }) {
                withStyle(span) {
                    // Spaces either side give the background some room, as the default does.
                    append(' ')
                    append(code)
                    append(' ')
                }
            }
            true
        }
    }
}

/** The code inside a `code span`, without its backticks, spaced as CommonMark says. */
internal fun codeSpanText(content: String, node: ASTNode): String {
    val children = node.children
    if (children.size < 3) return ""
    val raw = content.substring(children[1].startOffset, children[children.size - 2].endOffset).replace('\n', ' ')
    val padded = raw.length >= 2 && raw.startsWith(' ') && raw.endsWith(' ') && raw.isNotBlank()
    return if (padded) raw.substring(1, raw.length - 1) else raw
}

/** The copy button on a code block: "Copy", then "Copied" for a moment. Announced when it copies. */
@Composable
fun CopyCodeButton(code: String, tint: Color, modifier: Modifier = Modifier) {
    val copy = rememberCopyState()
    val shape = AgentTheme.shapes.small
    val done = copy.copied
    Box(
        modifier
            .clip(shape)
            .pressable(if (done) "Copied" else "Copy code", shape) { copy.copy(code) }
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.clearAndSetSemantics { }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (done) Icons.Check else Icons.Copy,
                null,
                tint = if (done) AgentTheme.colors.success else tint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                if (done) "Copied" else "Copy",
                Modifier.padding(start = 5.dp),
                color = if (done) AgentTheme.colors.success else tint,
                style = AgentTheme.type.meta,
            )
        }
    }
}
