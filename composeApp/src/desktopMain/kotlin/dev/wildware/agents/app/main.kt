package dev.wildware.agents.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Agents",
        state = rememberWindowState(width = 1440.dp, height = 900.dp),
    ) {
        AgentsApp()
    }
}
