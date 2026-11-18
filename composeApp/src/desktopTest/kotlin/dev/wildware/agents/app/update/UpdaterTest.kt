package dev.wildware.agents.app.update

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Download, check, stage, hand over and quit; and every way that stops short. */
class UpdaterTest {
    private val root: File = Files.createTempDirectory("agents-updater-test").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val linux = Installation.LinuxPackage("/opt/agents/bin/Agents", AssetKind.LinuxDeb)

    private fun updater(installer: Installer, events: MutableList<String>, installation: Installation = linux) = Updater(
        installation = installation,
        installer = installer,
        workRoot = File(root, "updates"),
        beforeQuit = { events += "flush" },
        quit = { events += "quit" },
        pid = 42,
        arch = "amd64",
    )

    @Test
    fun aGoodDownloadIsStagedThenTheHelperStartsThenTheAppQuits() = runTest {
        val events = mutableListOf<String>()
        val installer = FakeInstaller()
        val up = updater(installer, events)
        up.install(release(), FakeReleaseSource())
        assertEquals(listOf("prepare wildware-feed_1.0.2_amd64.deb", "launch"), installer.calls)
        assertEquals(listOf("flush", "quit"), events)
        assertEquals(InstallState.Restarting, up.state.value)
    }

    @Test
    fun aChecksumMismatchRefusesToInstall() = runTest {
        val events = mutableListOf<String>()
        val installer = FakeInstaller()
        val up = updater(installer, events)
        val source = FakeReleaseSource(bytes = mutableMapOf("wildware-feed_1.0.2_amd64.deb" to "tampered".toByteArray()))
        up.install(release(), source)
        val state = up.state.value
        assertIs<InstallState.Failed>(state)
        assertTrue("checksum" in state.message, state.message)
        assertEquals(emptyList(), installer.calls, "nothing was staged or launched")
        assertEquals(emptyList(), events, "and the app stayed open")
        val left = File(root, "updates").walk().filter { it.isFile }.toList()
        assertEquals(emptyList(), left, "the bad download is gone")
    }

    @Test
    fun aReleaseWithNoChecksumIsNotInstalled() = runTest {
        val installer = FakeInstaller()
        val up = updater(installer, mutableListOf())
        up.install(release("1.0.2", asset("wildware-feed_1.0.2_amd64.deb", "linux-deb", sha256 = null)), FakeReleaseSource())
        assertIs<InstallState.Failed>(up.state.value)
        assertEquals(emptyList(), installer.calls)
    }

    @Test
    fun aFailedDownloadSaysSoAndCanBeRetried() = runTest {
        val events = mutableListOf<String>()
        val installer = FakeInstaller()
        val up = updater(installer, events)
        val source = FakeReleaseSource(failWith = ReleaseSourceException("The download failed (GitHub answered 502)."))
        up.install(release(), source)
        assertEquals(InstallState.Failed("The download failed (GitHub answered 502)."), up.state.value)

        source.failWith = null
        up.install(release(), source)
        assertEquals(InstallState.Restarting, up.state.value)
        assertEquals(listOf("flush", "quit"), events)
    }

    @Test
    fun aHelperThatWontStartUndoesTheStagingAndKeepsTheAppOpen() = runTest {
        val events = mutableListOf<String>()
        val installer = object : Installer by FakeInstaller() {
            val calls = mutableListOf<String>()
            override fun prepare(installation: Installation, download: File, workDir: File, pid: Long) = Prepared.Restart(Helper(download, listOf("x")))
            override fun launch(helper: Helper) = throw InstallException("Couldn't start the step that finishes the update. Nothing was installed.")
            override fun abandon(installation: Installation, workDir: File) { calls += "abandon" }
        }
        val up = updater(installer, events)
        up.install(release(), FakeReleaseSource())
        assertIs<InstallState.Failed>(up.state.value)
        assertEquals(listOf("abandon"), installer.calls)
        assertEquals(listOf("flush"), events, "it never quit")
    }

    @Test
    fun aManualStepShowsTheFolderAndDoesNotQuit() = runTest {
        val events = mutableListOf<String>()
        val installer = FakeInstaller { Prepared.Manual("Install it with sudo apt install …", it.parentFile) }
        val up = updater(installer, events)
        up.install(release(), FakeReleaseSource())
        val state = up.state.value
        assertIs<InstallState.Manual>(state)
        assertEquals(emptyList(), events)
    }

    @Test
    fun aCopyRunFromTheSourceExplainsItCantUpdate() = runTest {
        val installer = FakeInstaller()
        val up = updater(installer, mutableListOf(), Installation.Unsupported("This copy wasn't installed from a release, so it can't update itself."))
        assertFalse(up.canInstall)
        up.install(release(), FakeReleaseSource())
        assertEquals(InstallState.Failed("This copy wasn't installed from a release, so it can't update itself."), up.state.value)
    }

    @Test
    fun anAppImageIsReplacedInOneRename() {
        val dir = File(root, "apps").apply { mkdirs() }
        val old = File(dir, "Agents.AppImage").apply { writeText("old") }
        val download = File(dir, ".Agents.AppImage.update").apply { writeText("new") }
        SystemInstaller.replaceAppImage(download, old)
        assertEquals("new", old.readText())
        assertTrue(old.canExecute())
        assertFalse(download.exists())
    }

    @Test
    fun theAppImageHelperOnlyRelaunchesOnceItIsInPlace() {
        val dir = File(root, "apps").apply { mkdirs() }
        val image = File(dir, "Agents.AppImage").apply { writeText("old") }
        val installer = SystemInstaller(commands = { _, _ -> error("runs nothing") }, which = { null })
        val work = File(root, "work").apply { mkdirs() }
        val asset = asset("Agents-1.0.2-x86_64.AppImage", "linux-appimage")
        val installation = Installation.LinuxAppImage(image.absolutePath)
        val target = installer.downloadTarget(installation, asset, work)
        assertEquals(dir.absoluteFile, target.parentFile.absoluteFile, "downloaded beside the AppImage, so the rename is atomic")
        target.writeText("new")
        val prepared = installer.prepare(installation, target, work, pid = 42)
        assertIs<Prepared.Restart>(prepared)
        assertEquals("new", image.readText())
        assertEquals(listOf("/bin/sh", File(work, "relaunch.sh").absolutePath), prepared.helper.command)
        assertEquals(SystemInstaller.AppImageEnv, prepared.helper.dropEnv)
    }

    @Test
    fun withoutPkexecADebInstallBecomesAManualStep() {
        val installer = SystemInstaller(commands = { _, _ -> error("runs nothing") }, which = { null })
        val work = File(root, "work").apply { mkdirs() }
        val deb = File(work, "agents_1.0.2_amd64.deb").apply { writeText("deb") }
        val prepared = installer.prepare(linux, deb, work, pid = 42)
        assertIs<Prepared.Manual>(prepared)
        assertTrue("sudo apt install ${deb.absolutePath}" in prepared.message, prepared.message)
    }

    @Test
    fun aDebInstallsThroughPkexecAndACancelledPasswordChangesNothing() {
        val ran = mutableListOf<List<String>>()
        var code = 0
        val installer = SystemInstaller(commands = { command, _ -> ran += command; code to "" }, which = { File("/usr/bin/$it") })
        val work = File(root, "work").apply { mkdirs() }
        val deb = File(work, "agents_1.0.2_amd64.deb").apply { writeText("deb") }
        val prepared = installer.prepare(linux, deb, work, pid = 42)
        assertEquals(listOf("/usr/bin/pkexec", "apt-get", "install", "-y", deb.absolutePath), ran.single())
        assertIs<Prepared.Restart>(prepared)
        assertEquals(listOf("/usr/bin/setsid", "/bin/sh", File(work, "relaunch.sh").absolutePath), prepared.helper.command)

        code = 126
        val e = assertFailsWith<InstallException> { installer.prepare(linux, deb, work, pid = 42) }
        assertEquals("The install was cancelled, so nothing changed.", e.message)
    }

    @Test
    fun theRpmUsesDnfWhenThereIsOne() {
        val ran = mutableListOf<List<String>>()
        val installer = SystemInstaller(commands = { command, _ -> ran += command; 0 to "" }, which = { File("/usr/bin/$it") })
        val work = File(root, "work").apply { mkdirs() }
        val rpm = File(work, "agents-1.0.2.x86_64.rpm").apply { writeText("rpm") }
        installer.prepare(Installation.LinuxPackage("/opt/agents/bin/Agents", AssetKind.LinuxRpm), rpm, work, pid = 42)
        assertEquals(listOf("/usr/bin/pkexec", "dnf", "install", "-y", rpm.absolutePath), ran.single())
    }

    @Test
    fun thePortableZipUnpacksWithoutItsTopFolderAndRefusesPathsThatClimbOut() {
        val zip = File(root, "portable.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("Agents/")); out.closeEntry()
            out.putNextEntry(ZipEntry("Agents/Agents.exe")); out.write("exe".toByteArray()); out.closeEntry()
            out.putNextEntry(ZipEntry("Agents/app/Agents.cfg")); out.write("cfg".toByteArray()); out.closeEntry()
        }
        val into = File(root, "Agents.updating")
        SystemInstaller.unzipStrippingTop(zip, into)
        assertContentEquals("exe".toByteArray(), File(into, "Agents.exe").readBytes())
        assertEquals("cfg", File(into, "app/Agents.cfg").readText())

        val evil = File(root, "evil.zip")
        ZipOutputStream(evil.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("Agents/../../escaped.txt")); out.write("x".toByteArray()); out.closeEntry()
        }
        assertFailsWith<InstallException> { SystemInstaller.unzipStrippingTop(evil, File(root, "evil")) }
        assertFalse(File(root.parentFile, "escaped.txt").exists())
    }
}
