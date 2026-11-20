package dev.wildware.agents.app.signin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.design.AppMark
import dev.wildware.agents.app.feed.AppButton
import dev.wildware.agents.app.feed.ButtonTone
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.safePadding
import dev.wildware.agents.app.feed.windowBackground

/** Signed in to the server on this computer, but it couldn't start: why, with "Try again". */
@Composable
fun LocalServerDownScreen(problem: String, safeArea: SafeArea, onRetry: () -> Unit, onSignOut: () -> Unit) {
    val colors = AgentTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .windowBackground(colors)
            .verticalScroll(rememberScrollState())
            .safePadding(safeArea, top = 24.dp, bottom = 24.dp)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 96.dp)) {
            AppMark(52.dp)
            Spacer(Modifier.height(20.dp))
            Text("Your feed can't open", Modifier.semantics { heading() }, color = colors.text, style = AgentTheme.type.display)
            Spacer(Modifier.height(16.dp))
            Banner(problem, isError = true)
            Spacer(Modifier.height(22.dp))
            AppButton("Try again", ButtonTone.Primary, modifier = Modifier.fillMaxWidth(), onClick = onRetry)
            Spacer(Modifier.height(10.dp))
            AppButton("Sign out", ButtonTone.Quiet, modifier = Modifier.fillMaxWidth(), onClick = onSignOut)
        }
    }
}
