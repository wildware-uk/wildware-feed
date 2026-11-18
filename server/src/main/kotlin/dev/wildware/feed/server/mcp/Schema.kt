package dev.wildware.feed.server.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Checks tool arguments against the advertised JSON Schema the way the TS server's zod schemas do,
 * with zod 4's wording (`Invalid input: expected string, received number at name`), and drops keys
 * the schema does not name, as zod does. Covers the subset of JSON Schema the tool list uses.
 */
object Schema {
    /** The cleaned arguments, or the issues; each issue already carries its ` at <path>`. */
    fun check(schema: JsonObject, value: JsonElement?): Pair<JsonObject?, List<String>> {
        val issues = mutableListOf<String>()
        val cleaned = visit(schema, value, emptyList(), issues)
        return (cleaned as? JsonObject).takeIf { issues.isEmpty() } to issues
    }

    internal fun received(v: JsonElement?): String = when {
        v == null -> "undefined"
        v is JsonNull -> "null"
        v is JsonArray -> "array"
        v is JsonObject -> "object"
        v is JsonPrimitive && v.isString -> "string"
        v is JsonPrimitive && v.booleanOrNull != null -> "boolean"
        else -> "number"
    }

    private fun issue(issues: MutableList<String>, path: List<Any>, message: String) {
        issues += if (path.isEmpty()) message else "$message at ${dotPath(path)}"
    }

    /** The SDK's `getDotPath`: `a.b`, with array indexes as `a[0]` (record keys stay `.key`, even numeric ones). */
    internal fun dotPath(path: List<Any>): String = buildString {
        path.forEachIndexed { i, seg ->
            when {
                i == 0 -> append(seg.toString())
                seg is Int -> append("[").append(seg).append("]")
                else -> append(".").append(seg.toString())
            }
        }
    }

    private const val MAX_SAFE = 9007199254740991.0

    private fun visit(schema: JsonObject, v: JsonElement?, path: List<Any>, issues: MutableList<String>): JsonElement? {
        schema["anyOf"]?.let { options ->
            val alternatives = options.jsonArray.map { it as JsonObject }
            for (alt in alternatives) {
                val trial = mutableListOf<String>()
                val result = visit(alt, v, path, trial)
                if (trial.isEmpty()) return result
            }
            // zod reports the first alternative's complaint for a nullable union.
            return visit(alternatives.first(), v, path, issues)
        }
        val type = (schema["type"] as? JsonPrimitive)?.content
        val enum = schema["enum"]?.jsonArray?.map { it.jsonPrimitive.content }
        if (type == null) return v // `{}`: anything
        // zod's enum says the same whatever it got, undefined and null included.
        val badOption = enum?.let { "Invalid option: expected one of ${it.joinToString("|") { o -> "\"$o\"" }}" }
        if (v == null) {
            issue(issues, path, badOption ?: "Invalid input: expected ${expected(schema, type)}, received undefined")
            return null
        }
        when (type) {
            "null" -> if (v !is JsonNull) issue(issues, path, "Invalid input: expected null, received ${received(v)}")
            "string" -> {
                if (v !is JsonPrimitive || !v.isString) {
                    issue(issues, path, badOption ?: "Invalid input: expected string, received ${received(v)}")
                    return null
                }
                if (badOption != null && v.content !in enum) {
                    issue(issues, path, badOption)
                }
                val length = v.content.length
                schema["maxLength"]?.jsonPrimitive?.intOrNull?.let { if (length > it) issue(issues, path, "Too big: expected string to have <=$it characters") }
                schema["minLength"]?.jsonPrimitive?.intOrNull?.let { if (length < it) issue(issues, path, "Too small: expected string to have >=$it characters") }
            }
            "boolean" -> if (v !is JsonPrimitive || v.isString || v.booleanOrNull == null) issue(issues, path, "Invalid input: expected boolean, received ${received(v)}")
            "integer", "number" -> {
                val n = (v as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull == null }?.doubleOrNull
                if (n == null) {
                    issue(issues, path, "Invalid input: expected number, received ${received(v)}")
                    return null
                }
                if (type == "integer") {
                    // zod's int(): a fraction stops there; an unsafe integer is reported, then the other checks run.
                    if (n != Math.floor(n) || n.isInfinite()) {
                        issue(issues, path, "Invalid input: expected int, received number")
                        return null
                    }
                    if (n > MAX_SAFE) issue(issues, path, "Too big: expected int to be <=${fmt(MAX_SAFE)}")
                    if (n < -MAX_SAFE) issue(issues, path, "Too small: expected int to be >=-${fmt(MAX_SAFE)}")
                }
                // The safe-integer bounds the JSON Schema carries are int()'s own, reported above.
                fun bound(key: String) = schema[key]?.jsonPrimitive?.doubleOrNull?.takeIf { type != "integer" || Math.abs(it) != MAX_SAFE }
                bound("exclusiveMinimum")?.let { if (n <= it) issue(issues, path, "Too small: expected number to be >${fmt(it)}") }
                bound("minimum")?.let { if (n < it) issue(issues, path, "Too small: expected number to be >=${fmt(it)}") }
                bound("maximum")?.let { if (n > it) issue(issues, path, "Too big: expected number to be <=${fmt(it)}") }
            }
            "array" -> {
                if (v !is JsonArray) {
                    issue(issues, path, "Invalid input: expected array, received ${received(v)}")
                    return null
                }
                val items = schema["items"] as? JsonObject
                val out = v.mapIndexed { i, item -> if (items != null) visit(items, item, path + i, issues) ?: item else item }
                schema["maxItems"]?.jsonPrimitive?.intOrNull?.let { if (v.size > it) issue(issues, path, "Too big: expected array to have <=$it items") }
                schema["minItems"]?.jsonPrimitive?.intOrNull?.let { if (v.size < it) issue(issues, path, "Too small: expected array to have >=$it items") }
                return JsonArray(out)
            }
            "object" -> {
                if (v !is JsonObject) {
                    issue(issues, path, "Invalid input: expected ${expected(schema, type)}, received ${received(v)}")
                    return null
                }
                val properties = schema["properties"] as? JsonObject
                if (properties == null) {
                    // A record: every value checked against additionalProperties.
                    val each = schema["additionalProperties"] as? JsonObject
                    return JsonObject(v.mapValues { (k, item) -> each?.let { visit(it, item, path + k, issues) } ?: item })
                }
                val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                val out = LinkedHashMap<String, JsonElement>()
                for ((key, propertySchema) in properties) {
                    val present = v[key]
                    if (present == null) {
                        if (key in required) visit(propertySchema as JsonObject, null, path + key, issues)
                        continue
                    }
                    visit(propertySchema as JsonObject, present, path + key, issues)?.let { out[key] = it }
                }
                return JsonObject(out)
            }
        }
        return v
    }

    private fun expected(schema: JsonObject, type: String) = when {
        type == "integer" -> "number"
        // z.record has no properties, only additionalProperties.
        type == "object" && schema["properties"] == null && schema["additionalProperties"] != null -> "record"
        else -> type
    }

    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
}
