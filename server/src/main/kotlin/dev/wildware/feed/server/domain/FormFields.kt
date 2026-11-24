package dev.wildware.feed.server.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * A form with several fields (`request_input` kind "form" with `fields`): text boxes, dropdowns,
 * radios, ticks and so on, each with its own rules. [check] checks what the agent asked for and
 * keeps it in config.fields; [answer] checks what a person sent against it.
 *
 * The answer is `{ action, values: { <id>: string | number | boolean | string[] } }`. An optional
 * field left empty is left out of values; a checkbox or toggle is always there (false when not
 * ticked), and checkboxes always a list.
 */
object FormFields {
    const val MAX = 20
    const val ID_MAX = 64
    const val LABEL_MAX = 100
    const val HINT_MAX = 300
    const val PLACEHOLDER_MAX = 200
    const val OPTIONS_MAX = 25
    const val OPTION_MAX = 200
    const val TEXT_MAX = 10_000

    /** Every type, in the order the tool description lists them. */
    val TYPES = listOf("text", "textarea", "number", "email", "url", "date", "select", "radio", "checkbox", "checkboxes", "toggle")

    /** What an older app shows above its one box, which can't hold these fields. */
    const val OLD_APP_LABEL = "This form needs a newer app"
    const val OLD_APP_PLACEHOLDER = "Update the app, or open it on the web, to fill it in."

    private val KEYS = setOf("id", "label", "type", "hint", "required", "default", "options", "min", "max", "placeholder")
    private val TEXTUAL = setOf("text", "textarea", "email", "url")
    private val PLACEHELD = setOf("text", "textarea", "number", "email", "url")
    private val LISTED = setOf("select", "radio", "checkboxes")
    private val BOOLEAN = setOf("checkbox", "toggle")
    private val ID = Regex("^[A-Za-z_][A-Za-z0-9_.-]*$")
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val URL = Regex("^https?://[^\\s/?#@]+(?:[/?#]\\S*)?$", RegexOption.IGNORE_CASE)
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** True for a form that asked with fields. */
    fun of(config: JsonObject?): JsonArray? = config?.get("fields") as? JsonArray

    /** The fields as the agent wrote them, checked strictly: an unknown key or type refuses the lot. */
    fun check(raw: JsonArray): JsonArray {
        if (raw.isEmpty()) throw invalid("fields needs at least one field")
        if (raw.size > MAX) throw invalid("a form has at most $MAX fields")
        val ids = HashSet<String>()
        return JsonArray(raw.mapIndexed { i, item -> field(item, "fields[$i]").also { if (!ids.add(it.id())) throw invalid("fields[$i].id ${it.id()} is used twice") } })
    }

    private fun JsonObject.id() = (this["id"] as JsonPrimitive).content

    private fun field(item: JsonElement, at: String): JsonObject {
        val o = item as? JsonObject ?: throw invalid("$at must be an object { id, label, type, ... }")
        o.keys.firstOrNull { it !in KEYS }?.let { throw invalid("$at: unknown key \"$it\" (a field takes ${KEYS.joinToString(", ")})") }
        val type = o["type"].jsString ?: throw invalid("$at.type is required: one of ${TYPES.joinToString(", ")}")
        if (type !in TYPES) throw invalid("$at.type \"$type\" is not a field type: use one of ${TYPES.joinToString(", ")}")
        val id = o["id"].jsString ?: throw invalid("$at.id is required")
        if (id.isEmpty() || id.length > ID_MAX || !ID.matches(id)) {
            throw invalid("$at.id must be 1-$ID_MAX letters, digits, _ . or -, starting with a letter or _")
        }
        val label = requiredText(o["label"].jsString ?: throw invalid("$at.label is required"), "$at.label", LABEL_MAX)
        val hint = o["hint"]?.let { optionalText(it.jsString ?: throw invalid("$at.hint must be a string"), "$at.hint", HINT_MAX) }
        val required = o["required"]?.let { it.jsBoolean ?: throw invalid("$at.required must be true or false") } ?: false
        if (required && type == "toggle") throw invalid("$at: a toggle is always on or off, so it can't be required")
        val placeholder = o["placeholder"]?.let { p ->
            if (type !in PLACEHELD) throw invalid("$at: placeholder is for a text, textarea, number, email or url field")
            optionalText(p.jsString ?: throw invalid("$at.placeholder must be a string"), "$at.placeholder", PLACEHOLDER_MAX)
        }
        val options = o["options"]?.let { list ->
            if (type !in LISTED) throw invalid("$at: options are for a select, radio or checkboxes field")
            val items = list as? JsonArray ?: throw invalid("$at.options must be a list of strings")
            if (items.isEmpty()) throw invalid("$at.options needs at least one option")
            if (items.size > OPTIONS_MAX) throw invalid("$at.options: at most $OPTIONS_MAX options")
            val clean = items.mapIndexed { j, opt -> requiredText(opt.jsString ?: throw invalid("$at.options[$j] must be a string"), "$at.options[$j]", OPTION_MAX) }
            if (clean.toSet().size != clean.size) throw invalid("$at.options must be distinct")
            clean
        }
        if (type in LISTED && options == null) throw invalid("$at: a $type field needs options")
        val (min, max) = bounds(o, type, options?.size ?: 0, at)

        val checked = buildJsonObject {
            put("id", id)
            put("label", label)
            put("type", type)
            hint?.let { put("hint", it) }
            if (required) put("required", true)
            placeholder?.let { put("placeholder", it) }
            options?.let { put("options", JsonArray(it.map(::JsonPrimitive))) }
            min?.let { put("min", it) }
            max?.let { put("max", it) }
        }
        // A default must be an answer the field would take, or the form starts out unsendable.
        val default = o["default"]?.takeIf { it !is JsonNull }?.let { value(checked, it, "$at.default", forDefault = true) }
        return if (default == null) checked else JsonObject(checked + ("default" to default))
    }

    /** min and max, by type: characters for text, the value for a number or date, how many for checkboxes. */
    private fun bounds(o: JsonObject, type: String, options: Int, at: String): Pair<JsonElement?, JsonElement?> {
        val rawMin = o["min"]?.takeIf { it !is JsonNull }
        val rawMax = o["max"]?.takeIf { it !is JsonNull }
        if (rawMin == null && rawMax == null) return null to null
        return when (type) {
            in TEXTUAL, "checkboxes" -> {
                val ceiling = if (type == "checkboxes") options else TEXT_MAX
                val what = if (type == "checkboxes") "how many may be ticked" else "a length in characters"
                fun whole(e: JsonElement?, key: String): Double? = e?.let {
                    val n = it.jsNumber ?: throw invalid("$at.$key must be a whole number ($what)")
                    if (!Js.isInteger(n) || n < 0) throw invalid("$at.$key must be a whole number ($what)")
                    if (n > ceiling) throw invalid("$at.$key must be at most $ceiling")
                    n
                }
                val min = whole(rawMin, "min")
                val max = whole(rawMax, "max")
                if (min != null && max != null && min > max) throw invalid("$at: min must not exceed max")
                min?.let(Js::numberLiteral) to max?.let(Js::numberLiteral)
            }
            "number" -> {
                fun num(e: JsonElement?, key: String): Double? = e?.let { it.jsNumber?.takeIf(Double::isFinite) ?: throw invalid("$at.$key must be a number") }
                val min = num(rawMin, "min")
                val max = num(rawMax, "max")
                if (min != null && max != null && min > max) throw invalid("$at: min must not exceed max")
                min?.let(Js::numberLiteral) to max?.let(Js::numberLiteral)
            }
            "date" -> {
                fun day(e: JsonElement?, key: String): String? = e?.let {
                    val s = it.jsString
                    if (s == null || !isDate(s)) throw invalid("$at.$key must be a date like \"2026-12-31\"")
                    s
                }
                val min = day(rawMin, "min")
                val max = day(rawMax, "max")
                if (min != null && max != null && min > max) throw invalid("$at: min must not be after max")
                min?.let(::JsonPrimitive) to max?.let(::JsonPrimitive)
            }
            else -> throw invalid("$at: min and max are for a text, textarea, email, url, number, date or checkboxes field")
        }
    }

    private fun isDate(s: String) = DATE.matches(s) && runCatching { LocalDate.parse(s) }.isSuccess

    /**
     * A person's answer to a form with [fields]: `{ action, values }`, every field checked by its
     * rules. Returns the value to keep, with every field in the order the agent asked.
     */
    fun answer(fields: JsonArray, actions: List<String>, value: JsonElement): JsonObject {
        if (value !is JsonObject) throw invalid("a form is answered with { action, values }")
        value.keys.firstOrNull { it != "action" && it != "values" }?.let {
            if (it == "text") throw invalid("This form has ${fields.size} fields. Update the app, or answer it on the web, to fill them in.")
            throw invalid("a form's answer has only action and values, not \"$it\"")
        }
        val action = value["action"].jsString
        if (action == null || action !in actions) throw invalid("${jsonStringifyJs(value["action"])} is not one of the actions offered")
        val given = value["values"] ?: throw invalid("This form has ${fields.size} fields. Update the app, or answer it on the web, to fill them in.")
        if (given !is JsonObject) throw invalid("a form's values are an object, by field id")
        val byId = fields.associateBy { ((it as JsonObject)["id"] as JsonPrimitive).content }
        given.keys.firstOrNull { it !in byId }?.let { throw invalid("this form has no field \"$it\"") }
        val out = LinkedHashMap<String, JsonElement>()
        for ((id, f) in byId) {
            val field = f as JsonObject
            value(field, given[id], label(field), forDefault = false)?.let { out[id] = it }
        }
        return buildJsonObject {
            put("action", action)
            put("values", JsonObject(out))
        }
    }

    private fun label(field: JsonObject) = "\"${(field["label"] as JsonPrimitive).content}\""

    /**
     * One field's value checked by its rules, or null when an optional field is left empty. [at]
     * names it in the error. A default is checked the same way, except that it needn't fill a
     * required field or reach a least length or count: the person finishes it.
     */
    private fun value(field: JsonObject, raw: JsonElement?, at: String, forDefault: Boolean): JsonElement? {
        val type = (field["type"] as JsonPrimitive).content
        val required = field["required"].jsBoolean == true && !forDefault
        val given = raw?.takeIf { it !is JsonNull }
        fun missing(): JsonElement? = if (required) throw invalid("$at is required") else null
        fun num(key: String) = field[key].jsNumber
        fun n(d: Double) = Js.numberToString(d)
        fun options() = (field["options"] as JsonArray).map { (it as JsonPrimitive).content }
        return when (type) {
            in TEXTUAL, "date", "select", "radio" -> {
                if (given == null) return missing()
                val text = (given.jsString ?: throw invalid("$at must be a string")).jsTrim()
                if (text.isEmpty()) return missing()
                when (type) {
                    "email" -> if (!EMAIL.matches(text)) throw invalid("$at must be an email address, like sam@example.com")
                    "url" -> if (!URL.matches(text)) throw invalid("$at must be a web address starting http:// or https://")
                    "date" -> {
                        if (!isDate(text)) throw invalid("$at must be a date like 2026-12-31")
                        field["min"].jsString?.let { if (text < it) throw invalid("$at must be on or after $it") }
                        field["max"].jsString?.let { if (text > it) throw invalid("$at must be on or before $it") }
                    }
                    "select", "radio" -> if (text !in options()) throw invalid("${jsonStringifyJs(JsonPrimitive(text))} is not one of the options for $at")
                }
                if (type in TEXTUAL) {
                    val most = num("max") ?: TEXT_MAX.toDouble()
                    if (!forDefault) num("min")?.let { if (text.length < it) throw invalid("$at must be at least ${n(it)} characters") }
                    if (text.length > most) throw invalid("$at must be at most ${n(most)} characters")
                }
                JsonPrimitive(text)
            }
            "number" -> {
                if (given == null) return missing()
                val d = given.jsNumber?.takeIf(Double::isFinite) ?: throw invalid("$at must be a number")
                num("min")?.let { if (d < it) throw invalid("$at must be at least ${n(it)}") }
                num("max")?.let { if (d > it) throw invalid("$at must be at most ${n(it)}") }
                Js.numberLiteral(d)
            }
            in BOOLEAN -> {
                val b = if (given == null) false else given.jsBoolean ?: throw invalid("$at must be true or false")
                if (required && !b) throw invalid("$at must be ticked")
                JsonPrimitive(b)
            }
            "checkboxes" -> {
                val list = if (given == null) JsonArray(emptyList()) else given as? JsonArray ?: throw invalid("$at must be a list of its options")
                val offered = options()
                val picked = list.map { item ->
                    item.jsString?.takeIf { it in offered } ?: throw invalid("${jsonStringifyJs(item)} is not one of the options for $at")
                }
                if (picked.toSet().size != picked.size) throw invalid("$at has an option ticked twice")
                if (required && picked.isEmpty()) throw invalid("$at is required: tick at least one")
                if (!forDefault) num("min")?.let { if (picked.size < it) throw invalid("$at: tick at least ${n(it)}") }
                num("max")?.let { if (picked.size > it) throw invalid("$at: tick at most ${n(it)}") }
                // In the order offered, so the answer reads the same whatever order they were ticked in.
                JsonArray(offered.filter { it in picked }.map(::JsonPrimitive))
            }
            else -> throw invalid("$at has an unknown type $type")
        }
    }

    /** One line for the agent: the action and what was filled in. */
    fun describe(value: JsonObject): String {
        val values = value["values"] as? JsonObject ?: JsonObject(emptyMap())
        val filled = values.entries.filter { (_, v) -> v !is JsonArray || v.isNotEmpty() }.map { it.key }
        val what = if (filled.isEmpty()) "nothing filled in" else "values for ${filled.joinToString(", ")}"
        return "${jsonStringifyJs(value["action"])} with $what. Read each in response.value.values, by field id."
    }
}
