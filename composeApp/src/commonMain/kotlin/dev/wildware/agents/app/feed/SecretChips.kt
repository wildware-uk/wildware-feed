package dev.wildware.agents.app.feed

import androidx.compose.foundation.border
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.Icons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a revealed value stays on the card, and a copied one on the clipboard. */
private const val ChipRevealMs = 30_000L
private const val ChipClipboardMs = 60_000L

/**
 * The secrets an agent attached to a post or reply: one locked chip each, "🔒 NAME — Reveal · Copy".
 * Nothing is fetched until the owner asks, and the device checks it is them first (Face ID, a
 * fingerprint or the passcode on a phone). A revealed value shows in place for half a minute, or
 * until the window loses focus; a copied one is wiped from the clipboard after a minute.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharedSecretChips(secrets: List<SharedSecret>, modifier: Modifier = Modifier) {
    if (secrets.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        secrets.forEach { SharedSecretChip(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedSecretChip(secret: SharedSecret) {
    val colors = AgentTheme.colors
    val ui = LocalFeedUi.current
    val auth = LocalDeviceAuth.current ?: rememberPlatformDeviceAuth()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val key = "${secret.cardKind}:${secret.cardId}:${secret.name}"
    var shown by remember(key) { mutableStateOf<String?>(null) }
    var note by remember(key) { mutableStateOf<String?>(null) }
    var gone by remember(key, secret.available) { mutableStateOf(!secret.available) }
    var busy by remember(key) { mutableStateOf(false) }
    // A revealed value hides itself again, and never outlives the card on screen.
    LaunchedEffect(shown) { if (shown != null) { delay(ChipRevealMs); shown = null } }
    DisposableEffect(key) { onDispose { shown = null } }
    // Leaving the app (or this window) hides it, so it is not in the app switcher's picture.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (!focused) shown = null }

    fun open(copy: Boolean) {
        if (busy || ui == null) return
        note = null
        scope.launch {
            if (!auth.confirm(if (copy) "Copy ${secret.name}" else "Show ${secret.name}")) {
                note = "Not confirmed, so it stays hidden."
                return@launch
            }
            busy = true
            ui.emit(FeedEvent.RevealSharedSecret(secret, copy) { value, problem, unavailable ->
                busy = false
                if (unavailable) gone = true
                note = if (unavailable) null else problem
                if (value == null) return@RevealSharedSecret
                if (!copy) {
                    shown = value
                    return@RevealSharedSecret
                }
                clipboard.setText(AnnotatedString(value))
                note = "Copied. The clipboard is cleared in a minute."
                ClipboardWiper.wipeLater(clipboard, value, ChipClipboardMs)
            })
        }
    }

    Column(
        Modifier
            .border(1.dp, colors.border, AgentTheme.shapes.medium)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = false) { contentDescription = "Secret ${secret.name}" + if (gone) ", no longer available" else "" },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Lock, null, tint = if (gone) colors.textFaint else colors.textMuted, modifier = Modifier.size(14.dp))
                Text(
                    secret.name,
                    Modifier.padding(start = 6.dp),
                    color = if (gone) colors.textMuted else colors.text,
                    style = AgentTheme.type.code,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (gone) {
                MetaText("No longer available", color = colors.textMuted)
            } else {
                val masked = shown == null
                // Two nodes, not one that changes: masked it says so; shown, a screen reader reads the
                // value itself, as the owner asked (and the web's mirror of the tree picks up a new node).
                if (masked) {
                    Text(
                        "••••••••",
                        Modifier.semantics { contentDescription = "Hidden value" },
                        color = colors.textFaint,
                        style = AgentTheme.type.code,
                        maxLines = 1,
                    )
                } else {
                    Text(shown.orEmpty(), color = colors.text, style = AgentTheme.type.code, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                AppButton(if (masked) "Reveal" else "Hide", ButtonTone.Quiet, icon = if (masked) Icons.Eye else Icons.EyeOff, enabled = !busy) {
                    if (masked) open(copy = false) else shown = null
                }
                AppButton("Copy", ButtonTone.Quiet, icon = Icons.Copy, enabled = !busy) { open(copy = true) }
            }
        }
        note?.let {
            MetaText(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.textMuted)
        }
    }
}
