package dev.wildware.agents.app.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wildware.agents.app.design.focusRing
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wildware.agents.app.ProductName
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import dev.wildware.agents.app.design.pressable
import dev.wildware.agents.app.feed.AppButton
import dev.wildware.agents.app.feed.ButtonTone
import dev.wildware.agents.app.feed.MarkdownBody
import dev.wildware.agents.app.feed.MetaText
import dev.wildware.agents.app.feed.PhoneBreakpoint
import dev.wildware.agents.app.feed.lift

/** The pill's spoken label. */
fun updatePillLabel(version: String) = "Update to $version available"

/**
 * The small "UPDATE AVAILABLE" button. It looks 24dp tall but takes clicks across the usual 44dp,
 * so place it by its middle.
 */
@Composable
fun UpdatePill(version: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.pill
    Box(modifier.clip(shape).pressable(updatePillLabel(version), shape, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .height(24.dp)
                .clip(shape)
                .background(colors.accent)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Download, null, tint = colors.onAccent, modifier = Modifier.size(12.dp))
            Text(
                "UPDATE AVAILABLE",
                Modifier.padding(start = 5.dp),
                color = colors.onAccent,
                style = AgentTheme.type.overline.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, lineHeight = 12.sp, letterSpacing = 0.5.sp),
                maxLines = 1,
            )
        }
    }
}

/**
 * Where the pill sits: the top right corner, level with the small labels there; in a narrow
 * window, whose top bar is full, at the bottom right above the composer.
 */
@Composable
fun BoxScope.UpdatePillPlaced(version: String, onClick: () -> Unit) {
    PillPlaced { UpdatePill(version, it, onClick) }
}

/** Puts [pill] where the update pill goes, passing it the modifier that places it. */
@Composable
fun BoxScope.PillPlaced(pill: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.matchParentSize()) {
        if (maxWidth < PhoneBreakpoint) {
            pill(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 84.dp))
        } else {
            pill(Modifier.align(Alignment.TopEnd).offset(y = (-1).dp).padding(end = 10.dp))
        }
    }
}

/** The restart pill's spoken label. */
fun restartPillLabel(version: String, asksForPassword: Boolean) =
    "Restart to update to $version" + if (asksForPassword) ". Your computer will ask for your password." else ""

/** What the restart pill's menu does. */
class RestartActions(
    val restart: () -> Unit = {},
    val showNotes: () -> Unit = {},
    val setAutomatic: (Boolean) -> Unit = {},
)

private val pillText @Composable get() = AgentTheme.type.overline.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, lineHeight = 12.sp, letterSpacing = 0.5.sp)

/**
 * "RESTART TO UPDATE", once a newer version has downloaded in the background: a click quits,
 * puts it in and opens the app again. The small arrow beside it opens a menu with the release
 * notes and the "Update automatically" switch. It never takes focus by itself, so it can't
 * interrupt typing. For a Linux package it says the computer will ask for a password.
 *
 * [busy] replaces it with what the restart is doing ("Restarting…"), not clickable.
 */
@Composable
fun RestartPill(
    version: String,
    modifier: Modifier = Modifier,
    asksForPassword: Boolean = false,
    automatic: Boolean = true,
    busy: String? = null,
    menuOpen: Boolean = false,
    actions: RestartActions = RestartActions(),
) {
    val colors = AgentTheme.colors
    val left = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
    val right = RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
    var open by remember { mutableStateOf(menuOpen) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (busy != null) {
            Box(Modifier.heightIn(min = 44.dp).semantics { contentDescription = busy; liveRegion = LiveRegionMode.Polite }, contentAlignment = Alignment.Center) {
                Row(
                    Modifier.height(24.dp).clip(AgentTheme.shapes.pill).background(colors.accent.copy(alpha = 0.75f)).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Refresh, null, tint = colors.onAccent, modifier = Modifier.size(12.dp))
                    Text(busy.uppercase(), Modifier.padding(start = 5.dp), color = colors.onAccent, style = pillText, maxLines = 1)
                }
            }
            return@Row
        }
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .focusRing(left)
                .semantics { contentDescription = restartPillLabel(version, asksForPassword) }
                .clickable(role = Role.Button, onClickLabel = "Restart to update", onClick = actions.restart),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.height(24.dp).clip(left).background(colors.accent).padding(start = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Refresh, null, tint = colors.onAccent, modifier = Modifier.size(12.dp))
                Text(
                    if (asksForPassword) "RESTART TO UPDATE · ASKS FOR PASSWORD" else "RESTART TO UPDATE",
                    Modifier.padding(start = 5.dp),
                    color = colors.onAccent,
                    style = pillText,
                    maxLines = 1,
                )
            }
        }
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .focusRing(right)
                .semantics { contentDescription = "Update options" }
                .clickable(role = Role.Button, onClickLabel = "Update options") { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.height(24.dp).clip(right).background(colors.accent),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(1.dp).height(14.dp).background(colors.onAccent.copy(alpha = 0.35f)))
                Icon(Icons.ChevronDown, null, tint = colors.onAccent, modifier = Modifier.padding(start = 5.dp, end = 8.dp).size(12.dp))
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                shape = AgentTheme.shapes.medium,
                containerColor = colors.surfaceRaised,
                border = BorderStroke(1.dp, colors.border),
            ) {
                MenuRow("Restart to update to $version", Icons.Refresh) { open = false; actions.restart() }
                if (asksForPassword) {
                    MetaText("Your computer will ask for your password.", Modifier.padding(start = 44.dp, end = 16.dp, bottom = 6.dp), color = colors.textMuted)
                }
                MenuRow("What's new in $version", Icons.Details) { open = false; actions.showNotes() }
                DropdownMenuItem(
                    text = { Text("Update automatically", style = AgentTheme.type.label) },
                    leadingIcon = { TickBox(automatic) },
                    onClick = { actions.setAutomatic(!automatic) },
                    modifier = Modifier.semantics { contentDescription = "Update automatically, " + if (automatic) "on" else "off" },
                    colors = MenuDefaults.itemColors(textColor = colors.text),
                )
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val colors = AgentTheme.colors
    DropdownMenuItem(
        text = { Text(label, style = AgentTheme.type.label) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(16.dp)) },
        onClick = onClick,
        colors = MenuDefaults.itemColors(textColor = colors.text, leadingIconColor = colors.textMuted),
    )
}

/** A small tick box, ticked when [on]. */
@Composable
private fun TickBox(on: Boolean) {
    val colors = AgentTheme.colors
    Box(
        Modifier
            .size(16.dp)
            .clip(AgentTheme.shapes.small)
            .background(if (on) colors.accent else Color.Transparent)
            .border(1.5.dp, if (on) colors.accent else colors.borderStrong, AgentTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(Icons.Check, null, tint = colors.onAccent, modifier = Modifier.size(12.dp))
    }
}

/** "Update automatically", with a tick box: the setting, in the update dialog. */
@Composable
fun AutomaticRow(on: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = AgentTheme.colors
    Row(
        modifier
            .clip(AgentTheme.shapes.small)
            .heightIn(min = 36.dp)
            .toggleable(value = on, role = Role.Checkbox) { onToggle(it) }
            .padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TickBox(on)
        Column(Modifier.padding(start = 10.dp)) {
            Text("Update automatically", color = colors.text, style = AgentTheme.type.label)
            MetaText("Downloads quietly, then one click restarts.", color = colors.textMuted)
        }
    }
}

/** What the dialog's buttons do. */
class UpdateActions(
    val install: () -> Unit = {},
    val cancel: () -> Unit = {},
    val dismiss: () -> Unit = {},
    val checkAgain: () -> Unit = {},
    val showFolder: (String) -> Unit = {},
    val setAutomatic: (Boolean) -> Unit = {},
)

/**
 * The update dialog: the new version and its notes with "Install and restart", then the install's
 * progress, then what went wrong if it did. Escape or a click outside closes it, except mid-install.
 */
@Composable
fun UpdateDialog(
    prompt: UpdatePrompt,
    install: InstallState,
    current: String,
    actions: UpdateActions,
    /** Why this copy can't update itself, when it can't. */
    cannotInstall: String? = null,
    /** The system will ask for a password (a Linux package): say so before it does. */
    asksForPassword: Boolean = false,
    /** The "Update automatically" setting, shown with the notes; null leaves it out. */
    automatic: Boolean? = null,
    /** The new version is already downloaded and checked, so installing is only a restart. */
    staged: Boolean = false,
) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.large
    val busy = install is InstallState.Downloading || install is InstallState.Verifying ||
        install is InstallState.Installing || install is InstallState.Restarting
    val close = { if (!busy) actions.dismiss() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close", onClick = close),
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(16.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .lift(AgentTheme.elevation.overlay, shape)
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.borderStrong, shape)
                .semantics { paneTitle = "Update" }
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { close(); true } else false
                }
                .padding(20.dp),
        ) {
            when (install) {
                is InstallState.Idle -> PromptBody(prompt, current, cannotInstall, actions, automatic, staged)
                is InstallState.Downloading -> Progress(
                    "Downloading the update…",
                    install.fraction,
                    install.fraction?.let { "${(it * 100).toInt()}%" } ?: megabytes(install.done),
                ) {
                    AppButton("Cancel", ButtonTone.Secondary, onClick = actions.cancel)
                }
                InstallState.Verifying -> Progress("Checking the download…", null, null)
                InstallState.Installing -> Progress(
                    "Installing…",
                    null,
                    if (asksForPassword) "Your computer may ask for your password to install it." else null,
                )
                InstallState.Restarting -> Progress("Restarting…", 1f, "$ProductName will open again in a moment.")
                is InstallState.Failed -> Message("The update didn't install", install.message) {
                    AppButton("Close", ButtonTone.Secondary, onClick = actions.dismiss)
                    AppButton("Retry", ButtonTone.Primary, icon = Icons.Refresh, onClick = actions.install)
                }
                is InstallState.Manual -> Message("Finish the update by hand", install.message) {
                    AppButton("Close", ButtonTone.Secondary, onClick = actions.dismiss)
                    install.folder?.let { folder -> AppButton("Show the download", ButtonTone.Primary, onClick = { actions.showFolder(folder) }) }
                }
            }
        }
    }
}

@Composable
private fun PromptBody(prompt: UpdatePrompt, current: String, cannotInstall: String?, actions: UpdateActions, automatic: Boolean?, staged: Boolean) {
    val colors = AgentTheme.colors
    when (prompt) {
        is UpdatePrompt.Offer -> {
            Title("$ProductName ${prompt.release.version} is available")
            MetaText("You have $current.", Modifier.padding(top = 4.dp), color = colors.textMuted)
            if (prompt.release.notes.isNotBlank()) {
                Box(
                    Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .clip(AgentTheme.shapes.medium)
                        .background(colors.text.copy(alpha = if (colors.isDark) 0.05f else 0.03f))
                        .border(1.dp, colors.border, AgentTheme.shapes.medium)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    MarkdownBody(prompt.release.notes)
                }
            }
            if (cannotInstall != null) {
                Text(cannotInstall, Modifier.padding(top = 12.dp), color = colors.textMuted, style = AgentTheme.type.body)
            }
            if (automatic != null && cannotInstall == null) AutomaticRow(automatic, actions.setAutomatic, Modifier.padding(top = 14.dp))
            Buttons {
                AppButton("Not now", ButtonTone.Quiet, onClick = actions.dismiss)
                if (staged) AppButton("Restart to update", ButtonTone.Primary, icon = Icons.Refresh, enabled = cannotInstall == null, onClick = actions.install)
                else AppButton("Install and restart", ButtonTone.Primary, icon = Icons.Download, enabled = cannotInstall == null, onClick = actions.install)
            }
        }
        UpdatePrompt.Checking -> Progress("Checking for updates…", null, null)
        UpdatePrompt.UpToDate -> Message("$ProductName is up to date", "You have $current, the newest version.") {
            AppButton("OK", ButtonTone.Primary, onClick = actions.dismiss)
        }
        is UpdatePrompt.CheckFailed -> Message("Couldn't check for updates", prompt.message) {
            AppButton("Close", ButtonTone.Secondary, onClick = actions.dismiss)
            AppButton("Try again", ButtonTone.Primary, icon = Icons.Refresh, onClick = actions.checkAgain)
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, color = AgentTheme.colors.text, style = AgentTheme.type.title)
}

@Composable
private fun Message(title: String, body: String, buttons: @Composable () -> Unit) {
    Title(title)
    Text(
        body,
        Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        color = AgentTheme.colors.textMuted,
        style = AgentTheme.type.body,
    )
    Buttons(buttons)
}

@Composable
private fun Progress(title: String, fraction: Float?, detail: String?, buttons: (@Composable () -> Unit)? = null) {
    val colors = AgentTheme.colors
    Title(title)
    Box(
        Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .height(6.dp)
            .clip(AgentTheme.shapes.pill)
            .background(colors.text.copy(alpha = if (colors.isDark) 0.12f else 0.08f))
            .clearAndSetSemantics {
                contentDescription = title
                if (fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                else progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction ?: 0.3f)
                .height(6.dp)
                .clip(AgentTheme.shapes.pill)
                .background(colors.accent.copy(alpha = if (fraction == null) 0.5f else 1f)),
        )
    }
    if (detail != null) MetaText(detail, Modifier.padding(top = 8.dp), color = colors.textMuted)
    if (buttons != null) Buttons(buttons) else Spacer(Modifier.height(4.dp))
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

private fun megabytes(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)

/**
 * The pill in the top right corner while a newer version is out, and the dialog when it is open.
 * Drawn over the whole window.
 *
 * With "Update automatically" on, nothing shows while the new version downloads; then the pill
 * reads "Restart to update". If the download keeps failing, the old "Update available" pill comes
 * back so the update is never stuck out of sight. A restart from the pill opens no dialog unless
 * something needs saying.
 */
@Composable
fun BoxScope.UpdateLayer(controller: UpdateController, showFolder: (String) -> Unit) {
    val available by controller.available.collectAsState()
    val prompt by controller.prompt.collectAsState()
    val install by controller.install.collectAsState()
    val background by controller.background.collectAsState()
    val automatic by controller.automatic.collectAsState()
    val quiet by controller.quiet.collectAsState()
    val busy = install is InstallState.Downloading || install is InstallState.Verifying ||
        install is InstallState.Installing || install is InstallState.Restarting
    val staged = (background as? BackgroundUpdate.Staged)?.release
    when {
        quiet && busy -> PillPlaced {
            RestartPill(available?.version.orEmpty(), it, busy = if (install == InstallState.Restarting) "Restarting…" else "Installing…")
        }
        staged != null -> PillPlaced {
            RestartPill(
                staged.version,
                it,
                asksForPassword = controller.asksForPassword,
                automatic = automatic,
                actions = RestartActions(
                    restart = { controller.restartToUpdate() },
                    showNotes = controller::showOffer,
                    setAutomatic = controller::setAutomatically,
                ),
            )
        }
        else -> available?.let { release ->
            val stillTrying = when (val b = background) {
                is BackgroundUpdate.Downloading -> true
                is BackgroundUpdate.Retrying -> b.attempt < AutoUpdater.ShowAfterFailures
                else -> controller.downloadsInBackground(release)
            }
            if (!stillTrying) UpdatePillPlaced(release.version, onClick = controller::showOffer)
        }
    }
    val showing = prompt ?: available?.takeIf { install != InstallState.Idle && (!quiet || !busy) }?.let { UpdatePrompt.Offer(it) }
    if (showing != null) {
        UpdateDialog(
            prompt = showing,
            install = install,
            current = controller.current,
            cannotInstall = controller.cannotInstallReason,
            asksForPassword = controller.asksForPassword,
            automatic = automatic,
            staged = staged != null && (showing as? UpdatePrompt.Offer)?.release?.version == staged.version,
            actions = UpdateActions(
                install = { if (staged != null && controller.restartToUpdate() != null) Unit else controller.installNow() },
                cancel = controller::cancel,
                dismiss = controller::dismiss,
                checkAgain = controller::checkByHand,
                showFolder = showFolder,
                setAutomatic = controller::setAutomatically,
            ),
        )
    }
}
