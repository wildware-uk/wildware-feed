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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import dev.wildware.agents.app.design.pressable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.app.ProductName
import dev.wildware.agents.app.crash.CrashReportSetting
import dev.wildware.agents.app.session.LiveActivityStatus
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.touchTarget

/** Whether the dashboard pushes this device its alerts (an iPhone), so every filter applies. */
val LocalPushAlerts = staticCompositionLocalOf { false }

/**
 * Opens the system's own switch that lets critical alerts through Do Not Disturb (Android's
 * "Critical" channel); null where there is none to open.
 */
val LocalCriticalAlertSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

/** "Send crash reports", in a build that sends them; null in any other, which shows no switch. */
val LocalCrashReports = staticCompositionLocalOf<CrashReportSetting?> { null }

/** The app's sounds and their switch; null where the device can't play them, which shows no switch. */
val LocalSounds = staticCompositionLocalOf<dev.wildware.agents.app.sound.SoundBoard?> { null }

/** The iPhone's Live Activities; null on a device without them, which shows nothing about them. */
val LocalLiveActivities = staticCompositionLocalOf<LiveActivityStatus?> { null }

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
 * while the app runs, which are questions, replies and critical updates, so it offers just those.
 * A phone also chooses whether critical alerts page it through Do Not Disturb.
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
            val systemSwitch = LocalCriticalAlertSettings.current
            // Paging means a phone: one the dashboard pushes, or an Android phone with its own channel.
            if (push || systemSwitch != null) CriticalGroup(ui, alerts.prefs, ready, systemSwitch)
            val note = alerts.problem ?: when {
                !alerts.loaded -> "Reading this device's settings…"
                push -> "The dashboard sends these to this device, even when $ProductName is closed."
                else -> "Shown while $ProductName is running on this device. Critical updates alert too; other updates and comments stay in the feed and under the bell."
            }
            Text(
                note,
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = if (alerts.problem != null) colors.error else colors.textMuted,
                style = AgentTheme.type.body,
            )
            LocalSounds.current?.let { SoundsGroup(it) }
            LocalVoiceReview.current?.takeIf { LocalVoiceInput.current != null }?.let { VoiceNotesGroup(it) }
            LocalLiveActivities.current?.let { LiveActivitiesGroup(it) }
            LocalCrashReports.current?.let { CrashReportsGroup(it) }
            // The hosted team's plan, then the dashboard's own settings, for whoever may change them.
            PlanEntry(ui)
            KeepMediaEntry(ui)
            GitHubEntry(ui)
            SecretsEntry(ui)
            DevicesEntry(ui)
            PeopleEntry(ui)
            ConnectAgentEntry(ui)
            TourEntry(ui)
            FeedbackEntry(ui)
            DeleteAccountGroup(ui)
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

/** A row that opens more settings: an icon, what it is, what it is set to now, and an arrow. */
@Composable
internal fun SettingsLinkRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.small
    Row(
        Modifier
            .fillMaxWidth()
            .touchTarget()
            .clip(shape)
            .pressable(label + if (value.isNotEmpty()) ", $value" else "", shape, labelIsShown = true, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), color = colors.text, style = AgentTheme.type.body)
        MetaText(value, color = colors.textMuted)
        Icon(Icons.ChevronRight, null, tint = colors.textFaint, modifier = Modifier.padding(start = 6.dp).size(16.dp))
    }
}

/**
 * "Send crash reports": on unless turned off, and off the moment it is. What a report holds is
 * PRIVACY.md's "Crash reports"; this says the short of it.
 */
@Composable
private fun SoundsGroup(sounds: dev.wildware.agents.app.sound.SoundBoard) {
    val on by sounds.enabled.collectAsState()
    SettingsHeading("Sounds")
    CheckRow("Play sounds", on, enabled = true) { sounds.set(!on) }
    Text(
        "A short sound in the app for questions, replies, finished work, errors and what you send. Follows your device's silent and Do Not Disturb settings; critical alerts still sound.",
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        color = AgentTheme.colors.textMuted,
        style = AgentTheme.type.body,
    )
}

/** "Review voice notes before sending": off, letting go of the mic sends the note, with a few seconds to Undo it. */
@Composable
private fun VoiceNotesGroup(setting: dev.wildware.agents.VoiceReviewSetting) {
    val on by setting.enabled.collectAsState()
    SettingsHeading("Voice notes")
    CheckRow("Review voice notes before sending", on, enabled = true) { setting.set(!on) }
    Text(
        if (on) "Your words go into the message box to check, then you send them." else "A voice note sends when you let go of the mic, with a few seconds to undo it.",
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        color = AgentTheme.colors.textMuted,
        style = AgentTheme.type.body,
    )
}

@Composable
private fun CrashReportsGroup(setting: CrashReportSetting) {
    val on by setting.enabled.collectAsState()
    SettingsHeading("Privacy")
    CheckRow("Send crash reports", on, enabled = true) { setting.set(!on) }
    Text(
        "When $ProductName crashes, it tells us where in its code and on which version and system. Never your messages, names, server or keys.",
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        color = AgentTheme.colors.textMuted,
        style = AgentTheme.type.body,
    )
}

/**
 * Whether the lock screen can show what agents are doing: says so when Live Activities are off
 * for this app, with a way to the switch, and why the last one would not start.
 */
@Composable
private fun LiveActivitiesGroup(status: LiveActivityStatus) {
    val colors = AgentTheme.colors
    val state by status.state.collectAsState()
    SettingsHeading("Live Activity")
    val note = when {
        !state.allowed -> "Live Activities are off for $ProductName, so nothing shows on the lock screen. Turn them on in Settings, under $ProductName."
        state.problem != null -> "The last Live Activity did not start: ${state.problem}"
        else -> "Shows on the lock screen and in the Dynamic Island while an agent is waiting on you or working."
    }
    Text(
        note,
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
        color = if (state.allowed && state.problem == null) colors.textMuted else colors.error,
        style = AgentTheme.type.body,
    )
    if (!state.allowed) {
        Row(Modifier.padding(horizontal = 8.dp)) {
            AppButton("Open Settings", ButtonTone.Quiet, onClick = status::openSettings)
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

/** One setting that is on or off, with a tick box; [detail], when given, is a quieter line under it. */
@Composable
internal fun CheckRow(label: String, on: Boolean, enabled: Boolean, detail: String? = null, onToggle: () -> Unit) {
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
        if (detail == null) {
            Text(label, color = colors.text, style = AgentTheme.type.body)
        } else {
            Column {
                Text(label, color = colors.text, style = AgentTheme.type.body)
                Text(detail, color = colors.textFaint, style = AgentTheme.type.meta)
            }
        }
    }
}

/**
 * Whether something an agent marks critical pages this phone through Do Not Disturb, and from whom.
 * Off unless turned on. The agent and project lists only narrow it: none ticked is anyone.
 */
@Composable
private fun CriticalGroup(ui: FeedUi, prefs: AlertPrefs?, enabled: Boolean, systemSwitch: (() -> Unit)?) {
    val colors = AgentTheme.colors
    val on = prefs?.critical == true
    val save = { next: AlertPrefs -> ui.emit(FeedEvent.SetAlertPrefs(next.takeUnless { it == AlertPrefs() })) }
    val base = prefs ?: AlertPrefs()
    MetaText("CRITICAL ALERTS", Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp).semantics { heading() }, color = colors.error)
    CheckRow("Page me for critical alerts", on, enabled) {
        save(if (on) base.copy(critical = null, criticalAgents = null, criticalProjects = null) else base.copy(critical = true))
    }
    Text(
        "Breaks through silent mode and Do Not Disturb. Agents are told to use it only when you have explicitly asked them to.",
        Modifier.padding(start = 48.dp, end = 16.dp, bottom = 4.dp),
        color = colors.textMuted,
        style = AgentTheme.type.meta,
    )
    if (!on) return
    if (systemSwitch != null) {
        AppButton(
            "Let them through Do Not Disturb",
            ButtonTone.Secondary,
            Modifier.padding(start = 48.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            icon = Icons.Bell,
            onClick = systemSwitch,
        )
    }
    fun pick(list: List<String>?, value: String): List<String>? =
        (if (list != null && value in list) list - value else (list ?: emptyList()) + value).takeIf { it.isNotEmpty() }
    MetaText("FROM AGENTS · NONE TICKED IS ANY", Modifier.padding(start = 48.dp, top = 8.dp, bottom = 2.dp), color = colors.textMuted)
    ui.state.agents.forEach { agent ->
        CriticalCheckRow(agent.name, prefs.criticalAgents?.contains(agent.id) == true, enabled, spoken = "Critical alerts from ${agent.name}", indent = 32.dp) {
            save(base.copy(criticalAgents = pick(prefs.criticalAgents, agent.id)))
        }
    }
    MetaText("FROM PROJECTS · NONE TICKED IS ANY", Modifier.padding(start = 48.dp, top = 8.dp, bottom = 2.dp), color = colors.textMuted)
    ui.projects.filter { !it.archived && it.slug != null }.forEach { project ->
        val slug = project.slug!!
        CriticalCheckRow(project.name, prefs.criticalProjects?.contains(slug) == true, enabled, spoken = "Critical alerts from ${project.name}", indent = 32.dp) {
            save(base.copy(criticalProjects = pick(prefs.criticalProjects, slug)))
        }
    }
}

/** A tick box in the critical group: red when on, with its own spoken label. */
@Composable
private fun CriticalCheckRow(label: String, on: Boolean, enabled: Boolean, spoken: String? = null, indent: Dp = 0.dp, onToggle: () -> Unit) {
    val colors = AgentTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .touchTarget()
            .toggleable(value = on, enabled = enabled, role = Role.Checkbox) { onToggle() }
            .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(start = 16.dp + indent, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(AgentTheme.shapes.small)
                .background(if (on) colors.error else Color.Transparent)
                .border(1.5.dp, if (on) colors.error else colors.borderStrong, AgentTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Icon(Icons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = colors.text, style = AgentTheme.type.body)
    }
}
