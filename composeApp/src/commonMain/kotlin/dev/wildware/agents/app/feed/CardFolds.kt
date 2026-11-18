package dev.wildware.agents.app.feed

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.TextStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The cards the owner has folded away on this device, newest first, as the website keeps them in the
 * browser. Folding is about reading, not about what happened: a card may be folded on the laptop
 * and open on the phone, so it is kept here and never sent to the dashboard. It keeps the newest
 * [max]; the oldest quietly come back open.
 */
@Stable
class CardFolds(private val store: TextStore, private val max: Int = 500) {
    private val ids = mutableStateListOf<String>().apply { addAll(read()) }

    fun isFolded(id: String): Boolean = id in ids

    fun set(id: String, folded: Boolean) {
        ids.remove(id)
        if (folded) ids.add(0, id)
        while (ids.size > max) ids.removeAt(ids.lastIndex)
        // A store that can't be written costs only the memory of it, never the feed.
        runCatching { store.write(JsonArray(ids.map(::JsonPrimitive)).toString()) }
    }

    private fun read(): List<String> = runCatching {
        Json.parseToJsonElement(store.read() ?: return emptyList()).jsonArray
            .mapNotNull { it.jsonPrimitive.contentOrNull }
            .take(max)
    }.getOrDefault(emptyList())
}

/** This device's folds. Previews and tests get one that forgets. */
val LocalCardFolds = staticCompositionLocalOf { CardFolds(MemoryTextStore()) }
