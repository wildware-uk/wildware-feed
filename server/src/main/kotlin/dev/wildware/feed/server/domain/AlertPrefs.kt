package dev.wildware.feed.server.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Which alerts a phone wants (migration 047; TS `assertPushPrefs` and `notifies`,
 * `src/domain/push.ts`): kinds, update levels and priorities. `null` is every alert.
 */
object AlertPrefs {
    val TYPES = listOf("request", "update", "message", "comment")
    val LEVELS = listOf("info", "success", "warn", "error")
    val PRIORITIES = listOf("low", "medium", "high")

    /** What an alert is about: its kind, and for an update its level and priority. */
    data class About(val type: String, val level: String? = null, val priority: String? = null)

    /** Checked and deduped, in the TS key order, or null for the default. */
    fun check(input: JsonElement?): JsonObject? {
        if (input == null || input is JsonNull) return null
        if (input !is JsonObject) throw invalid("preferences must be an object, or null for the default")
        val out = LinkedHashMap<String, JsonElement>()
        fun list(field: String, allowed: List<String>) {
            val value = input[field] ?: return
            if (value !is JsonArray) throw invalid("$field must be a list")
            for (member in value) {
                val s = member.jsString
                if (s == null || s !in allowed) throw invalid("$field must be any of: ${allowed.joinToString(", ")}")
            }
            out[field] = JsonArray(value.map { it.jsString!! }.distinct().map(::JsonPrimitive))
        }
        list("types", TYPES)
        list("levels", LEVELS)
        list("priorities", PRIORITIES)
        return if (out.isEmpty()) null else JsonObject(out)
    }

    private fun JsonElement?.strings(): List<String>? = (this as? JsonArray)?.map { (it as JsonPrimitive).content }

    fun notifies(prefs: JsonObject?, about: About): Boolean {
        val types = prefs?.get("types").strings() ?: TYPES
        if (about.type !in types) return false
        if (about.type != "update") return true
        val levels = prefs?.get("levels").strings()
        if (levels != null && about.level != null && about.level !in levels) return false
        val priorities = prefs?.get("priorities").strings()
        if (priorities != null && about.priority != null && about.priority !in priorities) return false
        return true
    }
}
