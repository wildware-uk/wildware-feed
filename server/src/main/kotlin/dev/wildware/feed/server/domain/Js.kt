package dev.wildware.feed.server.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.floor

/**
 * The bits of JavaScript the wire depends on: `Number(string)`, `String(number)`, object key order
 * and `encodeURIComponent`. The TS server's bodies are `JSON.stringify` of values it got from
 * `JSON.parse`, so a stored JSON column must come back out exactly as JavaScript would print it.
 */
object Js {
    private val parser = Json

    /** JavaScript's `Number(text)`: NaN when it is not a number. */
    fun number(text: String): Double {
        val s = text.jsTrim()
        if (s.isEmpty()) return 0.0
        val radix = when {
            s.length > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X') -> 16
            s.length > 2 && s[0] == '0' && (s[1] == 'o' || s[1] == 'O') -> 8
            s.length > 2 && s[0] == '0' && (s[1] == 'b' || s[1] == 'B') -> 2
            else -> 0
        }
        if (radix != 0) {
            val digits = s.substring(2)
            return runCatching { BigInteger(digits, radix).toDouble() }.getOrDefault(Double.NaN)
        }
        when (s) {
            "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY
            "-Infinity" -> return Double.NEGATIVE_INFINITY
        }
        if (!Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$").matches(s)) return Double.NaN
        return s.toDouble()
    }

    /** `Number.isInteger`. */
    fun isInteger(d: Double): Boolean = d.isFinite() && d == floor(d)

    /** `Number.isSafeInteger`. */
    fun isSafeInteger(d: Double): Boolean = isInteger(d) && abs(d) <= 9_007_199_254_740_991.0

    /** JavaScript's `String(number)` (ECMAScript Number::toString). */
    fun numberToString(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d == 0.0) return "0"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        if (d < 0) return "-" + numberToString(-d)
        // Double.toString is the shortest round-tripping decimal (JDK 19+), as JavaScript's is.
        val bd = shortest(BigDecimal(d.toString()).stripTrailingZeros(), d)
        val digits = bd.unscaledValue().toString()
        val k = digits.length
        val n = k - bd.scale()
        return when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                mantissa + "e" + (if (e >= 0) "+" else "-") + abs(e)
            }
        }
    }

    /**
     * Java always writes at least two significant digits (`4.9E-324`) where JavaScript writes one
     * when one is enough (`5e-324`); try the one-digit neighbours, nearest first.
     */
    private fun shortest(bd: BigDecimal, d: Double): BigDecimal {
        if (bd.precision() != 2) return bd
        val exact = BigDecimal(d)
        val one = java.math.MathContext(1, java.math.RoundingMode.FLOOR)
        val down = bd.round(one)
        val up = bd.round(java.math.MathContext(1, java.math.RoundingMode.CEILING))
        return listOf(down, up)
            .filter { it.toDouble() == d }
            .minByOrNull { (it - exact).abs() }
            ?.stripTrailingZeros() ?: bd
    }

    /** `JSON.parse` of a stored column, then put into the shape `JSON.stringify` would print. */
    fun parse(text: String): JsonElement {
        val parsed = parser.parseToJsonElement(text)
        // kotlinx reads a bare word like `nope` as a value; `JSON.parse` refuses it.
        fun strict(e: JsonElement): Boolean = when (e) {
            is JsonNull -> true
            is JsonPrimitive -> e.isString || e.content == "true" || e.content == "false" || JSON_NUMBER.matches(e.content)
            is JsonObject -> e.values.all(::strict)
            is JsonArray -> e.all(::strict)
        }
        if (!strict(parsed)) throw IllegalArgumentException("not JSON: $text")
        return normalize(parsed)
    }

    private val JSON_NUMBER = Regex("^-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?$")

    /** Like [parse], but null for SQL NULL. */
    fun parseOrNull(text: String?): JsonElement? = text?.let(::parse)

    /** A number as JavaScript prints it, as an unquoted JSON literal. */
    @OptIn(ExperimentalSerializationApi::class)
    fun numberLiteral(d: Double): JsonElement =
        if (d.isFinite()) JsonUnquotedLiteral(numberToString(d)) else JsonNull

    /**
     * What a JSON value becomes after a JavaScript round trip: integer-like keys first in ascending
     * order (then the rest in insertion order), numbers printed as JavaScript prints them.
     */
    fun normalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(orderedKeys(element.keys).associateWith { normalize(element.getValue(it)) })
        is JsonArray -> JsonArray(element.map(::normalize))
        is JsonNull -> element
        is JsonPrimitive -> when {
            element.isString -> element
            element.content == "true" || element.content == "false" -> element
            else -> numberLiteral(element.content.toDouble())
        }
    }

    /** The order `Object.keys` gives: array indices ascending, then strings as inserted. */
    fun orderedKeys(keys: Collection<String>): List<String> {
        val (indices, rest) = keys.partition(::isArrayIndex)
        return indices.sortedBy { it.toLong() } + rest
    }

    private fun isArrayIndex(key: String): Boolean {
        if (key.isEmpty() || key.length > 10) return false
        if (key != "0" && (key[0] == '0' || key.any { it !in '0'..'9' })) return false
        if (key == "0") return true
        return key.toLong() < 4_294_967_295L
    }

    /** `encodeURIComponent`. */
    fun encodeURIComponent(text: String): String {
        val out = StringBuilder()
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-_.!~*'()")) out.append(ch)
            else out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
        }
        return out.toString()
    }
}

/** JavaScript truthiness of a JSON value (`undefined` is Kotlin null). */
fun JsonElement?.truthy(): Boolean = when (this) {
    null, is JsonNull -> false
    is JsonPrimitive -> when {
        isString -> content.isNotEmpty()
        content == "true" -> true
        content == "false" -> false
        else -> content.toDouble().let { it != 0.0 && !it.isNaN() }
    }
    else -> true
}

/** `typeof value === 'string'`. */
val JsonElement?.jsString: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** `typeof value === 'boolean'`. */
val JsonElement?.jsBoolean: Boolean? get() =
    (this as? JsonPrimitive)?.takeIf { !it.isString && this !is JsonNull }?.content?.let {
        when (it) { "true" -> true; "false" -> false; else -> null }
    }

/** `typeof value === 'number'` (always finite in parsed JSON, bar overflow). */
val JsonElement?.jsNumber: Double? get() =
    (this as? JsonPrimitive)?.takeIf { !it.isString && this !is JsonNull && it.content != "true" && it.content != "false" }
        ?.content?.toDouble()
