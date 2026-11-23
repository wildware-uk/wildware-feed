package dev.wildware.agents

import kotlinx.serialization.Serializable

/**
 * A word as the phone's recognizer heard it, before it is placed in the note's text. [confidence]
 * is 0 to 1, or 1 when the recognizer didn't say. Times are seconds into the recording, both 0
 * when unknown. [alternatives] are the recognizer's own other guesses for this word, if any.
 */
class HeardWord(
    val text: String,
    val confidence: Double,
    val startSeconds: Double,
    val endSeconds: Double,
    val alternatives: List<String>,
)

/**
 * A word in a voice note's text that the phone wasn't sure of: where it sits in the text
 * ([start] up to [end]), how sure it was, and what else it might have been. Kept on the phone
 * with the unsent note only; it never goes to the dashboard.
 */
@Serializable
data class VoiceWord(val start: Int, val end: Int, val confidence: Double, val alternatives: List<String>)

/** A note's text after a word was settled, and the unsure words left in it. */
data class ChosenWord(val text: String, val words: List<VoiceWord>)

/** Which words in a voice note to question, and swapping one for another guess. */
object VoiceWords {
    /** A word heard with less confidence than this gets a dotted underline and a choice of others. */
    const val UnsureBelow = 0.5

    /** At most this many other guesses are offered for one word. */
    const val MostAlternatives = 4

    /**
     * The unsure words of [text], found in it in order. [best] is what the recognizer settled on,
     * word by word; [others] are its other whole guesses, whose words at the same moment of the
     * recording count as other guesses for the word there. Words that can't be found in [text]
     * (cut off at the length limit, say) are left out.
     */
    fun place(text: String, best: List<HeardWord>, others: List<List<HeardWord>>): List<VoiceWord> {
        var from = 0
        val placed = mutableListOf<VoiceWord>()
        for (heard in best) {
            val word = heard.text.trim()
            // A full stop or comma heard on its own is no word to question.
            if (word.none { it.isLetterOrDigit() }) continue
            val at = text.indexOf(word, from).takeIf { it >= 0 } ?: text.indexOf(word, from, ignoreCase = true)
            if (at < 0) continue
            from = at + word.length
            if (heard.confidence >= UnsureBelow) continue
            val choices = (heard.alternatives + others.mapNotNull { sameMoment(heard, it) })
                .map { it.trim() }
                .filter { it.isNotEmpty() && !sameWord(it, word) }
                .distinctBy { it.lowercase() }
                .take(MostAlternatives)
            placed += VoiceWord(at, at + word.length, heard.confidence, choices)
        }
        return placed
    }

    /**
     * Settles the unsure word at [index]: with a [choice] it takes that word's place, and the words
     * after it move to match; with none the word stays as it is. Either way it is no longer unsure.
     */
    fun choose(text: String, words: List<VoiceWord>, index: Int, choice: String?): ChosenWord {
        val word = words.getOrNull(index) ?: return ChosenWord(text, words)
        if (word.end > text.length) return ChosenWord(text, words - word)
        val rest = words.filterIndexed { i, _ -> i != index }
        if (choice == null || choice == text.substring(word.start, word.end)) return ChosenWord(text, rest)
        val shift = choice.length - (word.end - word.start)
        return ChosenWord(
            text.replaceRange(word.start, word.end, choice),
            rest.map { if (it.start >= word.end) it.copy(start = it.start + shift, end = it.end + shift) else it },
        )
    }

    /** The words of another guess said at the same moment as [heard], as one phrase. */
    private fun sameMoment(heard: HeardWord, other: List<HeardWord>): String? {
        if (heard.endSeconds <= heard.startSeconds) return null
        val words = other.filter { candidate ->
            val middle = (candidate.startSeconds + candidate.endSeconds) / 2
            candidate.endSeconds > candidate.startSeconds && middle >= heard.startSeconds && middle <= heard.endSeconds
        }
        return words.joinToString(" ") { it.text.trim() }.takeIf { it.isNotBlank() }
    }

    private fun sameWord(a: String, b: String): Boolean = bare(a) == bare(b)

    private fun bare(word: String): String = word.lowercase().filter { it.isLetterOrDigit() }
}
