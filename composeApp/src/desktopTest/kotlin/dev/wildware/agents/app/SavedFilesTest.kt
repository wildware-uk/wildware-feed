package dev.wildware.agents.app

import dev.wildware.agents.app.desktop.DesktopOs
import dev.wildware.agents.app.feed.SavedFiles
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where each desktop keeps downloads, and the command that shows a saved file in its folder. */
class SavedFilesTest {
    private val home: File = Files.createTempDirectory("home").toFile()

    @Test
    fun windowsReadsDownloadsFromTheRegistryWithItsVariablesFilledIn() {
        val output = """

            HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders
                {374DE290-123F-4565-9164-39C4925E467B}    REG_EXPAND_SZ    %USERPROFILE%\Downloads

        """.trimIndent()
        val env = mapOf("USERPROFILE" to "C:\\Users\\Sam")
        assertEquals(File("C:\\Users\\Sam\\Downloads"), SavedFiles.windowsDownloads(output, env::get))
    }

    @Test
    fun windowsFollowsDownloadsMovedToAnotherDrive() {
        val output = "    {374DE290-123F-4565-9164-39C4925E467B}    REG_SZ    D:\\My Downloads\n"
        assertEquals(File("D:\\My Downloads"), SavedFiles.windowsDownloads(output) { null })
    }

    @Test
    fun windowsGivesUpOnAVariableItCannotFillOrNoValue() {
        assertNull(SavedFiles.windowsDownloads("    {374DE290-123F-4565-9164-39C4925E467B}    REG_EXPAND_SZ    %ONEDRIVE%\\Downloads") { null })
        assertNull(SavedFiles.windowsDownloads("ERROR: The system was unable to find the specified registry key or value.") { null })
    }

    @Test
    fun windowsUsesTheRegistryFolderWhenItIsThere() {
        val moved = File(home, "elsewhere").apply { mkdirs() }
        val asked = mutableListOf<List<String>>()
        val found = SavedFiles.downloads(DesktopOs.Windows, home, env = { null }) { command ->
            asked += command
            "    {374DE290-123F-4565-9164-39C4925E467B}    REG_SZ    ${moved.path}"
        }
        assertEquals(moved, found)
        assertEquals(listOf(SavedFiles.windowsQuery), asked)
    }

    @Test
    fun withNoDownloadsFolderFilesGoHome() {
        assertEquals(home, SavedFiles.downloads(DesktopOs.Windows, home, env = { null }) { null })
        File(home, "Downloads").mkdirs()
        assertEquals(File(home, "Downloads"), SavedFiles.downloads(DesktopOs.Mac, home, env = { null }) { null })
    }

    @Test
    fun linuxReadsTheXdgDownloadFolder() {
        assertEquals(File(home, "Téléchargements"), SavedFiles.linuxDownloads("XDG_DESKTOP_DIR=\"\$HOME/Desktop\"\nXDG_DOWNLOAD_DIR=\"\$HOME/Téléchargements\"\n", home))
        assertEquals(File("/data/dl"), SavedFiles.linuxDownloads("XDG_DOWNLOAD_DIR=\"/data/dl\"", home))
        assertNull(SavedFiles.linuxDownloads("XDG_DOWNLOAD_DIR=\"\$HOME/\"", home), "home itself is not a downloads folder")
        assertNull(SavedFiles.linuxDownloads("# nothing set", home))
    }

    @Test
    fun linuxFindsItFromUserDirs() {
        val config = File(home, ".config").apply { mkdirs() }
        val dl = File(home, "Downloads-here").apply { mkdirs() }
        File(config, "user-dirs.dirs").writeText("XDG_DOWNLOAD_DIR=\"\$HOME/Downloads-here\"\n")
        assertEquals(dl, SavedFiles.downloads(DesktopOs.Linux, home, env = { null }) { null })
    }

    @Test
    fun eachSystemShowsTheFileInItsFolderNeverOpensIt() {
        val file = File("/tmp/My Files/report.pdf")
        assertEquals(listOf(listOf("explorer.exe", "/select,${file.absolutePath}")), SavedFiles.revealCommands(DesktopOs.Windows, file))
        assertEquals(listOf(listOf("open", "-R", file.absolutePath)), SavedFiles.revealCommands(DesktopOs.Mac, file))
        val linux = SavedFiles.revealCommands(DesktopOs.Linux, file)
        assertEquals("dbus-send", linux[0][0])
        assertTrue("array:string:file:/tmp/My%20Files/report.pdf" in linux[0], linux[0].toString())
        assertEquals(listOf("xdg-open", "/tmp/My Files"), linux[1], "the fallback opens the folder, not the file")
    }

    @Test
    fun linuxOpensTheFolderWhenNoFileManagerAnswers() {
        val ran = mutableListOf<String>()
        val shown = SavedFiles.reveal(File("/tmp/report.pdf"), DesktopOs.Linux) { command ->
            ran += command[0]
            ProcessBuilder(if (command[0] == "dbus-send") "false" else "true").start()
        }
        assertTrue(shown)
        assertEquals(listOf("dbus-send", "xdg-open"), ran)
    }

    @Test
    fun linuxStopsAtTheFileManagerWhenItAnswers() {
        val ran = mutableListOf<String>()
        SavedFiles.reveal(File("/tmp/report.pdf"), DesktopOs.Linux) { command -> ran += command[0]; ProcessBuilder("true").start() }
        assertEquals(listOf("dbus-send"), ran)
    }

    @Test
    fun windowsIsNotJudgedByExplorersAnswer() {
        // explorer answers 1 even when it worked.
        assertTrue(SavedFiles.reveal(File("C:\\a.pdf"), DesktopOs.Windows) { ProcessBuilder("false").start() })
        assertEquals(false, SavedFiles.reveal(File("C:\\a.pdf"), DesktopOs.Windows) { null })
    }
}
