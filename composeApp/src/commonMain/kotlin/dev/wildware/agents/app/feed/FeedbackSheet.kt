package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wildware.agents.FeedbackDraft
import dev.wildware.agents.FeedbackKinds
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.TextInput

/** The longest title and body the dashboard takes (server/spec/14-feedback.md). */
internal const val FeedbackTitleMax = 120
internal const val FeedbackBodyMax = 5_000

/** A status in words, as the team reads it. */
internal fun feedbackStatusLabel(status: String): String = when (status) {
    "pending" -> "Waiting for approval"
    "discarded" -> "Discarded"
    "sent" -> "Sent"
    "saved" -> "Saved"
    else -> status.replaceFirstChar { it.uppercase() }
}

/** An area in words. */
internal fun feedbackAreaLabel(area: String): String = when (area) {
    "agents" -> "Agents and the plugin"
    else -> area.replaceFirstChar { it.uppercase() }
}

/** The way into Feedback from the account sheet. */
@Composable
internal fun FeedbackEntry(ui: FeedUi) {
    if (!ui.state.feedback.available) return
    SettingsLinkRow(Icons.Feedback, "Feedback", "") { ui.openFeedback() }
}

/**
 * Feedback and feature requests: what the team has sent the people who build the dashboard, with
 * each one's status, and a form to send another. An agent's waits as a question in the feed until
 * an owner or an admin sends it; it shows here as waiting. Where the dashboard sends feedback
 * nowhere, it is saved here only, and the sheet says so.
 */
@Composable
fun FeedbackSheet(ui: FeedUi, safeArea: SafeArea) {
    val feedback = ui.state.feedback
    LaunchedEffect(Unit) { ui.emit(FeedEvent.ReadFeedback) }
    val compose = ui.feedbackCompose
    SettingsSheet(if (compose != null) "Send feedback" else "Feedback", safeArea, onClose = { ui.feedbackOpen = false }) {
        Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            if (compose != null) {
                FeedbackForm(ui, compose, onDone = { ui.feedbackCompose = null })
            } else {
                FeedbackList(ui, feedback, onNew = { ui.feedbackCompose = FeedbackCompose() })
            }
        }
    }
}

@Composable
private fun FeedbackList(ui: FeedUi, feedback: FeedbackState, onNew: () -> Unit) {
    val note = feedback.problem ?: when {
        !feedback.available -> "This dashboard can't take feedback yet."
        !feedback.loaded -> "Reading what your team has sent…"
        !feedback.sends -> "This dashboard sends feedback nowhere: it is saved here, for your team."
        feedback.items.isEmpty() -> "Nothing sent yet. Tell ${feedback.recipient.ifEmpty { "the people who build this" }} what's missing or in the way."
        else -> "What your team has sent ${feedback.recipient.ifEmpty { "the people who build this" }}."
    }
    FeedbackNote(note, error = feedback.problem != null)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
        AppButton("Send feedback", ButtonTone.Primary, icon = Icons.Plus, enabled = feedback.available, onClick = onNew)
    }
    feedback.items.forEach { FeedbackLine(it) }
}

@Composable
private fun FeedbackLine(row: FeedbackRow) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    val kind = if (row.kind == FeedbackKinds.FeatureRequest) "Feature request" else "Feedback"
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.title, Modifier.weight(1f), color = colors.text, style = AgentTheme.type.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            MetaText(
                row.statusLabel,
                color = when (row.status) {
                    "pending" -> colors.warning
                    "discarded" -> colors.textFaint
                    "sent" -> colors.accent
                    else -> colors.textMuted
                },
                style = AgentTheme.type.label,
            )
        }
        MetaText(listOfNotNull(kind, row.area?.let(::feedbackAreaLabel), row.who, row.time).joinToString(" · "), color = colors.textMuted)
        Text(row.body, Modifier.padding(top = 4.dp), color = colors.textMuted, style = AgentTheme.type.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedbackForm(ui: FeedUi, compose: FeedbackCompose, onDone: () -> Unit) {
    val colors = AgentTheme.colors
    val feedback = ui.state.feedback
    val title = rememberTextFieldState()
    val body = rememberTextFieldState()
    var kind by remember { mutableStateOf(FeedbackKinds.Feedback) }
    var area by remember { mutableStateOf(compose.area) }
    var card by remember { mutableStateOf(compose.card) }
    var includeEmail by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    // The boxes keep to what the dashboard takes, so nothing typed is lost to a refusal.
    LaunchedEffect(title.text) { if (title.text.length > FeedbackTitleMax) title.setTextAndPlaceCursorAtEnd(title.text.take(FeedbackTitleMax).toString()) }
    LaunchedEffect(body.text) { if (body.text.length > FeedbackBodyMax) body.setTextAndPlaceCursorAtEnd(body.text.take(FeedbackBodyMax).toString()) }

    SettingsHeading("What is it?")
    Column(Modifier.selectableGroup()) {
        RadioRow("Feedback about something here", kind == FeedbackKinds.Feedback, !sending) { kind = FeedbackKinds.Feedback }
        RadioRow("A feature you'd like", kind == FeedbackKinds.FeatureRequest, !sending) { kind = FeedbackKinds.FeatureRequest }
    }
    SettingsHeading("About")
    val areas = feedback.areas.ifEmpty { listOf("feed", "threads", "voice", "secrets", "apps", "agents", "billing", "other") }
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        areas.forEach { a -> Chip(feedbackAreaLabel(a), selected = area == a) { if (!sending) area = if (area == a) null else a } }
    }
    val linked = card
    if (linked != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("About the card “${compose.cardLabel.orEmpty().ifEmpty { "this card" }}”", Modifier.weight(1f), color = colors.textMuted, style = AgentTheme.type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            AppButton("Remove", ButtonTone.Quiet, enabled = !sending) { card = null }
        }
    }
    SettingsHeading("Title")
    FeedbackField {
        TextInput(title, label = "Title, in a line", placeholder = if (kind == FeedbackKinds.FeatureRequest) "What you'd like" else "What happened", modifier = it, enabled = !sending)
    }
    SettingsHeading("Details")
    FeedbackField {
        TextInput(
            body,
            label = "Details: what you tried, what happened, what would help",
            placeholder = "What you tried, what happened, what would help",
            modifier = it,
            multiline = true,
            minLines = 4,
            enabled = !sending,
        )
    }
    val recipient = feedback.recipient.ifEmpty { "the people who build this" }
    if (feedback.sends) CheckRow("Include my email, so they can write back", includeEmail, !sending) { includeEmail = !includeEmail }
    val where = if (feedback.sends) "Goes to $recipient, with your name." else "Saved on this dashboard: it sends feedback nowhere."
    FeedbackNote(problem ?: "$where Never include passwords or keys.", error = problem != null)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        AppButton("Cancel", ButtonTone.Secondary, enabled = !sending, onClick = onDone)
        AppButton(
            when {
                sending -> "Sending…"
                feedback.sends -> "Send to $recipient"
                else -> "Save it"
            },
            ButtonTone.Primary,
            icon = Icons.Send,
            enabled = !sending && title.text.isNotBlank() && body.text.isNotBlank(),
        ) {
            sending = true
            problem = null
            val draft = FeedbackDraft(
                kind, area, title.text.toString().trim(), body.text.toString().trim(), card,
                includeEmail = feedback.sends && includeEmail,
                client = dev.wildware.agents.app.crash.CrashReportingBuild.Release,
            )
            ui.emit(FeedEvent.SendFeedback(draft) { failed ->
                sending = false
                problem = failed
                if (failed == null) onDone()
            })
        }
    }
}

@Composable
private fun FeedbackField(input: @Composable (Modifier) -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        input(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surfaceSunken)
                .border(1.dp, colors.border, shape)
                .padding(horizontal = 10.dp),
        )
    }
}

@Composable
private fun FeedbackNote(text: String, error: Boolean) {
    val colors = AgentTheme.colors
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        color = if (error) colors.error else colors.textMuted,
        style = AgentTheme.type.body,
    )
}
