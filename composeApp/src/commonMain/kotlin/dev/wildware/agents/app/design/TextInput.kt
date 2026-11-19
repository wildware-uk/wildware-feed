package dev.wildware.agents.app.design

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import dev.wildware.agents.app.design.gesture.LocalSwipeGuard
import dev.wildware.agents.app.design.gesture.guardsSwipes

/**
 * A bare text input in the theme's type and colours, for the app's own boxes to wrap. [label] is
 * what a screen reader calls it; [placeholder] shows while it is empty.
 *
 * Enter sends a one-line input. A multi-line input sends on Cmd+Enter or Ctrl+Enter, and Enter
 * starts a new line, as in a chat app.
 */
@Composable
fun TextInput(
    state: TextFieldState,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    multiline: Boolean = false,
    /** How many lines a multi-line input shows even when empty, so it looks like a box for several. */
    minLines: Int = 1,
    secret: Boolean = false,
    enabled: Boolean = true,
    style: TextStyle = AgentTheme.type.body,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    onSubmit: (() -> Unit)? = null,
) {
    val colors = AgentTheme.colors
    val textStyle = style.copy(color = if (enabled) colors.text else colors.textFaint)
    val keys = Modifier.onPreviewKeyEvent { e ->
        val submit = onSubmit ?: return@onPreviewKeyEvent false
        if (e.type != KeyEventType.KeyDown || e.key != Key.Enter) return@onPreviewKeyEvent false
        val wantsSend = if (multiline) e.isMetaPressed || e.isCtrlPressed else !e.isShiftPressed
        if (wantsSend) submit()
        wantsSend
    }
    // Writing here never swipes the card around it: not from a drag inside, not while it has focus.
    val guard = LocalSwipeGuard.current
    val input = remember { Any() }
    if (guard != null) DisposableEffect(guard) { onDispose { guard.focus(input, false) } }
    val decorated = modifier
        .then(if (guard != null) Modifier.guardsSwipes(guard, input) else Modifier)
        .defaultMinSize(minHeight = MinTouchTarget)
        .then(keys)
        .semantics { contentDescription = label }
    val decorator: @Composable (@Composable () -> Unit) -> Unit = { inner ->
        Box(contentAlignment = if (minLines > 1) Alignment.TopStart else Alignment.CenterStart) {
            if (state.text.isEmpty()) {
                Text(placeholder, Modifier.clearAndSetSemantics { }, color = colors.textFaint, style = style, maxLines = 1)
            }
            inner()
        }
    }
    if (secret) {
        BasicSecureTextField(
            state = state,
            modifier = decorated,
            enabled = enabled,
            textStyle = textStyle,
            // A password keyboard: no suggestions, nothing learned. Passing Default would replace that.
            keyboardOptions = if (keyboard == KeyboardOptions.Default) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else keyboard,
            onKeyboardAction = { onSubmit?.invoke() },
            textObfuscationMode = TextObfuscationMode.Hidden,
            cursorBrush = SolidColor(colors.accent),
            decorator = { inner -> decorator(inner) },
        )
    } else {
        BasicTextField(
            state = state,
            modifier = decorated,
            enabled = enabled,
            textStyle = textStyle,
            keyboardOptions = keyboard,
            onKeyboardAction = { onSubmit?.invoke() },
            lineLimits = if (multiline) TextFieldLineLimits.MultiLine(minHeightInLines = minLines, maxHeightInLines = maxOf(6, minLines)) else TextFieldLineLimits.SingleLine,
            cursorBrush = SolidColor(colors.accent),
            decorator = { inner -> decorator(inner) },
        )
    }
}
