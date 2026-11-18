package dev.wildware.feed.server.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Reading embeds back (TS `src/domain/embeds.ts`): a stored spec is re-parsed by `parseEmbedSpec`,
 * which rebuilds it in a fixed key order, and stored data by `parseEmbedData`. The error texts are
 * the TS server's, though on the read side any refusal only means "drop it" (spec) or "`{}`" (data).
 */
object Embeds {
    const val VALUE_MAX_LENGTH = 20_000
    const val BLOCKS_MAX = 24
    const val FIELDS_MAX = 12
    const val DATA_KEYS_MAX = 40

    private val BLOCK_TYPES = listOf("header", "heading", "text", "markdown", "code", "quote", "fields", "chips", "image", "divider", "footer")
    private val FONTS = listOf("sans", "serif", "mono")
    private val RADII = listOf("none", "sm", "md", "lg")
    private val AVATAR_SHAPES = listOf("circle", "rounded", "square")
    private val SIZES = listOf("sm", "md", "lg")
    private val HEADER_LAYOUTS = listOf("inline", "stacked")
    private val TONES = listOf("normal", "muted", "accent")
    private val CHIP_STYLES = listOf("pill", "lozenge", "tag")
    private val FIELD_LAYOUTS = listOf("rows", "inline")
    private val PADDINGS = listOf("tight", "normal", "roomy")
    private val MARKS = listOf("slack", "github", "gitlab", "jira", "confluence", "linear", "notion", "figma", "link")
    val THEME_COLOUR = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

    private fun JsonElement?.absent() = this == null || this is JsonNull

    private fun colour(value: JsonElement?, field: String): String {
        val s = value.jsString
        if (s == null || !THEME_COLOUR.matches(s.jsTrim())) throw invalid("$field must be a hex colour like #1a2b3c")
        val hex = s.jsTrim().substring(1).lowercase()
        return "#" + if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
    }

    private fun text(value: JsonElement?, field: String, required: Boolean = false): String? {
        if (value.absent()) {
            if (required) throw invalid("$field is required")
            return null
        }
        val s = value.jsString ?: throw invalid("$field must be text")
        if (s.length > VALUE_MAX_LENGTH) throw invalid("$field must be at most $VALUE_MAX_LENGTH characters")
        return s
    }

    private fun obj(value: JsonElement?, field: String): JsonObject =
        value as? JsonObject ?: throw invalid("$field must be an object")

    private fun oneOf(value: JsonElement?, allowed: List<String>, field: String): String? {
        if (value.absent()) return null
        val s = value.jsString
        if (s == null || s !in allowed) throw invalid("$field must be one of: ${allowed.joinToString(", ")}")
        return s
    }

    /** `if (x) block.k = x` for a string: the empty string is falsy. */
    private fun JsonObjectBuilder.putTruthy(key: String, value: String?) {
        if (!value.isNullOrEmpty()) put(key, value)
    }

    private fun theme(raw: JsonElement?): JsonObject {
        if (raw.absent()) return JsonObject(emptyMap())
        val source = obj(raw, "theme")
        return buildJsonObject {
            for (key in listOf("accent", "background", "foreground", "muted", "border")) {
                if (!source[key].absent()) put(key, colour(source[key], "theme.$key"))
            }
            putTruthy("font", oneOf(source["font"], FONTS, "theme.font"))
            putTruthy("radius", oneOf(source["radius"], RADII, "theme.radius"))
        }
    }

    private fun frame(raw: JsonElement?): JsonObject {
        if (raw.absent()) return JsonObject(emptyMap())
        val source = obj(raw, "frame")
        return buildJsonObject {
            source["border"].jsBoolean?.let { put("border", it) }
            source["stripe"].jsBoolean?.let { put("stripe", it) }
            if (source["indent"].jsBoolean == true) put("indent", true)
            putTruthy("padding", oneOf(source["padding"], PADDINGS, "frame.padding"))
        }
    }

    private fun chrome(raw: JsonElement?): JsonObject {
        if (raw.absent()) return JsonObject(emptyMap())
        val source = obj(raw, "chrome")
        return buildJsonObject {
            text(source["label"], "chrome.label")?.let { put("label", it) }
            text(source["icon"], "chrome.icon")?.let { put("icon", it) }
            putTruthy("mark", oneOf(source["mark"], MARKS, "chrome.mark"))
            text(source["image"], "chrome.image")?.let { put("image", it) }
        }
    }

    private fun block(raw: JsonElement, at: Int): JsonObject {
        val source = obj(raw, "blocks[$at]")
        val type = oneOf(source["type"], BLOCK_TYPES, "blocks[$at].type")
        if (type.isNullOrEmpty()) throw invalid("blocks[$at].type is required")
        fun field(name: String, required: Boolean = false) = text(source[name], "blocks[$at].$name", required)
        fun choice(name: String, allowed: List<String>) = oneOf(source[name], allowed, "blocks[$at].$name")

        return when (type) {
            "header" -> {
                val values = listOf("avatar", "title", "subtitle", "meta").map { it to field(it) }
                val shape = choice("shape", AVATAR_SHAPES)
                val size = choice("size", SIZES)
                val layout = choice("layout", HEADER_LAYOUTS)
                buildJsonObject {
                    put("type", type)
                    for ((k, v) in values) if (v != null) put(k, v)
                    putTruthy("shape", shape)
                    putTruthy("size", size)
                    putTruthy("layout", layout)
                    if (source["bar"].jsBoolean == true) put("bar", true)
                }
            }
            "heading" -> {
                val level = if (source["level"].jsNumber == 2.0) 2 else 1
                val value = field("value", true)!!
                buildJsonObject { put("type", type); put("value", value); put("level", level) }
            }
            "text" -> {
                val tone = choice("tone", TONES)
                val size = choice("size", SIZES)
                val value = field("value", true)!!
                buildJsonObject { put("type", type); put("value", value); putTruthy("tone", tone); putTruthy("size", size) }
            }
            "markdown" -> {
                val size = choice("size", SIZES)
                val value = field("value", true)!!
                buildJsonObject { put("type", type); put("value", value); putTruthy("size", size) }
            }
            "chips" -> {
                val style = choice("style", CHIP_STYLES)
                val value = field("value", true)!!
                buildJsonObject { put("type", type); put("value", value); putTruthy("style", style) }
            }
            "quote" -> buildJsonObject { put("type", type); put("value", field("value", true)!!) }
            "code" -> {
                val language = field("language")
                val value = field("value", true)!!
                buildJsonObject { put("type", type); put("value", value); putTruthy("language", language) }
            }
            "image" -> {
                val alt = field("alt")
                val value = field("value", true)!!
                buildJsonObject { put("type", type); put("value", value); putTruthy("alt", alt) }
            }
            "divider" -> buildJsonObject { put("type", type) }
            "footer" -> {
                val value = field("value")
                val url = field("url")
                buildJsonObject { put("type", type); putTruthy("value", value); putTruthy("url", url) }
            }
            "fields" -> {
                val items = source["items"] as? JsonArray ?: throw invalid("blocks[$at].items must be a list")
                if (items.size > FIELDS_MAX) throw invalid("blocks[$at].items may hold at most $FIELDS_MAX rows")
                val rows = items.mapIndexed { index, entry ->
                    val row = obj(entry, "blocks[$at].items[$index]")
                    buildJsonObject {
                        put("label", text(row["label"], "blocks[$at].items[$index].label", true)!!)
                        put("value", text(row["value"], "blocks[$at].items[$index].value", true)!!)
                    }
                }
                val layout = choice("layout", FIELD_LAYOUTS)
                buildJsonObject { put("type", type); put("items", JsonArray(rows)); putTruthy("layout", layout) }
            }
            else -> error("unreachable")
        }
    }

    /** `parseEmbedSpec`: the spec rebuilt in its canonical shape, or a DomainError. */
    fun parseSpec(raw: JsonElement?): JsonObject {
        val source = obj(raw, "spec")
        val blocks = source["blocks"] as? JsonArray ?: throw invalid("spec.blocks must be a list of blocks")
        if (blocks.isEmpty()) throw invalid("spec.blocks must hold at least one block")
        if (blocks.size > BLOCKS_MAX) throw invalid("spec.blocks may hold at most $BLOCKS_MAX blocks")
        val theme = theme(source["theme"])
        val chrome = chrome(source["chrome"])
        val frame = frame(source["frame"])
        val parsed = blocks.mapIndexed { i, b -> block(b, i) }
        return buildJsonObject {
            put("theme", theme)
            put("chrome", chrome)
            put("frame", frame)
            put("blocks", JsonArray(parsed))
        }
    }

    /** A stored spec as the reader sees it, or null when it cannot be read (the embed is dropped). */
    fun specOf(stored: String): JsonObject? = runCatching { parseSpec(Js.parse(stored)) }.getOrNull()

    /** `parseEmbedData`: flat string values (or lists of them). */
    fun parseData(raw: JsonElement?, field: String): JsonObject {
        val source = obj(if (raw.absent()) JsonObject(emptyMap()) else raw, field)
        val keys = Js.orderedKeys(source.keys)
        if (keys.size > DATA_KEYS_MAX) throw invalid("$field may hold at most $DATA_KEYS_MAX values")
        return buildJsonObject {
            for (key in keys) {
                val value = source.getValue(key)
                if (value is JsonNull) continue
                if (value is JsonArray) {
                    put(key, JsonArray(value.mapIndexed { i, entry -> JsonPrimitive(scalar(entry, "$field.$key[$i]")) }))
                    continue
                }
                put(key, scalar(value, "$field.$key"))
            }
        }
    }

    /** Stored data as the reader sees it: `{}` when it cannot be read. */
    fun dataOf(stored: String): JsonObject = runCatching { parseData(Js.parse(stored), "data") }.getOrElse { JsonObject(emptyMap()) }

    private fun scalar(value: JsonElement, field: String): String {
        value.jsString?.let {
            if (it.length > VALUE_MAX_LENGTH) throw invalid("$field must be at most $VALUE_MAX_LENGTH characters")
            return it
        }
        value.jsNumber?.let { if (it.isFinite()) return Js.numberToString(it) }
        value.jsBoolean?.let { return it.toString() }
        throw invalid("$field must be text, a number, or a list of them")
    }
}
