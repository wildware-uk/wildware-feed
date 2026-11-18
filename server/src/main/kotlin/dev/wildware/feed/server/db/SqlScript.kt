package dev.wildware.feed.server.db

/**
 * Splits a migration into statements the way SQLite's own `exec` reads them: at each `;` that is
 * not inside a string, a quoted name or a comment. No migration defines a trigger, whose body
 * would hold `;`s of its own; [split] refuses one rather than cutting it wrongly.
 */
object SqlScript {
    fun split(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        fun flush() {
            val text = current.toString().trim()
            if (text.isNotEmpty() && text.lines().any { line -> line.trim().let { it.isNotEmpty() && !it.startsWith("--") } }) {
                require(!Regex("^\\s*CREATE\\s+(TEMP\\s+|TEMPORARY\\s+)?TRIGGER", RegexOption.IGNORE_CASE).containsMatchIn(stripComments(text))) {
                    "triggers are not supported by this splitter"
                }
                statements += text
            }
            current.clear()
        }
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '-' && sql.startsWith("--", i) -> {
                    val end = sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
                    current.append(sql, i, end); i = end
                }
                c == '/' && sql.startsWith("/*", i) -> {
                    val end = sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                    current.append(sql, i, end); i = end
                }
                c == '\'' || c == '"' || c == '`' || c == '[' -> {
                    val close = if (c == '[') ']' else c
                    var j = i + 1
                    while (j < sql.length) {
                        if (sql[j] == close) {
                            if (close != ']' && j + 1 < sql.length && sql[j + 1] == close) { j += 2; continue }
                            break
                        }
                        j++
                    }
                    val end = minOf(j + 1, sql.length)
                    current.append(sql, i, end); i = end
                }
                c == ';' -> { current.append(c); flush(); i++ }
                else -> { current.append(c); i++ }
            }
        }
        flush()
        return statements
    }

    private fun stripComments(text: String) = text.lines().filterNot { it.trim().startsWith("--") }.joinToString("\n")
}
