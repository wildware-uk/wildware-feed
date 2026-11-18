package dev.wildware.feed.server.domain

import java.text.CollationElementIterator
import java.text.Collator
import java.text.RuleBasedCollator
import java.util.Locale

/**
 * JavaScript's `a.localeCompare(b)` for sorting names, built on the JDK's own collator (root locale,
 * tertiary strength), so the server and the desktop app share one code path and no ICU jar.
 *
 * The JDK collator agrees with Node's ICU on letters, digits, accents, case, `ß`, Greek and CJK.
 * It differs in two places, both special-cased here (spec/README.md, known differences):
 * - it ignores spaces and most punctuation, where ICU sorts them before every digit and letter
 *   (`a b` < `a_b` < `a-b` < `ab`);
 * - it sorts symbols and emoji after everything, where ICU puts them after punctuation but before
 *   digits.
 * - it sorts letters with a stroke (`Ø`, `Ł`, `Đ`) after z, where ICU sorts them as the plain letter.
 * So the first comparison is on primary weights in which those characters weigh less than any
 * digit or letter; ties fall through to the JDK collator for accents and case.
 * `LocaleCompareTest` holds realistic names in the order Node sorts them. One known difference is
 * left: names equal but for case and `ß`/`ss` (`Strasse`, `straße`) tie-break in another order.
 */
object LocaleCompare : Comparator<String> {
    private val collator = (Collator.getInstance(Locale.ROOT) as RuleBasedCollator).apply { strength = Collator.TERTIARY }

    /** ICU's order for the common ASCII punctuation, after whitespace and dashes. */
    private const val PUNCTUATION = ",;:!?.'\"()[]{}@*/\\&#%`^+<=>|~"
    private const val LETTERS = 10_000_000

    /** Letters with a stroke, which ICU sorts as the plain letter (the stroke is an accent), the JDK after z. */
    private val STROKED = mapOf(
        'Ø'.code to "O", 'ø'.code to "o", 'Đ'.code to "D", 'đ'.code to "d", 'Ł'.code to "L", 'ł'.code to "l",
        'Ħ'.code to "H", 'ħ'.code to "h", 'Ŧ'.code to "T", 'ŧ'.code to "t",
    )

    @Synchronized
    override fun compare(a: String, b: String): Int {
        val ka = primary(a)
        val kb = primary(b)
        for (i in 0 until minOf(ka.size, kb.size)) {
            if (ka[i] != kb[i]) return ka[i].compareTo(kb[i])
        }
        if (ka.size != kb.size) return ka.size.compareTo(kb.size)
        return collator.compare(a, b)
    }

    /**
     * Where a space, punctuation mark or symbol sorts, in ICU's bands: whitespace, `_`, dashes,
     * other punctuation, symbols (emoji among them), currency; all before digits and letters.
     * Null for anything the JDK collator handles itself.
     */
    private fun variableRank(cp: Int): Int? {
        if (Character.isWhitespace(cp) || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()) return 1
        if (cp == '_'.code) return 2
        if (cp == '-'.code) return 3
        val listed = if (cp < 0x80) PUNCTUATION.indexOf(cp.toChar()) else -1
        if (listed >= 0) return 200_000 + listed
        return when (Character.getType(cp).toByte()) {
            Character.DASH_PUNCTUATION -> 1_000 + cp
            Character.CONNECTOR_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> 300_000 + cp
            Character.MATH_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL, Character.SURROGATE -> 2_000_000 + cp
            Character.CURRENCY_SYMBOL -> 4_000_000 + cp
            else -> null
        }
    }

    /**
     * A character that is letters in disguise (a ligature like `ﬁ`, a Roman numeral `Ⅳ`, a circled
     * `Ⓐ`, a full-width `Ａ`) sorts as those letters, as in ICU: its compatibility form, when that
     * differs and is all letters and digits.
     */
    private fun asLetters(cp: Int): String? {
        STROKED[cp]?.let { return it }
        val text = String(Character.toChars(cp))
        val folded = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
        if (folded == text || folded.isEmpty()) return null
        return folded.takeIf { f -> f.all { it.isLetterOrDigit() } }
    }

    private fun primary(s: String): IntArray {
        val out = ArrayList<Int>(s.length)
        val segment = StringBuilder()
        fun flush() {
            if (segment.isEmpty()) return
            val it = collator.getCollationElementIterator(segment.toString())
            while (true) {
                val e = it.next()
                if (e == CollationElementIterator.NULLORDER) break
                val p = CollationElementIterator.primaryOrder(e)
                if (p != 0) out += LETTERS + p
            }
            segment.clear()
        }
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val letters = asLetters(cp)
            val rank = if (letters == null) variableRank(cp) else null
            if (letters != null) {
                segment.append(letters)
            } else if (rank != null) {
                flush()
                out += rank
            } else {
                segment.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        flush()
        return out.toIntArray()
    }
}
