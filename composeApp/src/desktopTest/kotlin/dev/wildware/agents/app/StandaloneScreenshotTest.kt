package dev.wildware.agents.app

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.Author
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.local.AgentConnection
import dev.wildware.agents.app.local.FakeLocalServerHost
import dev.wildware.agents.app.local.LocalServerState
import dev.wildware.agents.app.local.ThisComputerActions
import dev.wildware.agents.app.local.ThisComputerDialog
import dev.wildware.agents.app.local.ThisComputerStep
import dev.wildware.agents.app.signin.SignInChoice
import dev.wildware.agents.app.signin.SignInScreen
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** "This computer" on the sign-in screen, and its settings dialog at each step, on made-up data. */
class StandaloneScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private val running = LocalServerState(running = true, localUrl = "http://127.0.0.1:4317")
    private val phoneOn = running.copy(phoneAccess = true, phoneUrl = "http://192.0.2.10:4317")

    @Test
    fun signIn() {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            shoot("standalone-$theme-sign-in-this-computer", dark) {
                SignInScreen("Laptop", null, SafeArea(), signInHere = {}, checkHost = { error("no network here") }) { _, _, _ -> }
            }
            shoot("standalone-$theme-sign-in-connect-to-a-host", dark) {
                SignInScreen(
                    "Laptop", null, SafeArea(), signInHere = {}, startWith = SignInChoice.Host,
                    checkHost = { error("no network here") },
                ) { _, _, _ -> }
            }
        }
        shoot("standalone-light-sign-in-already-running", dark = false) {
            SignInScreen(
                "Laptop", null, SafeArea(), signInHere = {},
                hostProblem = "Another copy of Wildware Feed is already running the server on this computer.",
                checkHost = { error("no network here") },
            ) { _, _, _ -> }
        }
    }

    @Test
    fun settings() {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            dialog("standalone-$theme-settings", dark, ThisComputerStep.Overview, running)
            dialog("standalone-$theme-settings-phone-on", dark, ThisComputerStep.Overview, phoneOn)
            dialog("standalone-$theme-phone-warning", dark, ThisComputerStep.PhoneWarning, running)
            dialog("standalone-$theme-phone-password", dark, ThisComputerStep.PhonePassword, running, problem = "The two passwords don't match. Type them again.")
            dialog(
                "standalone-$theme-connect-claude-code", dark,
                ThisComputerStep.AgentConnected(AgentConnection("http://127.0.0.1:4317", FakeLocalServerHost.FAKE_TOKEN)),
                running,
            )
        }
    }

    private fun dialog(name: String, dark: Boolean, step: ThisComputerStep, server: LocalServerState, problem: String? = null) {
        shoot(name, dark, overFeed = true) {
            ThisComputerDialog(step, server, busy = false, problem = problem, actions = ThisComputerActions())
        }
    }

    private fun shoot(name: String, dark: Boolean, overFeed: Boolean = false, content: @Composable BoxScope.() -> Unit) {
        val scale = 2f
        val width = 1440
        val height = 900
        ImageComposeScene(width * scale.toInt(), height * scale.toInt(), Density(scale)) {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                if (overFeed) {
                    // A made-up owner name, so the pictures can go in public docs.
                    AgentsPreview(dark = dark, state = FakeFeed.state.copy(owner = Author.Owner("Alex")), overlay = content)
                } else {
                    AgentTheme(dark) { androidx.compose.foundation.layout.Box { content() } }
                }
            }
        }.use { scene ->
            var t = 0L
            repeat(8) {
                scene.render(t)
                t += 16_000_000L
                Thread.sleep(40)
            }
            val png = scene.render(t).encodeToData(EncodedImageFormat.PNG) ?: error("could not encode $name")
            val file = File(outDir, "$name.png")
            file.writeBytes(png.bytes)
            assertTrue(file.length() > 0, "$name is empty")
            println("screenshot: ${file.absolutePath}")
        }
    }
}
