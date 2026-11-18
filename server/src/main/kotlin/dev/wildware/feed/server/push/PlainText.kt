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

    fun of(markdown: String): String {
        val lines = mutableListOf<String>()
        var bullet = ""
        fun visit(node: Node) {
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
                    children(node).forEach(::visit)
                }
                is Paragraph, is Heading, is TableCell -> {
                    lines += bullet + inline(node)
                    bullet = ""
                }
                is FencedCodeBlock -> lines += node.literal.trimEnd()
                is IndentedCodeBlock -> lines += node.literal.trimEnd()
                is HtmlBlock -> {
                    lines += bullet + node.literal.trimEnd()
                    bullet = ""
                }
                else -> children(node).forEach(::visit)
            }
        }
        visit(parser.parse(markdown))
        return lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun children(node: Node) = generateSequence(node.firstChild) { it.next }.toList()

    private fun inline(node: Node): String = buildString {
        fun walk(n: Node) {
            when (n) {
                is Text -> append(n.literal)
                is Code -> append(n.literal)
                is HtmlInline -> append(n.literal)
                is SoftLineBreak, is HardLineBreak -> append('\n')
                is Image -> append(altText(n).ifEmpty { "image" })
                else -> children(n).forEach(::walk)
            }
        }
        children(node).forEach(::walk)
    }

    private fun altText(image: Image): String = buildString {
        fun walk(n: Node) {
            when (n) {
                is Text -> append(n.literal)
                is Code -> append(n.literal)
                is SoftLineBreak, is HardLineBreak -> append('\n')
                else -> children(n).forEach(::walk)
            }
        }
        children(image).forEach(::walk)
    }

    /** `notificationText`: plain text, runs of spaces and tabs as one, cut to 140 UTF-16 units with an ellipsis. */
    fun notification(markdown: String, max: Int = 140): String {
        val plain = of(markdown).replace(Regex("[ \t]+"), " ")
        return if (plain.length <= max) plain else plain.substring(0, max).trimEnd() + "…"
    }
}
