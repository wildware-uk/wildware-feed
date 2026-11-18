package dev.wildware.agents.app

import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowIconTest {
    private val loader = Thread.currentThread().contextClassLoader

    @Test
    fun theWindowIconIsTheInstallersPicture() {
        val bytes = loader.getResourceAsStream("agents.png")!!.use { it.readBytes() }
        val image = Image.makeFromEncoded(bytes)
        assertEquals(1024, image.width)
        assertEquals(1024, image.height)
    }

    @Test
    fun theInstallerOnlyIconsStayOutOfTheApp() {
        assertNull(loader.getResource("agents.icns"))
        assertNull(loader.getResource("agents.ico"))
    }
}
