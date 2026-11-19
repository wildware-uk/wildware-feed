package dev.wildware.agents.app.feed

import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.Immutable
import dev.wildware.agents.app.ProductName
import androidx.compose.runtime.key
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.compositeOver
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
import dev.wildware.agents.app.design.focusRing
import androidx.compose.ui.semantics.Role
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
    val lit = LocalLit.current
    Box(
        modifier
            .fillMaxWidth()
            .semantics { if (lit) this[Highlighted] = true }
            .clip(shape)
            .background(if (selected) colors.surfaceRaised else colors.surface)
            .border(if (lit) 2.dp else 1.dp, if (lit) colors.accent else if (selected) colors.borderStrong else colors.border, shape)
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
    /** Opens the work-state choice when the status chip is tapped. */
    onStatus: (() -> Unit)? = null,
    high: Boolean = false,
    pinned: Boolean = false,
    /** Folding the card away; null where it can't fold (in its own thread). */
    fold: Fold? = null,
) {
    val colors = AgentTheme.colors
    val type = AgentTheme.type
    val spoken = listOfNotNull(
        author.name, role, time, "pinned".takeIf { pinned }, "high priority".takeIf { high }, level?.label, status?.let { "status ${it.label}" },
    ).joinToString(", ")
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showAvatar) {
            Avatar(author, AvatarSize)
            Spacer(Modifier.width(10.dp))
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (compact && author is Author.Owner) "You" else author.name,
                Modifier.clearAndSetSemantics { },
                color = colors.text,
                style = type.label.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            if (project != null && showsProject()) {
                Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
                // Gives way first on a narrow screen: the name and time keep their room.
                ProjectLabel(project, Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.width(10.dp))
            MetaText(time, Modifier.clearAndSetSemantics { })
            if (pinned) Icon(Icons.Pin, null, tint = colors.accent, modifier = Modifier.padding(start = 8.dp).size(13.dp))
        }
        if (!compact) Chips(level, status, high, onStatus = onStatus)
        // First thing a reader reaches for on a long feed, and the one control that changes nothing.
        fold?.let { IconAction(if (it.folded) Icons.ChevronRight else Icons.ChevronDown, it.label, Modifier.padding(start = 4.dp), onClick = it.toggle) }
    }
}

/** A card's fold: whether it is folded, what the control is called, and how to flip it. */
@Immutable
internal class Fold(val folded: Boolean, val label: String, val toggle: () -> Unit)

/** The fold for card [id], kept on this device; null in its own thread, where the whole card is the point. */
@Composable
private fun rememberFold(id: String, noun: String, name: String, inThread: Boolean): Fold? {
    if (inThread) return null
    val folds = LocalCardFolds.current
    val folded = folds.isFolded(id)
    return Fold(folded, "${if (folded) "Expand" else "Collapse"} $noun: $name") { folds.set(id, !folded) }
}

/**
 * What a folded card still shows under its header: one line of what it said, and what is hidden,
 * so the owner can tell whether they want it back without opening it.
 */
@Composable
private fun FoldedSummary(body: String, replies: Int) {
    val colors = AgentTheme.colors
    val line = dev.wildware.agents.PlainText.fromMarkdown(body).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    Text(
        line ?: "No words on this card.",
        color = colors.textMuted,
        style = AgentTheme.type.body,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (replies > 0) MetaText(if (replies == 1) "1 reply hidden" else "$replies replies hidden", Modifier.padding(top = 4.dp))
}

/**
 * The status chip and level tag. Tapping the chip changes the work state; screen readers do that
 * from the card's actions ("Work state…"), so the chips themselves stay out of the spoken header.
 */
@Composable
private fun Chips(level: Level?, status: WorkStatus?, high: Boolean = false, modifier: Modifier = Modifier, onStatus: (() -> Unit)? = null) {
    Row(
        modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (high) HighChip()
        if (status != null) {
            val shape = AgentTheme.shapes.pill
            StatusChip(status, if (onStatus != null) Modifier.clip(shape).clickable(onClick = onStatus) else Modifier)
        }
        if (level != null) LevelTag(level)
    }
}

/**
 * Whether cards should say which project they are from: yes in "All projects", where cards from
 * every project are mixed together; no inside one project, where it would only repeat the title.
 */
@Composable
private fun showsProject(): Boolean = LocalFeedUi.current?.state?.current?.slug == null

/**
 * "# project" beside the author, in the project's colour. Tapping it opens that project. It cuts
 * its name short with an ellipsis before it pushes the time off a narrow phone.
 */
@Composable
private fun ProjectLabel(project: Project, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val ui = LocalFeedUi.current
    val shape = AgentTheme.shapes.small
    val slug = project.slug
    Row(
        modifier
            .clip(shape)
            .focusRing(shape)
            .semantics(mergeDescendants = true) { contentDescription = "In project ${project.name}" }
            .then(
                if (ui != null && slug != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = "Open ${project.name}") {
                        ui.emit(FeedEvent.SelectProject(slug))
                    }
                } else Modifier,
            )
            .padding(horizontal = 3.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Hash, null, tint = colors.agent(project.hue), modifier = Modifier.size(11.dp))
        MetaText(project.name, Modifier.padding(start = 2.dp).clearAndSetSemantics { })
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
    val pinned = ui?.isPinned(item.id, item.pinned) ?: item.pinned
    val target = MenuTarget(item.id, item.agent.name, item.title, own = false, taggable = true, work = true, status = item.status, pinned = pinned, deletable = true, hasDetails = true)
    val tags = ui?.tagsFor(item.id, item.tags) ?: item.tags
    val reactions = ui?.reactionsFor(item.id, item.reactions) ?: item.reactions
    val chooseStatus = { ui?.emit(FeedEvent.ChooseStatus(item.id, item.status)); Unit }
    val fold = rememberFold(item.id, "update", item.title, inThread)
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
                onStatus = chooseStatus,
                high = item.high,
                pinned = pinned,
                fold = fold,
            )
            Column(Modifier.padding(start = indent, top = 8.dp)) {
                if (density == Density.Compact) Chips(item.level, item.status, item.high, Modifier.padding(bottom = 8.dp), onStatus = chooseStatus)
                Text(
                    item.title,
                    Modifier.semantics { heading() },
                    color = colors.text,
                    style = type.title,
                )
                Spacer(Modifier.height(6.dp))
                if (fold?.folded == true) FoldedSummary(item.body, item.replies.size) else {
                    // In its own thread the whole update is the point; in the feed a long one folds.
                    if (inThread) MarkdownBody(item.body) else Clamp(item.id, "the whole update", fade = if (selected) colors.surfaceRaised else colors.surface) { MarkdownBody(item.body) }
                    if (item.attachments.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        MediaGrid(item.attachments, onOpen = rememberTileOpener(item.attachments))
                    }
                    if (item.sounds.isNotEmpty()) Sounds(item.sounds, Modifier.padding(top = 10.dp))
                    if (item.files.isNotEmpty()) Files(item.files, Modifier.padding(top = 10.dp))
                    Embeds(item.embeds, Modifier.padding(top = 10.dp))
                    CardDetails(item.id, ui?.fieldsFor(item.id, item.fields) ?: item.fields, Modifier.padding(top = 8.dp))
                    TagChips(tags, Modifier.padding(top = 10.dp))
                    val inline = LocalInlineThreads.current && !inThread
                    val showThread = item.replies.isNotEmpty() && !inThread && !inline
                    val hasFooter = reactions.isNotEmpty() || showThread
                    if (hasFooter) {
                        Spacer(Modifier.height(12.dp))
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ReactionRow(reactions, item.id)
                            if (showThread) ThreadButton(item)
                        }
                    }
                    if (item.acks.read.isNotEmpty() || item.acks.thinking.isNotEmpty()) {
                        Spacer(Modifier.height(if (hasFooter) 4.dp else 12.dp))
                        AckLine(item.acks)
                    }
                    if (inline) InlineThread(item)
                }
            }
        }
    }
}
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
fun QuestionCard(
    item: FeedItem.Question,
    modifier: Modifier = Modifier,
    density: Density = Density.Comfortable,
    /** Drawn under the reply it answers, so it needn't say what it answers. */
    inThread: Boolean = false,
    /** The talk about it, under its controls. Off where the card is the head of its own thread. */
    showDiscussion: Boolean = true,
) {
    val colors = AgentTheme.colors
    val type = AgentTheme.type
    val shape = AgentTheme.shapes.large
    val muted = colors.textMuted
    val pad = if (density == Density.Comfortable) 22.dp else 18.dp
    val lit = LocalLit.current
    val frame = Modifier
        .semantics { if (lit) this[Highlighted] = true }
        .clip(shape)
        .background(colors.waitingSoft)
        .border(if (lit) 2.dp else 1.dp, if (lit) colors.waiting else colors.waiting.copy(alpha = 0.4f), shape)
    val ui = LocalFeedUi.current
    val primary = item.choices.firstOrNull { it.primary } ?: item.choices.firstOrNull()
    val decline = item.choices.lastOrNull { !it.primary }
    val answer = { choice: Choice -> ui?.emit(FeedEvent.Answer(item.id, listOf(choice.label))); Unit }
    val dismiss = { ui?.emit(FeedEvent.DismissQuestion(item.id)); Unit }
    // Buttons answer on the spot; choice and multi-choice gather picks, then send them.
    val gathers = item.kind == QuestionKind.Choice || item.kind == QuestionKind.MultiChoice
    val picks = ui?.picksFor(item.id) ?: emptySet()
    // Only within the question's bounds: a disabled Send that says why beats a refusal after it.
    val ready = item.picks.allows(picks.size)
    val send = {
        if (ready) ui?.emit(FeedEvent.Answer(item.id, item.choices.map { it.label }.filter { it in picks }))
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
            gathers && ready -> GestureAction(sendLabel, Icons.Send, colors.success, Color.White) { send() }
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
            Text(
                item.agent.name,
                Modifier.padding(start = 8.dp),
                color = colors.text,
                style = type.label.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            // On a phone the section above already says "Waiting on you", so the room goes to the
            // project instead; an amber dot keeps the cue. Screen readers still hear "waiting on you".
            val phone = LocalIsPhone.current
            if (phone) {
                Box(Modifier.padding(start = 8.dp).size(7.dp).clip(CircleShape).background(colors.waiting))
            } else {
                Text(
                    "WAITING ON YOU",
                    Modifier.padding(start = 10.dp),
                    color = colors.waiting,
                    style = type.overline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.weight(1f).padding(start = if (phone) 6.dp else 8.dp, end = 8.dp)) {
                // Inside a thread the project is already known.
                if (showsProject() && !inThread) ProjectLabel(item.project)
            }
            TimeLeft(item.expiresAt, item.expiresIn)
        }
        val context = item.inReplyTo
        if (context != null && !inThread) {
            Spacer(Modifier.height(10.dp))
            InReplyTo(context, item.threadId)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            item.prompt,
            Modifier.semantics { heading() },
            color = colors.text,
            style = type.title.copy(fontSize = type.title.fontSize * 1.08f),
        )
        Spacer(Modifier.height(6.dp))
        Clamp(item.id, "the whole explanation", fade = colors.waitingSoft.compositeOver(colors.background)) { MarkdownBody(item.body) }
        if (item.attachments.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MediaGrid(item.attachments, onOpen = rememberTileOpener(item.attachments))
        }
        if (item.sounds.isNotEmpty()) Sounds(item.sounds, Modifier.padding(top = 10.dp))
        if (item.files.isNotEmpty()) Files(item.files, Modifier.padding(top = 10.dp))
        Embeds(item.embeds, Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(16.dp))
        if (item.hasPictures) {
            PictureGrid(
                options = item.choices.map { PictureOption(it.label, picked = it.label in picks) },
                mode = when (item.kind) {
                    QuestionKind.Buttons -> PickMode.Instant
                    QuestionKind.Choice -> PickMode.One
                    QuestionKind.MultiChoice -> PickMode.Many
                    QuestionKind.Text, QuestionKind.Form, QuestionKind.Unsupported -> PickMode.Instant
                },
                onPick = { i -> pick(item.choices[i]) },
                onLookCloser = { i ->
                    // Look through every option's picture, starting at the one held.
                    val pictures = item.choices.mapNotNull { it.image }.filter { !it.isRemoved }
                    item.choices[i].image?.takeIf { !it.isRemoved }?.let { ui?.viewer = Gallery(pictures, pictures.indexOf(it)) }
                },
            ) { i, m ->
                val image = item.choices[i].image
                if (image != null) AttachmentImage(image, m) else Box(m.background(colors.surfaceSunken))
            }
        }
        if (!item.hasPictures) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item.choices.forEachIndexed { i, choice ->
                // Number keys answer on desktop; phones have no keyboard to hint at.
                val hint = if (density == Density.Comfortable) "${i + 1}" else null
                // Gathered picks show as picked; a buttons question marks its first, suggested answer.
                val picked = gathers && choice.label in picks
                val marked = if (gathers) picked else choice.primary
                AppButton(
                    choice.label,
                    if (marked) ButtonTone.Waiting else ButtonTone.Secondary,
                    modifier = if (gathers) Modifier.semantics { selected = picked } else Modifier,
                    icon = if (marked) Icons.Check else null,
                    enabled = !item.sending,
                    trailing = hint,
                ) { pick(choice) }
            }
        }
        if (gathers) {
            Spacer(Modifier.height(if (item.hasPictures) 14.dp else 10.dp))
            item.picks.hint(item.choices.size)?.let { hint ->
                // Red once there are too many: the one way the owner can be past the bound.
                MetaText(hint, Modifier.padding(bottom = 8.dp), color = if (picks.size > item.picks.max) colors.error else muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(sendLabel, ButtonTone.Waiting, icon = Icons.Send, enabled = ready && !item.sending, onClick = send)
                AppButton("Not now", ButtonTone.Secondary, enabled = !item.sending, onClick = dismiss)
            }
        }
        item.form?.let { form ->
            key(item.id) {
                FormAnswer(form, enabled = !item.sending) { action, text -> ui?.emit(FeedEvent.AnswerForm(item.id, action, text)) }
            }
        }
        if (item.kind == QuestionKind.Unsupported) {
            MetaText("This version of $ProductName can't answer this kind of question. Update the app to answer it.", color = colors.textMuted)
        }
        if (item.allowsText) {
            Spacer(Modifier.height(10.dp))
            key(item.id) {
                AnswerField(item.text, enabled = !item.sending) { text -> ui?.emit(FeedEvent.AnswerText(item.id, text)) }
            }
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
                    gathers -> "Tap to pick · swipe right to send"
                    primary != null -> "Swipe right: ${primary.label} · left: ${decline?.label ?: "Dismiss"}"
                    else -> "Swipe left: Not now"
                },
                Modifier.padding(top = 10.dp).clearAndSetSemantics { },
                color = muted.copy(alpha = 0.8f),
                style = type.meta,
            )
        }
        // Talking it over before deciding, as on the website: under the controls, so the answer is
        // still plainly the way to respond. A question asked inside a thread is talked over there.
        if (showDiscussion && !inThread) {
            if (LocalInlineThreads.current) InlineThread(item, action = "Discuss") else DiscussButton(item)
        }
    }
}
}

/** On a phone: opens the question's own thread, to talk it over. Says how many replies, and how many are new. */
@Composable
private fun DiscussButton(item: FeedItem.Question) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.pill
    val ui = LocalFeedUi.current
    val n = item.replies.size
    val spoken = buildString {
        append("Discuss")
        if (n > 0) append(", $n ${if (n == 1) "reply" else "replies"}")
        if (item.unreadReplies > 0) append(", ${item.unreadReplies} new")
    }
    Row(
        Modifier
            .padding(top = 10.dp)
            .clip(shape)
            .pressable(spoken, shape) {
                ui?.openThread(item.id, phone = true)
                ui?.emit(FeedEvent.OpenThread(item.id))
            }
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Reply, null, tint = colors.textMuted, modifier = Modifier.size(15.dp))
        Text(
            if (n == 0) "Discuss" else "Discuss · ${if (n == 1) "1 reply" else "$n replies"}",
            Modifier.padding(start = 6.dp),
            color = colors.textMuted,
            style = AgentTheme.type.label,
        )
        if (item.unreadReplies > 0) {
            Box(Modifier.padding(start = 6.dp).size(7.dp).clip(CircleShape).background(colors.accent))
        }
    }
}

/**
 * "Or write an answer": a box that sends on Enter (Cmd/Ctrl+Enter when it takes several lines) or its
 * arrow. A text question's [field] sets its placeholder, draft, lines and length, as the agent asked;
 * outside the length it says why and doesn't send.
 */
@Composable
private fun AnswerField(field: FormField?, enabled: Boolean, onSend: (String) -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val bg = colors.surface.copy(alpha = if (colors.isDark) 0.6f else 0.85f)
    val text = rememberTextFieldState(field?.initial.orEmpty())
    val typed = text.text.toString()
    // Empty is never sendable; past that, the agent's length rules. Said only once something is typed.
    val problem = if (typed.isBlank()) null else field?.problem(typed)
    val canSend = typed.isNotBlank() && problem == null
    val send = {
        if (canSend) {
            onSend(typed.trim())
            text.clearText()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(bg)
                .border(1.dp, if (problem != null) colors.waiting.copy(alpha = 0.6f) else colors.border, shape)
                .padding(start = 14.dp, end = 2.dp, top = if (field?.multiline == true) 8.dp else 0.dp),
            verticalAlignment = if (field?.multiline == true) Alignment.Bottom else Alignment.CenterVertically,
        ) {
            TextInput(
                text,
                label = "Your answer",
                placeholder = field?.placeholder?.ifBlank { null } ?: "Or write an answer…",
                modifier = Modifier.weight(1f),
                multiline = field?.multiline == true,
                minLines = if (field?.multiline == true) 3 else 1,
                enabled = enabled,
                onSubmit = send,
            )
            IconAction(Icons.Send, "Send answer", tint = if (canSend) colors.accent else colors.textFaint, onClick = send)
        }
        problem?.let { MetaText(it, color = colors.textMuted) }
    }
}

/**
 * A form question's answer: the field, named when the agent named it and holding the agent's
 * draft, then the agent's own actions. Each action sends the field with it, so the owner decides
 * what to do and what to do it to in one go.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormAnswer(form: FormField, enabled: Boolean, onAnswer: (action: String, text: String) -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val bg = colors.surface.copy(alpha = if (colors.isDark) 0.6f else 0.85f)
    val text = rememberTextFieldState(form.initial)
    val problem = form.problem(text.text.toString())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        form.label?.let { MetaText(it, color = colors.textMuted) }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(bg)
                .border(1.dp, if (problem != null) colors.waiting.copy(alpha = 0.6f) else colors.border, shape)
                .padding(horizontal = 14.dp, vertical = 2.dp),
        ) {
            TextInput(
                text,
                label = form.label ?: "Your answer",
                placeholder = form.placeholder,
                modifier = Modifier.fillMaxWidth(),
                multiline = form.multiline,
                minLines = if (form.multiline) 3 else 1,
                enabled = enabled,
            )
        }
        if (problem != null) MetaText(problem, color = colors.textMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            form.actions.forEachIndexed { i, action ->
                AppButton(
                    action,
                    if (i == 0) ButtonTone.Waiting else ButtonTone.Secondary,
                    icon = if (i == 0) Icons.Check else null,
                    enabled = enabled && problem == null,
                ) { onAnswer(action, text.text.toString()) }
            }
        }
    }
}

/**
 * The owner's line reached no agent. Red and a warning sign rather than a quiet grey, because it is
 * the one acknowledgement the owner has to act on: nobody is listening.
 */
@Composable
internal fun NotDelivered(modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    Row(modifier.semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Warning, null, tint = colors.error, modifier = Modifier.size(14.dp))
        Text(
            "Not delivered to any agent",
            Modifier.padding(start = 6.dp),
            color = colors.error,
            style = AgentTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
        )
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
    val pinned = ui?.isPinned(item.id, item.pinned) ?: item.pinned
    val target = MenuTarget(item.id, if (own) "You" else item.author.name, item.body, own = own, taggable = true, work = true, status = item.status, pinned = pinned, hasDetails = true)
    val tags = ui?.tagsFor(item.id, item.tags) ?: item.tags
    val reactions = ui?.reactionsFor(item.id, item.reactions) ?: item.reactions
    val chooseStatus = { ui?.emit(FeedEvent.ChooseStatus(item.id, item.status)); Unit }
    val fold = rememberFold(item.id, "post", dev.wildware.agents.PlainText.clip(dev.wildware.agents.PlainText.fromMarkdown(item.body), 60), inThread)
    WithMenu(target, modifier.alpha(if (item.sending) SendingAlpha else 1f)) { gestures ->
    CardFrame(gestures, density = density) {
        Column {
            CardHeader(
                author = item.author,
                role = if (own) "You" else (item.author as? Author.AgentAuthor)?.agent?.role,
                time = if (item.sending) SendingLabel else item.time,
                level = null,
                status = item.status,
                project = item.project,
                showAvatar = true,
                compact = density == Density.Compact,
                onStatus = chooseStatus,
                pinned = pinned,
                fold = fold,
            )
            Column(Modifier.padding(start = indent, top = 8.dp)) {
                if (density == Density.Compact && item.status != null) Chips(null, item.status, modifier = Modifier.padding(bottom = 8.dp), onStatus = chooseStatus)
                if (fold?.folded == true) {
                    FoldedSummary(item.body, item.replies.size)
                    return@Column
                }
                val body = @Composable { if (own) PlainBody(item.body) else MarkdownBody(item.body) }
                if (inThread) body() else Clamp(item.id, "the whole post") { body() }
                PostMedia(item.attachments, item.sounds, item.files, item.embeds)
                CardDetails(item.id, ui?.fieldsFor(item.id, item.fields) ?: item.fields, Modifier.padding(top = 8.dp))
                TagChips(tags, Modifier.padding(top = 10.dp))
                val inline = LocalInlineThreads.current && !inThread
                val showThread = item.replies.isNotEmpty() && !inThread && !inline
                if (reactions.isNotEmpty() || showThread) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ReactionRow(reactions, item.id)
                        if (showThread) ThreadButton(item)
                    }
                }
                if (item.acks.read.isNotEmpty() || item.acks.thinking.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    AckLine(item.acks)
                }
                if (item.undelivered) {
                    Spacer(Modifier.height(10.dp))
                    NotDelivered()
                }
                if (inline) InlineThread(item)
            }
        }
    }
}
}

/** A post's or reply's pictures and videos as tiles, then its sounds and files, as an update shows them. */
@Composable
internal fun PostMedia(attachments: List<Attachment>, sounds: List<Sound>, files: List<FileAttachment>, embeds: List<Embed> = emptyList()) {
    if (attachments.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        MediaGrid(attachments, onOpen = rememberTileOpener(attachments))
    }
    if (sounds.isNotEmpty()) Sounds(sounds, Modifier.padding(top = 10.dp))
    if (files.isNotEmpty()) Files(files, Modifier.padding(top = 10.dp))
    Embeds(embeds, Modifier.padding(top = 10.dp))
}

@Composable
fun FeedItemView(item: FeedItem, selected: Boolean, density: Density, modifier: Modifier = Modifier, inThread: Boolean = false) {
    when (item) {
        is FeedItem.Update -> UpdateCard(item, modifier, selected = selected, density = density, inThread = inThread)
        is FeedItem.Question -> QuestionCard(item, modifier, density = density, showDiscussion = !inThread)
        is FeedItem.Post -> PostCard(item, modifier, density = density, inThread = inThread)
    }
}
