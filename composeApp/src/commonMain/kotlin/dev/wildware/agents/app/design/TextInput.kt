package dev.wildware.agents.app.design

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import dev.wildware.agents.app.design.gesture.LocalSwipeGuard
import dev.wildware.agents.app.design.gesture.guardsSwipes

/**
 * Holds a box to [max] characters. Typing past it does nothing; a paste that runs over keeps what
 * fits, so a long text is cut rather than lost.
 */
private class CutAt(private val max: Int) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (length <= max) return
        if (originalText.length >= max) revertAllChanges() else replace(max, length, "")
    }
}

/**
 * Whether a text box has the cursor. Single-key shortcuts (J, K, N, /…) stand aside while it has,
 * so the owner's typing always reaches the box.
 */
object Typing {
    private val boxes = mutableSetOf<Any>()

    val active: Boolean get() = boxes.isNotEmpty()

    fun focus(box: Any, focused: Boolean) {
        if (focused) boxes += box else boxes -= box
    }
}

/**
 * How a secret box shows what was typed. Off by default; the owner turns it on with the eye to
 * check what they typed, and it turns itself off again: when the box loses the cursor, after
 * [ShownForMillis], and when the app leaves the front (another app, the app switcher, a locked
 * screen), so a value never stays on screen unwatched.
 */
object SecretReveal {
    const val ShownForMillis = 30_000L
    const val ShowLabel = "Show secret"
    const val HideLabel = "Hide secret"
}

/**
 * A bare text input in the theme's type and colours, for the app's own boxes to wrap. [label] is
 * what a screen reader calls it; [placeholder] shows while it is empty. A [secret] input is masked
 * and has an eye that shows the typed text for a moment ([SecretReveal]); either way it stays a
 * password box: no copying out, no suggestions, and a screen reader never reads it out.
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
    /** The most characters it takes; typing or pasting past it stops there. */
    maxLength: Int? = null,
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
    DisposableEffect(input) { onDispose { Typing.focus(input, false) } }
    var shown by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    if (secret) {
        // Back off: after a while, and whenever the app is not in front and watched.
        if (shown) LaunchedEffect(Unit) { delay(SecretReveal.ShownForMillis); shown = false }
        val inFront = LocalWindowInfo.current.isWindowFocused
        val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
        val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
        LaunchedEffect(inFront, resumed, shown) { if (shown && (!inFront || !resumed)) shown = false }
    }
    val decorated = modifier
        .then(if (guard != null) Modifier.guardsSwipes(guard, input) else Modifier)
        .focusRequester(focus)
        .onFocusChanged {
            Typing.focus(input, it.isFocused)
            if (focused && !it.isFocused) shown = false
            focused = it.isFocused
        }
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
        val eye = if (shown) SecretReveal.HideLabel else SecretReveal.ShowLabel
        BasicSecureTextField(
            state = state,
            modifier = decorated,
            enabled = enabled,
            textStyle = textStyle,
            // A password keyboard: no suggestions, nothing learned. Passing Default would replace that.
            keyboardOptions = if (keyboard == KeyboardOptions.Default) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else keyboard,
            onKeyboardAction = { onSubmit?.invoke() },
            // Still a secure field when shown: copying and cutting stay off, so it never reaches the clipboard.
            textObfuscationMode = if (shown) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
            cursorBrush = SolidColor(colors.accent),
            decorator = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { decorator(inner) }
                    val shape = AgentTheme.shapes.small
                    Box(
                        Modifier
                            .padding(start = 4.dp)
                            .clip(shape)
                            .touchTarget()
                            // The eye never takes the cursor from its box, and showing puts the cursor
                            // there: so leaving the box, by any way, is what hides it again.
                            .focusProperties { canFocus = false }
                            // Only what the eye does and whether the box is shown: never what is in it.
                            .semantics { contentDescription = eye; stateDescription = if (shown) "Shown" else "Hidden" }
                            .clickable(enabled = enabled, role = Role.Button, onClickLabel = eye) {
                                shown = !shown
                                if (shown && !focused) runCatching { focus.requestFocus() }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (shown) Icons.EyeOff else Icons.Eye, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
                    }
                }
            },
        )
    } else {
        BasicTextField(
            state = state,
            modifier = decorated,
            enabled = enabled,
            textStyle = textStyle,
            keyboardOptions = keyboard,
            inputTransformation = maxLength?.let { remember(it) { CutAt(it) } },
            onKeyboardAction = { onSubmit?.invoke() },
            lineLimits = if (multiline) TextFieldLineLimits.MultiLine(minHeightInLines = minLines, maxHeightInLines = maxOf(6, minLines)) else TextFieldLineLimits.SingleLine,
            cursorBrush = SolidColor(colors.accent),
            decorator = { inner -> decorator(inner) },
        )
    }
}
