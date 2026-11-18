package dev.wildware.agents

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The feed as it last was, kept on the device so the app opens onto it at once and then catches
 * up from the server.
 *
 * One document in [store]: a format [Version], whose dashboard and key it belongs to ([owner]),
 * and each reader's last good state by name. A document from another version, another dashboard
 * or another key is ignored, never shown. Writes wait [writeDelayMillis] so a burst of events
 * writes once, and the document is kept under [maxChars], dropping project feeds first.
 *
 * Every call comes from the engine's one thread. Encoding and writing happen off it.
 */
class FeedCache(
    private val store: TextStore,
    /** Whose feed this is: the dashboard's address and a fingerprint of the device key. */
    private val owner: String,
    private val writeDelayMillis: Long = 1_500,
    private val maxChars: Int = 4_000_000,
    /** Where encoding and disk writes run: never the engine's own thread. */
    private val background: CoroutineContext = Dispatchers.Default,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    /** What was on disk, read the first time a reader asks. */
    private var stored: Map<String, JsonElement>? = null

    /** What changed since, by name, waiting for the next write. */
    private val waiting = LinkedHashMap<String, () -> JsonElement>()
    private var writer: Job? = null
    @kotlin.concurrent.Volatile private var closed = false

    /** The last saved state for [key], or null when there is none or it can't be read any more. */
    internal fun <T> read(key: String, serializer: KSerializer<T>): T? {
        val element = loaded()[key] ?: return null
        return runCatching { json.decodeFromJsonElement(serializer, element) }.getOrNull()
    }

    /** Keeps [value] as [key]'s state; written to disk shortly, off the engine's thread. */
    internal fun <T> save(key: String, value: T, serializer: KSerializer<T>, scope: CoroutineScope) {
        if (closed) return
        waiting[key] = { json.encodeToJsonElement(serializer, value) }
        if (writer?.isActive == true) return
        writer = scope.launch {
            delay(writeDelayMillis)
            flush()
        }
    }

    /** Writes what is waiting now. */
    internal suspend fun flush() {
        if (closed || waiting.isEmpty()) return
        val changes = waiting.toMap()
        waiting.clear()
        val before = loaded()
        val text = withContext(background) {
            val entries = LinkedHashMap(before)
            // Newest last, so trimming drops the longest unchanged first.
            for ((key, encode) in changes) {
                entries.remove(key)
                entries[key] = encode()
            }
            document(entries)
        }
        if (closed || text == null) return
        stored = text.second
        withContext(background) { runCatching { store.write(text.first) } }
    }

    /** Stops writing: the owner signed out. Whatever was waiting is dropped. */
    fun close() {
        closed = true
        waiting.clear()
        writer?.cancel()
    }

    private fun loaded(): Map<String, JsonElement> = stored ?: parse(runCatching { store.read() }.getOrNull()).also { stored = it }

    private fun parse(text: String?): Map<String, JsonElement> {
        if (text.isNullOrEmpty()) return emptyMap()
        val doc = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
        val version = runCatching { doc["version"]?.jsonPrimitive?.int }.getOrNull()
        val whose = runCatching { doc["owner"]?.jsonPrimitive?.content }.getOrNull()
        if (version != Version || whose != owner) return emptyMap()
        return (doc["entries"] as? JsonObject)?.toMap() ?: emptyMap()
    }

    /** The document for [entries], trimmed to fit: project feeds go first, oldest first. */
    private fun document(entries: Map<String, JsonElement>): Pair<String, Map<String, JsonElement>>? {
        val kept = LinkedHashMap(entries)
        while (true) {
            val doc = buildJsonObject {
                put("version", Version)
                put("owner", owner)
                put("entries", JsonObject(kept))
            }
            val text = doc.toString()
            if (text.length <= maxChars) return text to kept
            val drop = kept.keys.firstOrNull { it.isProjectKey() } ?: kept.keys.firstOrNull() ?: return null
            kept.remove(drop)
        }
    }

    private fun String.isProjectKey() = substringAfter(':', "").let { it.isNotEmpty() && it != AllProjects }

    companion object {
        /** Bumped whenever what is stored changes shape: an older document is then ignored. */
        const val Version = 1

        /** The key part for the all-projects feed. */
        internal const val AllProjects = "*"

        internal fun key(kind: String, projectSlug: String?) = "$kind:${projectSlug ?: AllProjects}"

        /**
         * Whose feed a cache holds: the dashboard and a fingerprint of the key that read it. A
         * fingerprint, not the key: the key itself never goes in the cache.
         */
        fun ownerOf(serverUrl: String, authorization: String?): String {
            var hash = 1125899906842597L
            authorization.orEmpty().forEach { hash = 31 * hash + it.code }
            return "${serverUrl.trimEnd('/')}#${hash.toULong().toString(16)}"
        }
    }
}
