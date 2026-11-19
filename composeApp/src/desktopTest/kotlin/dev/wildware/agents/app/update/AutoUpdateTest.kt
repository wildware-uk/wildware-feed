package dev.wildware.agents.app.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * "Update automatically": found, then downloading in the background, then waiting with one
 * button, then restarting into it; and the ways it holds back (setting off, can't install,
 * a failing download that retries).
 */
class AutoUpdateTest {
    private val root: File = Files.createTempDirectory("agents-auto-update").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        root.deleteRecursively()
    }

    private val deb = Installation.LinuxPackage("/opt/agents/bin/Agents", AssetKind.LinuxDeb)
    private val appImage = Installation.LinuxAppImage("/apps/Wildware-Feed.AppImage")
    private val debName = "wildware-feed_1.0.2_amd64.deb"

    private class Rig(
        val updates: UpdateController,
        val source: FakeReleaseSource,
        val installer: FakeInstaller,
        val automatic: MutableStateFlow<Boolean>,
        val events: MutableList<String>,
        val updater: Updater,
    )

    private fun rig(
        automaticOn: Boolean = true,
        installation: Installation = deb,
        source: FakeReleaseSource = FakeReleaseSource(release("1.0.2")),
        installer: FakeInstaller = FakeInstaller(),
    ): Rig {
        val sources = MutableStateFlow<ReleaseSource?>(source)
        val automatic = MutableStateFlow(automaticOn)
        val events = mutableListOf<String>()
        val updater = Updater(installation, installer, File(root, "updates"), beforeQuit = { events += "flush" }, quit = { events += "quit" }, pid = 42, arch = "amd64")
        val checker = UpdateChecker(SemVer.parse("1.0.1")!!, sources, MutableStateFlow(true))
        val updates = UpdateController(
            "1.0.1", checker, updater, sources, scope,
            automatic = automatic,
            setAutomatic = { automatic.value = it },
            retryWaits = listOf(20.milliseconds),
        )
        updates.start()
        return Rig(updates, source, installer, automatic, events, updater)
    }

    private fun <T> waitFor(what: String, read: () -> T?, ok: (T) -> Boolean = { true }): T = runBlocking {
        withTimeout(5_000) {
            var value = read()
            while (value == null || !ok(value)) {
                delay(5)
                value = read()
            }
            value
        }
    }

    private fun Rig.staged() = waitFor("staged", { updates.background.value as? BackgroundUpdate.Staged })

    @Test
    fun aNewerVersionDownloadsThenWaitsThenOneClickRestartsIntoIt() = runBlocking {
        val rig = rig()
        val staged = rig.staged()
        assertEquals("1.0.2", staged.release.version)
        assertEquals(1, rig.source.downloads)
        assertEquals(emptyList(), rig.installer.calls, "nothing is installed before the click")
        assertEquals(emptyList(), rig.events, "and the app keeps running")
        assertNull(rig.updates.prompt.value, "no dialog opens by itself")
        assertEquals(TrayUpdate.Restart("1.0.2", asksForPassword = true), rig.updates.trayItem.first())

        val job = rig.updates.restartToUpdate()!!
        job.join()
        assertEquals(1, rig.source.downloads, "the restart uses the file already downloaded")
        assertEquals(listOf("prepare $debName", "launch"), rig.installer.calls)
        assertEquals(listOf("flush", "quit"), rig.events)
        assertTrue(rig.updates.quiet.value, "the pill shows the restart; no dialog")
        assertNull(rig.updates.prompt.value)
    }

    @Test
    fun theSettingOffKeepsTodaysCheckThenOffer() = runBlocking {
        val rig = rig(automaticOn = false)
        waitFor("found", { rig.updates.available.value })
        delay(100)
        assertEquals(BackgroundUpdate.Off, rig.updates.background.value)
        assertEquals(0, rig.source.downloads, "nothing downloads until asked")
        assertFalse(rig.updates.downloadsInBackground(release("1.0.2")))
        assertEquals(TrayUpdate.Install("1.0.2"), rig.updates.trayItem.first())
        assertNull(rig.updates.restartToUpdate(), "there is nothing to restart into")
    }

    @Test
    fun turningTheSettingOnLaterStartsTheDownloadAndOffAgainStopsWaiting() = runBlocking {
        val rig = rig(automaticOn = false)
        waitFor("found", { rig.updates.available.value })
        rig.updates.setAutomatically(true)
        rig.staged()
        rig.updates.setAutomatically(false)
        waitFor("off", { rig.updates.background.value }) { it == BackgroundUpdate.Off }
        assertFalse(rig.updates.applyOnQuit(), "quitting with the setting off installs nothing")
    }

    @Test
    fun aCopyThatCantUpdateItselfNeverDownloads() = runBlocking {
        val rig = rig(installation = Installation.Unsupported("This copy wasn't installed from a release, so it can't update itself."))
        waitFor("found", { rig.updates.available.value })
        delay(100)
        assertEquals(BackgroundUpdate.Off, rig.updates.background.value)
        assertEquals(0, rig.source.downloads)
        assertEquals(TrayUpdate.Open("1.0.2"), rig.updates.trayItem.first(), "the dialog says why")
    }

    @Test
    fun aReleaseWithNothingForThisComputerOrNoChecksumNeverDownloads() = runBlocking {
        val noChecksum = FakeReleaseSource(release("1.0.2", asset(debName, "linux-deb", sha256 = null)))
        val rig = rig(source = noChecksum)
        waitFor("found", { rig.updates.available.value })
        delay(100)
        assertEquals(BackgroundUpdate.Off, rig.updates.background.value)
        assertEquals(0, noChecksum.downloads)

        val macOnly = FakeReleaseSource(release("1.0.2", asset("Wildware-Feed-1.0.2-mac-arm64.dmg", "mac-dmg")))
        val other = rig(source = macOnly)
        waitFor("found", { other.updates.available.value })
        delay(100)
        assertEquals(0, macOnly.downloads)
    }

    @Test
    fun aFailedDownloadWaitsAndTriesAgain() = runBlocking {
        val source = FakeReleaseSource(release("1.0.2")).apply { failNext = 2 }
        val rig = rig(source = source)
        val retrying = waitFor("retrying", { rig.updates.background.value as? BackgroundUpdate.Retrying })
        assertEquals("The download failed (GitHub answered 502).", retrying.message)
        assertEquals(TrayUpdate.Downloading("1.0.2"), TrayUpdate.of(false, release("1.0.2"), InstallState.Idle, true, retrying))
        rig.staged()
        assertEquals(3, source.downloads, "two failures, then it worked")
    }

    @Test
    fun aDownloadThatBreaksOffCarriesOnFromWhereItStopped() = runBlocking {
        val source = FakeReleaseSource(release("1.0.2")).apply { breakAfter = 5 }
        val rig = rig(source = source)
        rig.staged()
        assertEquals(listOf(0L, 5L), source.startedFrom)
        assertTrue(rig.updater.staged(release("1.0.2")) != null, "and what it put together checks out")
    }

    @Test
    fun aDownloadThatDoesntMatchItsChecksumIsThrownAwayAndFetchedAgain() = runBlocking {
        val source = FakeReleaseSource(release("1.0.2"), bytes = mutableMapOf(debName to "tampered".toByteArray()))
        val rig = rig(source = source)
        val retrying = waitFor("retrying", { rig.updates.background.value as? BackgroundUpdate.Retrying })
        assertTrue("checksum" in retrying.message, retrying.message)
        assertTrue(File(root, "updates").walk().none { it.isFile && it.name.endsWith(".part") }, "the bad file is gone")
        source.bytes.remove(debName)
        rig.staged()
        assertEquals(listOf("1.0.2"), File(root, "updates").list()!!.toList())
    }

    @Test
    fun aRestartThatFailsShowsWhyAndTheDownloadStaysReady() = runBlocking {
        val installer = FakeInstaller { throw InstallException("The install was cancelled, so nothing changed.") }
        val rig = rig(installer = installer)
        rig.staged()
        rig.updates.restartToUpdate()!!.join()
        assertEquals(InstallState.Failed("The install was cancelled, so nothing changed."), rig.updates.install.value)
        assertEquals(emptyList(), rig.events, "the app stayed open")
        rig.staged()
        assertEquals(1, rig.source.downloads, "the next click needn't download again")
    }

    @Test
    fun quittingWithAnUpdateWaitingPutsItInWithoutReopening() = runBlocking {
        val rig = rig(installation = appImage, installer = FakeInstaller { Prepared.Done })
        rig.staged()
        assertTrue(rig.updates.applyOnQuit())
        assertEquals(listOf("prepare Wildware-Feed-1.0.2-x86_64.AppImage without relaunch"), rig.installer.calls)
        assertEquals(emptyList(), rig.events, "the app is quitting by itself; the updater doesn't quit it")

        val withHelper = rig(installation = appImage, installer = FakeInstaller())
        withHelper.staged()
        assertTrue(withHelper.updates.applyOnQuit())
        assertEquals(listOf("prepare Wildware-Feed-1.0.2-x86_64.AppImage without relaunch", "launch"), withHelper.installer.calls)
    }

    @Test
    fun aLinuxPackageWaitsForTheButtonBecauseItNeedsAPassword() = runBlocking {
        val rig = rig(installation = deb)
        rig.staged()
        assertFalse(rig.updates.applyOnQuit(), "quitting never pops up a password box")
        assertEquals(emptyList(), rig.installer.calls)
        assertTrue(rig.updates.asksForPassword)
        assertEquals("Restart to update to 1.0.2 (asks for your password)", TrayUpdate.Restart("1.0.2", asksForPassword = true).label)
    }

    @Test
    fun quittingWithNothingDownloadedDoesNothing() = runBlocking {
        val rig = rig(automaticOn = false, installation = appImage)
        waitFor("found", { rig.updates.available.value })
        assertFalse(rig.updates.applyOnQuit())
        assertEquals(emptyList(), rig.installer.calls)
    }

    @Test
    fun cleaningUpAtStartKeepsANewerDownloadWaiting() {
        val work = File(root, "updates").apply { mkdirs() }
        listOf("1.0.0", "1.0.1", "1.0.2", "junk").forEach { File(work, it).mkdirs() }
        val updater = Updater(deb, FakeInstaller(), work, beforeQuit = {}, quit = {}, arch = "amd64")
        updater.cleanUp(SemVer.parse("1.0.1")!!)
        assertEquals(listOf("1.0.2"), work.list()!!.toList())
    }

    @Test
    fun theTrayItemFollowsTheBackgroundDownload() {
        val r = release("1.0.2")
        assertEquals(TrayUpdate.Downloading("1.0.2"), TrayUpdate.of(false, r, InstallState.Idle, true, BackgroundUpdate.Downloading(r, 1, 10, 1)))
        assertFalse(TrayUpdate.Downloading("1.0.2").enabled)
        assertEquals("Downloading update 1.0.2…", TrayUpdate.Downloading("1.0.2").label)
        assertEquals(TrayUpdate.Restart("1.0.2"), TrayUpdate.of(false, r, InstallState.Idle, true, BackgroundUpdate.Staged(r)))
        assertEquals("Restart to update to 1.0.2", TrayUpdate.Restart("1.0.2").label)
        // A download that keeps failing goes back to the plain offer, so the update never hides.
        val stuck = BackgroundUpdate.Retrying(r, "offline", AutoUpdater.ShowAfterFailures)
        assertEquals(TrayUpdate.Install("1.0.2"), TrayUpdate.of(false, r, InstallState.Idle, true, stuck))
        assertIs<TrayUpdate.Installing>(TrayUpdate.of(false, r, InstallState.Restarting, true, BackgroundUpdate.Staged(r)))
    }
}
