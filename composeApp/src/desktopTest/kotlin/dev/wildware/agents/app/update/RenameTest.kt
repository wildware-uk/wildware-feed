package dev.wildware.agents.app.update

import dev.wildware.agents.app.desktop.DesktopOs
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Agents became Wildware Feed in 1.0.2. An installed Agents must find the renamed files and
 * update in place, and a renamed copy must still read releases named either way.
 */
class RenameTest {
    private val root: File = Files.createTempDirectory("agents-rename-test").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val installs = listOf(
        Installation.MacBundle("/Applications/Agents.app") to "aarch64",
        Installation.WindowsMsi("C:\\Users\\s\\AppData\\Local\\Agents\\Agents.exe") to "amd64",
        Installation.WindowsPortable("D:\\Tools\\Agents", "D:\\Tools\\Agents\\Agents.exe") to "amd64",
        Installation.LinuxAppImage("/home/s/Agents-1.0.1-x86_64.AppImage") to "amd64",
        Installation.LinuxPackage("/opt/agents/bin/Agents", AssetKind.LinuxDeb) to "amd64",
        Installation.LinuxPackage("/opt/agents/bin/Agents", AssetKind.LinuxRpm) to "amd64",
    )

    @Test
    fun releaseFilesNamedEitherWayAreRecognised() {
        listOf(allAssets("1.0.2"), oldAssets("1.0.2")).forEach { files ->
            files.forEach { assertEquals(it.assetKind, GitHubReleaseSource.kindOf(it.name), it.name) }
            val r = release("1.0.2", *files)
            installs.forEach { (installation, arch) ->
                val picked = chooseAsset(r, installation, arch)
                assertEquals(installation.kind?.wire, picked?.kind, "$installation from ${files.first().name}")
            }
        }
    }

    @Test
    fun theMsiFolderIsKnownByEitherName() {
        val env = mapOf("LOCALAPPDATA" to "C:\\Users\\s\\AppData\\Local")
        listOf("Agents\\Agents.exe", "Wildware Feed\\Wildware Feed.exe").forEach { tail ->
            val exe = "C:\\Users\\s\\AppData\\Local\\$tail"
            assertEquals(Installation.WindowsMsi(exe), Installation.detect(DesktopOs.Windows, exe, env) { false })
        }
    }

    @Test
    fun afterAnMsiUpgradeTheAppOpensFromItsNewFolder() {
        assertEquals(
            listOf(
                "C:\\Users\\s\\AppData\\Local\\Wildware Feed\\Wildware Feed.exe",
                "C:\\Users\\s\\AppData\\Local\\Agents\\Agents.exe",
            ),
            SystemInstaller.msiLaunchers("C:\\Users\\s\\AppData\\Local\\Agents\\Agents.exe"),
        )
    }

    @Test
    fun afterAPackageUpgradeTheAppOpensByItsNewName() {
        val renamed = setOf("/opt/agents/bin/Wildware Feed")
        assertEquals("/opt/agents/bin/Wildware Feed", SystemInstaller.packageLauncher("/opt/agents/bin/Agents") { it.path in renamed })
        assertEquals("/opt/agents/bin/Agents", SystemInstaller.packageLauncher("/opt/agents/bin/Agents") { it.path == "/opt/agents/bin/Agents" })
    }

    @Test
    fun aPortableAgentsFolderTakesTheRenamedProgramAndKeepsItsOwnName() {
        val folder = File(root, "Agents").apply { mkdirs() }
        File(folder, "Agents.exe").writeText("old")
        val zip = File(root, "Wildware-Feed-1.0.2-windows-x64-portable.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("Wildware Feed/Wildware Feed.exe")); out.write("new".toByteArray()); out.closeEntry()
        }
        val work = File(root, "work").apply { mkdirs() }
        val installer = SystemInstaller(commands = { _, _ -> error("runs nothing") }, which = { null })
        val prepared = installer.prepare(Installation.WindowsPortable(folder.path, "${folder.path}/Agents.exe"), zip, work, pid = 42)
        assertIs<Prepared.Restart>(prepared)
        assertEquals("new", File(root, "Agents.updating/Wildware Feed.exe").readText(), "staged beside the old folder, under its name")
        val script = prepared.helper.script.readText()
        assertTrue("Start-Process -FilePath '${folder.path}\\Wildware Feed.exe'" in script, script)
    }
}
