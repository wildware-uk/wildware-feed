package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.app.ProductName
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.touchTarget

/** Whether the dashboard pushes this device its alerts (an iPhone), so every filter applies. */
val LocalPushAlerts = staticCompositionLocalOf { false }

/** One switch: what it is called and what it sets. */
private data class AlertChoice(val label: String, val axis: AlertPrefs.Axis, val value: String)

private val kinds = listOf(
    AlertChoice("Questions", AlertPrefs.Axis.Types, AlertPrefs.Request),
    AlertChoice("Replies to me", AlertPrefs.Axis.Types, AlertPrefs.Message),
    AlertChoice("Comments", AlertPrefs.Axis.Types, AlertPrefs.Comment),
    AlertChoice("Updates", AlertPrefs.Axis.Types, AlertPrefs.Update),
)
private val levels = listOf(
    AlertChoice("Error", AlertPrefs.Axis.Levels, "error"),
    AlertChoice("Warning", AlertPrefs.Axis.Levels, "warn"),
    AlertChoice("Success", AlertPrefs.Axis.Levels, "success"),
    AlertChoice("Info", AlertPrefs.Axis.Levels, "info"),
)
private val priorities = listOf(
    AlertChoice("High", AlertPrefs.Axis.Priorities, "high"),
    AlertChoice("Medium", AlertPrefs.Axis.Priorities, "medium"),
    AlertChoice("Low", AlertPrefs.Axis.Priorities, "low"),
)

/**
 * Which alerts this device gets, the same three filters the website has for a browser. An iPhone
 * is pushed its alerts, so every filter applies; a computer or an Android phone raises its own
 * while the app runs, which are questions and replies only, so it offers just those.
 */
@Composable
fun AlertSettingsSheet(ui: FeedUi, safeArea: SafeArea) {
    val colors = AgentTheme.colors
    val alerts = ui.state.alerts
    val push = LocalPushAlerts.current
    val set = { choice: AlertChoice -> ui.emit(FeedEvent.SetAlertPrefs(AlertPrefs.toggle(alerts.prefs, choice.axis, choice.value))) }
    SettingsSheet("Alerts on this device", safeArea, onClose = { ui.alertsOpen = false }) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            val ready = alerts.loaded
            Group("Tell me about", if (push) kinds else kinds.take(2), alerts.prefs, ready, set)
            if (push) {
                val updatesOn = AlertPrefs.isOn(alerts.prefs, AlertPrefs.Axis.Types, AlertPrefs.Update)
                Group("Update levels", levels, alerts.prefs, ready && updatesOn, set)
                Group("Update priority", priorities, alerts.prefs, ready && updatesOn, set)
            }
            val note = alerts.problem ?: when {
                !alerts.loaded -> "Reading this device's settings…"
                push -> "The dashboard sends these to this device, even when $ProductName is closed."
                else -> "Shown while $ProductName is running on this device. Updates and comments stay in the feed and under the bell."
            }
            Text(
                note,
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = if (alerts.problem != null) colors.error else colors.textMuted,
                style = AgentTheme.type.body,
            )
            // The dashboard's own settings, for whoever may change them.
            KeepMediaEntry(ui)
        }
    }
}

/**
 * The frame every settings sheet shares: a dimmed backdrop that closes it, a raised card near the
 * top with a heading and "Done", and Escape to close.
 */
@Composable
internal fun SettingsSheet(title: String, safeArea: SafeArea, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = onClose),
        )
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = safeArea.top + 64.dp, start = 12.dp, end = 12.dp, bottom = safeArea.bottom + 12.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .lift(AgentTheme.elevation.overlay, shape)
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.borderStrong, shape)
                .semantics { paneTitle = title }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                        onClose()
                        true
                    } else {
                        false
                    }
                },
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).semantics { heading() }, color = colors.text, style = AgentTheme.type.label)
                AppButton("Done", ButtonTone.Quiet, onClick = onClose)
            }
            Box(Modifier.fillMaxWidth().heightIn(max = 1.dp).background(colors.border))
            content()
        }
    }
}

/** A small heading over a group of settings. */
@Composable
internal fun SettingsHeading(title: String) {
    MetaText(title.uppercase(), Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp).semantics { heading() }, color = AgentTheme.colors.textMuted)
}

@Composable
private fun Group(title: String, choices: List<AlertChoice>, prefs: AlertPrefs?, enabled: Boolean, onToggle: (AlertChoice) -> Unit) {
    SettingsHeading(title)
    choices.forEach { choice ->
        CheckRow(choice.label, AlertPrefs.isOn(prefs, choice.axis, choice.value), enabled) { onToggle(choice) }
    }
}

/** One setting that is on or off, with a tick box. */
@Composable
internal fun CheckRow(label: String, on: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val colors = AgentTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .touchTarget()
            .toggleable(value = on, enabled = enabled, role = Role.Checkbox) { onToggle() }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(AgentTheme.shapes.small)
                .background(if (on) colors.accent else Color.Transparent)
                .border(1.5.dp, if (on) colors.accent else colors.borderStrong, AgentTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Icon(Icons.Check, null, tint = colors.onAccent, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = colors.text, style = AgentTheme.type.body)
    }
}
