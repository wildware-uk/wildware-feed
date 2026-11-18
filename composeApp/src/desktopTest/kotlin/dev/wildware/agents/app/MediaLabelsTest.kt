package dev.wildware.agents.app

import dev.wildware.agents.app.feed.MediaLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How long a sound or video is, and how big a file is, said the way the website says it. */
class MediaLabelsTest {
    @Test
    fun durationsReadAsMinutesAndSeconds() {
        assertEquals("0:42", MediaLabels.duration(42_000))
        assertEquals("12:05", MediaLabels.duration(725_000))
        assertEquals("0:01", MediaLabels.duration(600))
        assertNull(MediaLabels.duration(0))
        assertNull(MediaLabels.duration(null))
    }

    @Test
    fun sizesUseTheUsualShortUnits() {
        assertEquals("empty", MediaLabels.size(0))
        assertEquals("512 B", MediaLabels.size(512))
        assertEquals("1.5 KB", MediaLabels.size(1536))
        assertEquals("15 MB", MediaLabels.size(15L * 1024 * 1024))
        assertEquals("2.0 GB", MediaLabels.size(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun aFileWithNoNameIsUntitled() {
        assertEquals("Untitled file", MediaLabels.fileName(null))
        assertEquals("Untitled file", MediaLabels.fileName("  "))
        assertEquals("build.log", MediaLabels.fileName(" build.log "))
    }
}
