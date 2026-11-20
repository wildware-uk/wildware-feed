package dev.wildware.agents

/**
 * Agent markdown as plain text, for places that can't draw it: Spotlight, Siri and widgets.
 * The dashboard has the same rule server-side (`src/domain/plain-text.ts`): each block becomes
 * one line, list items keep a bullet, and real emphasis goes while a lone `*` or `_` stays.
 */
object PlainText {
    fun fromMarkdown(markdown: String): String =
        // Posted text is anyone's, so nothing in it may stop a card being drawn: should the
        // conversion still fail somehow, the card shows its words as written.
        try {
            convert(markdown)
        } catch (e: Throwable) {
            markdown.replace("\u0000", "").trim()
        }

    private fun convert(markdown: String): String {
        val lines = mutableListOf<String>()
        var fence: String? = null
        // U+0000 marks the text set aside in [inline], so posted text may not hold it.
        for (raw in markdown.replace("\u0000", "").replace("\r\n", "\n").split('\n')) {
            val trimmed = raw.trim()
            val open = fence
            if (open != null) {
                if (trimmed.startsWith(open)) fence = null else lines += raw.trimEnd()
                continue
            }
            val start = fenceStart.find(trimmed)
            if (start != null) {
                fence = start.value
                continue
            }
            if (trimmed.isEmpty() || isRule(trimmed) || isTableDivider(trimmed)) continue
            val line = block(trimmed)
            // The inline patterns can rescan a line from each place they start, so a long line
            // is left as written: its cost stays in step with its length, and no word is lost.
            lines += if (line.length <= INLINE_MAX) inline(line) else line
        }
        return lines.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /**
     * The first [count] sentences of [text], after skipping [skip] of them, as one line for
     * reading aloud or a small screen. Each line of plain text is at least one sentence, so a
     * bullet list reads as a list. Longer than [maxLength] and it is cut at a word, with an ellipsis.
     */
    fun sentences(text: String, count: Int, skip: Int = 0, maxLength: Int = 280): String {
        val picked = units(text)
            .drop(skip)
            .take(count)
            .map { if (it.last() in ".!?…:") it else "$it." }
            .joinToString(" ")
        return clip(picked, maxLength)
    }

    /** The first sentence as written, for a headline: no full stop added. */
    fun firstSentence(text: String, maxLength: Int = 120): String = clip(units(text).firstOrNull().orEmpty(), maxLength)

    private fun units(text: String): List<String> =
        text.split('\n')
            .map { it.removePrefix("• ").trim() }
            .filter { it.isNotEmpty() }
            .flatMap { line -> line.split(sentenceEnd).map(String::trim).filter(String::isNotEmpty) }

    fun clip(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        val cut = text.take(maxLength - 1)
        val atWord = cut.substringBeforeLast(' ', cut).trimEnd(',', ';', ':', ' ')
        return "$atWord…"
    }

    private fun block(line: String): String {
        var text = line
        text = withoutQuoteMarks(text)
        text = withoutClosingHashes(text.replace(heading, ""))
        bullet.find(text)?.let { return "• " + text.substring(it.range.last + 1) }
        numbered.find(text)?.let { return "${it.groupValues[1]}. " + text.substring(it.range.last + 1) }
        if (text.startsWith("|")) {
            text = text.trim('|').split('|').joinToString(" · ") { it.trim() }
        }
        return text
    }

    private fun inline(line: String): String {
        // Code and escaped characters are set aside first, so nothing inside them reads as emphasis.
        val kept = mutableListOf<String>()
        fun keep(value: String): String {
            kept += value
            return "\u0000${kept.size - 1}\u0000"
        }
        var text = line
        text = codeSpan.replace(text) { keep(it.groupValues[2].trim()) }
        text = escaped.replace(text) { keep(it.groupValues[1]) }
        text = image.replace(text) { it.groupValues[1].ifEmpty { "image" } }
        text = link.replace(text) { it.groupValues[1] }
        text = autolink.replace(text) { it.groupValues[1] }
        for (pattern in emphasis) text = pattern.replace(text) { it.groupValues[1] }
        return placeholder.replace(text) { kept.getOrNull(it.groupValues[1].toIntOrNull() ?: -1).orEmpty() }
    }

    // The rule, quote and divider checks are loops: a regex repeating a group once per mark
    // recurses once per mark on some engines, and a long line ran out of stack.

    /** Three or more of one of `-`, `*` or `_`, with spaces between them allowed: `---`, `* * *`. */
    private fun isRule(line: String): Boolean {
        val mark = line.first()
        if (mark != '-' && mark != '*' && mark != '_') return false
        var count = 0
        for (c in line) {
            when (c) {
                mark -> count++
                ' ' -> {}
                else -> return false
            }
        }
        return count >= 3
    }

    /** A table's header divider: cells of two or more dashes, each with an optional `:` either side. */
    private fun isTableDivider(line: String): Boolean {
        var body = line.removePrefix("|")
        if (body.endsWith('|')) body = body.dropLast(1)
        if (body.isEmpty()) return false
        return body.split('|').all { cell ->
            val c = cell.trim(' ', '\t').removePrefix(":").removeSuffix(":")
            c.length >= 2 && c.all { it == '-' }
        }
    }

    /** The line after any run of `>` quote marks, each with the one space that may follow it. */
    private fun withoutQuoteMarks(line: String): String {
        var i = 0
        while (i < line.length && line[i] == '>') {
            i++
            if (i < line.length && line[i].isWhitespace()) i++
        }
        return line.substring(i)
    }

    /** A heading's closing `#`s and the space before them: `Title ##` is `Title`; `C#` stays. */
    private fun withoutClosingHashes(line: String): String {
        val hashes = line.trimEnd('#')
        if (hashes.length == line.length || hashes.isEmpty() || !hashes.last().isWhitespace()) return line
        return hashes.trimEnd()
    }

    private val fenceStart = Regex("^(`{3,}|~{3,})")
    private val heading = Regex("^#{1,6}\\s+")
    private val bullet = Regex("^[-*+]\\s+(\\[[ xX]]\\s+)?")
    private val numbered = Regex("^(\\d{1,9})[.)]\\s+")
    private val codeSpan = Regex("(`+)(.+?)\\1")
    private val escaped = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!>|~])")
    private val image = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val autolink = Regex("<((?:https?|mailto):[^>\\s]+)>")
    private val emphasis = listOf(
        Regex("\\*\\*(\\S(?:.*?\\S)?)\\*\\*"),
        Regex("(?<![A-Za-z0-9_])__(\\S(?:.*?\\S)?)__(?![A-Za-z0-9_])"),
        Regex("~~(\\S(?:.*?\\S)?)~~"),
        Regex("\\*(\\S(?:.*?\\S)?)\\*"),
        Regex("(?<![A-Za-z0-9_])_(\\S(?:.*?\\S)?)_(?![A-Za-z0-9_])"),
    )
    private val placeholder = Regex("\u0000(\\d+)\u0000")
    private val sentenceEnd = Regex("(?<=[.!?…])\\s+")

    /** Longest line the inline patterns run on. */
    private const val INLINE_MAX = 500
}
