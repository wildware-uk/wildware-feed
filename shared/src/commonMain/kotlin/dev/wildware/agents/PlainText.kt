package dev.wildware.agents

/**
 * Agent markdown as plain text, for places that can't draw it: Spotlight, Siri and widgets.
 * The dashboard has the same rule server-side (`src/domain/plain-text.ts`): each block becomes
 * one line, list items keep a bullet, and real emphasis goes while a lone `*` or `_` stays.
 */
object PlainText {
    fun fromMarkdown(markdown: String): String {
        val lines = mutableListOf<String>()
        var fence: String? = null
        for (raw in markdown.replace("\r\n", "\n").split('\n')) {
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
            if (trimmed.isEmpty() || rule.matches(trimmed) || tableDivider.matches(trimmed)) continue
            lines += inline(block(trimmed))
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
        text = text.replace(quote, "")
        text = text.replace(heading, "").replace(closingHashes, "")
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
        return placeholder.replace(text) { kept[it.groupValues[1].toInt()] }
    }

    private val fenceStart = Regex("^(`{3,}|~{3,})")
    private val rule = Regex("^([-*_])( *\\1){2,}$")
    private val tableDivider = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")
    private val quote = Regex("^(>\\s?)+")
    private val heading = Regex("^#{1,6}\\s+")
    private val closingHashes = Regex("\\s+#+$")
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
}
