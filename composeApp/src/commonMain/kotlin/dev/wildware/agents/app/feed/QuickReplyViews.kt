package dev.wildware.agents.app.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/*
 * Quick replies (docs/QUICK_REPLIES.md): up to 4 buttons, or a small form, an agent put on a message.
 * Unlike a question they wait on nothing and take none of the waiting colour: they are a short cut
 * for the likely answer. A tap goes up as your reply in the thread, and the buttons give way to what
 * was chosen and by whom.
 */

/** The buttons or form on a message, or what was chosen once you have answered. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickReplyBar(quick: QuickReplies?, modifier: Modifier = Modifier) {
    if (quick == null) return
    val ui = LocalFeedUi.current
    val colors = AgentTheme.colors
    // One key per message: a second tap, or a try again after an answer lost on the way, sends the same reply.
    val sendKey = remember(quick.messageId) { "qr-${quick.messageId}-${Random.nextLong().toULong().toString(36)}" }
    var pending by remember(quick.messageId) { mutableStateOf<String?>(null) }
    var failed by remember(quick.messageId) { mutableStateOf(false) }
    LaunchedEffect(quick.mine) { if (quick.mine != null) pending = null }
    val open = quick.mine == null && pending == null

    fun send(button: String, values: JsonObject?) {
        if (!open) return
        pending = button
        failed = false
        ui?.emit(FeedEvent.QuickReply(quick.messageId, button, values, sendKey) { ok -> if (!ok) { pending = null; failed = true } })
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            quick.mine != null -> Unit
            quick.form != null -> FieldsFormAnswer(quick.messageId, quick.form, enabled = open) { value ->
                send((value["action"] as? JsonPrimitive)?.content ?: quick.buttons.first(), value["values"] as? JsonObject ?: JsonObject(emptyMap()))
            }
            else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.buttons.forEach { label ->
                    AppButton(label, ButtonTone.Secondary, enabled = open) { send(label, null) }
                }
            }
        }
        val status = when {
            pending != null -> "Sending…"
            failed -> "That didn't send. Try again."
            else -> null
        }
        if (status != null) {
            MetaText(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = if (failed) colors.error else colors.textMuted)
        }
        quick.answers.forEach { answer -> AnswerLine(answer, sentForm = quick.form != null && quick.buttons.size == 1) }
    }
}

/** "You chose 👍 Ship it", or "Sam sent it" for a form with one button: who answered, and with what. */
@Composable
private fun AnswerLine(answer: QuickAnswer, sentForm: Boolean) {
    val colors = AgentTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Check, null, tint = if (answer.mine) colors.accent else colors.textMuted, modifier = Modifier.padding(end = 6.dp).size(14.dp))
        Text(
            buildAnnotatedString {
                append(answer.who)
                if (sentForm) {
                    append(" sent it")
                } else {
                    append(" chose ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(answer.button) }
                }
            },
            color = colors.textMuted,
            style = AgentTheme.type.meta,
        )
    }
}
