package dev.wildware.feed.server.domain

/** A refusal the caller can act on, with the TS server's code and status (spec/02 §1.2). */
open class DomainError(val code: String, override val message: String, val status: Int) : RuntimeException(message)

fun invalid(message: String) = DomainError("invalid_argument", message, 400)
fun notFound(message: String) = DomainError("not_found", message, 404)
fun conflict(message: String) = DomainError("conflict", message, 409)

/** `requiredText` from the TS domain: trimmed, non-empty, at most [max] UTF-16 units. */
fun requiredText(value: String, field: String, max: Int): String {
    val trimmed = value.jsTrim()
    if (trimmed.isEmpty()) throw invalid("$field is required")
    if (trimmed.length > max) throw invalid("$field must be at most $max characters")
    return trimmed
}

/**
 * JavaScript's `String.prototype.trim`: strips JS whitespace and line terminators, which is not the
 * same set as Kotlin's `trim()` (spec/README.md, "Text rules use JavaScript semantics").
 */
fun String.jsTrim(): String {
    var start = 0
    var end = length
    while (start < end && this[start].isJsWhitespace()) start++
    while (end > start && this[end - 1].isJsWhitespace()) end--
    return substring(start, end)
}

fun Char.isJsWhitespace(): Boolean = when (this) {
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', ' ', ' ', ' ',
    ' ', ' ', ' ', ' ', '　', '﻿' -> true
    in ' '..' ' -> true
    else -> false
}
