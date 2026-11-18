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
import dev.wildware.agents.app.design.TextInput
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import dev.wildware.agents.app.design.PickMode
import dev.wildware.agents.app.design.PictureGrid
import dev.wildware.agents.app.design.PictureOption
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
private fun ThreadButton(item: Threaded) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.pill
    val n = item.replies.size
    val label = "$n ${if (n == 1) "reply" else "replies"}"
    val spoken = "Open thread, $label" + if (item.unreadReplies > 0) ", ${item.unreadReplies} new" else ""
    val ui = LocalFeedUi.current
    val isPhone = LocalIsPhone.current
    val open = {
        ui?.openThread(item.id, phone = isPhone)
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
    val primary = item.choices.firstOrNull { it.primary } ?: item.choices.firstOrNull()
    val decline = item.choices.lastOrNull { !it.primary }
    val answer = { choice: Choice -> ui?.emit(FeedEvent.Answer(item.id, listOf(choice.label))); Unit }
    val dismiss = { ui?.emit(FeedEvent.DismissQuestion(item.id)); Unit }
    // Buttons answer on the spot; choice and multi-choice gather picks, then send them.
    val gathers = item.kind == QuestionKind.Choice || item.kind == QuestionKind.MultiChoice
    val picks = ui?.picksFor(item.id) ?: emptySet()
    val send = {
        if (picks.isNotEmpty()) ui?.emit(FeedEvent.Answer(item.id, item.choices.map { it.label }.filter { it in picks }))
        Unit
    }
    val pick = { choice: Choice -> if (gathers) ui?.toggle(item, choice.label) else answer(choice); Unit }
    val sendLabel = when (picks.size) {
        0 -> "Send"
        1 -> "Send 1 pick"
        else -> "Send ${picks.size} picks"
    }
    val preview = LocalPreview.current
    val commitPx = with(androidx.compose.ui.platform.LocalDensity.current) { SwipeCommitDistance.toPx() }
    val swipe = remember { SwipeActionsState(preview.questionSwipe * commitPx) }
    SwipeActions(
        shape = shape,
        modifier = modifier,
        state = swipe,
        startAction = when {
            item.sending -> null
            !gathers && primary != null -> GestureAction(primary.label, Icons.Check, colors.success, Color.White) { answer(primary) }
            picks.isNotEmpty() -> GestureAction(sendLabel, Icons.Send, colors.success, Color.White) { send() }
            else -> null
        },
        endAction = when {
            item.sending -> null
            !gathers && decline != null -> GestureAction(decline.label, Icons.Close, colors.textMuted, colors.background) { answer(decline) }
            else -> GestureAction("Not now", Icons.Close, colors.textMuted, colors.background) { dismiss() }
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
                    gathers && (e.key == Key.Enter || e.key == Key.Y) -> { send(); true }
                    e.key == Key.Y && primary != null -> { answer(primary); true }
                    e.key == Key.N && decline != null && !gathers -> { answer(decline); true }
                    e.key == Key.N -> { dismiss(); true }
                    number in item.choices.indices -> { pick(item.choices[number]); true }
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
        if (item.attachment != null) {
            Spacer(Modifier.height(10.dp))
            AttachmentView(item.attachment) { ui?.viewer = item.attachment }
        }
        Spacer(Modifier.height(16.dp))
        if (item.hasPictures) {
            PictureGrid(
                options = item.choices.map { PictureOption(it.label, picked = it.label in picks) },
                mode = when (item.kind) {
                    QuestionKind.Buttons -> PickMode.Instant
                    QuestionKind.Choice -> PickMode.One
                    QuestionKind.MultiChoice -> PickMode.Many
                    QuestionKind.Text, QuestionKind.Unsupported -> PickMode.Instant
                },
                onPick = { i -> pick(item.choices[i]) },
                onLookCloser = { i -> item.choices[i].image?.let { ui?.viewer = it } },
            ) { i, m ->
                val image = item.choices[i].image
                if (image != null) AttachmentImage(image, m) else Box(m.background(colors.surfaceSunken))
            }
        }
        if (gathers) {
            if (item.hasPictures) Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(sendLabel, ButtonTone.Waiting, icon = Icons.Send, enabled = picks.isNotEmpty() && !item.sending, onClick = send)
                AppButton("Not now", ButtonTone.Secondary, enabled = !item.sending, onClick = dismiss)
            }
        }
        if (!item.hasPictures) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item.choices.forEachIndexed { i, choice ->
                // Number keys answer on desktop; phones have no keyboard to hint at.
                val hint = if (density == Density.Comfortable) "${i + 1}" else null
                AppButton(
                    choice.label,
                    if (choice.primary) ButtonTone.Waiting else ButtonTone.Secondary,
                    icon = if (choice.primary) Icons.Check else null,
                    enabled = !item.sending,
                    trailing = hint,
                ) { pick(choice) }
            }
        }
        if (item.kind == QuestionKind.Unsupported) {
            MetaText("Answer this one on the website: the app can't show its form yet.", color = colors.textMuted)
        }
        if (item.allowsText) {
            Spacer(Modifier.height(10.dp))
            AnswerField(enabled = !item.sending) { text -> ui?.emit(FeedEvent.AnswerText(item.id, text)) }
        }
        if (item.sending) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Clock, null, tint = colors.textMuted, modifier = Modifier.size(14.dp))
                MetaText("Answer waiting to send", Modifier.padding(start = 6.dp), color = colors.textMuted)
            }
        }
        if (LocalIsPhone.current) {
            Text(
                when {
                    item.hasPictures && gathers -> "Tap to pick · hold to look closer · swipe right to send"
                    item.hasPictures -> "Tap a picture to answer · hold to look closer"
                    primary != null -> "Swipe right: ${primary.label} · left: ${decline?.label ?: "Dismiss"}"
                    else -> "Swipe left: Not now"
                },
                Modifier.padding(top = 10.dp).clearAndSetSemantics { },
                color = muted.copy(alpha = 0.8f),
                style = type.meta,
            )
        }
    }
}
}

/** "Or write an answer": a one-line box that sends on Enter or its arrow. */
@Composable
private fun AnswerField(enabled: Boolean, onSend: (String) -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val bg = colors.surface.copy(alpha = if (colors.isDark) 0.6f else 0.85f)
    val text = rememberTextFieldState()
    val send = {
        val typed = text.text.toString().trim()
        if (typed.isNotEmpty()) {
            onSend(typed)
            text.clearText()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(1.dp, colors.border, shape)
            .padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextInput(text, label = "Your answer", placeholder = "Or write an answer…", modifier = Modifier.weight(1f), enabled = enabled, onSubmit = send)
        IconAction(Icons.Send, "Send answer", tint = if (text.text.isBlank()) colors.textFaint else colors.accent, onClick = send)
    }
}

/** Something written straight into the feed, by the owner or an agent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PostCard(item: FeedItem.Post, modifier: Modifier = Modifier, density: Density = Density.Comfortable, inThread: Boolean = false) {
    val colors = AgentTheme.colors
    val ui = LocalFeedUi.current
    val own = item.author is Author.Owner
    val indent = if (density == Density.Compact) 0.dp else BodyIndent
    val target = MenuTarget(item.id, if (own) "You" else item.author.name, item.body, own = own)
    val reactions = ui?.reactionsFor(item.id, item.reactions) ?: item.reactions
    WithMenu(target, modifier) { gestures ->
    CardFrame(gestures, density = density) {
        Column {
            CardHeader(
                author = item.author,
                role = if (own) "You" else (item.author as? Author.AgentAuthor)?.agent?.role,
                time = item.time,
                level = null,
                status = null,
                project = item.project,
                showAvatar = true,
                compact = density == Density.Compact,
            )
            Column(Modifier.padding(start = indent, top = 8.dp)) {
                if (own) Text(item.body, color = colors.text, style = AgentTheme.type.body) else MarkdownBody(item.body)
                val showThread = item.replies.isNotEmpty() && !inThread
                if (reactions.isNotEmpty() || showThread) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ReactionRow(reactions)
                        if (showThread) ThreadButton(item)
                    }
                }
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
fun FeedItemView(item: FeedItem, selected: Boolean, density: Density, modifier: Modifier = Modifier, inThread: Boolean = false) {
    when (item) {
        is FeedItem.Update -> UpdateCard(item, modifier, selected = selected, density = density, inThread = inThread)
        is FeedItem.Question -> QuestionCard(item, modifier, density = density)
        is FeedItem.Post -> PostCard(item, modifier, density = density, inThread = inThread)
    }
}
