package dev.wildware.agents.app.update

import dev.wildware.agents.app.desktop.updateNotice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tray menu's update item, and the check behind it that never opens the window. */
class TrayUpdateTest {
    private val newer = release("1.0.3")

    @Test
    fun saysCheckForUpdatesWhenThereIsNothingToInstall() {
        val item = TrayUpdate.of(checking = false, available = null, install = InstallState.Idle, canInstall = true)
        assertEquals(TrayUpdate.Check, item)
        assertEquals("Check for updates…", item.label)
        assertTrue(item.enabled)
    }

    @Test
    fun saysCheckingWhileACheckRuns() {
        val item = TrayUpdate.of(checking = true, available = null, install = InstallState.Idle, canInstall = true)
        assertEquals("Checking…", item.label)
        assertFalse(item.enabled)
    }

    @Test
    fun offersToInstallANewerVersion() {
        val item = TrayUpdate.of(checking = false, available = newer, install = InstallState.Idle, canInstall = true)
        assertEquals(TrayUpdate.Install("1.0.3"), item)
        assertEquals("Install update 1.0.3", item.label)
        assertTrue(item.enabled)
    }

    @Test
    fun aRetryAfterAFailedInstallIsStillAnInstall() {
        val item = TrayUpdate.of(checking = false, available = newer, install = InstallState.Failed("no space"), canInstall = true)
        assertEquals(TrayUpdate.Install("1.0.3"), item)
    }

    @Test
    fun opensTheDialogWhenThisCopyCannotInstallItself() {
        val item = TrayUpdate.of(checking = false, available = newer, install = InstallState.Idle, canInstall = false)
        assertEquals(TrayUpdate.Open("1.0.3"), item)
        assertEquals("Update to 1.0.3…", item.label)
    }

    @Test
    fun opensTheDialogWhenAnInstallNeedsFinishingByHand() {
        val item = TrayUpdate.of(checking = false, available = newer, install = InstallState.Manual("Drag it in", null), canInstall = true)
        assertEquals(TrayUpdate.Open("1.0.3"), item)
    }

    @Test
    fun cannotBeClickedWhileInstalling() {
        for (state in listOf(InstallState.Downloading(1, 10), InstallState.Verifying, InstallState.Installing, InstallState.Restarting)) {
            val item = TrayUpdate.of(checking = true, available = newer, install = state, canInstall = true)
            assertEquals(TrayUpdate.Installing, item, "$state")
            assertEquals("Installing update…", item.label)
            assertFalse(item.enabled)
        }
    }

    @Test
    fun aQuietCheckShowsCheckingThenOffersTheInstall() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = FakeReleaseSource(newer).apply { gate = CompletableDeferred() }
        val updates = controller("1.0.2", fake, scope)

        val result = scope.async { updates.checkQuietly() }
        withTimeout(5_000) { updates.trayItem.first { it == TrayUpdate.Checking } }
        assertNull(updates.checkQuietly(), "a second click while checking does nothing")

        fake.gate!!.complete(Unit)
        assertEquals(CheckResult.Newer(newer), result.await())
        assertEquals(TrayUpdate.Install("1.0.3"), updates.trayItem.first())
        assertNull(updates.prompt.value, "the window's dialog stays shut")
        scope.cancel()
    }

    @Test
    fun aQuietCheckWhenUpToDateLeavesTheItemAsItWas() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val updates = controller("1.0.3", FakeReleaseSource(newer), scope)

        assertEquals(CheckResult.UpToDate, updates.checkQuietly())
        assertEquals(TrayUpdate.Check, updates.trayItem.first())
        assertNull(updates.prompt.value)
        scope.cancel()
    }

    @Test
    fun aCheckFromTheCommandBarAlsoShowsCheckingInTheTray() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fake = FakeReleaseSource(newer).apply { gate = CompletableDeferred() }
        val updates = controller("1.0.2", fake, scope)

        updates.checkByHand()
        withTimeout(5_000) { updates.trayItem.first { it == TrayUpdate.Checking } }
        fake.gate!!.complete(Unit)
        withTimeout(5_000) { updates.trayItem.first { it == TrayUpdate.Install("1.0.3") } }
        scope.cancel()
    }

    @Test
    fun theNoticeSaysWhatTheCheckFound() {
        val upToDate = updateNotice(CheckResult.UpToDate, "1.0.3")
        assertEquals("You're up to date", upToDate.title)
        assertEquals("You have Wildware Feed 1.0.3, the newest version.", upToDate.body)
        assertNull(upToDate.focusId)

        val found = updateNotice(CheckResult.Newer(newer), "1.0.2")
        assertEquals("Wildware Feed 1.0.3 is available", found.title)
        assertEquals("Choose Install update 1.0.3 in the tray menu to install it and restart.", found.body)

        val failed = updateNotice(CheckResult.Failed("GitHub didn't answer."), "1.0.2")
        assertEquals("Couldn't check for updates", failed.title)
        assertEquals("GitHub didn't answer.", failed.body)
    }

    private fun controller(current: String, fake: FakeReleaseSource, scope: CoroutineScope): UpdateController {
        val source = MutableStateFlow<ReleaseSource?>(fake)
        val checker = UpdateChecker(SemVer.parse(current)!!, source, MutableStateFlow(false))
        val work = Files.createTempDirectory("agents-tray").toFile()
        val updater = Updater(Installation.LinuxPackage("/opt/agents/bin/Agents", AssetKind.LinuxDeb), FakeInstaller(), work, beforeQuit = {}, quit = {}, arch = "amd64")
        return UpdateController(current, checker, updater, source, scope)
    }
}
