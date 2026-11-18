package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable

/** The app's mark: three stacked bars, a feed in miniature. */
@Composable
fun AppMark(size: androidx.compose.ui.unit.Dp = 28.dp) {
    // The mark is always ink and lime, in light and dark alike.
    val shape = AgentTheme.shapes.small
    val bg = Brush.linearGradient(listOf(Color(0xFF1A1C20), Color(0xFF0B0C0E)))
    val bar = Color(0xFFC6F169)
    Box(
        Modifier.size(size).clip(shape).background(bg).border(1.dp, AgentTheme.colors.borderStrong, shape).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(size * 0.08f)) {
            listOf(0.5f, 0.36f, 0.22f).forEach { w ->
                Box(Modifier.width(size * w).height(size * 0.08f).clip(CircleShape).background(bar))
            }
        }
    }
}

@Composable
private fun Kbd(text: String, light: Boolean = false) {
    val colors = AgentTheme.colors
    Text(
        text,
        Modifier
            .clip(AgentTheme.shapes.small)
            .border(1.dp, colors.border, AgentTheme.shapes.small)
            .background(if (light) colors.surface else Color.Transparent)
            .padding(horizontal = 5.dp, vertical = 1.dp)
            .clearAndSetSemantics { },
        color = colors.textFaint,
        style = AgentTheme.type.meta.copy(fontSize = 11.sp, lineHeight = 14.sp),
    )
}

@Composable
private fun SearchField(modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, shape)
            .pressable("Search everything. Shortcut Command K", shape) {}
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Search, null, tint = colors.textFaint, modifier = Modifier.size(16.dp))
        Text("Search", Modifier.padding(start = 8.dp).weight(1f), color = colors.textFaint, style = AgentTheme.type.label)
        Kbd("⌘K", light = true)
    }
}

@Composable
private fun NavRow(
    label: String,
    selected: Boolean,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
    spoken: String = label,
) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    val bg = if (selected) colors.text.copy(alpha = if (colors.isDark) 0.07f else 0.06f) else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .pressable(spoken, shape, role = Role.Tab, labelIsShown = spoken == label) {}
            .semantics { this.selected = selected }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { leading() }
        Text(
            label,
            Modifier.padding(start = 10.dp).weight(1f),
            color = if (selected) colors.text else colors.textMuted,
            style = AgentTheme.type.label.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

/** Projects, people and the way in to everything else. Desktop only; phones use the top bar. */
@Composable
fun Sidebar(state: FeedState, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    Column(
        modifier
            .fillMaxHeight()
            .background(colors.sidebar),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(18.dp))
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AppMark()
                Text(
                    "Agents",
                    Modifier.padding(start = 10.dp).weight(1f),
                    color = colors.text,
                    style = AgentTheme.type.title.copy(fontSize = 17.sp, lineHeight = 22.sp),
                )
                IconAction(Icons.Bell, "Notifications")
            }
            Spacer(Modifier.height(14.dp))
            SearchField()
            Spacer(Modifier.height(14.dp))
            NavRow(
                "Inbox",
                selected = false,
                leading = { Icon(Icons.Inbox, null, tint = colors.textMuted, modifier = Modifier.size(18.dp)) },
                trailing = { UnreadBadge(state.totalUnread) },
                spoken = "Inbox, ${state.totalUnread} unread",
            )
            NavRow(
                "Waiting on you",
                selected = false,
                leading = { Icon(Icons.Clock, null, tint = colors.waiting, modifier = Modifier.size(18.dp)) },
                trailing = {
                    val waiting = state.projects.sumOf { it.waiting }
                    Box(
                        Modifier.clip(AgentTheme.shapes.pill).background(colors.waiting).padding(horizontal = 7.dp, vertical = 1.dp)
                            .clearAndSetSemantics { },
                    ) {
                        Text("$waiting", color = colors.onWaiting, style = AgentTheme.type.meta.copy(fontWeight = FontWeight.Bold, fontSize = 11.5.sp))
                    }
                },
                spoken = "Waiting on you, ${state.projects.sumOf { it.waiting }} questions",
            )
            Spacer(Modifier.height(22.dp))
            SectionLabel("Projects", Modifier.padding(horizontal = 10.dp))
            Spacer(Modifier.height(8.dp))
            state.projects.forEach { p ->
                val selected = p.id == state.current.id
                NavRow(
                    p.name,
                    selected = selected,
                    leading = { ProjectGlyph(p) },
                    trailing = { UnreadBadge(p.unread, emphasised = selected) },
                    spoken = "${p.name}, ${p.unread} unread" + if (p.waiting > 0) ", ${p.waiting} waiting on you" else "",
                )
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Agents", Modifier.weight(1f))
                MetaText("${state.agents.count { it.presence == Presence.Online }} online")
            }
            Spacer(Modifier.height(8.dp))
            state.agents.forEach { a -> AgentRow(a) }
            Spacer(Modifier.height(18.dp))
        }
        OwnerRow(state.owner)
    }
}

@Composable
private fun ProjectGlyph(p: Project) {
    val colors = AgentTheme.colors
    Icon(Icons.Hash, null, tint = colors.textFaint, modifier = Modifier.size(15.dp))
}

@Composable
private fun AgentRow(agent: Agent) {
    val colors = AgentTheme.colors
    val spoken = "${agent.name}, ${agent.role}, ${agent.presence.label}" + (agent.doing?.let { ", $it" } ?: "")
    val shape = AgentTheme.shapes.small
    Row(
        Modifier.fillMaxWidth().clip(shape).pressable(spoken, shape) {}.padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(Author.AgentAuthor(agent), 26.dp, showPresence = true)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                agent.name,
                color = if (agent.presence == Presence.Offline) colors.textFaint else colors.text,
                style = AgentTheme.type.label,
            )
            MetaText(agent.doing ?: agent.presence.label)
        }
    }
}

@Composable
private fun OwnerRow(owner: Author.Owner) {
    val colors = AgentTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.sidebar)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(owner, 28.dp)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(owner.name, color = colors.text, style = AgentTheme.type.label)
            MetaText("Owner")
        }
        IconAction(Icons.Moon, "Switch light or dark")
        IconAction(Icons.Settings, "Settings")
    }
}

/** The title area above the feed: where you are, who is around, and the filters. */
@Composable
fun FeedHeader(state: FeedState, compact: Boolean, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val type = AgentTheme.type
    val online = state.agents.filter { it.presence == Presence.Online }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MetaText("PROJECT", style = type.overline)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.current.name,
                        Modifier.semantics { heading() },
                        color = colors.text,
                        style = if (compact) type.display.copy(fontSize = type.display.fontSize * 0.82f, lineHeight = type.display.lineHeight * 0.85f) else type.display,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (compact) {
                        Icon(Icons.ChevronDown, "Switch project", tint = colors.textMuted, modifier = Modifier.padding(start = 4.dp).size(20.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(colors.online))
                    MetaText(
                        "${online.size} agents online · ${state.current.unread} new since you looked",
                        Modifier.padding(start = 7.dp),
                        color = colors.textMuted,
                    )
                }
            }
            if (!compact) {
                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp), modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "Online: " + online.joinToString { it.name }
                }) {
                    online.forEach { a ->
                        Box(Modifier.clip(CircleShape).background(colors.background).padding(2.dp)) { Avatar(Author.AgentAuthor(a), 28.dp) }
                    }
                }
                Spacer(Modifier.width(14.dp))
                AppButton("Mark all read", ButtonTone.Secondary, icon = Icons.Check)
            }
        }
        Spacer(Modifier.height(if (compact) 12.dp else 18.dp))
        Row(
            Modifier.fillMaxWidth().then(if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip("All", selected = true, count = 12)
            Chip("Waiting", selected = false, count = 1)
            Chip("Updates", selected = false)
            Chip("Errors", selected = false, count = 1)
            if (!compact) Chip("From you", selected = false)
            if (!compact) {
                Spacer(Modifier.weight(1f))
                Kbd("J")
                Spacer(Modifier.width(3.dp))
                Kbd("K")
                MetaText("to move", Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** The box for writing to everyone in the project. */
@Composable
fun Composer(projectName: String, modifier: Modifier = Modifier, placeholder: String? = null, compact: Boolean = false) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val text = placeholder ?: "Write to everyone in $projectName…"
    Row(
        modifier
            .fillMaxWidth()
            .lift(AgentTheme.elevation.raised, shape)
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, shape)
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(Icons.Plus, "Attach a file or photo")
        Box(
            Modifier.weight(1f).pressable("Message. $text", AgentTheme.shapes.small) {},
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(text, color = colors.textFaint, style = AgentTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!compact) {
            IconAction(Icons.At, "Mention an agent")
            MetaText("⌘↵", Modifier.padding(horizontal = 6.dp))
        }
        SendButton()
    }
}

@Composable
private fun SendButton() {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Box(Modifier.pressable("Send", shape) {}, contentAlignment = Alignment.Center) {
        Box(Modifier.size(34.dp).clip(shape).background(colors.accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Send, null, tint = colors.onAccent, modifier = Modifier.size(18.dp))
        }
    }
}

/** One reply in a thread. */
@Composable
fun ReplyView(reply: Reply, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val spoken = "${reply.author.name}, ${reply.time}" + if (reply.unread) ", new" else ""
    Row(modifier.fillMaxWidth()) {
        Avatar(reply.author, 28.dp, showPresence = false)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Row(
                Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (reply.author is Author.Owner) "You" else reply.author.name,
                    Modifier.clearAndSetSemantics { },
                    color = colors.text,
                    style = AgentTheme.type.label.copy(fontWeight = FontWeight.SemiBold),
                )
                MetaText(reply.time, Modifier.padding(start = 8.dp).clearAndSetSemantics { })
            }
            Spacer(Modifier.height(2.dp))
            MarkdownBody(reply.body)
            if (reply.reactions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                ReactionRow(reply.reactions, showAdd = false)
            }
        }
    }
}

/** A thread opened beside the feed: the card it hangs off, its replies, and a reply box. */
@Composable
fun ThreadPane(item: FeedItem.Update, projectName: String, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    Column(
        modifier
            .fillMaxHeight()
            .background(colors.surface),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Thread")
                Spacer(Modifier.height(3.dp))
                Text(
                    item.title,
                    Modifier.semantics { heading() },
                    color = colors.text,
                    style = AgentTheme.type.bodyStrong,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconAction(Icons.More, "More thread actions")
            IconAction(Icons.Close, "Close thread")
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(Author.AgentAuthor(item.agent), 22.dp)
                MetaText("${item.agent.name} started this · ${item.time}", Modifier.padding(start = 8.dp), color = colors.textMuted)
            }
            Spacer(Modifier.height(12.dp))
            val firstUnread = item.replies.indexOfFirst { it.unread }
            item.replies.forEachIndexed { i, reply ->
                if (i == firstUnread) NewDivider(item.unreadReplies)
                InteractiveReply(reply)
            }
        }
        ThreadComposer(LocalFeedUi.current, Modifier.padding(14.dp))
    }
}

@Composable
internal fun NewDivider(count: Int) {
    val colors = AgentTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = "$count new replies below" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(colors.accent.copy(alpha = 0.5f)))
        Text(
            "$count new",
            Modifier.padding(horizontal = 10.dp).clearAndSetSemantics { },
            color = colors.accent,
            style = AgentTheme.type.meta.copy(fontWeight = FontWeight.SemiBold),
        )
        Box(Modifier.weight(1f).height(1.dp).background(colors.accent.copy(alpha = 0.5f)))
    }
}

@Composable
fun VerticalRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().width(1.dp).background(AgentTheme.colors.border))
}
