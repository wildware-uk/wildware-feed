package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** How many questions wait on the owner across every project, thread questions included. */
val FeedState.waitingTotal: Int get() = projects.sumOf { it.waiting }

/**
 * Shows every question waiting on the owner, from every project: All projects with the Waiting
 * filter on. The count beside each "Waiting on you" entry is for every project, so the list it
 * opens must be too.
 */
fun FeedUi.showWaiting() {
    if (state.current.slug != null) emit(FeedEvent.SelectProject(null))
    filter = FeedFilter.Waiting
}

/** Whether the screen is showing every waiting question, as [showWaiting] leaves it. */
val FeedUi.showingWaiting: Boolean get() = filter == FeedFilter.Waiting && state.current.slug == null

/** The time now, in epoch milliseconds. Tests and screenshots set a fixed one. */
@OptIn(ExperimentalTime::class)
val LocalNow = staticCompositionLocalOf<() -> Long> { { Clock.System.now().toEpochMilliseconds() } }

/** Under this, time left turns red and counts down by the second. */
private const val SoonMillis = 5 * 60_000L

/**
 * How long a question has before it times out, counting down as it goes. Red in its last five
 * minutes. Without an [expiresAt] it shows [fallback] as it is.
 */
@Composable
fun TimeLeft(expiresAt: Long?, fallback: String, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val clock = LocalNow.current
    var now by remember(clock) { mutableLongStateOf(clock()) }
    if (expiresAt != null) {
        LaunchedEffect(expiresAt, clock) {
            while (true) {
                now = clock()
                val left = expiresAt - now
                if (left <= 0) break
                // Seconds matter only near the end; otherwise a calm tick is enough.
                delay(if (left <= SoonMillis + 1_000) 1_000 else 15_000)
            }
        }
    }
    val left = expiresAt?.let { (it - now).coerceAtLeast(0) }
    val soon = left != null && left < SoonMillis
    val text = if (left == null) "$fallback left" else timeLeftLabel(left)
    val spoken = if (left == null) "$fallback left" else spokenTimeLeft(left)
    val tint = if (soon) colors.error else colors.textMuted
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Clock, null, tint = tint, modifier = Modifier.size(14.dp))
        MetaText(
            text,
            Modifier.padding(start = 5.dp).clearAndSetSemantics { },
            color = tint,
            style = AgentTheme.type.meta.let { if (soon) it.copy(fontWeight = FontWeight.SemiBold) else it },
        )
    }
}

/** "2 days left", "3 h 10 min left", "42 min left", then "4:59 left" in the last five minutes. */
internal fun timeLeftLabel(leftMillis: Long): String {
    val seconds = leftMillis / 1_000
    val minutes = seconds / 60
    return when {
        leftMillis <= 0 -> "Timed out"
        leftMillis < SoonMillis -> "${minutes}:${(seconds % 60).toString().padStart(2, '0')} left"
        minutes < 60 -> "$minutes min left"
        minutes < 48 * 60 -> if (minutes % 60 == 0L) "${minutes / 60} h left" else "${minutes / 60} h ${minutes % 60} min left"
        else -> "${minutes / (60 * 24)} days left"
    }
}

private fun spokenTimeLeft(leftMillis: Long): String {
    val minutes = leftMillis / 60_000
    return when {
        leftMillis <= 0 -> "Timed out"
        leftMillis < 60_000 -> "Under a minute left, ending soon"
        leftMillis < SoonMillis -> "$minutes ${if (minutes == 1L) "minute" else "minutes"} left, ending soon"
        else -> timeLeftLabel(leftMillis).replace(" h", " hours").replace(" min", " minutes")
    }
}

/** An amber count of questions waiting: on screen, not just read aloud. Nothing when there are none. */
@Composable
fun WaitingBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val colors = AgentTheme.colors
    Box(
        modifier
            .clip(AgentTheme.shapes.pill)
            .background(colors.waiting)
            .padding(horizontal = 7.dp, vertical = 1.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", color = colors.onWaiting, style = AgentTheme.type.meta.copy(fontWeight = FontWeight.Bold, fontSize = 11.5.sp))
    }
}

/** Questions asked under a post itself, at the top of its thread. Answered there. */
@Composable
fun HeadQuestions(item: Threaded) {
    (item as? FeedItem.Post)?.questions?.forEach { q ->
        QuestionCard(q, Modifier.padding(top = 10.dp), density = Density.Compact, inThread = true)
    }
}

/** "1 question" or "3 questions", for spoken labels. */
internal fun questions(count: Int) = if (count == 1) "1 question" else "$count questions"

/** A project in a list as a screen reader says it: name, pinned, unread, and what waits on the owner there. */
internal fun projectSpoken(p: Project): String =
    p.name + (if (p.pinned) ", pinned" else "") + ", ${p.unread} unread" + if (p.waiting > 0) ", ${questions(p.waiting)} waiting on you" else ""

/**
 * "In reply to forge: …" on a question asked inside a thread, with a way to that thread. The
 * question is answered right here all the same.
 */
@Composable
fun InReplyTo(context: String, threadId: String?, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    val ui = LocalFeedUi.current
    val isPhone = LocalIsPhone.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Reply, null, tint = colors.textMuted, modifier = Modifier.size(14.dp))
        Text(
            if (context.startsWith("Asked")) context else "In reply to $context",
            Modifier.padding(start = 6.dp).weight(1f),
            color = colors.textMuted,
            style = AgentTheme.type.meta,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (threadId != null && ui != null) {
            val shape = AgentTheme.shapes.pill
            Text(
                "Open thread",
                Modifier
                    .padding(start = 8.dp)
                    .clip(shape)
                    .pressable("Open thread", shape, labelIsShown = true) {
                        if (isPhone) {
                            ui.openThread(threadId, phone = true)
                        } else {
                            // Desktop threads sit under their card: scroll there, every reply showing.
                            ui.expandedThreads.add(threadId)
                            ui.reveal(threadId)
                        }
                        ui.emit(FeedEvent.OpenThread(threadId))
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = if (colors.isDark) colors.text else colors.accent,
                style = AgentTheme.type.label,
            )
        }
    }
}
