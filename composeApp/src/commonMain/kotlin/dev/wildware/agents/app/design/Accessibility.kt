package dev.wildware.agents.app.design

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** What the platform says about reduced motion and contrast right now. */
@Composable
expect fun systemAccessibility(): AccessibilityPrefs

/** Grows the hit area to at least 44 by 44 points without changing how the thing looks. */
fun Modifier.touchTarget(): Modifier = defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)

/** Draws a clear ring while the element has keyboard focus, so keyboard users can see where they are. */
fun Modifier.focusRing(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = AgentTheme.colors.focus
    this
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, color, shape) else Modifier)
}

/**
 * Makes something pressable the accessible way: a spoken [label], a [role], a 44pt target and a
 * visible focus ring. Set [labelIsShown] when the label is already on screen as text, so a screen
 * reader does not say it twice.
 */
fun Modifier.pressable(
    label: String,
    shape: Shape,
    role: Role = Role.Button,
    labelIsShown: Boolean = false,
    onClick: () -> Unit,
): Modifier = this
    .touchTarget()
    .focusRing(shape)
    .then(if (labelIsShown) Modifier else Modifier.semantics { contentDescription = label })
    .clickable(role = role, onClickLabel = label, onClick = onClick)
