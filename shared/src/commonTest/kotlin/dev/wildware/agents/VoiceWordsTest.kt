package dev.wildware.agents

import kotlin.test.Test
import kotlin.test.assertEquals

/** Finding a voice note's unsure words in its text, and swapping one for another guess. */
class VoiceWordsTest {
    private fun heard(text: String, confidence: Double, start: Double = 0.0, end: Double = 0.0, alternatives: List<String> = emptyList()) =
        HeardWord(text, confidence, start, end, alternatives)

    @Test
    fun `only the unsure words are kept and each is found where it is in the text`() {
        val text = "Ship it on Friday. Ship it."
        val best = listOf(
            heard("Ship", 0.9), heard("it", 0.9), heard("on", 0.9), heard("Friday", 0.3, alternatives = listOf("Monday", "friday")),
            heard("Ship", 0.2, alternatives = listOf("Chip")), heard("it", 0.9),
        )
        assertEquals(
            listOf(VoiceWord(11, 17, 0.3, listOf("Monday")), VoiceWord(19, 23, 0.2, listOf("Chip"))),
            VoiceWords.place(text, best, emptyList()),
        )
    }

    @Test
    fun `other guesses at the same moment are offered for a word`() {
        val best = listOf(heard("I", 0.9, 0.0, 0.2), heard("scream", 0.4, 0.2, 0.8))
        val other = listOf(heard("ice", 0.0, 0.0, 0.3), heard("cream", 0.0, 0.35, 0.8))
        val sameAgain = listOf(heard("I", 0.0, 0.0, 0.2), heard("scream", 0.0, 0.2, 0.8))
        assertEquals(listOf(VoiceWord(2, 8, 0.4, listOf("cream"))), VoiceWords.place("I scream", best, listOf(other, sameAgain)))
    }

    @Test
    fun `words missing from the text are skipped`() {
        assertEquals(emptyList(), VoiceWords.place("Ship it", listOf(heard("tonight", 0.1)), emptyList()))
    }

    @Test
    fun `choosing a word swaps it in and moves the words after it`() {
        val words = listOf(VoiceWord(0, 4, 0.3, listOf("Chip")), VoiceWord(11, 17, 0.3, listOf("Monday")))
        val chosen = VoiceWords.choose("Ship it on Friday", words, 0, "Chip in")
        assertEquals("Chip in it on Friday", chosen.text)
        assertEquals(listOf(VoiceWord(14, 20, 0.3, listOf("Monday"))), chosen.words)
    }

    @Test
    fun `keeping a word leaves the text and settles the word`() {
        val words = listOf(VoiceWord(0, 4, 0.3, listOf("Chip")))
        assertEquals(ChosenWord("Ship it", emptyList()), VoiceWords.choose("Ship it", words, 0, null))
    }
}
