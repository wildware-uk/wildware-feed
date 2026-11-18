package dev.wildware.agents.app.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The app's icon: three lime bars, a feed in miniature, on an ink tile. Used for windows and the dock. */
val AppIcon: ImageVector = ImageVector.Builder("agents", 64.dp, 64.dp, 64f, 64f).apply {
    addPath(addPathNodes("M14 2H50A12 12 0 0 1 62 14V50A12 12 0 0 1 50 62H14A12 12 0 0 1 2 50V14A12 12 0 0 1 14 2Z"), fill = SolidColor(Color(0xFF111215)))
    listOf(32f, 23f, 14f).forEachIndexed { i, width ->
        val y = 22f + i * 9f
        addPath(addPathNodes("M16 ${y}H${16 + width}A2.5 2.5 0 0 1 ${16 + width} ${y + 5}H16A2.5 2.5 0 0 1 16 ${y}Z"), fill = SolidColor(Color(0xFFC6F169)))
    }
}.build()
