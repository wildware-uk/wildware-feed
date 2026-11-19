package dev.wildware.agents

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Turns a spoken reply into the answer a request expects, or null when it is not clear enough to send. */
object SpokenAnswer {
    private val yes = setOf("yes", "yeah", "yep", "sure", "ok", "okay", "approve", "approved", "go", "go ahead", "do it", "confirm")
    private val no = setOf("no", "nope", "nah", "deny", "denied", "stop", "don't", "do not", "cancel", "reject")

    fun parse(request: PendingRequest, spoken: String): JsonElement? {
        val said = normalise(spoken)
        if (said.isEmpty()) return null
        return when (request.kind) {
            RequestKind.Confirm -> when {
                said in yes || yes.any { said.startsWith("$it ") } -> JsonPrimitive(true)
                said in no || no.any { said.startsWith("$it ") } -> JsonPrimitive(false)
                else -> null
            }

            RequestKind.Buttons, RequestKind.Choice -> matchOption(said, request.options.orEmpty())?.let(::JsonPrimitive)
            RequestKind.Text -> JsonPrimitive(spoken.trim())
            RequestKind.MultiChoice, RequestKind.Form, RequestKind.Secret -> null
        }
    }

    private fun matchOption(said: String, options: List<String>): String? {
        options.firstOrNull { normalise(it) == said }?.let { return it }
        ordinal(said)?.let { index -> options.getOrNull(index)?.let { return it } }
        val containing = options.filter { option -> normalise(option).let { said.contains(it) || it.contains(said) } }
        return containing.singleOrNull()
    }

    private val ordinals = listOf("first", "second", "third", "fourth", "fifth")
    private val numbers = listOf("one", "two", "three", "four", "five")

    private fun ordinal(said: String): Int? {
        val word = said.removePrefix("the ").removePrefix("option ").removeSuffix(" one").trim()
        return ordinals.indexOf(word).takeIf { it >= 0 }
            ?: numbers.indexOf(word).takeIf { it >= 0 }
            ?: word.toIntOrNull()?.minus(1)?.takeIf { it >= 0 }
    }

    private fun normalise(text: String) =
        text.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
}
