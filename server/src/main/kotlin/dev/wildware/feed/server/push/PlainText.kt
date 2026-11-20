package dev.wildware.feed.server.push

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser

/**
 * Markdown to the plain text a notification shows (TS `src/domain/plain-text.ts`, which walks
 * markdown-it tokens): one line per paragraph, heading or table cell, list items prefixed `• ` or
 * `N. `, code blocks kept, images as their alt text or "image", lines trimmed, blank ones dropped.
 * HTML is shown as written, as markdown-it does with `html: false`.
 */
object PlainText {
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).build()

    /**
     * Every walk here keeps its own stack rather than recursing, so a body nested thousands deep
     * (blockquotes, lists, links) can't run the thread out of stack.
     */
    fun of(markdown: String): String {
        val lines = mutableListOf<String>()
        var bullet = ""
        preOrder(parser.parse(markdown)) { node ->
            when (node) {
                is ListItem -> {
                    val list = node.parent
                    bullet = if (list is OrderedList) {
                        val start = list.markerStartNumber ?: 1
                        var index = 0
                        var sibling = list.firstChild
                        while (sibling != null && sibling !== node) { index++; sibling = sibling.next }
                        "${start + index}. "
                    } else {
                        "• "
                    }
                    true
                }
                is Paragraph, is Heading, is TableCell -> {
                    lines += bullet + inline(node)
                    bullet = ""
                    false
                }
                is FencedCodeBlock -> { lines += node.literal.trimEnd(); false }
                is IndentedCodeBlock -> { lines += node.literal.trimEnd(); false }
                is HtmlBlock -> {
                    lines += bullet + node.literal.trimEnd()
                    bullet = ""
                    false
                }
                else -> true
            }
        }
        return lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /**
     * Visit [root] and what is under it in document order, without recursion. [visit] answers
     * whether to go on into a node's children.
     */
    private inline fun preOrder(root: Node, visit: (Node) -> Boolean) {
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (!visit(node)) continue
            var child = node.lastChild
            while (child != null) { stack.addLast(child); child = child.previous }
        }
    }

    /** The text under [node], [node] itself left out. */
    private inline fun textUnder(node: Node, crossinline leaf: StringBuilder.(Node) -> Boolean): String = buildString {
        var child = node.lastChild
        val stack = ArrayDeque<Node>()
        while (child != null) { stack.addLast(child); child = child.previous }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (leaf(n)) continue
            var c = n.lastChild
            while (c != null) { stack.addLast(c); c = c.previous }
        }
    }

    private fun inline(node: Node): String = textUnder(node) { n ->
        when (n) {
            is Text -> { append(n.literal); true }
            is Code -> { append(n.literal); true }
            is HtmlInline -> { append(n.literal); true }
            is SoftLineBreak, is HardLineBreak -> { append('\n'); true }
            is Image -> { append(altText(n).ifEmpty { "image" }); true }
            else -> false
        }
    }

    private fun altText(image: Image): String = textUnder(image) { n ->
        when (n) {
            is Text -> { append(n.literal); true }
            is Code -> { append(n.literal); true }
            is SoftLineBreak, is HardLineBreak -> { append('\n'); true }
            else -> false
        }
    }

    /** `notificationText`: plain text, runs of spaces and tabs as one, cut to 140 UTF-16 units with an ellipsis. */
    fun notification(markdown: String, max: Int = 140): String {
        val plain = of(markdown).replace(Regex("[ \t]+"), " ")
        return if (plain.length <= max) plain else plain.substring(0, max).trimEnd() + "…"
    }
}
