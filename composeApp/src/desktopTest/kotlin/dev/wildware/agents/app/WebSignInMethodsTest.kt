package dev.wildware.agents.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.wildware.agents.HostCheck
import dev.wildware.agents.SignInMethod
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.signin.ConnectState
import dev.wildware.agents.app.signin.SignInScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The web app is served by its host, so it skips "Connect to a host". It still asks that host how
 * people sign in: a team's server takes email and password, and without the Email box an invited
 * member could not sign in on the web at all.
 */
@OptIn(ExperimentalTestApi::class)
class WebSignInMethodsTest {
    private val home = "https://acme.feed.example.com"
    private val both = listOf(SignInMethod.Password, SignInMethod.EmailPassword)

    private fun passwordOnly() = ConnectState(home, HostCheck.Feed(home, listOf(SignInMethod.Password)))

    @Test
    fun `a team's server adds email and password`() = runBlocking {
        val connect = passwordOnly()
        connect.learnSignIn { HostCheck.Feed(it, both) }
        assertEquals(both, connect.host?.signIn)
    }

    @Test
    fun `when the host can't say, the password alone stays`() = runBlocking {
        val failed = passwordOnly().apply { learnSignIn { error("offline") } }
        assertEquals(listOf(SignInMethod.Password), failed.host?.signIn)
        val unreachable = passwordOnly().apply { learnSignIn { HostCheck.Unreachable(it) } }
        assertEquals(listOf(SignInMethod.Password), unreachable.host?.signIn)
    }

    @Test
    fun `being cancelled is not swallowed`() {
        assertFailsWith<CancellationException> {
            runBlocking { passwordOnly().learnSignIn { throw CancellationException("left the screen") } }
        }
    }

    @Test
    fun `the sign-in form shows the Email box once a team's server says so`() = runDesktopComposeUiTest {
        val answer = CompletableDeferred<HostCheck>()
        setContent {
            AgentTheme(false) {
                val connect = remember { passwordOnly() }
                LaunchedEffect(connect) { connect.learnSignIn { answer.await() } }
                SignInScreen(
                    "Browser", null, SafeArea(), connect = connect, hostFixed = true,
                    checkHost = { error("not asked here") }, signIn = { _, _, _ -> },
                    signInWithEmail = { _, _, _, _ -> },
                )
            }
        }
        waitForIdle()
        assertEquals(0, onAllNodes(hasContentDescription("Email")).fetchSemanticsNodes().size, "the password alone, until the host says")
        answer.complete(HostCheck.Feed(home, both))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasContentDescription("Email")).fetchSemanticsNodes().isNotEmpty() }
    }
}
