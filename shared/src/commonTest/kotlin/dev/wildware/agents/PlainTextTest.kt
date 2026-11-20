package dev.wildware.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class PlainTextTest {
    @Test
    fun ordinaryMarkdownStillBecomesPlainText() {
        val text = PlainText.fromMarkdown(
            """
            # Title #
            > quoted **bold** and `code`
            - [x] done item
            ---
            | a | b |
            |---|:--:|
            | 1 | 2 |
            See [the docs](https://example.com) and \*stars\*.
            """.trimIndent(),
        )
        assertEquals("Title\nquoted bold and code\n• done item\na · b\n1 · 2\nSee the docs and *stars*.", text)
    }

    @Test
    fun rulesQuotesAndDividersAreRecognisedWithoutRegexRecursion() {
        assertEquals("", PlainText.fromMarkdown("- - -"))
        assertEquals("", PlainText.fromMarkdown("***"))
        assertEquals("", PlainText.fromMarkdown("|--|:--:|"))
        assertEquals("-", PlainText.fromMarkdown("-"))
        assertEquals("a -- b", PlainText.fromMarkdown("a -- b"))
        assertEquals("deep", PlainText.fromMarkdown(">> > deep"))
        assertEquals("C#", PlainText.fromMarkdown("C#"))
    }

    @Test
    fun longLinesOfRepeatedMarksDoNotOverflowTheStack() {
        for (line in listOf("-".repeat(100_000), ">".repeat(100_000), "--|".repeat(33_333), "_ ".repeat(50_000))) {
            PlainText.fromMarkdown(line)
            PlainText.fromMarkdown("intro\n$line\noutro")
        }
        assertEquals("", PlainText.fromMarkdown("-".repeat(100_000)))
        assertEquals("x", PlainText.fromMarkdown(">".repeat(100_000) + "x"))
    }

    @Test
    fun theHiddenMarkerInPostedTextCannotBreakTheConversion() {
        assertEquals("a 9b", PlainText.fromMarkdown("a \u00009\u0000b"))
        assertEquals("99999999999999999999x", PlainText.fromMarkdown("\u000099999999999999999999\u0000x"))
        assertEquals("code and 0", PlainText.fromMarkdown("`code` and \u00000\u0000"))
    }

    @Test
    fun oneLongLineConvertsInTimeInStepWithItsLength() {
        val worst = listOf("[", "**a ", "![", "<http:", "a`", "a ", "_a ", "~~a ").map { it.repeat(100_000 / it.length) }
        val clock = TimeSource.Monotonic
        for (line in worst) {
            val started = clock.markNow()
            val out = PlainText.fromMarkdown(line)
            val took = started.elapsedNow().inWholeMilliseconds
            assertTrue(took < 1_500, "${line.take(8)}… took $took ms")
            // Nothing is cut: a long line comes back with every word.
            assertTrue(out.length >= line.trim().length - 2, "${line.take(8)}… lost text")
        }
    }
}
