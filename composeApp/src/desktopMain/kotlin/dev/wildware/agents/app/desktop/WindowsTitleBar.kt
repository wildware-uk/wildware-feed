package dev.wildware.agents.app.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.delay

/**
 * On Windows 10 and 11 the window's title bar is drawn by the system, white unless asked: this asks
 * for the dark frame when the app is dark, and on Windows 11 for the app's own colour. Everywhere
 * else it does nothing (macOS already follows the system).
 */
object WindowsTitleBar {
    /** `DWMWA_USE_IMMERSIVE_DARK_MODE`, and the number Windows 10 used for it before build 18985. */
    const val DarkMode = 20
    const val DarkModeBefore20H1 = 19

    /** `DWMWA_CAPTION_COLOR`: Windows 11 only; Windows 10 refuses it, which is harmless. */
    const val CaptionColour = 35

    /** One attribute to set, and the older number to try when Windows refuses the first. */
    data class Step(val attribute: Int, val fallback: Int?, val value: Int)

    /** What to set for [osName], light or [dark], with [caption] (ARGB) as the bar's colour if given. */
    fun plan(osName: String, dark: Boolean, caption: Int?): List<Step> {
        if (!osName.startsWith("Windows", ignoreCase = true)) return emptyList()
        return listOfNotNull(
            Step(DarkMode, DarkModeBefore20H1, if (dark) 1 else 0),
            caption?.let { Step(CaptionColour, null, colorRef(it)) },
        )
    }

    /** ARGB as a Windows COLORREF: 0x00BBGGRR. */
    fun colorRef(argb: Int): Int = ((argb and 0xFF) shl 16) or (argb and 0xFF00) or ((argb shr 16) and 0xFF)

    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwm? by lazy { runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() }

    /** Applies [plan] to [window] once it is on screen. Never throws: at worst the bar stays as it was. */
    suspend fun apply(window: java.awt.Window, dark: Boolean, caption: Int?) {
        val steps = plan(System.getProperty("os.name").orEmpty(), dark, caption)
        if (steps.isEmpty()) return
        // The handle exists only once the window has been shown.
        repeat(100) { if (!window.isDisplayable) delay(50) }
        val api = dwm ?: return
        val hwnd = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return
        for (step in steps) {
            val set = { attribute: Int -> runCatching { api.DwmSetWindowAttribute(hwnd, attribute, IntByReference(step.value), 4) }.getOrDefault(-1) }
            if (set(step.attribute) != 0) step.fallback?.let(set)
        }
    }
}
