package dev.wildware.agents.app.feed

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.CodeHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import dev.wildware.agents.app.design.CodeColors
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Syntax highlighting for code blocks, worked out ahead of time and off the UI thread.
 *
 * Highlighting a block is the costliest thing a card draws: one block took as long as ten
 * paragraphs. So it is done with the markdown parse ([MarkdownCache.warm]), not while drawing.
 * The highlighter is run with a stand-in theme whose colours only say which kind of token each
 * run is; drawing swaps in the real theme's colours, so one result serves light and dark alike.
 */
@OptIn(ExperimentalAtomicApi::class)
object CodeHighlights {
    private const val Capacity = 300

    /** Copied on write, as [MarkdownCache] is: a reader never waits for a highlight elsewhere. */
    private val held = AtomicReference<Map<Key, List<CodeHighlight>>>(emptyMap())

    private data class Key(val code: String, val language: String?)

    /** Told of every highlight worked out, for tests that check none happens while drawing. */
    internal var onHighlight: ((String) -> Unit)? = null

    fun get(code: String, language: String?): List<CodeHighlight> = held.load()[Key(code, language)] ?: highlight(code, language)

    /** Highlights every code block in a parsed body that isn't held yet. Safe from any thread. */
    internal fun warm(root: ASTNode, content: String) {
        for ((code, language) in codeBlocks(root, content)) {
            if (Key(code, language) !in held.load()) highlight(code, language)
        }
    }

    private fun highlight(code: String, language: String?): List<CodeHighlight> {
        onHighlight?.invoke(code)
        val lang = language?.let { SyntaxLanguage.getByName(it) } ?: SyntaxLanguage.DEFAULT
        val found = Highlights.Builder().code(code).language(lang).theme(KindTheme).build().getHighlights()
        while (true) {
            val before = held.load()
            val next = LinkedHashMap<Key, List<CodeHighlight>>(before.size + 1)
            before.entries.drop(if (before.size >= Capacity) Capacity / 4 else 0).forEach { next[it.key] = it.value }
            next[Key(code, language)] = found
            if (held.compareAndSet(before, next)) return found
        }
    }

    /**
     * The code and language of each fenced block, exactly as the renderer's MarkdownCodeFence hands
     * them to the block it draws, so what is warmed here is what drawing asks for.
     */
    internal fun codeBlocks(root: ASTNode, content: String): List<Pair<String, String?>> {
        val found = mutableListOf<Pair<String, String?>>()
        fun walk(node: ASTNode) {
            if (node.type == MarkdownElementTypes.CODE_FENCE) {
                fenceText(node, content)?.let(found::add)
                return
            }
            node.children.forEach(::walk)
        }
        walk(root)
        return found
    }

    private fun fenceText(node: ASTNode, content: String): Pair<String, String?>? {
        val language = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(content)?.toString()
        val children = node.children
        if (children.size < 3) return null
        val start = children[if (language != null && children.size > 3) 3 else 2].startOffset
        val end = children[(children.size - 2).coerceAtLeast(2)].endOffset
        if (end < start) return null
        return content.subSequence(start, end).toString().replaceIndent() to language
    }

    /** A theme whose colours are token kinds, 1 to 9, for [CodeColors.colorOf] to turn into real colours. */
    private val KindTheme = SyntaxTheme(
        key = "agents-kinds",
        code = 1, keyword = 2, string = 3, literal = 4, comment = 5,
        metadata = 6, multilineComment = 7, punctuation = 8, mark = 9,
    )
}

/** The real colour for a token kind from [CodeHighlights]' stand-in theme. */
private fun CodeColors.colorOf(kind: Int): Color = when (kind and 0xFFFFFF) {
    2 -> keyword
    3 -> string
    4, 6 -> literal
    5, 7 -> comment
    8, 9 -> punctuation
    else -> text
}

/** [code]'s highlights in [colors], as the text to draw. */
internal fun highlighted(code: String, language: String?, colors: CodeColors): AnnotatedString {
    val runs = CodeHighlights.get(code, language)
    return buildAnnotatedString {
        append(code)
        for (run in runs) {
            val at = run.location
            if (at.start < 0 || at.end > code.length || at.start >= at.end) continue
            when (run) {
                is ColorHighlight -> addStyle(SpanStyle(color = colors.colorOf(run.rgb)), at.start, at.end)
                is BoldHighlight -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), at.start, at.end)
            }
        }
    }
}

/**
 * A highlighted code block's text, laid out as the renderer's own highlighted block lays it out:
 * one line of text per line of code, scrolling sideways when long. Drawing only applies colours.
 */
@Composable
internal fun HighlightedCode(code: String, language: String?, style: TextStyle, colors: CodeColors, padding: PaddingValues) {
    val text = remember(code, language, colors) { highlighted(code, language, colors) }
    Text(text, Modifier.horizontalScroll(rememberScrollState()).padding(padding), style = style)
}
