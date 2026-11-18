package dev.wildware.agents.app

import dev.wildware.agents.app.feed.FormField
import dev.wildware.agents.app.feed.QuestionKind
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
import dev.wildware.agents.app.feed.Gallery
import dev.wildware.agents.app.feed.MenuTarget
import dev.wildware.agents.app.feed.PendingFile
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
        val images = state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().first { it.attachments.isNotEmpty() }.attachments
        shoot("phone-light-gesture-swipe", Size.Phone, dark = false, preview = ScreenPreview(questionSwipe = 1.15f))
        shoot("phone-dark-gesture-menu", Size.Phone, dark = true, preview = ScreenPreview(menu = MenuTarget(fix.id, fix.agent.name, fix.title, own = false)))
        shoot("phone-light-gesture-drawer", Size.Phone, dark = false, preview = ScreenPreview(drawerOpen = true))
        shoot("phone-dark-gesture-thread", Size.Phone, dark = true, preview = ScreenPreview(threadOpen = true, replySwipe = "r3"))
        shoot("phone-dark-gesture-viewer", Size.Phone, dark = true, preview = ScreenPreview(viewer = Gallery(images, index = 1)))
    }

    /** "All projects" on a phone, where each card has to say which project it is from. */
    @Test
    fun allProjects() {
        for (dark in listOf(false, true)) {
            for (startAt in listOf(null, "u3")) {
                val name = "phone-${if (dark) "dark" else "light"}-all-projects" + if (startAt != null) "-scrolled" else ""
                shoot(name, Size.Phone, dark) {
                    AgentsPreview(dark = dark, state = FakeFeed.allProjects, safeArea = Size.Phone.safeArea, startAt = startAt)
                }
            }
        }
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

    /** Search and commands open, with a search typed; a card with three pictures; files waiting in the composer. */
    @Test
    fun polish() {
        shoot("desktop-dark-command-bar", Size.Desktop, dark = true, preview = ScreenPreview(command = "watch"))
        shoot("phone-dark-command-bar", Size.Phone, dark = true, preview = ScreenPreview(command = ""))
        shoot("desktop-light-picture-grid", Size.Desktop, dark = false, startAt = "u2")
        shoot("phone-dark-picture-grid", Size.Phone, dark = true, startAt = "u2")
        val files = listOf(
            PendingFile("build-times.png", "image/png", samplePng()),
            PendingFile("gradle-profile.html", "text/html", ByteArray(10)),
        )
        shoot("desktop-dark-attachments", Size.Desktop, dark = true, startAt = "o1", preview = ScreenPreview(composerFiles = files))
    }

    /** A small chart-like picture, so attachment thumbnails have something real to show. */
    private fun samplePng(): ByteArray {
        val image = java.awt.image.BufferedImage(240, 160, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(0x1B1F3A), 240f, 160f, java.awt.Color(0x3A6B5C))
        g.fillRect(0, 0, 240, 160)
        g.color = java.awt.Color(0xC6F169)
        listOf(40, 70, 55, 100, 130).forEachIndexed { i, h -> g.fillRect(20 + i * 44, 150 - h, 28, h) }
        g.dispose()
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
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

    /** A form question: the agent's draft in a named field, and the agent's own actions. */
    @Test
    fun formQuestion() {
        val form = FormField(
            label = "Commit message",
            initial = "Fix unread counts across sessions\n\nEach connection now keeps its own read marks.",
            multiline = true,
            min = 10,
            max = 500,
            actions = listOf("Commit", "Commit and push", "Discard"),
        )
        fun FeedItem.asForm() = if (this is FeedItem.Question && id == "q1") {
            copy(prompt = "Commit with this message?", body = "Edit it if you like.", choices = emptyList(), allowsText = false, kind = QuestionKind.Form, form = form)
        } else {
            this
        }
        val state = FakeFeed.state.let { it.copy(sections = it.sections.map { section -> section.copy(items = section.items.map { item -> item.asForm() }) }) }
        for (size in Size.entries) {
            for (dark in listOf(false, true)) {
                shoot("${size.label}-${if (dark) "dark" else "light"}-form", size, dark) {
                    AgentsPreview(dark = dark, state = state, safeArea = size.safeArea, startAt = "q1")
                }
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
            // Pictures decode off the main thread, so give them a moment between frames.
            var t = 0L
            repeat(8) {
                scene.render(t)
                t += 16_000_000L
                Thread.sleep(40)
            }
            val image = scene.render(t)
            val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("could not encode $name")
            val file = File(outDir, "$name.png")
            file.writeBytes(png.bytes)
            assertTrue(file.length() > 0, "$name is empty")
            println("screenshot: ${file.absolutePath}")
        }
    }
}
