package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons

/**
 * Up arrow in an empty message box opens the owner's own last message there to change, as chat
 * apps do. This finds that message: the newest one the owner wrote in a channel, or in a thread.
 * When the newest one can't be changed (a voice note, one still sending, one with no words), there
 * is nothing to open: an older one is never picked instead, so Up never edits a surprise.
 */
object LastOwn {
    /** The owner's newest post in project [projectId], among the cards loaded. */
    fun inChannel(sections: List<FeedSection>, projectId: String): EditAsk? = sections.asSequence()
        .flatMap { it.items }
        .filterIsInstance<FeedItem.Post>()
        .filter { it.author is Author.Owner && it.project.id == projectId }
        // Ties (no time known) keep the feed's order: the newest first.
        .maxByOrNull { it.createdAt }
        ?.takeIf { !it.voice && !it.sending }
        ?.let { ask(it.id, it.body) }

    /** The owner's newest line in [thread]: their last reply, else the card itself when it is their post. */
    fun inThread(thread: Threaded?): EditAsk? {
        thread ?: return null
        val reply = thread.replies.lastOrNull { it.author is Author.Owner && !it.assignment }
        if (reply != null) return reply.takeIf { !it.voice && !it.sending }?.let { ask(it.id, it.body) }
        val post = thread as? FeedItem.Post ?: return null
        if (post.author !is Author.Owner || post.voice || post.sending) return null
        return ask(post.id, post.body)
    }

    private fun ask(id: String, body: String): EditAsk? =
        if (body.isBlank() || id.startsWith(dev.wildware.agents.DraftPrefix)) null else EditAsk(id, body)
}

/** Over a message box with the owner's own message open in it: "Editing message · Esc to cancel". */
@Composable
internal fun EditingStrip(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(AgentTheme.shapes.medium)
            .background(colors.accentSoft)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Pencil, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Text(EditingLabel, color = colors.text, style = AgentTheme.type.label)
        }
        IconAction(Icons.Close, "Stop editing", onClick = onCancel)
    }
}

internal const val EditingLabel = "Editing message · Esc to cancel"
