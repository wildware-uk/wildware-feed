package dev.wildware.agents.app.feed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wildware.agents.BlockActor
import dev.wildware.agents.BlockView
import dev.wildware.agents.ChartSeries
import dev.wildware.agents.DiffFileView
import dev.wildware.agents.StepStage
import dev.wildware.agents.app.design.AgentColors
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.focusRing
import dev.wildware.agents.app.design.touchTarget
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/*
 * Blocks (docs/BLOCKS.md), drawn natively: a checklist, steps, a diff, a chart, a table, a log and a
 * poll. Each stays a fixed, modest height in the feed: long tables and logs fold to a few rows, and
 * unfold into a box of their own with a lazy list in it, so a long feed never lays out a thousand
 * rows it isn't showing.
 */

/** Every block on a card, one under another. */
@Composable
fun CardBlocks(blocks: List<CardBlock>, modifier: Modifier = Modifier) {
    if (blocks.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block -> key(block.cardKind, block.cardId, block.block.id) { BlockCard(block) } }
    }
}

@Composable
fun BlockCard(block: CardBlock, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val view = block.block
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        when (view.type) {
            BlockView.Checklist -> ChecklistBlock(block)
            BlockView.Steps -> StepsBlock(view)
            BlockView.Diff -> DiffBlock(view)
            BlockView.Chart -> ChartBlock(view)
            BlockView.Table -> TableBlock(view)
            BlockView.Log -> LogBlock(view)
            BlockView.Poll -> PollBlock(block)
            else -> {
                BlockTitle(view.title)
                Text("This block needs a newer version of the app.", color = colors.textMuted, style = AgentTheme.type.meta)
            }
        }
    }
}

/** A block's own heading, with a note on the right ("2 of 5"). */
@Composable
private fun BlockTitle(title: String?, note: String? = null, modifier: Modifier = Modifier) {
    if (title == null && note == null) return
    val colors = AgentTheme.colors
    Row(modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (title != null) {
            Text(
                title,
                Modifier.weight(1f).semantics { heading() },
                color = colors.text,
                style = AgentTheme.type.label.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (note != null) MetaText(note, Modifier.padding(start = 8.dp), color = colors.textMuted)
    }
}

/** A thin bar, [fraction] of it filled. */
@Composable
private fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(colors.border)) {
        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(color))
    }
}

/** A quiet text button inside a block: "Show all 240 rows". */
@Composable
private fun BlockAction(label: String, spoken: String = label, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    Box(
        modifier
            .clip(shape)
            .focusRing(shape)
            .semantics { contentDescription = spoken; role = Role.Button }
            .padding(top = 4.dp)
            .then(Modifier.touchTarget())
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, color = colors.accent, style = AgentTheme.type.meta.copy(fontWeight = FontWeight.Medium))
    }
}

// ---- checklist ----------------------------------------------------------------------------------

@Composable
private fun ChecklistBlock(block: CardBlock) {
    val view = block.block
    val colors = AgentTheme.colors
    val ui = LocalFeedUi.current
    // A tap goes to the engine, which shows it at once and puts it back if the server refuses.
    val items = view.items
    val done = items.count { it.done }
    BlockTitle(view.title ?: "Checklist", "$done of ${items.size}")
    Meter(if (items.isEmpty()) 0f else done.toFloat() / items.size, colors.success, Modifier.padding(bottom = 6.dp))
    items.forEach { item ->
        key(item.id) {
            val who = item.owner?.let { owner -> ownerLabel(owner) }
            val doneBy = item.doneBy?.takeIf { item.done }?.let { actorLabel(it, block) }
            val spoken = buildString {
                append(item.text)
                if (who != null) append(", waiting on ").append(if (who == "You") "you" else who)
                if (doneBy != null) append(", done by ").append(if (doneBy == "You") "you" else doneBy)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(AgentTheme.shapes.small)
                    .focusRing(AgentTheme.shapes.small)
                    .semantics(mergeDescendants = true) {
                        contentDescription = spoken
                        role = Role.Checkbox
                        toggleableState = ToggleableState(item.done)
                    }
                    .heightIn(min = 48.dp)
                    .clickable { ui?.emit(FeedEvent.CheckBlockItem(block.cardKind, block.cardId, view.id, item.id, !item.done)) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tick(item.done)
                Column(Modifier.weight(1f).padding(start = 10.dp).clearAndSetSemantics { }) {
                    Text(
                        item.text,
                        color = if (item.done) colors.textMuted else colors.text,
                        style = AgentTheme.type.body.copy(textDecoration = if (item.done) TextDecoration.LineThrough else null),
                    )
                    if (doneBy != null) MetaText("Done by $doneBy")
                }
                if (who != null) OwnerChip(who, Modifier.padding(start = 8.dp).clearAndSetSemantics { })
            }
        }
    }
}

/** A round-cornered box, ticked or not. */
@Composable
private fun Tick(done: Boolean) {
    val colors = AgentTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(20.dp)
            .clip(shape)
            .background(if (done) colors.success else Color.Transparent)
            .border(1.5.dp, if (done) colors.success else colors.borderStrong, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (done) Icon(Icons.Check, null, tint = colors.onAccent, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun OwnerChip(who: String, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val you = who == "You"
    Box(
        modifier
            .clip(AgentTheme.shapes.pill)
            .background(if (you) colors.waitingSoft else colors.surfaceRaised)
            .border(1.dp, if (you) colors.waiting.copy(alpha = 0.35f) else colors.border, AgentTheme.shapes.pill)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            who,
            color = if (you) colors.waiting else colors.textMuted,
            style = AgentTheme.type.meta.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp),
        )
    }
}

/** "you" is you; anyone else by the name the agent gave. */
private fun ownerLabel(owner: String): String = if (owner.trim().equals("you", ignoreCase = true) || owner.trim().equals("owner", ignoreCase = true)) "You" else owner

/** Who did it, as the screen says it: "You", a person's name on a team, or an agent's name. */
private fun actorLabel(actor: BlockActor, block: CardBlock): String = when {
    actor.kind == "person" && actor.id == block.me -> "You"
    actor.kind == "person" && !block.team -> "You"
    else -> actor.name ?: if (actor.kind == "agent") "an agent" else "someone"
}

// ---- steps --------------------------------------------------------------------------------------

@OptIn(ExperimentalTime::class)
@Composable
private fun StepsBlock(view: BlockView) {
    val colors = AgentTheme.colors
    val now = tickingNow(view.stages.any { it.state == "running" } || view.eta != null)
    val finished = view.stages.count { it.state == "done" || it.state == "skipped" }
    val failed = view.stages.any { it.state == "failed" }
    val progress = view.progress ?: if (view.stages.isEmpty()) 0.0 else finished.toDouble() / view.stages.size
    val eta = view.eta?.let { millis(it) }?.let { at -> etaLabel(at - now) }
    val note = listOfNotNull("${(progress * 100).roundToInt()}%", eta).joinToString(" · ")
    BlockTitle(view.title ?: "Steps", note)
    Meter(progress.toFloat(), if (failed) colors.error else colors.accent, Modifier.padding(bottom = 8.dp).semantics {
        contentDescription = "${(progress * 100).roundToInt()} percent done" + (eta?.let { ", $it" } ?: "")
    })
    view.stages.forEachIndexed { at, stage ->
        key(stage.id) {
            val took = duration(stage, now)
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull("Step ${at + 1}: ${stage.label}", stateWord(stage.state), stage.detail, took).joinToString(", ")
                }.padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top,
            ) {
                StageMark(stage.state, Modifier.padding(top = 2.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp).clearAndSetSemantics { }) {
                    Text(
                        stage.label,
                        color = when (stage.state) {
                            "pending", "skipped" -> colors.textMuted
                            else -> colors.text
                        },
                        style = if (stage.state == "running") AgentTheme.type.bodyStrong else AgentTheme.type.body,
                    )
                    stage.detail?.let {
                        Text(it, color = if (stage.state == "failed") colors.error else colors.textMuted, style = AgentTheme.type.meta)
                    }
                }
                if (took != null) MetaText(took, Modifier.padding(start = 8.dp, top = 2.dp).clearAndSetSemantics { })
            }
        }
    }
}

@Composable
private fun StageMark(state: String, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val size = 18.dp
    when (state) {
        "done" -> Box(modifier.size(size).clip(CircleShape).background(colors.success), contentAlignment = Alignment.Center) {
            Icon(Icons.Check, null, tint = colors.onAccent, modifier = Modifier.size(12.dp))
        }
        "failed" -> Box(modifier.size(size).clip(CircleShape).background(colors.error), contentAlignment = Alignment.Center) {
            Icon(Icons.Close, null, tint = colors.onAccent, modifier = Modifier.size(12.dp))
        }
        "running" -> Box(modifier.size(size).clip(CircleShape).border(2.dp, colors.accent, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
        }
        "skipped" -> Box(modifier.size(size).clip(CircleShape).border(1.5.dp, colors.border, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.width(8.dp).height(1.5.dp).background(colors.textFaint))
        }
        else -> Box(modifier.size(size).clip(CircleShape).border(1.5.dp, colors.borderStrong, CircleShape))
    }
}

private fun stateWord(state: String) = when (state) {
    "done" -> "done"
    "failed" -> "failed"
    "running" -> "running"
    "skipped" -> "skipped"
    else -> "not started"
}

/**
 * The time now, moving on by the second while [active]: a running stage counts up, and a poll
 * closes on screen when its time comes. Still otherwise, so a finished block never redraws.
 */
@Composable
private fun tickingNow(active: Boolean): Long {
    val clock = LocalNow.current
    var now by remember(clock) { mutableLongStateOf(clock()) }
    if (active) {
        LaunchedEffect(clock) {
            while (true) {
                now = clock()
                delay(1_000)
            }
        }
    }
    return now
}

@OptIn(ExperimentalTime::class)
internal fun millis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()

/** How long a stage took, or has been running: "1m 20s". */
private fun duration(stage: StepStage, now: Long): String? {
    val start = stage.startedAt?.let(::millis) ?: return null
    val end = stage.endedAt?.let(::millis) ?: if (stage.state == "running") now else return null
    return span(end - start)
}

internal fun span(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

/** "about 4 min left", "overdue". */
internal fun etaLabel(left: Long): String {
    if (left <= 0) return "due now"
    val minutes = (left + 59_999) / 60_000
    return when {
        minutes < 60 -> "about $minutes min left"
        minutes < 48 * 60 -> "about ${(minutes + 30) / 60} h left"
        else -> "about ${(minutes + 12 * 60) / (24 * 60)} days left"
    }
}

// ---- diff ---------------------------------------------------------------------------------------

@Composable
private fun DiffBlock(view: BlockView) {
    val adds = view.files.sumOf { it.additions }
    val dels = view.files.sumOf { it.deletions }
    val lines = view.files.sumOf { f -> f.hunks.sumOf { it.lines.size } }
    BlockTitle(view.title ?: "Changes", "${view.files.size} file${if (view.files.size == 1) "" else "s"}")
    // A small diff opens whole; a big one opens folded, file by file.
    val open = remember(view.id) { mutableStateMapOf<String, Boolean>() }
    val startOpen = view.files.size == 1 && lines <= 80
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (view.files.size > 1) AddsDels(adds, dels, Modifier.semantics { contentDescription = "$adds lines added, $dels removed" })
        view.files.forEach { file ->
            key(file.path) {
                val isOpen = open[file.path] ?: startOpen
                DiffFileRow(file, isOpen) { open[file.path] = !isOpen }
                if (isOpen) DiffFileBody(file)
            }
        }
    }
}

@Composable
private fun AddsDels(adds: Int, dels: Int, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    Text(
        buildAnnotatedString {
            // A side with nothing on it says nothing: a new file is "+2", not "+2 −0".
            if (adds > 0 || dels == 0) { pushStyle(SpanStyle(color = colors.success)); append("+$adds"); pop() }
            if (adds > 0 && dels > 0) append("  ")
            if (dels > 0) { pushStyle(SpanStyle(color = colors.error)); append("−$dels"); pop() }
        },
        modifier,
        style = AgentTheme.type.meta.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
private fun DiffFileRow(file: DiffFileView, open: Boolean, onToggle: () -> Unit) {
    val colors = AgentTheme.colors
    val what = when (file.status) {
        "added" -> "new"
        "deleted" -> "deleted"
        "renamed" -> "renamed"
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AgentTheme.shapes.small)
            .focusRing(AgentTheme.shapes.small)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(file.path, what, "${file.additions} added, ${file.deletions} removed").joinToString(", ")
                stateDescription = if (open) "Open" else "Folded"
                role = Role.Button
            }
            .heightIn(min = 36.dp)
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (open) Icons.ChevronDown else Icons.ChevronRight, null, tint = colors.textFaint, modifier = Modifier.size(14.dp))
        // The file's name first, where it can't be cut off; its folder after, faint.
        val name = file.path.substringAfterLast('/')
        val folder = file.path.substringBeforeLast('/', "")
        Text(
            buildAnnotatedString {
                append(name)
                pushStyle(SpanStyle(color = colors.textFaint))
                if (file.oldPath != null) append("  from ${middleEllipsis(file.oldPath!!, 48)}")
                else if (folder.isNotEmpty()) append("  ${middleEllipsis(folder, 48)}")
                pop()
            },
            Modifier.weight(1f).padding(start = 6.dp).clearAndSetSemantics { },
            color = colors.text,
            style = AgentTheme.type.code.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (what != null) MetaText(what, Modifier.padding(start = 6.dp).clearAndSetSemantics { }, color = colors.textMuted)
        AddsDels(file.additions, file.deletions, Modifier.padding(start = 8.dp).clearAndSetSemantics { })
    }
}

/** The lines of one file, coloured by what they are and, where it's known, by the language. */
@Composable
private fun DiffFileBody(file: DiffFileView) {
    val colors = AgentTheme.colors
    val code = colors.code
    if (file.binary || file.hunks.isEmpty()) {
        MetaText(if (file.binary) "A binary file: nothing to show." else "Only renamed.", Modifier.padding(start = 20.dp))
        return
    }
    var all by remember(file.path) { mutableStateOf(false) }
    val total = file.hunks.sumOf { it.lines.size + 1 }
    val language = languageOf(file.path)
    // Colour is cheap for a short file; a long one is drawn plain rather than hold up a frame.
    val rows = remember(file, language, code) { diffRows(file, if (total <= HighlightUpTo) language else null, code) }
    val longest = remember(rows) { rows.maxOf { it.text.length } }
    Column(
        Modifier.fillMaxWidth().clip(AgentTheme.shapes.small).background(code.background)
            .semantics { contentDescription = "The changes to ${file.path}" },
    ) {
        // Every row as wide as the longest line and at least the box, so a tint runs edge to edge;
        // the width is worked out from the line lengths, not by measuring every row.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxOf(maxWidth, DiffCharWidth * longest + 36.dp)
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                if (all && rows.size > DiffLinesShown) {
                    // Unfolded: a box of its own that scrolls, drawing only the lines in view.
                    LazyColumn(Modifier.width(wide).heightIn(max = ExpandedHeight).padding(vertical = 4.dp)) {
                        itemsIndexed(rows) { _, row -> DiffLine(row, Modifier.width(wide)) }
                    }
                } else {
                    Column(Modifier.width(wide).padding(vertical = 4.dp)) {
                        rows.take(DiffLinesShown).forEach { DiffLine(it, Modifier.width(wide)) }
                    }
                }
            }
        }
    }
    if (rows.size > DiffLinesShown) {
        BlockAction(if (all) "Fold" else "Show ${rows.size - DiffLinesShown} more lines", if (all) "Fold ${file.path}" else "Show all $total lines of ${file.path}") { all = !all }
    }
}

@Composable
private fun DiffLine(row: DiffRow, modifier: Modifier) {
    val colors = AgentTheme.colors
    val code = colors.code
    val tint = when (row.kind) {
        '+' -> colors.success.copy(alpha = if (colors.isDark) 0.16f else 0.12f)
        '-' -> colors.error.copy(alpha = if (colors.isDark) 0.16f else 0.10f)
        '@' -> colors.accent.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    Row(modifier.background(tint).padding(horizontal = 8.dp)) {
        Text(
            if (row.kind == '@' || row.kind == '\\') " " else row.kind.toString(),
            Modifier.width(12.dp),
            color = when (row.kind) {
                '+' -> colors.success
                '-' -> colors.error
                else -> code.punctuation
            },
            style = AgentTheme.type.code.copy(fontSize = 12.sp),
        )
        Text(row.text, style = AgentTheme.type.code.copy(fontSize = 12.sp, color = if (row.kind == '@') code.comment else code.text), softWrap = false)
    }
}

/** About how wide one character of 12 sp code is. */
private val DiffCharWidth = 7.4.dp
private const val HighlightUpTo = 600

@Immutable
private class DiffRow(val kind: Char, val text: AnnotatedString)

private const val DiffLinesShown = 120

private fun diffRows(file: DiffFileView, language: String?, code: dev.wildware.agents.app.design.CodeColors): List<DiffRow> = buildList {
    for (hunk in file.hunks) {
        add(DiffRow('@', AnnotatedString(hunk.header)))
        val body = hunk.lines.joinToString("\n") { if (it.isEmpty()) "" else it.substring(1) }
        // One highlight per hunk (cached by its text), then cut back into lines.
        val lit = if (language != null && body.length < 20_000) highlighted(body, language, code) else AnnotatedString(body)
        var start = 0
        hunk.lines.forEachIndexed { i, line ->
            val length = if (line.isEmpty()) 0 else line.length - 1
            val end = (start + length).coerceAtMost(lit.length)
            add(DiffRow(line.firstOrNull() ?: ' ', lit.subSequence(start, end)))
            start = end + 1
        }
    }
}

/** The highlighter's name for a file's language, from its extension; null for plain text. */
internal fun languageOf(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
    "kt", "kts" -> "kotlin"
    "java" -> "java"
    "js", "mjs", "cjs", "jsx" -> "javascript"
    "ts", "tsx" -> "typescript"
    "py" -> "python"
    "rs" -> "rust"
    "go" -> "go"
    "swift" -> "swift"
    "c", "h" -> "c"
    "cc", "cpp", "hpp", "cxx" -> "cpp"
    "cs" -> "csharp"
    "rb" -> "ruby"
    "php" -> "php"
    "sh", "bash", "zsh" -> "shell"
    "pl" -> "perl"
    "dart" -> "dart"
    "coffee" -> "coffeescript"
    else -> null
}

/** "a/very/long/…/path/File.kt": the start and the end, which say the most. */
internal fun middleEllipsis(text: String, max: Int): String {
    if (text.length <= max) return text
    val keep = max - 1
    val tail = keep * 2 / 3
    return text.take(keep - tail) + "…" + text.takeLast(tail)
}

// ---- chart --------------------------------------------------------------------------------------

@Composable
private fun ChartBlock(view: BlockView) {
    val colors = AgentTheme.colors
    val bars = view.kind == "bar"
    val series = view.series.filter { it.points.isNotEmpty() }
    val unit = view.unit?.let { " $it" }.orEmpty()
    val last = series.firstOrNull()?.points?.lastOrNull()?.getOrNull(1)?.doubleOrNull
    BlockTitle(view.title ?: "Chart", last?.let { "${number(it)}$unit" })
    if (series.isEmpty()) {
        MetaText("No points yet.")
        return
    }
    val labels = series.any { s -> s.points.any { it.getOrNull(0)?.isString == true } }
    val categories = if (labels) series.flatMap { s -> s.points.mapNotNull { it.getOrNull(0)?.content } }.distinct() else emptyList()
    val ys = series.flatMap { s -> s.points.mapNotNull { it.getOrNull(1)?.doubleOrNull } }
    val top = (ys.maxOrNull() ?: 1.0).let { if (bars) maxOf(it, 0.0) else it }
    val bottom = (ys.minOrNull() ?: 0.0).let { if (bars) minOf(it, 0.0) else it }
    val range = if (top == bottom) 1.0 else top - bottom
    val xs = if (labels) emptyList() else series.flatMap { s -> s.points.mapNotNull { it.getOrNull(0)?.doubleOrNull } }
    val xMin = xs.minOrNull() ?: 0.0
    val xMax = xs.maxOrNull() ?: 1.0
    val palette = chartPalette(colors)
    val spoken = buildString {
        append(if (bars) "Bar chart" else "Line chart")
        view.title?.let { append(", ").append(it) }
        view.xLabel?.let { append(", over ").append(it) }
        series.forEachIndexed { at, s ->
            val values = s.points.mapNotNull { it.getOrNull(1)?.doubleOrNull }
            append(". ").append(s.label ?: "Series ${at + 1}").append(": ").append(values.size).append(" points")
            if (values.isNotEmpty()) append(", from ${number(values.min())} to ${number(values.max())}$unit, last ${number(values.last())}$unit")
        }
    }
    Row(Modifier.fillMaxWidth().semantics { contentDescription = spoken }) {
        Column(Modifier.height(ChartHeight).padding(end = 6.dp).clearAndSetSemantics { }, verticalArrangement = Arrangement.SpaceBetween) {
            MetaText(number(top) + unit)
            if (bottom < 0 && top > 0) MetaText("0")
            MetaText(number(bottom) + unit)
        }
        Column(Modifier.weight(1f).clearAndSetSemantics { }) {
            val grid = colors.border
            Canvas(Modifier.fillMaxWidth().height(ChartHeight)) {
                fun yAt(v: Double) = (size.height * (1 - (v - bottom) / range)).toFloat()
                // Gridlines at the top, middle and bottom.
                for (f in listOf(0f, 0.5f, 1f)) drawLine(grid, Offset(0f, size.height * f), Offset(size.width, size.height * f), strokeWidth = 1f)
                if (bars) {
                    val groups = if (labels) categories.size else series.maxOf { it.points.size }
                    val slot = size.width / groups.coerceAtLeast(1)
                    val barWidth = (slot * 0.7f / series.size).coerceAtMost(28.dp.toPx())
                    val zero = yAt(0.0)
                    series.forEachIndexed { si, s ->
                        val color = palette[si % palette.size]
                        s.points.forEachIndexed { pi, p ->
                            val y = p.getOrNull(1)?.doubleOrNull ?: return@forEachIndexed
                            val g = if (labels) categories.indexOf(p[0].content) else pi
                            val left = slot * g + (slot - barWidth * series.size) / 2 + barWidth * si
                            val yy = yAt(y)
                            drawRect(color, Offset(left, minOf(yy, zero)), Size(barWidth * 0.9f, abs(zero - yy).coerceAtLeast(1f)))
                        }
                    }
                } else {
                    series.forEachIndexed { si, s ->
                        val color = palette[si % palette.size]
                        val path = Path()
                        var lastPoint: Offset? = null
                        val count = s.points.size
                        s.points.forEachIndexed { pi, p ->
                            val y = p.getOrNull(1)?.doubleOrNull ?: return@forEachIndexed
                            val x = if (labels) {
                                val g = categories.indexOf(p[0].content)
                                if (categories.size <= 1) size.width / 2 else size.width * g / (categories.size - 1)
                            } else {
                                val xv = p.getOrNull(0)?.doubleOrNull ?: pi.toDouble()
                                if (xMax == xMin) size.width / 2 else (size.width * (xv - xMin) / (xMax - xMin)).toFloat()
                            }
                            val at = Offset(x.toFloat(), yAt(y))
                            if (lastPoint == null) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
                            lastPoint = at
                        }
                        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        lastPoint?.let { drawCircle(color, 3.5.dp.toPx(), it) }
                        if (count == 1) lastPoint?.let { drawCircle(color, 3.5.dp.toPx(), it) }
                    }
                }
            }
            if (bars && labels && categories.size <= MaxBarLabels) {
                // A few bars: each says what it is, under it.
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    categories.forEach { MetaText(it, Modifier.weight(1f), style = AgentTheme.type.meta.copy(textAlign = TextAlign.Center)) }
                }
            } else Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                val first = if (labels) categories.firstOrNull() else number(xMin)
                val end = if (labels) categories.lastOrNull() else number(xMax)
                if (first != null) MetaText(first, Modifier.weight(1f))
                if (end != null && end != first) MetaText(end, Modifier.weight(1f), style = AgentTheme.type.meta.copy(textAlign = TextAlign.End))
            }
            view.xLabel?.let { MetaText(it, Modifier.fillMaxWidth(), style = AgentTheme.type.meta.copy(textAlign = TextAlign.Center)) }
        }
    }
    if (series.size > 1 || view.yLabel != null) Legend(series, palette, view.yLabel)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(series: List<ChartSeries>, palette: List<Color>, yLabel: String?) {
    FlowRow(Modifier.padding(top = 6.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        yLabel?.let { MetaText(it, color = AgentTheme.colors.textMuted) }
        if (series.size > 1) series.forEachIndexed { at, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(palette[at % palette.size]))
                MetaText(s.label ?: s.id, Modifier.padding(start = 4.dp))
            }
        }
    }
}

private val ChartHeight = 140.dp
private const val MaxBarLabels = 8

private fun chartPalette(colors: AgentColors): List<Color> = listOf(colors.accent, colors.success, colors.warning, colors.error) + colors.agents

/** A number as people write it: no ".0", at most two places, thousands kept short. */
internal fun number(v: Double): String {
    val a = abs(v)
    return when {
        a >= 1_000_000 -> trim(v / 1_000_000) + "M"
        a >= 10_000 -> trim(v / 1_000) + "k"
        v == v.toLong().toDouble() -> v.toLong().toString()
        else -> trim(v)
    }
}

private fun trim(v: Double): String {
    val r = (v * 100).roundToInt() / 100.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString().trimEnd('0').trimEnd('.')
}

// ---- table --------------------------------------------------------------------------------------

@Composable
private fun TableBlock(view: BlockView) {
    val colors = AgentTheme.colors
    var sortBy by rememberSaveable(view.id) { mutableStateOf(-1) }
    var descending by rememberSaveable(view.id) { mutableStateOf(false) }
    var all by rememberSaveable(view.id) { mutableStateOf(false) }
    BlockTitle(view.title ?: "Table", "${view.rows.size} row${if (view.rows.size == 1) "" else "s"}")
    val rows = remember(view.rows, sortBy, descending) {
        if (sortBy < 0) view.rows else {
            val sorted = view.rows.sortedWith { a, b -> compareCells(a.getOrNull(sortBy), b.getOrNull(sortBy)) }
            if (descending) sorted.reversed() else sorted
        }
    }
    val natural = remember(view.columns, view.rows) { columnWidths(view) }
    val limit = view.limit.coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val widths = fitColumns(view, natural, maxWidth)
    val total = widths.fold(0.dp) { a, b -> a + b }
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(Modifier.width(total)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                view.columns.forEachIndexed { at, column ->
                    val sorted = sortBy == at
                    val numeric = column.kind == "number"
                    Row(
                        Modifier
                            .width(widths[at])
                            .focusRing(AgentTheme.shapes.small)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "Sort by ${column.label}"
                                stateDescription = when {
                                    !sorted -> "not sorted"
                                    descending -> "sorted descending"
                                    else -> "sorted ascending"
                                }
                                role = Role.Button
                            }
                            .heightIn(min = 32.dp)
                            .clickable {
                                when {
                                    !sorted -> { sortBy = at; descending = false }
                                    !descending -> descending = true
                                    else -> { sortBy = -1; descending = false }
                                }
                            }
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (numeric) Arrangement.End else Arrangement.Start,
                    ) {
                        Text(
                            column.label,
                            Modifier.weight(1f, fill = false).clearAndSetSemantics { },
                            color = if (sorted) colors.text else colors.textMuted,
                            style = AgentTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (sorted) Text(if (descending) " ↓" else " ↑", color = colors.text, style = AgentTheme.type.meta)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            if (all && rows.size > limit) {
                // Unfolded: a box of its own that scrolls, drawing only the rows in view.
                val state = rememberLazyListState()
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = ExpandedHeight), state = state) {
                    itemsIndexed(rows) { at, row -> TableRow(view, row, widths, at) }
                }
            } else {
                rows.take(limit).forEachIndexed { at, row -> TableRow(view, row, widths, at) }
            }
        }
    }
    }
    if (rows.size > limit) {
        BlockAction(if (all) "Show fewer" else "Show all ${rows.size} rows") { all = !all }
    }
}

@Composable
private fun TableRow(view: BlockView, row: List<JsonElement>, widths: List<Dp>, at: Int) {
    val colors = AgentTheme.colors
    val spoken = view.columns.mapIndexed { i, c -> "${c.label}: ${cellText(row.getOrNull(i))}" }.joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (at % 2 == 1) colors.surface.copy(alpha = 0.5f) else Color.Transparent)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        view.columns.forEachIndexed { i, column ->
            Text(
                cellText(row.getOrNull(i)),
                Modifier.width(widths[i]).padding(horizontal = 6.dp).clearAndSetSemantics { },
                color = if (row.getOrNull(i) is JsonNull) colors.textFaint else colors.text,
                style = if (column.kind == "number") AgentTheme.type.code.copy(fontSize = 13.sp, textAlign = TextAlign.End) else AgentTheme.type.body.copy(fontSize = 14.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val ExpandedHeight = 360.dp

internal fun cellText(cell: JsonElement?): String = when (cell) {
    null, is JsonNull -> "—"
    is JsonPrimitive -> when {
        cell.isString -> cell.content
        cell.booleanOrNull != null -> if (cell.booleanOrNull == true) "Yes" else "No"
        else -> cell.doubleOrNull?.let { if (it == it.toLong().toDouble() && abs(it) < 1e15) it.toLong().toString() else trim(it) } ?: cell.content
    }
    else -> cell.toString()
}

/** Numbers in order and before words; words without case; empty cells last. */
internal fun compareCells(a: JsonElement?, b: JsonElement?): Int {
    fun number(c: JsonElement?) = (c as? JsonPrimitive)?.takeIf { !it.isString }?.let { it.doubleOrNull ?: it.booleanOrNull?.let { b -> if (b) 1.0 else 0.0 } }
    fun text(c: JsonElement?) = (c as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
    val na = number(a)
    val nb = number(b)
    val ta = text(a)
    val tb = text(b)
    return when {
        na != null && nb != null -> na.compareTo(nb)
        na != null -> -1
        nb != null -> 1
        ta != null && tb != null -> ta.compareTo(tb)
        ta != null -> -1
        tb != null -> 1
        else -> 0
    }
}

/** Each column as wide as its longest cell needs, within reason. */
private fun columnWidths(view: BlockView): List<Dp> = view.columns.mapIndexed { i, column ->
    val longest = (view.rows.take(200).map { cellText(it.getOrNull(i)).length } + column.label.length + 2).max()
    (longest * 7.5f + 16).coerceIn(56f, 240f).dp
}

/**
 * The widths to draw at in [room]: numbers keep what they need; text columns share what is left,
 * wrapping onto a second line, down to [TextColumnMin]. Only past that does the table scroll sideways.
 */
private fun fitColumns(view: BlockView, natural: List<Dp>, room: Dp): List<Dp> {
    val text = view.columns.indices.filter { view.columns[it].kind != "number" }
    if (text.isEmpty()) return natural
    val fixed = view.columns.indices.filter { it !in text }.fold(0.dp) { a, i -> a + natural[i] }
    val wanted = text.fold(0.dp) { a, i -> a + natural[i] }
    val left = room - fixed
    // Room to spare grows them; too little shrinks them, each to no less than it needs or the minimum.
    val scale = left / wanted
    return natural.mapIndexed { i, w -> if (i in text) maxOf(w * scale, minOf(w, TextColumnMin)) else w }
}

private val TextColumnMin = 96.dp

// ---- log ----------------------------------------------------------------------------------------

@Composable
private fun LogBlock(view: BlockView) {
    val colors = AgentTheme.colors
    var all by rememberSaveable(view.id) { mutableStateOf(false) }
    val tail = view.tail.coerceAtLeast(1)
    BlockTitle(view.title ?: "Log", "${view.lines.size + view.dropped} line${if (view.lines.size + view.dropped == 1L) "" else "s"}")
    val code = colors.code
    Column(
        Modifier.fillMaxWidth().clip(AgentTheme.shapes.small).background(code.background).padding(vertical = 6.dp)
            .semantics { contentDescription = "Log, ${view.lines.size} lines" + (view.highlight?.let { ". Marked: $it" } ?: "") },
    ) {
        if (view.lines.isEmpty()) {
            LogLine("Nothing yet.", colors, faint = true)
        } else if (all) {
            if (view.dropped > 0) LogLine("${view.dropped} earlier lines not kept", colors, faint = true)
            val state = rememberLazyListState(initialFirstVisibleItemIndex = (view.lines.size - 1).coerceAtLeast(0))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = ExpandedHeight), state = state) {
                itemsIndexed(view.lines) { _, line -> LogLine(line, colors, marked = line == view.highlight) }
            }
            // A log that keeps growing is followed at its end, unless the owner scrolled up to read.
            LaunchedEffect(view.lines.size) {
                val seen = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (seen >= state.layoutInfo.totalItemsCount - 4) state.scrollToItem((view.lines.size - 1).coerceAtLeast(0))
            }
        } else {
            val marked = view.highlight?.takeIf { h -> view.lines.takeLast(tail).none { it == h } }
            if (marked != null) {
                LogLine(marked, colors, marked = true)
                LogLine("⋯", colors, faint = true)
            }
            view.lines.takeLast(tail).forEach { LogLine(it, colors, marked = it == view.highlight) }
        }
    }
    val hidden = view.lines.size - tail
    if (hidden > 0) BlockAction(if (all) "Fold" else "Show all ${view.lines.size} lines") { all = !all }
}

@Composable
private fun LogLine(text: String, colors: AgentColors, marked: Boolean = false, faint: Boolean = false) {
    val tone = when {
        faint -> colors.code.comment
        marked -> colors.error
        LogError.containsMatchIn(text) -> colors.error
        LogWarn.containsMatchIn(text) -> colors.warning
        else -> colors.code.text
    }
    Text(
        text.ifEmpty { " " },
        Modifier
            .fillMaxWidth()
            .background(if (marked) colors.error.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 1.dp),
        color = tone,
        style = AgentTheme.type.code.copy(fontSize = 12.sp, lineHeight = 17.sp),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

private val LogError = Regex("\\b(error|fail(ed|ure)?|fatal|panic|exception)\\b", RegexOption.IGNORE_CASE)
private val LogWarn = Regex("\\b(warn(ing)?|deprecated)\\b", RegexOption.IGNORE_CASE)

// ---- poll ---------------------------------------------------------------------------------------

@OptIn(ExperimentalTime::class)
@Composable
private fun PollBlock(block: CardBlock) {
    val view = block.block
    val colors = AgentTheme.colors
    val closesAt = view.closesAt?.let(::millis)
    val now = tickingNow(!view.closed && closesAt != null)
    val ui = LocalFeedUi.current
    val closed = view.closed || (closesAt != null && closesAt <= now)
    fun mineOf(option: dev.wildware.agents.PollOption) = option.voters.any { it.kind == "person" && it.id == block.me }
    val mine = view.options.filter(::mineOf).map { it.id }
    val shown = view
    val total = shown.totalVoters
    val note = when {
        closed -> "Closed"
        closesAt != null -> "Closes ${etaLabel(closesAt - now).removePrefix("about ").removeSuffix(" left").let { if (it == "due now") "now" else "in $it" }}"
        else -> null
    }
    BlockTitle(view.title, note)
    Text(view.question.orEmpty(), Modifier.padding(bottom = 8.dp), color = colors.text, style = AgentTheme.type.bodyStrong)
    val most = shown.options.maxOfOrNull { it.votes } ?: 0
    shown.options.forEach { option ->
        key(option.id) {
            val picked = option.id in mine
            val fraction = if (total == 0) 0f else option.votes.toFloat() / total
            val shape = AgentTheme.shapes.small
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(shape)
                    .border(1.dp, if (picked) colors.accent else colors.border, shape)
                    .focusRing(shape)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${option.label}, ${option.votes} vote${if (option.votes == 1) "" else "s"}"
                        if (view.multi) {
                            role = Role.Checkbox
                            toggleableState = ToggleableState(picked)
                        } else {
                            role = Role.RadioButton
                            selected = picked
                        }
                        if (closed) stateDescription = "Closed"
                    }
                    .heightIn(min = 48.dp)
                    .then(if (closed) Modifier else Modifier.clickable {
                        val next = when {
                            view.multi -> if (picked) mine - option.id else mine + option.id
                            picked -> emptyList()
                            else -> listOf(option.id)
                        }
                        ui?.emit(FeedEvent.VoteBlock(block.cardKind, block.cardId, view.id, next))
                    }),
                contentAlignment = Alignment.CenterStart,
            ) {
                // How many chose it, as a fill behind the words.
                Box(Modifier.matchParentSize()) {
                    if (fraction > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(if (option.votes == most) colors.accentSoft else colors.border.copy(alpha = 0.5f)))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PollMark(picked, view.multi)
                    Text(option.label, Modifier.weight(1f).padding(start = 8.dp).clearAndSetSemantics { }, color = colors.text, style = AgentTheme.type.body)
                    Text(
                        "${option.votes}",
                        Modifier.padding(start = 8.dp).clearAndSetSemantics { },
                        color = colors.textMuted,
                        style = AgentTheme.type.meta.copy(fontWeight = FontWeight.Medium),
                    )
                }
            }
        }
    }
    val voters = shown.options.flatMap { it.voters }.distinctBy { it.kind to it.id }
    val names = if (block.team) voters.map { actorLabel(it, block) } else emptyList()
    val foot = listOfNotNull(
        "$total vote${if (total == 1) "" else "s"}",
        if (view.multi) "choose any" else null,
        names.takeIf { it.isNotEmpty() }?.joinToString(", "),
    ).joinToString(" · ")
    MetaText(foot, Modifier.padding(top = 4.dp))
}

@Composable
private fun PollMark(picked: Boolean, multi: Boolean) {
    val colors = AgentTheme.colors
    val shape = if (multi) RoundedCornerShape(5.dp) else CircleShape
    Box(
        Modifier.size(18.dp).clip(shape).background(if (picked) colors.accent else Color.Transparent).border(1.5.dp, if (picked) colors.accent else colors.borderStrong, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (picked) {
            if (multi) Icon(Icons.Check, null, tint = colors.onAccent, modifier = Modifier.size(12.dp))
            else Box(Modifier.size(7.dp).clip(CircleShape).background(colors.onAccent))
        }
    }
}
