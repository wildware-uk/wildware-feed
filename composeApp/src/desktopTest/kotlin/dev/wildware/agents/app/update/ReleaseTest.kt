package dev.wildware.agents.app.update

import dev.wildware.agents.app.desktop.DesktopOs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Versions, how the app was installed, and which file updates it. */
class ReleaseTest {
    private fun v(text: String) = SemVer.parse(text) ?: error("didn't parse $text")

    @Test
    fun versionsCompareByNumberNotByText() {
        assertTrue(v("1.0.10") > v("1.0.9"))
        assertTrue(v("1.2.0") > v("1.1.99"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertEquals(0, v("v1.0.2").compareTo(v("1.0.2")))
        assertTrue(v("1.0.2") > v("1.0.2-beta.1"), "a release is newer than its own beta")
        assertTrue(v("1.0.2-beta.1") > v("1.0.1"))
    }

    @Test
    fun nonsenseIsNotAVersion() {
        listOf("", "1.0", "1.0.0.0", "latest", "v", "1.a.0").forEach { assertNull(SemVer.parse(it), it) }
    }

    private val noFiles: (String) -> Boolean = { false }

    @Test
    fun aMacAppIsItsBundle() {
        val found = Installation.detect(DesktopOs.Mac, "/Applications/Agents.app/Contents/MacOS/Agents", emptyMap(), noFiles)
        assertEquals(Installation.MacBundle("/Applications/Agents.app"), found)
    }

    @Test
    fun windowsInLocalAppDataIsTheMsiAndAnywhereElseIsThePortableZip() {
        val env = mapOf("LOCALAPPDATA" to "C:\\Users\\alex\\AppData\\Local")
        assertEquals(
            Installation.WindowsMsi("C:\\Users\\alex\\AppData\\Local\\Agents\\Agents.exe"),
            Installation.detect(DesktopOs.Windows, "C:\\Users\\alex\\AppData\\Local\\Agents\\Agents.exe", env, noFiles),
        )
        assertEquals(
            Installation.WindowsPortable("D:\\Tools\\Agents", "D:\\Tools\\Agents\\Agents.exe"),
            Installation.detect(DesktopOs.Windows, "D:\\Tools\\Agents\\Agents.exe", env, noFiles),
        )
    }

    @Test
    fun linuxKnowsAppImageDebAndRpm() {
        assertEquals(
            Installation.LinuxAppImage("/home/alex/Apps/Agents.AppImage"),
            Installation.detect(DesktopOs.Linux, "/tmp/.mount_x/bin/Agents", mapOf("APPIMAGE" to "/home/alex/Apps/Agents.AppImage"), noFiles),
        )
        assertEquals(
            Installation.LinuxPackage("/opt/agents/bin/Agents", AssetKind.LinuxDeb),
            Installation.detect(DesktopOs.Linux, "/opt/agents/bin/Agents", emptyMap()) { it == "/var/lib/dpkg/info/agents.list" },
        )
        assertEquals(
            Installation.LinuxPackage("/opt/agents/bin/Agents", AssetKind.LinuxRpm),
            Installation.detect(DesktopOs.Linux, "/opt/agents/bin/Agents", emptyMap()) { it == "/usr/bin/rpm" },
        )
    }

    @Test
    fun runFromTheSourceItCannotUpdateItself() {
        DesktopOs.entries.forEach { os ->
            val found = Installation.detect(os, null, emptyMap(), noFiles)
            assertTrue(found is Installation.Unsupported, "$os: $found")
            assertNull(chooseAsset(release(), found, "amd64"))
        }
    }

    @Test
    fun eachInstallGetsItsOwnKindOfFile() {
        val r = release()
        assertEquals("Agents-1.0.2-mac-arm64.dmg", chooseAsset(r, Installation.MacBundle("/Applications/Agents.app"), "aarch64")?.name)
        assertEquals("Agents-1.0.2-windows-x64.msi", chooseAsset(r, Installation.WindowsMsi("C:\\a\\Agents.exe"), "amd64")?.name)
        assertEquals("Agents-1.0.2-windows-x64-portable.zip", chooseAsset(r, Installation.WindowsPortable("D:\\Agents", "D:\\Agents\\Agents.exe"), "amd64")?.name)
        assertEquals("Agents-1.0.2-x86_64.AppImage", chooseAsset(r, Installation.LinuxAppImage("/a/Agents.AppImage"), "amd64")?.name)
        assertEquals("agents_1.0.2_amd64.deb", chooseAsset(r, Installation.LinuxPackage("/opt/agents/bin/Agents", AssetKind.LinuxDeb), "amd64")?.name)
        assertEquals("agents-1.0.2.x86_64.rpm", chooseAsset(r, Installation.LinuxPackage("/opt/agents/bin/Agents", AssetKind.LinuxRpm), "amd64")?.name)
    }

    @Test
    fun noFileForTheWrongProcessorOrAMissingKind() {
        assertNull(chooseAsset(release(), Installation.MacBundle("/Applications/Agents.app"), "x86_64"), "an Intel Mac can't run the Apple silicon build")
        assertNull(chooseAsset(release(), Installation.LinuxAppImage("/a/Agents.AppImage"), "aarch64"))
        val macOnly = release("1.0.2", asset("Agents-1.0.2-mac-arm64.dmg", "mac-dmg"))
        assertNull(chooseAsset(macOnly, Installation.WindowsMsi("C:\\a\\Agents.exe"), "amd64"))
    }
}
