package dev.wildware.agents.app.feed

import kotlin.test.Test
import kotlin.test.assertEquals

/** A saved file's name never climbs out of Downloads or hides what the file really is. */
class SafeFileNameTest {
    private fun safe(name: String) = DesktopMediaHandler.safeFileName(name)

    @Test
    fun `text-direction tricks, Windows device names and trailing dots are taken out`() {
        // Right-to-left override: would show as "Q3 reportexe.pdf".
        assertEquals("Q3 reportfdp.exe", safe("Q3 report‮fdp.exe"))
        assertEquals("ab.pdf", safe("a​b.pdf"))
        assertEquals("_CON.txt", safe("CON.txt"))
        assertEquals("_nul", safe("nul"))
        assertEquals("report.pdf", safe("report.pdf. . "))
        assertEquals("build.log", safe("../../build.log"))
        assertEquals("Résumé – final.pdf", safe("Résumé – final.pdf"))
        assertEquals("download", safe("‮"))
    }
}
