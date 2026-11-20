package dev.wildware.feed.server.domain

import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * The shapes of blocks (docs/BLOCKS.md): each type checked strictly and written in one canonical
 * form, and a patch merged into a written block. Pure: no database, so every rule is easy to test.
 *
 * The canonical form is what is stored and, with names filled in ([Blocks.view]), what everyone
 * reads. It also holds what only the server knows: who ticked a line and when, when a stage started
 * and ended, how many lines a log dropped, a poll's votes. Those are worked out from the block as it
 * was before ([previous]), never taken from what an agent sent.
 */
object BlockShapes {
    val TYPES = listOf("checklist", "steps", "diff", "chart", "table", "log", "poll")
    val STAGE_STATES = listOf("pending", "running", "done", "failed", "skipped")
    private val ENDED = setOf("done", "failed", "skipped")
    private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")

    const val BLOCKS_MAX = 8
    const val BLOCK_CHARS_MAX = 100_000
    const val TITLE_MAX = 200
    const val ITEMS_MAX = 100
    const val ITEM_TEXT_MAX = 500
    const val OWNER_MAX = 80
    const val STAGES_MAX = 30
    const val LABEL_MAX = 120
    const val DETAIL_MAX = 500
    const val FILES_MAX = 50
    const val DIFF_LINES_MAX = 5_000
    const val DIFF_LINE_MAX = 2_000
    const val PATH_MAX = 500
    const val SERIES_MAX = 8
    const val POINTS_MAX = 500
    const val X_LABEL_MAX = 40
    const val AXIS_MAX = 80
    const val UNIT_MAX = 20
    const val COLUMNS_MAX = 20
    const val ROWS_MAX = 1_000
    const val CELL_MAX = 500
    const val TABLE_LIMIT_DEFAULT = 10
    const val TABLE_LIMIT_MAX = 100
    const val LOG_LINES_MAX = 1_000
    const val LOG_LINE_MAX = 2_000
    const val LOG_CHARS_MAX = 80_000
    const val LOG_TAIL_DEFAULT = 5
    const val LOG_TAIL_MAX = 50
    const val QUESTION_MAX = 300
    const val OPTIONS_MIN = 2
    const val OPTIONS_MAX = 10

    /** What each type takes when written whole, besides `id`, `type` and `title`. */
    private val KEYS = mapOf(
        "checklist" to listOf("items"),
        "steps" to listOf("stages", "progress", "eta"),
        "diff" to listOf("text", "files"),
        "chart" to listOf("kind", "series", "x_label", "y_label", "unit"),
        "table" to listOf("columns", "rows", "limit"),
        "log" to listOf("lines", "text", "highlight", "tail"),
        "poll" to listOf("question", "options", "multi", "closes_at", "closed"),
    )

    /** What a patch of each type may name, besides `title`. */
    private val PATCH_KEYS = mapOf(
        "checklist" to listOf("items", "remove"),
        "steps" to listOf("stages", "remove", "progress", "eta"),
        "diff" to listOf("text", "files"),
        "chart" to listOf("kind", "series", "remove", "x_label", "y_label", "unit"),
        "table" to listOf("columns", "rows", "append_rows", "limit"),
        "log" to listOf("lines", "text", "append", "highlight", "tail"),
        "poll" to listOf("question", "options", "remove", "multi", "closes_at", "closed"),
    )

    /** Each type's list of things with ids, which a patch merges by id. */
    private val LISTS = mapOf("checklist" to "items", "steps" to "stages", "chart" to "series", "poll" to "options")

    /** Keys only the server writes, kept in a stored block but never accepted from an agent. */
    private val SERVER_KEYS = setOf("dropped", "votes")
    private val SERVER_ITEM_KEYS = setOf("done_by", "done_at", "started_at", "ended_at")

    /** Who a change is by: `agent:<id>` or `person:<membership id>`. */
    @JvmInline
    value class Actor(val value: String) {
        companion object {
            fun agent(id: String) = Actor("agent:$id")
            fun person(id: String) = Actor("person:$id")
        }
    }

    /**
     * [raw] (`blocks[at]` from a post) checked and written canonically. [previous] is the block as it
     * was, for what the server works out; [stored] says [raw] is a merge of a stored block (its
     * server keys are allowed through, and ignored).
     */
    fun normalize(
        raw: JsonElement?,
        path: String,
        actor: Actor,
        now: Long,
        previous: JsonObject? = null,
        stored: Boolean = false,
        carryDropped: Boolean = true,
    ): JsonObject {
        val o = obj(raw, path)
        val type = o["type"].jsString ?: throw invalid("$path.type is required: one of ${TYPES.joinToString(", ")}")
        if (type !in TYPES) throw invalid("$path.type must be one of: ${TYPES.joinToString(", ")}")
        val allowed = listOf("id", "type", "title") + KEYS.getValue(type) + if (stored) SERVER_KEYS else emptySet()
        only(o, path, allowed, "a $type block")
        val id = id(o["id"], "$path.id")
        val prev = previous?.takeIf { it["type"].jsString == type }
        val ctx = Ctx(path, actor, now, prev, stored)
        val out = LinkedHashMap<String, JsonElement>()
        out["id"] = JsonPrimitive(id)
        out["type"] = JsonPrimitive(type)
        optionalText(o, "title", path, TITLE_MAX)?.let { out["title"] = JsonPrimitive(it) }
        when (type) {
            "checklist" -> checklist(o, ctx, out)
            "steps" -> steps(o, ctx, out)
            "diff" -> diff(o, ctx, out)
            "chart" -> chart(o, ctx, out)
            "table" -> table(o, ctx, out)
            "log" -> log(o, ctx, out, carryDropped)
            else -> poll(o, ctx, out)
        }
        var block = JsonObject(out)
        var chars = Wire.encode(block).length
        // A log and a chart keep their newest lines and points: written out (escapes and all) they
        // shed the oldest until they fit, rather than refusing the next append for ever.
        while (chars > BLOCK_CHARS_MAX && (type == "log" || type == "chart")) {
            block = shed(block, chars - BLOCK_CHARS_MAX) ?: break
            chars = Wire.encode(block).length
        }
        if (chars > BLOCK_CHARS_MAX) throw invalid("$path is $chars characters written out; a block may be at most $BLOCK_CHARS_MAX")
        return block
    }

    /**
     * [block] (a log or a chart) with its oldest lines or points dropped, about [over] characters'
     * worth and at least one; null when there is nothing left to drop.
     */
    private fun shed(block: JsonObject, over: Int): JsonObject? {
        if (block["type"].jsString == "log") {
            val lines = (block["lines"] as? JsonArray) ?: return null
            if (lines.isEmpty()) return null
            var drop = 0
            var freed = 0
            while (drop < lines.size && (drop == 0 || freed < over)) freed += Wire.encode(lines[drop++]).length + 1
            val dropped = (block["dropped"]?.jsNumber?.toLong() ?: 0) + drop
            return JsonObject(block + ("lines" to JsonArray(lines.drop(drop))) + ("dropped" to JsonPrimitive(dropped)))
        }
        val series = (block["series"] as? JsonArray)?.map { it as JsonObject } ?: return null
        // From the longest series first, a tenth of its points at a time.
        val longest = series.maxByOrNull { (it["points"] as JsonArray).size } ?: return null
        val points = longest["points"] as JsonArray
        if (points.isEmpty()) return null
        val cut = maxOf(1, points.size / 10)
        val trimmed = series.map { if (it === longest) JsonObject(it + ("points" to JsonArray(points.drop(cut)))) else it }
        return JsonObject(block + ("series" to JsonArray(trimmed)))
    }

    /** A list of blocks from a post: at most [BLOCKS_MAX], each id once. */
    fun normalizeAll(raw: List<JsonElement>, actor: Actor, now: Long): List<JsonObject> {
        if (raw.size > BLOCKS_MAX) throw invalid("a card may carry at most $BLOCKS_MAX blocks")
        val blocks = raw.mapIndexed { at, block -> normalize(block, "blocks[$at]", actor, now) }
        val seen = HashSet<String>()
        for ((at, block) in blocks.withIndex()) {
            val id = block["id"].jsString!!
            if (!seen.add(id)) throw invalid("blocks[$at].id \"$id\" is used twice: each block on a card needs its own id")
        }
        return blocks
    }

    /**
     * [patch] applied to [stored] (canonical), checked again whole. A plain value is replaced and
     * null clears it; a list with ids is merged by id; `remove` takes ids away; `append`,
     * `append_rows` and a series' `points` add to the end.
     */
    fun applyPatch(stored: JsonObject, patch: JsonElement?, actor: Actor, now: Long, path: String = "patch"): JsonObject {
        val p = obj(patch, path)
        val type = stored["type"].jsString!!
        if (p.isEmpty()) throw invalid("$path is empty: name what to change")
        only(p, path, listOf("title") + PATCH_KEYS.getValue(type), "a patch of a $type block")
        val out = LinkedHashMap(stored)
        val listKey = LISTS[type]
        var carryDropped = true
        for ((key, value) in p) {
            when {
                key == listKey -> out[key] = mergeById(out[key] as? JsonArray ?: JsonArray(emptyList()), value, "$path.$key", if (type == "chart") setOf("points") else emptySet())
                key == "remove" -> {
                    val list = listKey!!
                    val ids = (value as? JsonArray)?.mapIndexed { at, it -> it.jsString ?: throw invalid("$path.remove[$at] must be an id") }
                        ?: throw invalid("$path.remove must be a list of ids")
                    val existing = (out[list] as? JsonArray).orEmpty()
                    for (id in ids) if (existing.none { (it as JsonObject)["id"].jsString == id }) throw notFound("$path.remove: no ${singular(list)} \"$id\"")
                    out[list] = JsonArray(existing.filter { (it as JsonObject)["id"].jsString !in ids })
                }
                type == "log" && key == "append" -> {
                    val lines = (out["lines"] as? JsonArray).orEmpty() + logLines(value, "$path.append")
                    out["lines"] = JsonArray(lines)
                    out.remove("text")
                }
                type == "log" && (key == "lines" || key == "text") -> {
                    out.remove("lines"); out.remove("text")
                    out["lines"] = JsonArray(logLines(value, "$path.$key"))
                    carryDropped = false
                }
                type == "table" && key == "append_rows" -> {
                    val rows = value as? JsonArray ?: throw invalid("$path.append_rows must be a list of rows")
                    out["rows"] = JsonArray((out["rows"] as? JsonArray).orEmpty() + rows)
                }
                type == "diff" && (key == "text" || key == "files") -> {
                    out.remove("text"); out.remove("files")
                    out[key] = value
                }
                value is JsonNull -> out.remove(key)
                else -> out[key] = value
            }
        }
        return normalize(JsonObject(out), path, actor, now, previous = stored, stored = true, carryDropped = carryDropped)
    }

    /** A person's tick or untick on a checklist line, by [actor]. */
    fun check(stored: JsonObject, itemId: String, done: Boolean, actor: Actor, now: Long): JsonObject {
        if (stored["type"].jsString != "checklist") throw invalid("only a checklist has lines to tick")
        val items = (stored["items"] as? JsonArray).orEmpty()
        if (items.none { (it as JsonObject)["id"].jsString == itemId }) throw notFound("no line \"$itemId\" on that checklist")
        val merged = LinkedHashMap(stored)
        merged["items"] = JsonArray(items.map { item ->
            item as JsonObject
            if (item["id"].jsString == itemId) JsonObject(item + ("done" to JsonPrimitive(done))) else item
        })
        return normalize(JsonObject(merged), "block", actor, now, previous = stored, stored = true)
    }

    /** A person's vote on a poll: [options] replaces theirs; none takes it back. */
    fun vote(stored: JsonObject, options: List<String>, actor: Actor, now: Long): JsonObject {
        if (stored["type"].jsString != "poll") throw invalid("only a poll takes votes")
        if (pollClosed(stored, now)) throw invalid("that poll has closed")
        val known = (stored["options"] as? JsonArray).orEmpty().map { (it as JsonObject)["id"].jsString!! }
        val chosen = options.distinct()
        for (option in chosen) if (option !in known) throw notFound("no option \"$option\" on that poll")
        if (stored["multi"].jsBoolean != true && chosen.size > 1) throw invalid("that poll takes one choice")
        val others = (stored["votes"] as? JsonArray).orEmpty().filter { (it as JsonObject)["by"].jsString != actor.value }
        val mine = if (chosen.isEmpty()) emptyList() else listOf(JsonObject(mapOf(
            "by" to JsonPrimitive(actor.value),
            "options" to JsonArray(chosen.map(::JsonPrimitive)),
            "at" to JsonPrimitive(Wire.isoOf(now)),
        )))
        return JsonObject(LinkedHashMap(stored).apply { put("votes", JsonArray(others + mine)) })
    }

    /** Closed by the agent, or past its closing time. */
    fun pollClosed(block: JsonObject, now: Long): Boolean {
        if (block["closed"].jsBoolean == true) return true
        val closes = block["closes_at"].jsString ?: return false
        return runCatching { OffsetDateTime.parse(closes).toInstant().toEpochMilli() <= now }.getOrDefault(false)
    }

    // ---- the types ------------------------------------------------------------------------------

    private class Ctx(val path: String, val actor: Actor, val now: Long, val prev: JsonObject?, val stored: Boolean) {
        val at: JsonPrimitive get() = JsonPrimitive(Wire.isoOf(now))

        /** The previous version of the thing with [id] in the list [key]. */
        fun before(key: String, id: String): JsonObject? =
            (prev?.get(key) as? JsonArray)?.firstOrNull { (it as JsonObject)["id"].jsString == id } as JsonObject?

        fun itemKeys(keys: List<String>) = if (stored) keys + SERVER_ITEM_KEYS else keys
    }

    private fun checklist(o: JsonObject, c: Ctx, out: MutableMap<String, JsonElement>) {
        val items = list(o, "items", c.path, ITEMS_MAX, required = true)
        out["items"] = JsonArray(uniqueIds(items, "${c.path}.items") { at, raw ->
            val path = "${c.path}.items[$at]"
            val item = obj(raw, path)
            only(item, path, c.itemKeys(listOf("id", "text", "done", "owner")), "a checklist line")
            val id = id(item["id"], "$path.id")
            val done = bool(item, "done", path) ?: false
            val before = c.before("items", id)
            val was = before?.get("done").jsBoolean ?: false
            buildMap {
                put("id", JsonPrimitive(id))
                put("text", JsonPrimitive(text(item, "text", path, ITEM_TEXT_MAX)))
                put("done", JsonPrimitive(done))
                optionalText(item, "owner", path, OWNER_MAX)?.let { put("owner", JsonPrimitive(it)) }
                if (done) {
                    if (was && before != null && before["done_by"] != null) {
                        put("done_by", before["done_by"]!!)
                        before["done_at"]?.let { put("done_at", it) }
                    } else {
                        put("done_by", JsonPrimitive(c.actor.value))
                        put("done_at", c.at)
                    }
                }
            }
        })
    }

    private fun steps(o: JsonObject, c: Ctx, out: MutableMap<String, JsonElement>) {
        val stages = list(o, "stages", c.path, STAGES_MAX, required = true)
        out["stages"] = JsonArray(uniqueIds(stages, "${c.path}.stages") { at, raw ->
            val path = "${c.path}.stages[$at]"
            val stage = obj(raw, path)
            only(stage, path, c.itemKeys(listOf("id", "label", "state", "detail")), "a stage")
            val id = id(stage["id"], "$path.id")
            val state = stage["state"]?.let { it.jsString?.takeIf { s -> s in STAGE_STATES } ?: throw invalid("$path.state must be one of: ${STAGE_STATES.joinToString(", ")}") }
                ?: "pending"
            val before = c.before("stages", id)
            val was = before?.get("state").jsString
            buildMap {
                put("id", JsonPrimitive(id))
                put("label", JsonPrimitive(text(stage, "label", path, LABEL_MAX)))
                put("state", JsonPrimitive(state))
                optionalText(stage, "detail", path, DETAIL_MAX)?.let { put("detail", JsonPrimitive(it)) }
                // When it started: the moment it began running, kept while it runs and after it ends.
                val started = when {
                    state == "pending" -> null
                    // Started again after it ended (a retry): its time counts from now.
                    state == "running" && was in ENDED -> c.at
                    state == "running" && was != "running" && before?.get("started_at") == null -> c.at
                    else -> before?.get("started_at") ?: if (state == "running") c.at else null
                }
                started?.let { put("started_at", it) }
                if (state in ENDED) put("ended_at", if (was in ENDED) before?.get("ended_at") ?: c.at else c.at)
            }
        })
        o["progress"]?.takeIf { it !is JsonNull }?.let {
            val n = it.jsNumber?.takeIf { v -> v.isFinite() && v in 0.0..1.0 } ?: throw invalid("${c.path}.progress must be a number from 0 to 1")
            out["progress"] = JsonPrimitive(n)
        }
        iso(o, "eta", c.path)?.let { out["eta"] = JsonPrimitive(it) }
    }

    private fun diff(o: JsonObject, c: Ctx, out: MutableMap<String, JsonElement>) {
        val text = o["text"]?.takeIf { it !is JsonNull }
        val files = o["files"]?.takeIf { it !is JsonNull }
        if (text != null && files != null) throw invalid("${c.path} takes text or files, not both")
        val parsed: List<DiffFile> = when {
            text != null -> {
                val t = text.jsString ?: throw invalid("${c.path}.text must be a unified diff, as text")
                if (t.length > BLOCK_CHARS_MAX) throw invalid("${c.path}.text may be at most $BLOCK_CHARS_MAX characters")
                UnifiedDiff.parse(t).also { if (it.isEmpty()) throw invalid("${c.path}.text has no files in it: give a unified diff, as git diff prints it") }
            }
            files != null -> {
                val list = files as? JsonArray ?: throw invalid("${c.path}.files must be a list")
                if (list.isEmpty()) throw invalid("${c.path}.files must name at least one file")
                list.mapIndexed { at, raw -> diffFile(raw, "${c.path}.files[$at]") }
            }
            else -> throw invalid("${c.path} needs text (a unified diff) or files")
        }
        if (parsed.size > FILES_MAX) throw invalid("${c.path} has ${parsed.size} files; a diff may show at most $FILES_MAX")
        val lines = parsed.sumOf { f -> f.hunks.sumOf { it.lines.size } }
        if (lines > DIFF_LINES_MAX) throw invalid("${c.path} has $lines lines; a diff may show at most $DIFF_LINES_MAX")
        for ((at, f) in parsed.withIndex()) {
            for (h in f.hunks) for (line in h.lines) {
                if (line.length > DIFF_LINE_MAX) throw invalid("${c.path}: a line in ${f.path} is ${line.length} characters; at most $DIFF_LINE_MAX")
            }
            if (f.path.length > PATH_MAX) throw invalid("${c.path}.files[$at].path may be at most $PATH_MAX characters")
        }
        out["files"] = JsonArray(parsed.map { it.json() })
    }

    private fun diffFile(raw: JsonElement, path: String): DiffFile {
        val f = obj(raw, path)
        only(f, path, listOf("path", "old_path", "status", "hunks", "additions", "deletions", "binary"), "a diff file")
        val name = text(f, "path", path, PATH_MAX)
        val old = optionalText(f, "old_path", path, PATH_MAX)
        val status = f["status"]?.takeIf { it !is JsonNull }?.let {
            it.jsString?.takeIf { s -> s in DiffFile.STATUSES } ?: throw invalid("$path.status must be one of: ${DiffFile.STATUSES.joinToString(", ")}")
        }
        val hunks = (f["hunks"] ?: JsonArray(emptyList())).let { it as? JsonArray ?: throw invalid("$path.hunks must be a list") }
            .flatMapIndexed { at, h -> hunk(h, "$path.hunks[$at]") }
        val binary = f["binary"].jsBoolean == true
        if (hunks.isEmpty() && !binary && status != "renamed") throw invalid("$path.hunks must have at least one hunk")
        return DiffFile(name, old, status ?: if (old != null && old != name) "renamed" else "modified", hunks, binary)
    }

    /** A hunk as text (`@@ … @@` then its lines), or as the `{header, lines}` it is stored as. */
    private fun hunk(raw: JsonElement, path: String): List<DiffHunk> {
        raw.jsString?.let { text ->
            val parsed = UnifiedDiff.hunks(text)
            if (parsed.isEmpty()) throw invalid("$path must start with an @@ header line")
            return parsed
        }
        val h = obj(raw, path)
        only(h, path, listOf("header", "lines"), "a hunk")
        val header = h["header"].jsString ?: throw invalid("$path.header must be the @@ line")
        val lines = (h["lines"] as? JsonArray)?.mapIndexed { at, l -> l.jsString ?: throw invalid("$path.lines[$at] must be text") }
            ?: throw invalid("$path.lines must be a list of lines")
        for ((at, l) in lines.withIndex()) if (l.isNotEmpty() && l[0] !in " +-\\") throw invalid("$path.lines[$at] must start with a space, + or -")
        return listOf(DiffHunk(header, lines))
    }

    private fun chart(o: JsonObject, c: Ctx, out: MutableMap<String, JsonElement>) {
        val kind = o["kind"]?.takeIf { it !is JsonNull }?.let { it.jsString?.takeIf { k -> k == "line" || k == "bar" } ?: throw invalid("${c.path}.kind must be line or bar") }
            ?: "line"
        out["kind"] = JsonPrimitive(kind)
        val series = list(o, "series", c.path, SERIES_MAX, required = true)
        var labels: Boolean? = null
        out["series"] = JsonArray(uniqueIds(series, "${c.path}.series") { at, raw ->
            val path = "${c.path}.series[$at]"
            val s = obj(raw, path)
            only(s, path, listOf("id", "label", "points"), "a series")
            val points = (s["points"] ?: JsonArray(emptyList())) as? JsonArray ?: throw invalid("$path.points must be a list of [x, y]")
            val kept = points.takeLast(POINTS_MAX).mapIndexed { i, p ->
                val pp = "$path.points[${i + maxOf(0, points.size - POINTS_MAX)}]"
                val (x, y) = point(p, pp)
                val isLabel = x.isString
                if (labels != null && labels != isLabel) throw invalid("$pp: every x in a chart is a number, or every x is a label, not a mix")
                labels = isLabel
                JsonArray(listOf(x, y))
            }
            buildMap {
                put("id", JsonPrimitive(id(s["id"], "$path.id")))
                optionalText(s, "label", path, LABEL_MAX)?.let { put("label", JsonPrimitive(it)) }
                put("points", JsonArray(kept))
            }
        })
        optionalText(o, "x_label", c.path, AXIS_MAX)?.let { out["x_label"] = JsonPrimitive(it) }
        optionalText(o, "y_label", c.path, AXIS_MAX)?.let { out["y_label"] = JsonPrimitive(it) }
        optionalText(o, "unit", c.path, UNIT_MAX)?.let { out["unit"] = JsonPrimitive(it) }
    }

    private fun point(raw: JsonElement, path: String): Pair<JsonPrimitive, JsonPrimitive> {
        val (x, y) = when (raw) {
            is JsonArray -> if (raw.size == 2) raw[0] to raw[1] else throw invalid("$path must be [x, y]")
            is JsonObject -> {
                only(raw, path, listOf("x", "y"), "a point")
                raw["x"] to raw["y"]
            }
            else -> throw invalid("$path must be [x, y]")
        }
        val px = x as? JsonPrimitive
        val xOk = px != null && px !is JsonNull && (
            (px.isString && px.content.isNotBlank() && px.content.length <= X_LABEL_MAX) || (!px.isString && px.booleanOrNull == null && px.jsNumber?.isFinite() == true)
        )
        if (!xOk) throw invalid("$path: x must be a number, or a label of at most $X_LABEL_MAX characters")
        val py = (y as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull && it.booleanOrNull == null && it.jsNumber?.isFinite() == true }
            ?: throw invalid("$path: y must be a number")
        return px!! to py
    }

    private fun table(o: JsonObject, c: Ctx, out: MutableMap<String, JsonElement>) {
        val columns = list(o, "columns", c.path, COLUMNS_MAX, required = true).mapIndexed { at, raw ->
            val path = "${c.path}.columns[$at]"
            raw.jsString?.let { label ->
                if (label.jsTrim().isEmpty() || label.jsTrim().length > LABEL_MAX) throw invalid("$path must be a label of 1 to $LABEL_MAX characters")
                return@mapIndexed mapOf("label" to JsonPrimitive(label.jsTrim()), "kind" to JsonPrimitive("text"))
            }
            val col = obj(raw, path)
            only(col, path, listOf("label", "kind"), "a column")
            val kind = col["kind"]?.takeIf { it !is JsonNull }?.let { it.jsString?.takeIf { k -> k == "text" || k == "number" } ?: throw invalid("$path.kind must be text or number") }
                ?: "text"
            mapOf("label" to JsonPrimitive(text(col, "label", path, LABEL_MAX)), "kind" to JsonPrimitive(kind))
        }
        out["columns"] = JsonArray(columns.map(::JsonObject))
        val rows = list(o, "rows", c.path, ROWS_MAX, required = false)
        out["rows"] = JsonArray(rows.mapIndexed { r, raw ->
            val path = "${c.path}.rows[$r]"
            val row = raw as? JsonArray ?: throw invalid("$path must be a list of cells, one per column")
            if (row.size != columns.size) throw invalid("$path has ${row.size} cells; the table has ${columns.size} columns")
            JsonArray(row.mapIndexed { i, cell ->
                val cp = "$path[$i]"
                val numeric = columns[i]["kind"]!!.content == "number"
                when {
                    cell is JsonNull -> cell
                    cell is JsonPrimitive && cell.isString -> {
                        if (numeric) throw invalid("$cp: column \"${columns[i]["label"]!!.content}\" holds numbers")
                        if (cell.content.length > CELL_MAX) throw invalid("$cp may be at most $CELL_MAX characters")
                        cell
                    }
                    cell is JsonPrimitive && cell.booleanOrNull != null -> {
                        if (numeric) throw invalid("$cp: column \"${columns[i]["label"]!!.content}\" holds numbers")
                        cell
                    }
                    cell is JsonPrimitive && cell.jsNumber?.isFinite() == true -> cell
                    else -> throw invalid("$cp must be text, a number, true, false or null")
                }
            })
        })
        o["limit"]?.takeIf { it !is JsonNull }?.let {
            val n = it.jsNumber?.takeIf { v -> v == Math.floor(v) && v in 1.0..TABLE_LIMIT_MAX.toDouble() }
                ?: throw invalid("${c.path}.limit must be a whole number from 1 to $TABLE_LIMIT_MAX")
            out["limit"] = JsonPrimitive(n.toInt())
        } ?: run { out["limit"] = JsonPrimitive(TABLE_LIMIT_DEFAULT) }
    }

    private fun log(o: JsonObject, c: Ctx, out: MutableMap<String, JsonElement>, carryDropped: Boolean) {
        val text = o["text"]?.takeIf { it !is JsonNull }
        val given = o["lines"]?.takeIf { it !is JsonNull }
        if (text != null && given != null) throw invalid("${c.path} takes lines or text, not both")
        val all = when {
            text != null -> logLines(text, "${c.path}.text")
            given != null -> logLines(given, "${c.path}.lines")
            else -> emptyList()
        }
        for ((at, line) in all.withIndex()) {
            if (line.content.length > LOG_LINE_MAX) throw invalid("${c.path}: line ${at + 1} is ${line.content.length} characters; a log line may be at most $LOG_LINE_MAX")
        }
        // The newest lines are kept: at most so many, and at most so many characters in all.
        var kept = all.takeLast(LOG_LINES_MAX)
        var chars = kept.sumOf { it.content.length }
        var start = 0
        while (chars > LOG_CHARS_MAX && start < kept.size) chars -= kept[start++].content.length
        kept = kept.drop(start)
        val dropped = all.size - kept.size
        out["lines"] = JsonArray(kept)
        val before = if (carryDropped) c.prev?.get("dropped")?.jsNumber?.toLong() ?: 0 else 0
        if (before + dropped > 0) out["dropped"] = JsonPrimitive(before + dropped)
        optionalText(o, "highlight", c.path, LOG_LINE_MAX)?.let { out["highlight"] = JsonPrimitive(it) }
        o["tail"]?.takeIf { it !is JsonNull }?.let {
            val n = it.jsNumber?.takeIf { v -> v == Math.floor(v) && v in 1.0..LOG_TAIL_MAX.toDouble() }
                ?: throw invalid("${c.path}.tail must be a whole number from 1 to $LOG_TAIL_MAX")
            out["tail"] = JsonPrimitive(n.toInt())
        } ?: run { out["tail"] = JsonPrimitive(LOG_TAIL_DEFAULT) }
    }

    /** Log lines from text or a list of text, split at line breaks, `\r` dropped. */
    private fun logLines(raw: JsonElement, path: String): List<JsonPrimitive> {
        val texts = when {
            raw.jsString != null -> listOf(raw.jsString!!)
            raw is JsonArray -> raw.mapIndexed { at, l -> l.jsString ?: throw invalid("$path[$at] must be text") }
            else -> throw invalid("$path must be text, or a list of lines")
        }
        val lines = texts.flatMap { t -> t.replace("\r\n", "\n").replace('\r', '\n').let { if (it.endsWith("\n")) it.dropLast(1) else it }.split('\n') }
        return lines.map(::JsonPrimitive)
    }

    private fun poll(o: JsonObject, c: Ctx, out: MutableMap<String, JsonElement>) {
        out["question"] = JsonPrimitive(text(o, "question", c.path, QUESTION_MAX))
        val options = list(o, "options", c.path, OPTIONS_MAX, required = true)
        if (options.size < OPTIONS_MIN) throw invalid("${c.path}.options needs at least $OPTIONS_MIN options")
        val written = uniqueIds(options, "${c.path}.options") { at, raw ->
            val path = "${c.path}.options[$at]"
            val opt = obj(raw, path)
            only(opt, path, listOf("id", "label"), "a poll option")
            mapOf("id" to JsonPrimitive(id(opt["id"], "$path.id")), "label" to JsonPrimitive(text(opt, "label", path, LABEL_MAX)))
        }
        out["options"] = JsonArray(written)
        val multi = bool(o, "multi", c.path) ?: false
        out["multi"] = JsonPrimitive(multi)
        iso(o, "closes_at", c.path)?.let { out["closes_at"] = JsonPrimitive(it) }
        out["closed"] = JsonPrimitive(bool(o, "closed", c.path) ?: false)
        // Votes are the server's: the ones from before, for options still there.
        val ids = written.map { it["id"]!!.jsString!! }.toSet()
        val votes = (c.prev?.get("votes") as? JsonArray).orEmpty().mapNotNull { v ->
            v as JsonObject
            val kept = (v["options"] as JsonArray).filter { it.jsString in ids }.let { if (multi) it else it.take(1) }
            if (kept.isEmpty()) null else JsonObject(v + ("options" to JsonArray(kept)))
        }
        if (votes.isNotEmpty()) out["votes"] = JsonArray(votes)
    }

    // ---- merging --------------------------------------------------------------------------------

    private fun mergeById(existing: JsonArray, raw: JsonElement, path: String, appendKeys: Set<String>): JsonArray {
        val given = raw as? JsonArray ?: throw invalid("$path must be a list, each with an id")
        val out = existing.map { it as JsonObject }.toMutableList()
        val seen = HashSet<String>()
        for ((at, entry) in given.withIndex()) {
            val e = obj(entry, "$path[$at]")
            val id = id(e["id"], "$path[$at].id")
            if (!seen.add(id)) throw invalid("$path[$at].id \"$id\" is named twice in one patch")
            for (key in e.keys) if (key in SERVER_ITEM_KEYS) throw invalid("$path[$at].$key is the server's to write")
            val index = out.indexOfFirst { it["id"].jsString == id }
            if (index < 0) {
                out += JsonObject(e.filterValues { it !is JsonNull })
                continue
            }
            val merged = LinkedHashMap(out[index])
            for ((key, value) in e) {
                when {
                    value is JsonNull -> merged.remove(key)
                    key in appendKeys -> {
                        val add = value as? JsonArray ?: throw invalid("$path[$at].$key must be a list")
                        merged[key] = JsonArray((merged[key] as? JsonArray).orEmpty() + add)
                    }
                    else -> merged[key] = value
                }
            }
            out[index] = JsonObject(merged)
        }
        return JsonArray(out)
    }

    // ---- reading --------------------------------------------------------------------------------

    private fun obj(raw: JsonElement?, path: String): JsonObject = raw as? JsonObject ?: throw invalid("$path must be an object")

    private fun only(o: JsonObject, path: String, allowed: Collection<String>, what: String) {
        for (key in o.keys) {
            if (key !in allowed) {
                val shown = allowed.filter { it !in SERVER_KEYS && it !in SERVER_ITEM_KEYS && it !in setOf("additions", "deletions", "binary") }
                throw invalid("$path.$key is not something $what has: it takes ${shown.joinToString(", ")}")
            }
        }
    }

    private fun id(raw: JsonElement?, path: String): String {
        val text = raw.jsString ?: throw invalid("$path is required: 1 to 64 letters, digits, -, _ or .")
        if (!ID.matches(text)) throw invalid("$path must be 1 to 64 letters, digits, -, _ or ., starting with a letter or digit")
        return text
    }

    private fun text(o: JsonObject, key: String, path: String, max: Int): String {
        val raw = o[key]
        val value = raw.jsString ?: throw invalid(if (raw == null || raw is JsonNull) "$path.$key is required" else "$path.$key must be text")
        val trimmed = value.jsTrim()
        if (trimmed.isEmpty()) throw invalid("$path.$key is required")
        if (trimmed.length > max) throw invalid("$path.$key must be at most $max characters")
        return trimmed
    }

    private fun optionalText(o: JsonObject, key: String, path: String, max: Int): String? {
        val raw = o[key]
        if (raw == null || raw is JsonNull) return null
        val value = raw.jsString ?: throw invalid("$path.$key must be text")
        val trimmed = value.jsTrim()
        if (trimmed.length > max) throw invalid("$path.$key must be at most $max characters")
        return trimmed.ifEmpty { null }
    }

    private fun bool(o: JsonObject, key: String, path: String): Boolean? {
        val raw = o[key]
        if (raw == null || raw is JsonNull) return null
        return raw.jsBoolean ?: throw invalid("$path.$key must be true or false")
    }

    private fun list(o: JsonObject, key: String, path: String, max: Int, required: Boolean): JsonArray {
        val raw = o[key]
        if (raw == null || raw is JsonNull) {
            if (required) throw invalid("$path.$key is required")
            return JsonArray(emptyList())
        }
        val list = raw as? JsonArray ?: throw invalid("$path.$key must be a list")
        if (required && list.isEmpty()) throw invalid("$path.$key must have at least one entry")
        if (list.size > max) throw invalid("$path.$key may have at most $max entries")
        return list
    }

    /** An ISO-8601 time with its offset, written back as UTC. */
    private fun iso(o: JsonObject, key: String, path: String): String? {
        val raw = o[key]
        if (raw == null || raw is JsonNull) return null
        val text = raw.jsString ?: throw invalid("$path.$key must be a time, like 2026-11-20T15:00:00Z")
        val ms = try {
            OffsetDateTime.parse(text.jsTrim()).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            throw invalid("$path.$key must be a time with its offset, like 2026-11-20T15:00:00Z")
        }
        return Wire.isoOf(ms)
    }

    private fun uniqueIds(list: JsonArray, path: String, each: (Int, JsonElement) -> Map<String, JsonElement>): List<JsonObject> {
        val seen = HashSet<String>()
        return list.mapIndexed { at, raw ->
            val written = each(at, raw)
            val id = written["id"]?.jsString
            if (id != null && !seen.add(id)) throw invalid("$path[$at].id \"$id\" is used twice")
            JsonObject(written)
        }
    }

    private fun singular(list: String) = when (list) {
        "items" -> "line"
        "stages" -> "stage"
        "series" -> "series"
        else -> "option"
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}

/** One file of a diff, as stored: its hunks with their lines, each line's first character its kind. */
data class DiffFile(val path: String, val oldPath: String?, val status: String, val hunks: List<DiffHunk>, val binary: Boolean = false) {
    fun json(): JsonObject = JsonObject(buildMap {
        put("path", JsonPrimitive(path))
        if (oldPath != null && oldPath != path) put("old_path", JsonPrimitive(oldPath))
        put("status", JsonPrimitive(status))
        put("additions", JsonPrimitive(hunks.sumOf { h -> h.lines.count { it.startsWith("+") } }))
        put("deletions", JsonPrimitive(hunks.sumOf { h -> h.lines.count { it.startsWith("-") } }))
        if (binary) put("binary", JsonPrimitive(true))
        put("hunks", JsonArray(hunks.map { h -> JsonObject(mapOf("header" to JsonPrimitive(h.header), "lines" to JsonArray(h.lines.map(::JsonPrimitive)))) }))
    })

    companion object {
        val STATUSES = listOf("added", "deleted", "modified", "renamed")
    }
}

data class DiffHunk(val header: String, val lines: List<String>)

/** Unified diff text, as `git diff` or `diff -u` prints it, read into files and hunks. */
object UnifiedDiff {
    private val GIT = Regex("^diff --git a/(.*) b/(.*)$")
    private val HUNK = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$")

    fun parse(text: String): List<DiffFile> {
        val files = mutableListOf<DiffFile>()
        var path: String? = null
        var oldPath: String? = null
        var status = "modified"
        var binary = false
        var hunks = mutableListOf<DiffHunk>()
        var header: String? = null
        var lines = mutableListOf<String>()
        // What the open hunk's header says is still to come, so a "--- " inside it is a line, not a file.
        var oldLeft = 0
        var newLeft = 0
        var open = false

        fun endHunk() {
            header?.let { hunks += DiffHunk(it, lines) }
            header = null
            lines = mutableListOf()
        }

        fun endFile() {
            endHunk()
            if (open && path != null) files += DiffFile(path!!, oldPath, status, hunks, binary)
            open = false
            path = null; oldPath = null; status = "modified"; binary = false
            hunks = mutableListOf()
        }

        fun strip(p: String) = p.substringBefore('\t').let { if (it.startsWith("a/") || it.startsWith("b/")) it.substring(2) else it }

        for (line in text.replace("\r\n", "\n").split('\n')) {
            val inHunk = header != null && (oldLeft > 0 || newLeft > 0)
            if (inHunk) {
                val kind = if (line.isEmpty()) ' ' else line[0]
                when (kind) {
                    ' ' -> { oldLeft--; newLeft--; lines += line.ifEmpty { " " } }
                    '-' -> { oldLeft--; lines += line }
                    '+' -> { newLeft--; lines += line }
                    '\\' -> lines += line
                    else -> { oldLeft = 0; newLeft = 0 }
                }
                if (kind in " +-\\") continue
            }
            if (header != null && line.startsWith("\\")) { lines += line; continue }
            val git = GIT.matchEntire(line)
            val hunk = HUNK.matchEntire(line)
            when {
                git != null -> {
                    endFile(); open = true
                    oldPath = git.groupValues[1]; path = git.groupValues[2]
                }
                line.startsWith("--- ") -> {
                    // A diff with no git header starts its file here; after a hunk, so does the next one.
                    if (!open || header != null) { endFile(); open = true }
                    val p = line.removePrefix("--- ")
                    if (p == "/dev/null") status = "added" else oldPath = strip(p)
                }
                open && line.startsWith("+++ ") -> {
                    val p = line.removePrefix("+++ ")
                    if (p == "/dev/null") { status = "deleted"; path = oldPath } else path = strip(p)
                }
                open && hunk != null -> {
                    endHunk(); header = line
                    oldLeft = hunk.groupValues[2].ifEmpty { "1" }.toInt()
                    newLeft = hunk.groupValues[4].ifEmpty { "1" }.toInt()
                }
                open && header == null && line.startsWith("new file mode") -> status = "added"
                open && header == null && line.startsWith("deleted file mode") -> status = "deleted"
                open && header == null && line.startsWith("rename from ") -> { oldPath = line.removePrefix("rename from "); status = "renamed" }
                open && header == null && line.startsWith("rename to ") -> { path = line.removePrefix("rename to "); status = "renamed" }
                open && line.startsWith("Binary files ") -> binary = true
                else -> {}
            }
        }
        endFile()
        return files.map { f ->
            // Only now is it plain whether a git rename also changed lines.
            if (f.oldPath == f.path) f.copy(oldPath = null) else f
        }
    }

    /** Hunks from text that starts with an `@@` header line. */
    fun hunks(text: String): List<DiffHunk> {
        val out = mutableListOf<DiffHunk>()
        var header: String? = null
        var lines = mutableListOf<String>()
        val rows = text.replace("\r\n", "\n").trimEnd('\n').split('\n')
        for ((at, line) in rows.withIndex()) {
            if (HUNK.matches(line)) {
                header?.let { out += DiffHunk(it, lines) }
                header = line; lines = mutableListOf()
                continue
            }
            if (header == null) return emptyList()
            when {
                line.isEmpty() -> lines += " "
                line[0] in " +-\\" -> lines += line
                else -> throw invalid("line ${at + 1} of a hunk must start with a space, + or -")
            }
        }
        header?.let { out += DiffHunk(it, lines) }
        return out
    }
}
