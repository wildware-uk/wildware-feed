package dev.wildware.agents.app.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons

/** The way into Devices from the account sheet: a row saying how many devices are signed in. */
@Composable
internal fun DevicesEntry(ui: FeedUi) {
    val devices = ui.state.devices
    SettingsHeading("Signed in")
    SettingsLinkRow(Icons.Phone, "Devices", if (devices.loaded) "${devices.devices.size}" else "") {
        ui.alertsOpen = false
        ui.devicesOpen = true
    }
}

/**
 * Every device signed in to this dashboard as the owner, this one first, with when each was last
 * used. Revoking one cuts off its key at once, so a lost phone can't read or answer anything more;
 * it asks first, and revoking this device signs it out.
 */
@Composable
fun DevicesSheet(ui: FeedUi, safeArea: SafeArea) {
    val colors = AgentTheme.colors
    val list = ui.state.devices
    LaunchedEffect(Unit) { ui.emit(FeedEvent.ReadDevices) }
    var asking by remember { mutableStateOf<DeviceRow?>(null) }
    SettingsSheet("Devices", safeArea, onClose = { ui.devicesOpen = false }) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            list.devices.forEach { device ->
                DeviceLine(device, removing = list.removing == device.id, enabled = list.removing == null) { asking = device }
                if (asking?.id == device.id) {
                    ConfirmRevoke(
                        device,
                        onCancel = { asking = null },
                        onConfirm = {
                            asking = null
                            ui.emit(FeedEvent.RevokeDevice(device.id))
                        },
                    )
                }
            }
            val note = list.problem ?: when {
                !list.loaded -> "Reading the devices…"
                else -> "Revoke a device you've lost or no longer use. It is signed out at once and can't read or answer anything more."
            }
            Text(
                note,
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = if (list.problem != null) colors.error else colors.textMuted,
                style = AgentTheme.type.body,
            )
        }
    }
}

@Composable
private fun DeviceLine(device: DeviceRow, removing: Boolean, enabled: Boolean, onRevoke: () -> Unit) {
    val colors = AgentTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Phone, null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.name, Modifier.weight(1f, fill = false), color = colors.text, style = AgentTheme.type.body, maxLines = 1)
                if (device.current) MetaText("  This device", color = colors.accent, style = AgentTheme.type.label)
            }
            MetaText(device.seen, color = colors.textMuted)
            if (device.added.isNotEmpty()) MetaText(device.added, color = colors.textFaint)
        }
        AppButton(
            if (removing) "Revoking…" else "Revoke",
            ButtonTone.Quiet,
            enabled = enabled,
            onClick = onRevoke,
        )
    }
}

/** The step before a device is cut off: what will happen, plainly, and a clear way out. */
@Composable
private fun ConfirmRevoke(device: DeviceRow, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val colors = AgentTheme.colors
    val shape = AgentTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(colors.error.copy(alpha = 0.08f))
            .border(1.dp, colors.error.copy(alpha = 0.4f), shape)
            .padding(12.dp),
    ) {
        Text(
            if (device.current) "Revoke this device? It is signed out now, and you sign in again to use it."
            else "Revoke ${device.name}? It is signed out at once and can't read or answer anything more.",
            color = colors.text,
            style = AgentTheme.type.body,
        )
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
            AppButton("Keep it", ButtonTone.Secondary, onClick = onCancel)
            Spacer(Modifier.width(8.dp))
            AppButton(if (device.current) "Revoke and sign out" else "Revoke", ButtonTone.Destructive, onClick = onConfirm)
        }
    }
}
