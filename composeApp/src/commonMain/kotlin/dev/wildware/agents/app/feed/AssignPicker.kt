package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.TextInput
import dev.wildware.agents.app.design.pressable

/*
 * Assigning work (server migration 074): up to five people or agents on a card. "Assign…" on a
 * board card, a card's ⋯ menu and the thread opens the picker; A on a focused board card too.
 * The board shows each card's assignees as small avatars.
 */

/** Someone a card is, or could be, assigned to. [principal] is `member:<id>` or `agent:<id>`. */
@Immutable
data class Assignee(
    val principal: String,
    val name: String,
    val isAgent: Boolean,
    val handle: String? = null,
    /** They can read the card's channel: only then can they be assigned. */
    val canRead: Boolean = true,
    val hue: Int = 0,
)

/** The picker, while open: the card, who is on it, and who could be. */
@Immutable
data class AssignState(
    val kind: String,
    val id: String,
    val title: String,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val max: Int = 5,
    val assigned: List<Assignee> = emptyList(),
    val candidates: List<Assignee> = emptyList(),
    val problem: String? = null,
)

/** How [a] is drawn: an agent in its colour, a person in theirs. */
internal fun assigneeAuthor(a: Assignee): Author = Author.Person(a.name, a.handle ?: a.name, a.hue)

/** The board's filter: everything, mine, nobody's, or one person's or agent's. */
sealed interface AssignedFilter {
    /** What `GET /api/board?assigned=` takes; null for everything. */
    val wire: String?

    data object All : AssignedFilter { override val wire: String? = null }
    data object Mine : AssignedFilter { override val wire: String = "me" }
    data object Nobody : AssignedFilter { override val wire: String = "none" }
    data class One(val who: Assignee) : AssignedFilter { override val wire: String = who.principal }
}

/** A card's assignees as overlapping avatars, at most [shown], then "+N". */
@Composable
fun AssigneeAvatars(assignees: List<Assignee>, modifier: Modifier = Modifier, size: Dp = 18.dp, shown: Int = 3) {
    if (assignees.isEmpty()) return
    val colors = AgentTheme.colors
    val spoken = "Assigned to " + assignees.joinToString(", ") { it.name }
    Row(modifier.clearAndSetSemantics { contentDescription = spoken; testTag = "assignees" }, verticalAlignment = Alignment.CenterVertically) {
        assignees.take(shown).forEachIndexed { i, a ->
            Box(
                Modifier
                    .offset(x = -(size / 4) * i)
                    .clip(CircleShape)
                    .border(1.5.dp, colors.surfaceRaised, CircleShape),
            ) {
                Avatar(assigneeAuthor(a), size)
            }
        }
        if (assignees.size > shown) MetaText("+${assignees.size - shown}", Modifier.offset(x = -(size / 4) * (shown - 1)).padding(start = 2.dp))
    }
}

/**
 * The picker while it is open. It reads only the picker's own part of the feed's state, so a new
 * card or reply never redraws the screen around it.
 */
@Composable
fun AssignPickerHost(ui: FeedUi, safeArea: SafeArea) {
    val open by remember(ui) { derivedStateOf { ui.state.assign } }
    open?.let { AssignPicker(ui, it, safeArea) }
}

/**
 * The picker: search people and agents, tick up to [AssignState.max], Save. Someone who can't read
 * the card's channel is listed, greyed, saying so. Escape or a tap outside closes it.
 */
@Composable
fun AssignPicker(ui: FeedUi, state: AssignState, safeArea: SafeArea) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val close = { ui.emit(FeedEvent.CloseAssign) }
    val chosen = remember(state.id, state.loaded) { mutableStateListOf<String>().apply { addAll(state.assigned.map { it.principal }) } }
    val query = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.id) { runCatching { focus.requestFocus() } }
    val words = query.text.toString().trim().lowercase()
    val shownList = state.candidates.filter { c ->
        words.isEmpty() || c.name.lowercase().contains(words) || c.handle?.lowercase()?.contains(words) == true
    }
    val save = { if (!state.saving) ui.emit(FeedEvent.SaveAssignees(chosen.toList())) }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = close),
        )
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = safeArea.top + 72.dp, start = 12.dp, end = 12.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .lift(AgentTheme.elevation.overlay, shape)
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.borderStrong, shape)
                .semantics { paneTitle = "Assign" }
                .onPreviewKeyEvent { e ->
                    when {
                        e.type != KeyEventType.KeyDown -> false
                        e.key == Key.Escape -> { close(); true }
                        else -> false
                    }
                }
                .padding(vertical = 8.dp),
        ) {
            Text("Assign", Modifier.padding(horizontal = 16.dp).semantics { heading() }, color = colors.text, style = AgentTheme.type.label)
            MetaText(state.title, Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            TextInput(
                query, label = "Search people and agents", placeholder = "Search people and agents",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().focusRequester(focus),
                onSubmit = save,
            )
            when {
                !state.loaded -> MetaText("Loading…", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                shownList.isEmpty() -> MetaText("Nobody matches.", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(shownList, key = { it.principal }) { c ->
                    val on = c.principal in chosen
                    val full = !on && chosen.size >= state.max
                    val can = c.canRead && !full
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .semantics { selected = on }
                            .then(
                                // Spoken as what a press does: "Assign forge", "Unassign forge".
                                if (can || on) Modifier.pressable(if (on) "Unassign ${c.name}" else "Assign ${c.name}", AgentTheme.shapes.small, role = Role.Checkbox) {
                                    if (on) chosen.remove(c.principal) else chosen.add(c.principal)
                                } else Modifier.alpha(0.5f),
                            )
                            .semantics { testTag = "assign-${c.name}" }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(assigneeAuthor(c), 24.dp)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Text(c.name, color = colors.text, style = AgentTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            MetaText(
                                when {
                                    !c.canRead -> "Can't read this channel"
                                    c.isAgent -> "Agent"
                                    else -> c.handle?.let { "@$it" } ?: "Person"
                                },
                            )
                        }
                        if (on) Icon(Icons.Check, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
            state.problem?.let { MetaText(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = colors.error) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MetaText(if (chosen.isEmpty()) "Nobody assigned" else "${chosen.size} of ${state.max}", Modifier.weight(1f))
                PickerButton("Cancel", primary = false, onClick = close)
                PickerButton(if (state.saving) "Saving…" else "Save", primary = true, onClick = save)
            }
        }
    }
}

@Composable
private fun PickerButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    Text(
        label,
        Modifier
            .clip(shape)
            .background(if (primary) colors.accent else Color.Transparent)
            .pressable(label, shape, role = Role.Button, labelIsShown = true, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (primary) colors.background else colors.text,
        style = AgentTheme.type.label,
    )
}

/** A line in a thread saying who assigned whom: small, muted, not a message to answer. */
@Composable
fun AssignmentLine(reply: Reply, modifier: Modifier = Modifier) {
    val who = if (reply.author is Author.Owner) "You" else reply.author.name
    val what = reply.body.lineSequence().firstOrNull().orEmpty().replace("@", "").replace("\u200B", "")
    MetaText(
        "$who $what · ${reply.time}",
        modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp).semantics { testTag = "assignment-line" },
    )
}
