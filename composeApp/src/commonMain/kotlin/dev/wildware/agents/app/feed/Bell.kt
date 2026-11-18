package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable

/** The bell, with how many notifications are new on it. Opens the list under it. */
@Composable
fun BellButton(unseen: Int, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    Box(
        Modifier.clip(shape).pressable(if (unseen > 0) "Notifications, $unseen new" else "Notifications", shape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Bell, contentDescription = null, tint = if (unseen > 0) colors.text else colors.textMuted, modifier = Modifier.size(18.dp))
        if (unseen > 0) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .offset(x = 9.dp, y = (-8).dp)
                    .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
                    .clip(AgentTheme.shapes.pill)
                    .background(colors.accent)
                    .padding(horizontal = 4.dp)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unseen > 99) "99+" else unseen.toString(),
                    color = colors.onAccent,
                    style = AgentTheme.type.meta.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp, lineHeight = 12.sp),
                )
            }
        }
    }
}

/**
 * Everything the owner was told about, newest first: updates, replies and questions, including
 * ones whose push never arrived. A row goes to its card and marks only that one seen; "Mark all
 * read" is a separate, deliberate act, so opening one reply never wipes a list not yet read.
 */
@Composable
fun NotificationPanel(ui: FeedUi, safeArea: SafeArea, go: (path: String) -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val bell = ui.state.bell
    val close = { ui.bellOpen = false }
    val phone = ui.isPhone
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (phone) 0.35f else 0.15f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = close),
        )
        Column(
            Modifier
                .align(if (phone) Alignment.TopCenter else Alignment.TopStart)
                .padding(
                    top = safeArea.top + if (phone) 56.dp else 60.dp,
                    start = if (phone) 8.dp else 16.dp,
                    end = if (phone) 8.dp else 0.dp,
                )
                .then(if (phone) Modifier.fillMaxWidth() else Modifier.width(384.dp))
                .lift(AgentTheme.elevation.overlay, shape)
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.borderStrong, shape)
                .semantics { paneTitle = "Notifications" }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                        close()
                        true
                    } else {
                        false
                    }
                },
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Notifications", Modifier.weight(1f).semantics { heading() }, color = colors.text, style = AgentTheme.type.label)
                if (bell.unseen > 0) {
                    AppButton("Mark all read", ButtonTone.Quiet) { ui.emit(FeedEvent.ClearNotifications) }
                }
            }
            Box(Modifier.fillMaxWidth().heightIn(max = 1.dp).background(colors.border))
            if (bell.items.isEmpty()) {
                Text(
                    "Nothing yet. Updates, replies and anything an agent is waiting on you for land here.",
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                    color = colors.textMuted,
                    style = AgentTheme.type.body,
                )
            } else {
                LazyColumn(Modifier.heightIn(max = if (phone) 520.dp else 560.dp)) {
                    items(bell.items, key = { it.id }) { item ->
                        BellRow(item) {
                            ui.emit(FeedEvent.OpenNotification(item.id))
                            ui.bellOpen = false
                            // Gone from the screen with the panel, so the screen does the going.
                            go(item.path)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BellRow(item: BellItem, onOpen: () -> Unit) {
    val colors = AgentTheme.colors
    val spoken = buildString {
        if (item.unread) append("New. ")
        append(item.title)
        if (item.body.isNotBlank()) append(". ").append(item.body)
        item.project?.let { append(". In ").append(it) }
        if (item.time.isNotEmpty()) append(". ").append(item.time)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(spoken, AgentTheme.shapes.small, onClick = onOpen)
            .background(if (item.unread) colors.accent.copy(alpha = if (colors.isDark) 0.08f else 0.05f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The dot says "new" for those who see it; the row's label says it for those who don't.
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(if (item.unread) colors.accent else Color.Transparent))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).alpha(if (item.unread) 1f else 0.72f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    Modifier.weight(1f),
                    color = colors.text,
                    style = AgentTheme.type.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.time.isNotEmpty()) MetaText(item.time, Modifier.padding(start = 8.dp), color = colors.textMuted)
            }
            if (item.body.isNotBlank()) {
                Text(item.body, color = colors.textMuted, style = AgentTheme.type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            item.project?.let { MetaText("# $it", color = colors.textFaint) }
        }
    }
}
