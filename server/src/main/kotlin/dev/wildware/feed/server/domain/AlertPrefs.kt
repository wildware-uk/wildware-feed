package dev.wildware.feed.server.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Which alerts a phone wants (migration 047; TS `assertPushPrefs` and `notifies`,
 * `src/domain/push.ts`): kinds, update levels and priorities, and whether something marked critical
 * pages it through Do Not Disturb, from which agents and projects. `null` is every alert, no paging.
 */
object AlertPrefs {
    val TYPES = listOf("request", "update", "message", "comment")
    val LEVELS = listOf("info", "success", "warn", "error")
    val PRIORITIES = listOf("low", "medium", "high", "critical")

    /**
     * What an alert is about: its kind, for an update its level, its priority (updates and
     * questions), and who sent it from which project, for a phone that lets only some page it.
     */
    data class About(
        val type: String,
        val level: String? = null,
        val priority: String? = null,
        val agentId: String? = null,
        val projectSlug: String? = null,
    )

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
        fun names(field: String) {
            val value = input[field] ?: return
            if (value !is JsonArray) throw invalid("$field must be a list")
            if (value.any { it.jsString == null }) throw invalid("$field must be a list of strings")
            out[field] = JsonArray(value.map { it.jsString!! }.distinct().map(::JsonPrimitive))
        }
        list("types", TYPES)
        list("levels", LEVELS)
        list("priorities", PRIORITIES)
        input["critical"]?.let { value ->
            val flag = (value as? JsonPrimitive)?.takeIf { !it.isString && it.content in setOf("true", "false") }?.content?.toBoolean()
                ?: throw invalid("critical must be true or false")
            out["critical"] = JsonPrimitive(flag)
        }
        names("criticalAgents")
        names("criticalProjects")
        return if (out.isEmpty()) null else JsonObject(out)
    }

    private fun JsonElement?.strings(): List<String>? = (this as? JsonArray)?.map { (it as JsonPrimitive).content }

    /**
     * Whether this pages the phone: marked critical, critical alerts turned on, and from an agent and
     * project it allowed (when it named any).
     */
    fun pages(prefs: JsonObject?, about: About): Boolean {
        if (about.priority != "critical" || (prefs?.get("critical") as? JsonPrimitive)?.content != "true") return false
        val agents = prefs["criticalAgents"].strings()
        if (agents != null && (about.agentId == null || about.agentId !in agents)) return false
        val projects = prefs["criticalProjects"].strings()
        if (projects != null && (about.projectSlug == null || about.projectSlug !in projects)) return false
        return true
    }

    fun notifies(prefs: JsonObject?, about: About): Boolean {
        // A phone that asked to be paged for this is told, whatever else it filters out.
        if (pages(prefs, about)) return true
        val types = prefs?.get("types").strings() ?: TYPES
        if (about.type !in types) return false
        if (about.type != "update") return true
        val levels = prefs?.get("levels").strings()
        if (levels != null && about.level != null && about.level !in levels) return false
        val priorities = prefs?.get("priorities").strings()
        // Critical outranks high: a phone that wants high-priority updates hears critical ones.
        if (priorities != null && about.priority != null && about.priority !in priorities &&
            !(about.priority == "critical" && "high" in priorities)
        ) {
            return false
        }
        return true
    }
}
