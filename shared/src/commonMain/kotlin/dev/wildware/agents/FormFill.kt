package dev.wildware.agents

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * One field of a form an agent asked to be filled in (request_input kind "form" with `fields`), as
 * the server checked it. [min] and [max] are numbers, or dates ("2026-12-31") for a date field.
 */
@Serializable
data class FormFieldView(
    val id: String,
    val label: String,
    val type: String,
    val hint: String? = null,
    val required: Boolean = false,
    /** `default` on the wire, a keyword in Swift. */
    @SerialName("default") val defaultValue: JsonElement? = null,
    val options: List<String> = emptyList(),
    val min: JsonElement? = null,
    val max: JsonElement? = null,
    val placeholder: String? = null,
) {
    /** Null for a type newer than this app. */
    val fieldType: FieldType? get() = FieldType.fromWire(type)
}

enum class FieldType(val wire: String) {
    Text("text"),
    TextArea("textarea"),
    Number("number"),
    Email("email"),
    Url("url"),
    Date("date"),
    Select("select"),
    Radio("radio"),
    Checkbox("checkbox"),
    Checkboxes("checkboxes"),
    Toggle("toggle");

    companion object {
        fun fromWire(wire: String): FieldType? = entries.firstOrNull { it.wire == wire }
    }
}

/** What a person has put in one field so far: typed words, a tick, or the options ticked. */
sealed interface FieldInput {
    data class Typed(val text: String) : FieldInput
    data class Ticked(val on: Boolean) : FieldInput
    data class Picked(val options: Set<String>) : FieldInput
}

/**
 * Filling in a form: where each field starts, what is wrong with it, and the answer to send. The
 * checks are the server's, so Send is only on when the server would take it; the server still
 * checks every answer itself.
 */
object FormFill {
    /** The longest text the server takes when the agent set no maximum. */
    const val TextMost = 10_000

    /** Whether this app can draw every field; if not, the card says to update the app. */
    fun understands(fields: List<FormFieldView>): Boolean = fields.all { it.fieldType != null }

    /** A field's starting input: the agent's default, or empty. */
    fun start(field: FormFieldView): FieldInput {
        val default = field.defaultValue?.takeIf { it !is JsonNull }
        return when (field.fieldType) {
            FieldType.Checkbox, FieldType.Toggle -> FieldInput.Ticked((default as? JsonPrimitive)?.booleanOrNull == true)
            FieldType.Checkboxes -> FieldInput.Picked((default as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.filter { it in field.options }?.toSet().orEmpty())
            FieldType.Number -> FieldInput.Typed((default as? JsonPrimitive)?.doubleOrNull?.let(::numberText).orEmpty())
            else -> FieldInput.Typed((default as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty())
        }
    }

    fun start(fields: List<FormFieldView>): Map<String, FieldInput> = fields.associate { it.id to start(it) }

    /** Why [input] can't be sent, or null when it can. [empty] says the problem is only that it is unfilled. */
    data class Problem(val text: String, val empty: Boolean = false)

    fun problem(field: FormFieldView, input: FieldInput?): Problem? {
        val type = field.fieldType ?: return Problem("This app can't fill in this field. Update the app.")
        return when (type) {
            FieldType.Checkbox, FieldType.Toggle -> {
                val on = (input as? FieldInput.Ticked)?.on == true
                if (field.required && !on) Problem("Tick this to send.", empty = true) else null
            }
            FieldType.Checkboxes -> {
                val picked = (input as? FieldInput.Picked)?.options.orEmpty().count { it in field.options }
                val least = maxOf(field.min.number()?.toInt() ?: 0, if (field.required) 1 else 0)
                val most = field.max.number()?.toInt()
                when {
                    picked < least -> Problem(if (least == 1) "Tick at least one." else "Tick at least $least ($picked so far).", empty = picked == 0)
                    most != null && picked > most -> Problem("Tick at most $most.")
                    else -> null
                }
            }
            else -> {
                val text = (input as? FieldInput.Typed)?.text?.trim().orEmpty()
                if (text.isEmpty()) return if (field.required) Problem("Required.", empty = true) else null
                typedProblem(field, type, text)?.let(::Problem)
            }
        }
    }

    private fun typedProblem(field: FormFieldView, type: FieldType, text: String): String? = when (type) {
        FieldType.Email -> "Write an email address, like sam@example.com.".takeIf { !Email.matches(text) }
        FieldType.Url -> "Write a web address starting http:// or https://.".takeIf { !Url.matches(text) }
        FieldType.Date -> when {
            !isDate(text) -> "Write a date as year-month-day, like 2026-12-31."
            field.min.string()?.let { text < it } == true -> "On or after ${field.min.string()}."
            field.max.string()?.let { text > it } == true -> "On or before ${field.max.string()}."
            else -> null
        }
        FieldType.Number -> {
            val n = number(text)
            when {
                n == null -> "Write a number."
                field.min.number()?.let { n < it } == true -> "At least ${numberText(field.min.number()!!)}."
                field.max.number()?.let { n > it } == true -> "At most ${numberText(field.max.number()!!)}."
                else -> null
            }
        }
        FieldType.Select, FieldType.Radio -> "Pick one of the options.".takeIf { text !in field.options }
        else -> {
            val least = field.min.number()?.toInt() ?: 0
            val most = field.max.number()?.toInt() ?: TextMost
            when {
                text.length < least -> "At least $least characters (${text.length} so far)."
                text.length > most -> (text.length - most).let { "$it character${if (it == 1) "" else "s"} too long (at most $most)." }
                else -> null
            }
        }
    }

    /** The fields that still stop it being sent, in order. */
    fun blocking(fields: List<FormFieldView>, inputs: Map<String, FieldInput>): List<FormFieldView> =
        fields.filter { problem(it, inputs[it.id]) != null }

    fun ready(fields: List<FormFieldView>, inputs: Map<String, FieldInput>): Boolean = blocking(fields, inputs).isEmpty()

    /** What to send: `{ action, values }`, every field as the server reads it, empty optional ones left out. */
    fun answer(fields: List<FormFieldView>, action: String, inputs: Map<String, FieldInput>): JsonObject = buildJsonObject {
        put("action", action)
        put("values", buildJsonObject {
            for (field in fields) {
                val input = inputs[field.id] ?: start(field)
                when (field.fieldType) {
                    FieldType.Checkbox, FieldType.Toggle -> put(field.id, (input as? FieldInput.Ticked)?.on == true)
                    FieldType.Checkboxes -> {
                        val picked = (input as? FieldInput.Picked)?.options.orEmpty()
                        put(field.id, JsonArray(field.options.filter { it in picked }.map(::JsonPrimitive)))
                    }
                    FieldType.Number -> (input as? FieldInput.Typed)?.text?.trim()?.let(::number)?.let { n ->
                        put(field.id, if (n == kotlin.math.floor(n) && kotlin.math.abs(n) < 1e15) JsonPrimitive(n.toLong()) else JsonPrimitive(n))
                    }
                    else -> (input as? FieldInput.Typed)?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { put(field.id, it) }
                }
            }
        })
    }

    /**
     * What was sent, field by field, in words, for the read-only card once it is answered: each
     * field's label with its value, ticks as Yes or No, empty ones as "—".
     */
    fun said(fields: List<FormFieldView>, values: JsonObject?): List<Pair<String, String>> = fields.map { field ->
        val value = values?.get(field.id)
        val words = when (value) {
            null, is JsonNull -> null
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ").ifEmpty { null }
            is JsonPrimitive -> when {
                value.isString -> value.content.ifBlank { null }
                value.booleanOrNull != null -> if (value.booleanOrNull == true) "Yes" else "No"
                else -> value.doubleOrNull?.let(::numberText) ?: value.content
            }
            is JsonObject -> null
        }
        field.label to (words ?: "—")
    }

    /** The values of a form-with-fields answer (`{ kind, value: { action, values } }`), or null for any other answer. */
    fun valuesOf(answer: JsonElement?): JsonObject? =
        ((answer as? JsonObject)?.get("value") as? JsonObject)?.get("values") as? JsonObject

    private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A typed number, read as the server reads one: digits, a point, a sign, an exponent. */
    fun number(text: String): Double? = text.takeIf { NumberText.matches(it) }?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /** A number as a person writes it: 5, not 5.0. */
    fun numberText(n: Double): String = if (n == kotlin.math.floor(n) && kotlin.math.abs(n) < 1e15) n.toLong().toString() else n.toString()

    fun isDate(text: String): Boolean {
        val m = DateText.matchEntire(text) ?: return false
        val (y, mo, d) = m.destructured.toList().map { it.toInt() }
        if (mo !in 1..12 || d < 1) return false
        val leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[mo - 1]
        return d <= days
    }

    private val Email = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val Url = Regex("^https?://[^\\s/?#@]+(?:[/?#]\\S*)?$", RegexOption.IGNORE_CASE)
    private val DateText = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")
    private val NumberText = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
}
