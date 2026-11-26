package dev.wildware.agents.app.sound

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The grid, the moments, and the files behind them. */
class SoundsTest {
    private val resources = File("src/commonMain/composeResources/files/sounds")

    @Test
    fun `each priority and level has its own grid sound`() {
        val levels = mapOf("info" to "info", "success" to "success", "warn" to "warning", "error" to "error")
        val grid = buildSet {
            for (priority in listOf("low", "medium", "high", "critical")) {
                for ((level, column) in levels) {
                    val sound = Sound.grid(priority, level)
                    assertEquals("$priority-$column", sound.file)
                    assertEquals(priority == "critical", sound.critical, sound.name)
                    add(sound)
                }
            }
        }
        assertEquals(16, grid.size)
    }

    @Test
    fun `an update with no priority is medium and with no level is info`() {
        assertEquals(Sound.MediumInfo, Sound.grid(null, null))
        assertEquals(Sound.MediumError, Sound.grid(null, "error"))
        assertEquals(Sound.HighInfo, Sound.grid("high", null))
        assertEquals(Sound.MediumInfo, Sound.grid("urgent", "loud"))
    }

    @Test
    fun `each moment has its sound`() {
        val moments = mapOf(
            Sound.Question to "question",
            Sound.Answered to "answered",
            Sound.Message to "message",
            Sound.Done to "done",
            Sound.Mention to "mention",
            Sound.Permission to "permission",
            Sound.Critical to "critical",
            Sound.Reaction to "reaction",
            Sound.Uploaded to "uploaded",
            Sound.Error to "error",
        )
        moments.forEach { (sound, file) -> assertEquals(file, sound.file) }
        assertEquals(26, moments.size + Sound.entries.count { '-' in it.file })
        assertEquals(Sound.entries.toSet(), moments.keys + Sound.entries.filter { '-' in it.file })
        assertEquals(setOf(Sound.Answered, Sound.Uploaded, Sound.Error), Sound.entries.filter { it.own }.toSet())
    }

    @Test
    fun `a critical page outranks everything and each sound has its own rank`() {
        assertEquals(Sound.Critical, Sound.entries.maxBy { it.rank })
        assertEquals(Sound.entries.size, Sound.entries.map { it.rank }.toSet().size)
        assertTrue(Sound.Question.rank > Sound.HighError.rank)
        assertTrue(Sound.HighInfo.rank > Sound.MediumError.rank)
        assertTrue(Sound.MediumInfo.rank > Sound.LowError.rank)
    }

    @Test
    fun `every sound is a 16-bit mono WAV in the resources and the rest wait for later`() {
        for (sound in Sound.entries) {
            val file = File(resources, "${sound.file}.wav")
            assertTrue(file.isFile, sound.resource)
            val header = file.readBytes().copyOf(36)
            assertEquals("RIFF", header.decodeToString(0, 4))
            assertEquals(1, header[22].toInt(), "${sound.file} is mono")
            assertEquals(16, header[34].toInt(), "${sound.file} is 16-bit")
        }
        // Shipped for moments the app doesn't have yet: agents coming and going, a send, thinking.
        val unused = resources.list()!!.map { it.removeSuffix(".wav") }.toSet() - Sound.entries.map { it.file }.toSet()
        assertEquals(setOf("online", "offline", "sent", "thinking"), unused)
    }
}
