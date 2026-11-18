package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable
import dev.wildware.agents.app.design.gesture.GestureAction
import dev.wildware.agents.app.design.gesture.SwipeActions
import dev.wildware.agents.app.design.gesture.SwipeActionsState
import dev.wildware.agents.app.design.gesture.SwipeCommitDistance
import androidx.compose.foundation.focusable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/** How roomy a card is. Phones get a little less padding than desktop windows. */
enum class Density { Comfortable, Compact }

/** The box around one feed item: a flat panel with a crisp one-pixel edge. */
@Composable
fun CardFrame(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    density: Density = Density.Comfortable,
    content: @Composable () -> Unit,
) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val pad = if (density == Density.Comfortable) 18.dp else 14.dp
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.surfaceRaised else colors.surface)
            .border(1.dp, if (selected) colors.borderStrong else colors.border, shape)
            .padding(horizontal = pad, vertical = pad - 2.dp),
    ) { content() }
}

private val AvatarSize = 26.dp

/** Name, role, time, level and status on one line; read aloud as one sentence. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardHeader(
    author: Author,
    role: String?,
    time: String,
    level: Level?,
    status: WorkStatus?,
    project: Project?,
    showAvatar: Boolean,
    compact: Boolean = false,
) {
    val colors = AgentTheme.colors
    val type = AgentTheme.type
    val spoken = listOfNotNull(author.name, role, time, level?.label, status?.let { "status ${it.label}" }).joinToString(", ")
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showAvatar) {
            Avatar(author, AvatarSize)
            Spacer(Modifier.width(10.dp))
        }
        Row(Modifier.weight(1f).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (compact && author is Author.Owner) "You" else author.name,
                color = colors.text,
                style = type.label.copy(fontWeight = FontWeight.SemiBold),
            )
            if (project != null && !compact) {
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Hash, null, tint = colors.textFaint, modifier = Modifier.size(11.dp))
                MetaText(project.name, Modifier.padding(start = 2.dp))
            }
            Spacer(Modifier.width(10.dp))
            MetaText(time)
        }
        if (!compact) Chips(level, status)
    }
}

@Composable
private fun Chips(level: Level?, status: WorkStatus?, modifier: Modifier = Modifier) {
    Row(
        modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status != null) StatusChip(status)
        if (level != null) LevelTag(level)
    }
}

/** Offset that lines body text up under the name rather than under the avatar. */
private val BodyIndent = 36.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateCard(
    item: FeedItem.Update,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    density: Density = Density.Comfortable,
    /** Shown at the top of its own thread, so it drops the "replies" button. */
    inThread: Boolean = false,
) {
    val colors = AgentTheme.colors
    val type = AgentTheme.type
    val ui = LocalFeedUi.current
    val indent = if (density == Density.Compact) 0.dp else BodyIndent
    val target = MenuTarget(item.id, item.agent.name, item.title, own = false)
    val reactions = ui?.reactionsFor(item.id, item.reactions) ?: item.reactions
    WithMenu(target, modifier) { gestures ->
    CardFrame(gestures, selected = selected, density = density) {
        Column {
            CardHeader(
                author = Author.AgentAuthor(item.agent),
                role = item.agent.role,
                time = item.time,
                level = item.level,
                status = item.status,
                project = item.project,
                showAvatar = true,
                compact = density == Density.Compact,
            )
            Column(Modifier.padding(start = indent, top = 8.dp)) {
                if (density == Density.Compact) Chips(item.level, item.status, Modifier.padding(bottom = 8.dp))
                Text(
                    item.title,
                    Modifier.semantics { heading() },
                    color = colors.text,
                    style = type.title,
                )
                Spacer(Modifier.height(6.dp))
                MarkdownBody(item.body)
                if (item.attachment != null) {
                    Spacer(Modifier.height(10.dp))
                    AttachmentView(item.attachment) { ui?.viewer = item.attachment }
                }
                if (item.tags.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.tags.forEach { Tag(it) }
                    }
                }
                val showThread = item.replies.isNotEmpty() && !inThread
                val hasFooter = reactions.isNotEmpty() || showThread
                if (hasFooter) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ReactionRow(reactions)
                        if (showThread) ThreadButton(item)
                    }
                }
                if (item.acks.read.isNotEmpty() || item.acks.thinking.isNotEmpty()) {
                    Spacer(Modifier.height(if (hasFooter) 4.dp else 12.dp))
                    AckLine(item.acks)
                }
            }
        }
    }
}
}

@Composable
private fun Tag(text: String) {
    val colors = AgentTheme.colors
    Text(
        "#$text",
        Modifier
            .clip(AgentTheme.shapes.small)
            .background(colors.text.copy(alpha = if (colors.isDark) 0.07f else 0.045f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        color = colors.textMuted,
        style = AgentTheme.type.meta,
    )
}

/** "4 replies · 2 new", with the faces of who replied. Opens the thread. */
@Composable
private fun ThreadButton(item: FeedItem.Update) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.pill
    val n = item.replies.size
    val label = "$n ${if (n == 1) "reply" else "replies"}"
    val spoken = "Open thread, $label" + if (item.unreadReplies > 0) ", ${item.unreadReplies} new" else ""
    val ui = LocalFeedUi.current
    val isPhone = LocalIsPhone.current
    val open = {
        if (isPhone) ui?.phoneThread = item
        ui?.emit(FeedEvent.OpenThread(item.id))
    }
    Box(Modifier.clip(shape).pressable(spoken, shape) { open() }, contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier
                .clip(shape)
                .padding(start = 4.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                item.replies.map { it.author }.distinct().take(3).forEach { a ->
                    Box(Modifier.clip(CircleShape).background(colors.surface).padding(1.5.dp)) { Avatar(a, 20.dp) }
                }
            }
            Text(
                label,
                Modifier.padding(start = 8.dp),
                color = if (colors.isDark) colors.text else colors.accent,
                style = AgentTheme.type.label,
            )
            if (item.unreadReplies > 0) {
                Spacer(Modifier.width(8.dp))
                UnreadBadge(item.unreadReplies, emphasised = true, what = "new replies")
            }
        }
    }
}

/** A question an agent is waiting on. The loudest thing on the screen, on purpose. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionCard(item: FeedItem.Question, modifier: Modifier = Modifier, density: Density = Density.Comfortable) {
    val colors = AgentTheme.colors
    val type = AgentTheme.type
    val shape = AgentTheme.shapes.large
    val muted = colors.textMuted
    val pad = if (density == Density.Comfortable) 22.dp else 18.dp
    val frame = Modifier
        .clip(shape)
        .background(colors.waitingSoft)
        .border(1.dp, colors.waiting.copy(alpha = 0.4f), shape)
    val ui = LocalFeedUi.current
    val primary = item.choices.firstOrNull { it.primary } ?: item.choices.first()
    val decline = item.choices.lastOrNull { !it.primary }
    val answer = { choice: Choice -> ui?.emit(FeedEvent.Answer(item.id, choice.label)); Unit }
    val dismiss = { ui?.emit(FeedEvent.DismissQuestion(item.id)); Unit }
    val preview = LocalPreview.current
    val commitPx = with(androidx.compose.ui.platform.LocalDensity.current) { SwipeCommitDistance.toPx() }
    val swipe = remember { SwipeActionsState(preview.questionSwipe * commitPx) }
    SwipeActions(
        shape = shape,
        modifier = modifier,
        state = swipe,
        startAction = GestureAction(primary.label, Icons.Check, colors.success, Color.White) { answer(primary) },
        endAction = GestureAction(decline?.label ?: "Dismiss", Icons.Close, colors.textMuted, colors.background) {
            if (decline != null) answer(decline) else dismiss()
        },
    ) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(frame)
            .semantics(mergeDescendants = false) { contentDescription = "Question from ${item.agent.name}, waiting on you" }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onKeyEvent false
                val number = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five).indexOf(e.key)
                when {
                    e.key == Key.Y -> { answer(primary); true }
                    e.key == Key.N && decline != null -> { answer(decline); true }
                    number in item.choices.indices -> { answer(item.choices[number]); true }
                    else -> false
                }
            }
            .focusable()
            .padding(pad),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(Author.AgentAuthor(item.agent), 22.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "WAITING ON YOU",
                Modifier.weight(1f),
                color = colors.waiting,
                style = type.overline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Clock, null, tint = muted, modifier = Modifier.size(14.dp))
            MetaText("${item.expiresIn} left", Modifier.padding(start = 5.dp), color = muted)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            item.prompt,
            Modifier.semantics { heading() },
            color = colors.text,
            style = type.title.copy(fontSize = type.title.fontSize * 1.08f),
        )
        Spacer(Modifier.height(6.dp))
        MarkdownBody(item.body)
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item.choices.forEachIndexed { i, choice ->
                // Number keys answer on desktop; phones have no keyboard to hint at.
                val hint = if (density == Density.Comfortable) "${i + 1}" else null
                AppButton(
                    choice.label,
                    if (choice.primary) ButtonTone.Waiting else ButtonTone.Secondary,
                    icon = if (choice.primary) Icons.Check else null,
                    trailing = hint,
                ) { answer(choice) }
            }
        }
        if (item.allowsText) {
            Spacer(Modifier.height(10.dp))
            AnswerField()
        }
        if (LocalIsPhone.current) {
            Text(
                "Swipe right: ${primary.label} · left: ${decline?.label ?: "Dismiss"}",
                Modifier.padding(top = 10.dp).clearAndSetSemantics { },
                color = muted.copy(alpha = 0.8f),
                style = type.meta,
            )
        }
    }
}
}

/** "Or write an answer", a one-line field that grows into the reply. */
@Composable
private fun AnswerField() {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val bg = colors.surface.copy(alpha = if (colors.isDark) 0.6f else 0.85f)
    val fg = colors.textFaint
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, colors.border, shape)
            .pressable("Write your own answer", shape) {}
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Or write an answer…", Modifier.weight(1f), color = fg, style = AgentTheme.type.body)
        Icon(Icons.Send, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
    }
}

/** Something the owner posted to the feed. Marked as theirs at a glance. */
@Composable
fun OwnerPostCard(item: FeedItem.OwnerPost, modifier: Modifier = Modifier, density: Density = Density.Comfortable) {
    val colors = AgentTheme.colors
    val indent = if (density == Density.Compact) 0.dp else BodyIndent
    val target = MenuTarget(item.id, "You", item.body, own = true)
    WithMenu(target, modifier) { gestures ->
    CardFrame(gestures, density = density) {
        Column {
            CardHeader(
                author = item.author,
                role = "You",
                time = item.time,
                level = null,
                status = null,
                project = item.project,
                showAvatar = true,
                compact = density == Density.Compact,
            )
            Column(Modifier.padding(start = indent, top = 8.dp)) {
                Text(item.body, color = colors.text, style = AgentTheme.type.body)
                if (item.acks.read.isNotEmpty() || item.acks.thinking.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    AckLine(item.acks)
                }
            }
        }
    }
}
}

@Composable
fun FeedItemView(item: FeedItem, selected: Boolean, density: Density, modifier: Modifier = Modifier) {
    when (item) {
        is FeedItem.Update -> UpdateCard(item, modifier, selected = selected, density = density)
        is FeedItem.Question -> QuestionCard(item, modifier, density = density)
        is FeedItem.OwnerPost -> OwnerPostCard(item, modifier, density = density)
    }
}
