package dev.wildware.agents.android

import dev.wildware.agents.app.desktop.DesktopNotice
import dev.wildware.agents.app.sound.Sound
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelsTest {
    private fun notice(sound: Sound?, critical: Boolean = false) = DesktopNotice("k", "t", "b", null, critical = critical, sound = sound)

    @Test
    fun eachNoticeGoesToTheChannelWithItsSound() {
        assertEquals(Channels.Questions, Channels.of(notice(Sound.Question)))
        assertEquals(Channels.Joins, Channels.of(notice(Sound.Permission)))
        assertEquals(Channels.Replies, Channels.of(notice(Sound.Message)))
        assertEquals(Channels.Mentions, Channels.of(notice(Sound.Mention)))
        // Marked critical but not allowed to page this phone: an ordinary question.
        assertEquals(Channels.Questions, Channels.of(notice(Sound.Critical)))
        assertEquals(Channels.Critical, Channels.of(notice(Sound.Critical, critical = true)))
        assertEquals(Channels.Critical, Channels.of(notice(Sound.CriticalError, critical = true)))
        assertEquals(Channels.Replies, Channels.of(notice(null)))
    }

    @Test
    fun everyChannelSoundIsBundled() {
        for ((channel, name) in Channels.Sounds) {
            assertTrue(File("src/main/res/raw/$name.ogg").isFile, "$channel plays res/raw/$name.ogg")
        }
        val kept = File("src/main/res/raw/keep.xml").readText()
        for (name in Channels.Sounds.values) assertTrue("@raw/$name" in kept, "keep.xml keeps $name")
    }

    @Test
    fun aRetiredChannelIsNeverOneInUse() {
        // Android never changes a channel's sound: a new sound needs a new id, and the old one goes.
        for (channel in Channels.Sounds.keys) assertTrue(channel !in Channels.Retired, channel)
    }
}
