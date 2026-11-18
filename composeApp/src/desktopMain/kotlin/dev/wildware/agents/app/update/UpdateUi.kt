package dev.wildware.agents.app.update

import androidx.compose.foundation.background
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
    BoxWithConstraints(Modifier.matchParentSize()) {
        if (maxWidth < PhoneBreakpoint) {
            UpdatePill(version, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 84.dp), onClick)
        } else {
            UpdatePill(version, Modifier.align(Alignment.TopEnd).offset(y = (-1).dp).padding(end = 10.dp), onClick)
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
                is InstallState.Idle -> PromptBody(prompt, current, cannotInstall, actions)
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
private fun PromptBody(prompt: UpdatePrompt, current: String, cannotInstall: String?, actions: UpdateActions) {
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
            Buttons {
                AppButton("Not now", ButtonTone.Quiet, onClick = actions.dismiss)
                AppButton("Install and restart", ButtonTone.Primary, icon = Icons.Download, enabled = cannotInstall == null, onClick = actions.install)
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
 */
@Composable
fun BoxScope.UpdateLayer(controller: UpdateController, showFolder: (String) -> Unit) {
    val available by controller.available.collectAsState()
    val prompt by controller.prompt.collectAsState()
    val install by controller.install.collectAsState()
    available?.let { release ->
        UpdatePillPlaced(release.version, onClick = controller::showOffer)
    }
    val showing = prompt ?: available?.takeIf { install != InstallState.Idle }?.let { UpdatePrompt.Offer(it) }
    if (showing != null) {
        UpdateDialog(
            prompt = showing,
            install = install,
            current = controller.current,
            cannotInstall = controller.cannotInstallReason,
            asksForPassword = controller.asksForPassword,
            actions = UpdateActions(
                install = controller::installNow,
                cancel = controller::cancel,
                dismiss = controller::dismiss,
                checkAgain = controller::checkByHand,
                showFolder = showFolder,
            ),
        )
    }
}
