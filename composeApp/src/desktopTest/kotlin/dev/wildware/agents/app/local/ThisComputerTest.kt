package dev.wildware.agents.app.local

import dev.wildware.agents.app.signin.SignInChoice
import dev.wildware.agents.app.signin.defaultSignInChoice
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThisComputerTest {
    @Test
    fun theDesktopStartsOnThisComputerAndPhonesOnAHost() {
        assertEquals(SignInChoice.ThisComputer, defaultSignInChoice(canHostLocally = true))
        assertEquals(SignInChoice.Host, defaultSignInChoice(canHostLocally = false))
    }

    @Test
    fun thePhonePasswordMustBeLongEnoughAndTypedTheSameTwice() {
        assertEquals("Choose a password your phone will sign in with.", checkPhonePassword("", ""))
        assertEquals("Choose a password your phone will sign in with.", checkPhonePassword("   ", "   "))
        assertEquals("Use at least 8 characters.", checkPhonePassword("short", "short"))
        assertEquals("The two passwords don't match. Type them again.", checkPhonePassword("correct horse", "correct hose"))
        assertNull(checkPhonePassword("correct horse", "correct horse"))
    }

    @Test
    fun turningPhonesOnWarnsThenAsksForAPasswordBeforeAnythingChanges() = runTest(UnconfinedTestDispatcher()) {
        val host = FakeLocalServerHost()
        val settings = ThisComputer(host, this)
        settings.open()
        settings.setPhoneAccess(true)
        assertEquals(ThisComputerStep.PhoneWarning, settings.view.value.step)
        settings.acceptWarning()
        assertEquals(ThisComputerStep.PhonePassword, settings.view.value.step)
        assertTrue(host.phoneCalls.isEmpty())

        settings.choosePassword("correct horse", "correct hose")
        assertEquals(ThisComputerStep.PhonePassword, settings.view.value.step)
        assertEquals("The two passwords don't match. Type them again.", settings.view.value.problem)
        assertTrue(host.phoneCalls.isEmpty())

        settings.choosePassword("correct horse", "correct horse")
        assertEquals(listOf<Pair<Boolean, String?>>(true to "correct horse"), host.phoneCalls)
        assertEquals(ThisComputerView(ThisComputerStep.Overview), settings.view.value)
        assertEquals("http://192.0.2.10:4317", settings.server.value.phoneUrl)
    }

    @Test
    fun cancellingTheWarningChangesNothing() = runTest(UnconfinedTestDispatcher()) {
        val host = FakeLocalServerHost()
        val settings = ThisComputer(host, this)
        settings.open()
        settings.setPhoneAccess(true)
        settings.back()
        assertEquals(ThisComputerStep.Overview, settings.view.value.step)
        assertTrue(host.phoneCalls.isEmpty())
    }

    @Test
    fun turningPhonesOffNeedsNoPassword() = runTest(UnconfinedTestDispatcher()) {
        val host = FakeLocalServerHost(LocalServerState(running = true, phoneAccess = true, phoneUrl = "http://192.0.2.10:4317"))
        val settings = ThisComputer(host, this)
        settings.open()
        settings.setPhoneAccess(false)
        assertEquals(listOf<Pair<Boolean, String?>>(false to null), host.phoneCalls)
        assertEquals(ThisComputerStep.Overview, settings.view.value.step)
        assertNull(settings.server.value.phoneUrl)
    }

    @Test
    fun connectingClaudeCodeShowsItsSettings() = runTest(UnconfinedTestDispatcher()) {
        val host = FakeLocalServerHost()
        val settings = ThisComputer(host, this)
        settings.open()
        settings.connectAgent()
        assertEquals(listOf("Claude Code"), host.agentNames)
        assertEquals(
            ThisComputerStep.AgentConnected(AgentConnection("http://127.0.0.1:4317", FakeLocalServerHost.FAKE_TOKEN)),
            settings.view.value.step,
        )
    }

    @Test
    fun aFailureStaysOnItsPageAndSaysWhy() = runTest(UnconfinedTestDispatcher()) {
        val host = FakeLocalServerHost(failure = IllegalStateException("The server on this computer didn't start."))
        val settings = ThisComputer(host, this)
        settings.open()
        settings.connectAgent()
        assertEquals(ThisComputerView(ThisComputerStep.Overview, problem = "The server on this computer didn't start."), settings.view.value)
    }
}
