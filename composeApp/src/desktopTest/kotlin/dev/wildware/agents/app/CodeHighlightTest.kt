package dev.wildware.agents.app

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.SpanStyle
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.colorsFor
import dev.wildware.agents.app.feed.CodeHighlights
import dev.wildware.agents.app.feed.MarkdownBody
import dev.wildware.agents.app.feed.MarkdownCache
import dev.wildware.agents.app.feed.highlighted
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Code blocks are highlighted with the markdown parse, off the UI thread, never while drawing. */
@OptIn(ExperimentalTestApi::class)
class CodeHighlightTest {
    private val highlights = AtomicInteger()

    @AfterTest
    fun stopCounting() {
        CodeHighlights.onHighlight = null
    }

    /** Fences as agents write them: with and without a language, indented, several in one body. */
    private fun bodies(tag: Long) = listOf(
        "Fixed it:\n\n```kotlin\nval answer = $tag\nfun main() = println(\"hi\") // done\n```",
        "```\nplain $tag\n```",
        "Steps:\n\n1. Run this:\n\n   ```sh\n   ./gradlew build --tag $tag\n   ```\n\n2. Then this:\n\n   ```json\n   {\"tag\": $tag}\n   ```",
        "Two blocks.\n\n```ts\nconst a = $tag;\n```\n\nand\n\n```python\nprint($tag)\n```",
    )

    @Test
    fun warmedCodeIsNeverHighlightedWhileDrawing() = runDesktopComposeUiTest(390, 1600) {
        val texts = bodies(System.nanoTime())
        CodeHighlights.onHighlight = { highlights.incrementAndGet() }
        MarkdownCache.warm(texts)
        assertEquals(6, highlights.get(), "each of the six blocks was highlighted once, with the parse")

        highlights.set(0)
        setContent { AgentTheme(dark = true) { Column { texts.forEach { MarkdownBody(it) } } } }
        waitForIdle()
        assertEquals(0, highlights.get(), "drawing found every block ready")
    }

    @Test
    fun tokensTakeTheThemesColours() {
        val dark = colorsFor(true).code
        val light = colorsFor(false).code
        val code = "val x = \"hi\" // note"
        CodeHighlights.get(code, "kotlin")
        fun colourAt(text: androidx.compose.ui.text.AnnotatedString, word: String) =
            text.spanStyles.filter { it.start <= code.indexOf(word) && code.indexOf(word) < it.end }.map(androidx.compose.ui.text.AnnotatedString.Range<SpanStyle>::item).lastOrNull { it.color.isSpecified() }?.color
        for (colors in listOf(dark, light)) {
            val text = highlighted(code, "kotlin", colors)
            assertEquals(colors.keyword, colourAt(text, "val"))
            assertEquals(colors.string, colourAt(text, "\"hi\""))
            assertEquals(colors.comment, colourAt(text, "// note"))
        }
    }

    private fun androidx.compose.ui.graphics.Color.isSpecified() = this != androidx.compose.ui.graphics.Color.Unspecified

    /** Not an assertion: what highlighting a block costs, against drawing it once highlighted. */
    @Test
    fun drawingCostWithHighlightsReady() = runDesktopComposeUiTest(390, 1600) {
        var body by mutableStateOf<String?>(null)
        var round by mutableIntStateOf(0)
        setContent { AgentTheme(dark = true) { key(round) { body?.let { MarkdownBody(it) } } } }
        waitForIdle()
        val highlight = mutableListOf<Long>()
        val draw = mutableListOf<Long>()
        repeat(24) { i ->
            val text = bodies(System.nanoTime())[0]
            val code = text.substringAfter("```kotlin\n").substringBefore("\n```")
            val h = System.nanoTime()
            CodeHighlights.get(code + " ", "kotlin")
            val dh = System.nanoTime() - h
            MarkdownCache.warm(listOf(text))
            val t = System.nanoTime()
            body = text; round++; waitForIdle()
            val dt = System.nanoTime() - t
            if (i >= 4) { highlight += dh; draw += dt }
            body = null; waitForIdle()
        }
        fun median(l: List<Long>) = l.sorted()[l.size / 2] / 1000
        println("Code block: highlighting costs ${median(highlight)} us, now off the UI thread; drawing the body then costs ${median(draw)} us")
        assertTrue(draw.isNotEmpty())
    }
}
