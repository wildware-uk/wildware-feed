package dev.wildware.agents.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import dev.wildware.agents.app.design.AccessibilityPrefs
import dev.wildware.agents.app.design.AgentTheme
import dev.wildware.agents.app.signin.SignInScreen
import androidx.compose.runtime.Composable
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.FeedItem
import dev.wildware.agents.app.feed.MenuTarget
import dev.wildware.agents.app.feed.SafeArea
import dev.wildware.agents.app.feed.ScreenPreview
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Draws the feed at each size and theme and saves each one as a PNG, so the screens can be
 * checked without a device.
 */
class ScreenshotTest {
    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    private enum class Size(val label: String, val width: Int, val height: Int, val scale: Float, val safeArea: SafeArea) {
        Desktop("desktop", 1440, 900, 2f, SafeArea()),
        Phone("phone", 390, 844, 3f, SafeArea(top = 54.dp, bottom = 34.dp)),
    }

    @Test
    fun everySizeAndTheme() {
        for (size in Size.entries) {
            for (dark in listOf(false, true)) {
                shoot("${size.label}-${if (dark) "dark" else "light"}", size, dark)
            }
        }
    }

    /** Scrolled down to the code block, the image and the owner's own post. */
    @Test
    fun scrolledFurther() {
        shoot("desktop-light-scrolled", Size.Desktop, dark = false, startAt = "u1")
        shoot("phone-dark-scrolled", Size.Phone, dark = true, startAt = "u2")
    }

    /** The phone mid-gesture: a swiped question, the long-press menu, the drawer, a thread, the image viewer. */
    @Test
    fun gestures() {
        val state = FakeFeed.state
        val fix = state.openThread as FeedItem.Update
        val image = state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().firstNotNullOf { it.attachment }
        shoot("phone-light-gesture-swipe", Size.Phone, dark = false, preview = ScreenPreview(questionSwipe = 1.15f))
        shoot("phone-dark-gesture-menu", Size.Phone, dark = true, preview = ScreenPreview(menu = MenuTarget(fix.id, fix.agent.name, fix.title, own = false)))
        shoot("phone-light-gesture-drawer", Size.Phone, dark = false, preview = ScreenPreview(drawerOpen = true))
        shoot("phone-dark-gesture-thread", Size.Phone, dark = true, preview = ScreenPreview(threadOpen = true, replySwipe = "r3"))
        shoot("phone-dark-gesture-viewer", Size.Phone, dark = true, preview = ScreenPreview(viewer = image))
    }

    /** The sign-in screen, before anything is typed. Nothing reaches a network. */
    @Test
    fun signIn() {
        for (size in Size.entries) {
            for (dark in listOf(false, true)) {
                shoot("${size.label}-${if (dark) "dark" else "light"}-sign-in", size, dark) {
                    AgentTheme(dark) {
                        SignInScreen(deviceName = "Alex's MacBook", notice = null, safeArea = size.safeArea) { _, _, _ -> }
                    }
                }
            }
        }
    }

    /** A question answered by picture, with two pictures picked. */
    @Test
    fun pictureQuestion() {
        val preview = ScreenPreview(picked = mapOf("q2" to setOf("Dusk", "Night")))
        for (size in Size.entries) {
            for (dark in listOf(false, true)) {
                shoot("${size.label}-${if (dark) "dark" else "light"}-pictures", size, dark, startAt = "q2", preview = preview)
            }
        }
    }

    /** Increase Contrast on, in both themes, and the largest common text size. */
    @Test
    fun accessibilityVariants() {
        shoot("phone-light-high-contrast", Size.Phone, dark = false, prefs = AccessibilityPrefs(highContrast = true))
        shoot("phone-dark-high-contrast", Size.Phone, dark = true, prefs = AccessibilityPrefs(highContrast = true))
        shoot("desktop-dark-high-contrast", Size.Desktop, dark = true, prefs = AccessibilityPrefs(highContrast = true))
        shoot("phone-dark-large-text", Size.Phone, dark = true, fontScale = 1.35f)
        shoot("phone-light-large-text", Size.Phone, dark = false, fontScale = 1.35f)
    }

    private fun shoot(
        name: String,
        size: Size,
        dark: Boolean,
        prefs: AccessibilityPrefs = AccessibilityPrefs(),
        fontScale: Float = 1f,
        startAt: String? = null,
        preview: ScreenPreview = ScreenPreview(),
        content: @Composable () -> Unit = {
            AgentsPreview(dark = dark, accessibility = prefs, safeArea = size.safeArea, startAt = startAt, preview = preview)
        },
    ) {
        val px = { dp: Int -> (dp * size.scale).toInt() }
        ImageComposeScene(px(size.width), px(size.height), Density(size.scale, fontScale)) {
            CompositionLocalProvider(LocalDensity provides Density(size.scale, fontScale)) { content() }
        }.use { scene ->
            // A few frames let fonts, layout and anything launched on first composition settle.
            var t = 0L
            repeat(4) { scene.render(t); t += 16_000_000L }
            val image = scene.render(t)
            val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("could not encode $name")
            val file = File(outDir, "$name.png")
            file.writeBytes(png.bytes)
            assertTrue(file.length() > 0, "$name is empty")
            println("screenshot: ${file.absolutePath}")
        }
    }
}
