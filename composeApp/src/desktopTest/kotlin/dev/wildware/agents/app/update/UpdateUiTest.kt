package dev.wildware.agents.app.update

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.wildware.agents.app.design.AgentTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The pill and the dialog, driven by a fake release source. */
@OptIn(ExperimentalTestApi::class)
class UpdateUiTest {
    private fun controller(current: String, fake: FakeReleaseSource, scope: CoroutineScope, installer: Installer = FakeInstaller(), quit: () -> Unit = {}): UpdateController {
        val source = MutableStateFlow<ReleaseSource?>(fake)
        val checker = UpdateChecker(SemVer.parse(current)!!, source, MutableStateFlow(false))
        val work: File = Files.createTempDirectory("agents-ui").toFile()
        val updater = Updater(Installation.LinuxPackage("/opt/agents/bin/Agents", AssetKind.LinuxDeb), installer, work, beforeQuit = {}, quit = quit, arch = "amd64")
        runBlocking { checker.checkNow() }
        return UpdateController(current, checker, updater, source, scope)
    }

    @Test
    fun thePillShowsOnlyWhenThereIsANewerVersion() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val newer = controller("1.0.1", FakeReleaseSource(release("1.0.2")), scope)
        val same = controller("1.0.2", FakeReleaseSource(release("1.0.2")), scope)
        var which by mutableStateOf(same)
        setContent {
            AgentTheme(false) { Box(Modifier.fillMaxSize()) { UpdateLayer(which) {} } }
        }
        onNodeWithContentDescription("Update to 1.0.2 available").assertDoesNotExist()
        which = newer
        waitForIdle()
        onNodeWithContentDescription("Update to 1.0.2 available").assertExists()
        scope.cancel()
    }

    @Test
    fun clickingThePillShowsTheNotesThenInstallingRestarts() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val installer = FakeInstaller()
        var quit = false
        val updates = controller("1.0.1", FakeReleaseSource(release("1.0.2")), scope, installer) { quit = true }
        setContent {
            AgentTheme(false) { Box(Modifier.fillMaxSize()) { UpdateLayer(updates) {} } }
        }
        onNodeWithContentDescription("Update to 1.0.2 available").performClick()
        onNodeWithText("Agents 1.0.2 is available").assertExists()
        onNodeWithText("You have 1.0.1.").assertExists()
        onNodeWithText("Not now").assertExists()
        onNodeWithText("Install and restart").performClick()
        waitUntil(timeoutMillis = 5_000) { quit }
        assertEquals(listOf("prepare agents_1.0.2_amd64.deb", "launch"), installer.calls)
        onNodeWithText("Restarting…").assertExists()
        scope.cancel()
    }

    @Test
    fun aFailureShowsPlainlyWithRetry() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = FakeReleaseSource(release("1.0.2"), bytes = mutableMapOf("agents_1.0.2_amd64.deb" to "tampered".toByteArray()))
        val updates = controller("1.0.1", fake, scope)
        setContent {
            AgentTheme(true) { Box(Modifier.fillMaxSize()) { UpdateLayer(updates) {} } }
        }
        updates.showOffer()
        onNodeWithText("Install and restart").performClick()
        waitUntil(timeoutMillis = 5_000) { updates.install.value is InstallState.Failed }
        onNodeWithText("The update didn't install").assertExists()
        onNodeWithText("Retry").assertExists()
        scope.cancel()
    }

    @Test
    fun checkingByHandWhenUpToDateSaysSo() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val updates = controller("1.0.2", FakeReleaseSource(release("1.0.2")), scope)
        setContent {
            AgentTheme(false) { Box(Modifier.fillMaxSize()) { UpdateLayer(updates) {} } }
        }
        updates.checkByHand()
        waitUntil(timeoutMillis = 5_000) { updates.prompt.value == UpdatePrompt.UpToDate }
        onNodeWithText("Agents is up to date").assertExists()
        scope.cancel()
    }
}
