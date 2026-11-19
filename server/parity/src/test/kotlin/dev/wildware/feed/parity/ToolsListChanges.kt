package dev.wildware.feed.parity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where the Kotlin server's tools/list differs from the TS server's on purpose, from
 * `src/test/resources/tools-list-kotlin-changes.json`. The TS server is retired, so the Kotlin
 * server's descriptions may move on, but only by an entry in that file: anything else that differs
 * from `tools-list.json` is drift, and the test fails on it.
 *
 * Each entry names a tool, a path of keys inside it and why, and one of:
 * - `replace` / `with`: swap that exact text, found once, in the string at the path;
 * - `was` / `now`: the value at the path was `was` and is now `now` (no `was`: a new key).
 *
 * Every entry is checked against the TS payload as it applies, so an entry that no longer fits
 * fails too.
 */
object ToolsListChanges {
    fun load(): JsonArray = Parity.json.parseToJsonElement(
        ToolsListChanges::class.java.getResource("/tools-list-kotlin-changes.json")!!.readText(),
    ).jsonArray

    /** [tools] (the TS payload) with every change in [changes] applied, in order. */
    fun apply(tools: JsonArray, changes: JsonArray = load()): JsonArray {
        var out = tools
        for (change in changes) {
            val c = change.jsonObject
            val name = c.getValue("tool").jsonPrimitive.content
            val path = c.getValue("path").jsonArray.map { it.jsonPrimitive.content }
            val index = out.indexOfFirst { it.jsonObject["name"]?.jsonPrimitive?.content == name }
            check(index >= 0) { "tools-list-kotlin-changes.json: no tool $name" }
            val changed = edit(out[index].jsonObject, path) { old ->
                val where = "tools-list-kotlin-changes.json: $name ${path.joinToString(".")}"
                when {
                    "replace" in c -> {
                        val text = (old as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("$where is not text")
                        val from = c.getValue("replace").jsonPrimitive.content
                        check(text.split(from).size == 2) { "$where: the text to replace is not there exactly once" }
                        JsonPrimitive(text.replace(from, c.getValue("with").jsonPrimitive.content))
                    }
                    else -> {
                        check(old == c["was"]) { "$where was $old, not ${c["was"]}" }
                        c.getValue("now")
                    }
                }
            }
            out = JsonArray(out.toMutableList().also { it[index] = changed })
        }
        return out
    }

    private fun edit(o: JsonObject, path: List<String>, change: (JsonElement?) -> JsonElement): JsonObject {
        val key = path.first()
        val next = if (path.size == 1) change(o[key]) else edit(o[key]?.jsonObject ?: error("no $key"), path.drop(1), change)
        return JsonObject(LinkedHashMap(o).apply { put(key, next) })
    }
}
