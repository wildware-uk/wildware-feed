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
import dev.wildware.agents.app.feed.AgentDetails
import dev.wildware.agents.app.feed.Bell
import dev.wildware.agents.app.feed.BellItem
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.Presence
import dev.wildware.agents.app.feed.FeedFilter
import dev.wildware.agents.app.feed.StatusPick
import dev.wildware.agents.app.feed.WorkStatus
import dev.wildware.agents.app.feed.FileAttachment
import dev.wildware.agents.app.feed.Sound
import dev.wildware.agents.app.feed.VideoSource
import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.app.feed.AlertSettings
import dev.wildware.agents.app.feed.LocalPushAlerts
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
        Wide("desktop-1920", 1920, 1080, 1f, SafeArea()),
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

    /** Waiting on you: a thread question at the top, the drawer's counts, the question under its reply, desktop's view. */
    @Test
    fun waitingOnYou() {
        val state = FakeFeed.allProjects
        val fix = state.sections.flatMap { it.items }.first { it.id == "u1" } as dev.wildware.agents.app.feed.Threaded
        fun at(content: @Composable () -> Unit): @Composable () -> Unit = {
            CompositionLocalProvider(dev.wildware.agents.app.feed.LocalNow provides { FakeFeed.Now }) { content() }
        }
        shoot("phone-dark-waiting", Size.Phone, dark = true, content = at {
            AgentsPreview(dark = true, state = state, safeArea = Size.Phone.safeArea)
        })
        shoot("phone-light-waiting-drawer", Size.Phone, dark = false, content = at {
            AgentsPreview(dark = false, state = state, safeArea = Size.Phone.safeArea, preview = ScreenPreview(drawerOpen = true))
        })
        shoot("phone-dark-waiting-thread", Size.Phone, dark = true, content = at {
            AgentsPreview(dark = true, state = state.copy(openThread = fix), safeArea = Size.Phone.safeArea, preview = ScreenPreview(threadOpen = true, replySwipe = "r1"))
        })
        shoot("desktop-dark-waiting", Size.Desktop, dark = true, content = at {
            AgentsPreview(dark = true, state = state, safeArea = Size.Desktop.safeArea)
        })
    }

    /** Scrolled into the questions: the "Waiting on you (3)" heading holds the top. */
    @Test
    fun stickyWaiting() {
        val now = dev.wildware.agents.app.feed.LocalNow provides { FakeFeed.Now }
        shoot("phone-dark-sticky-waiting", Size.Phone, dark = true) {
            CompositionLocalProvider(now) {
                AgentsPreview(dark = true, state = FakeFeed.allProjects, safeArea = Size.Phone.safeArea, startAt = "q1")
            }
        }
        shoot("desktop-light-sticky-waiting", Size.Desktop, dark = false) {
            CompositionLocalProvider(now) {
                AgentsPreview(dark = false, state = FakeFeed.allProjects, safeArea = Size.Desktop.safeArea, startAt = "q2")
            }
        }
    }

    /** The first load: grey stand-ins in the feed's shape, not a "Loading" line. */
    @Test
    fun firstLoad() {
        val loading = FakeFeed.state.copy(loaded = false, sections = emptyList(), openThread = null)
        for (dark in listOf(false, true)) {
            shoot("phone-${if (dark) "dark" else "light"}-loading", Size.Phone, dark) {
                AgentsPreview(dark = dark, state = loading, safeArea = Size.Phone.safeArea)
            }
        }
    }

    /** The high-priority disk update, on a phone and on desktop. */
    @Test
    fun highPriority() {
        shoot("phone-dark-high-priority", Size.Phone, dark = true, startAt = "u3")
        shoot("desktop-light-high-priority", Size.Desktop, dark = false, startAt = "u3")
    }


    /** The card menu offering Pin, on desktop and a phone; and a pinned card above the feed. */
    @Test
    fun pinning() {
        val fix = FakeFeed.state.openThread as FeedItem.Update
        val menu = MenuTarget(fix.id, fix.agent.name, fix.title, own = false, at = androidx.compose.ui.geometry.Offset(900f, 120f), pinned = false)
        shoot("desktop-dark-pin-menu", Size.Desktop, dark = true, startAt = "u1", preview = ScreenPreview(menu = menu))
        shoot("phone-dark-pin-menu", Size.Phone, dark = true, preview = ScreenPreview(menu = menu))
        val disk = FakeFeed.state.sections.flatMap { it.items }.first { it.id == "u3" } as FeedItem.Update
        // As the engine lays it out: Waiting on you, then Pinned, then the days.
        val rest = FakeFeed.state.sections.map { s -> s.copy(items = s.items.filter { it.id != "u3" }) }
        val pinnedState = FakeFeed.state.copy(
            sections = rest.take(1) + dev.wildware.agents.app.feed.FeedSection("Pinned", listOf(disk.copy(pinned = true))) + rest.drop(1),
        )
        shoot("desktop-light-pinned", Size.Desktop, dark = false) {
            AgentsPreview(dark = false, state = pinnedState, safeArea = Size.Desktop.safeArea)
        }
        shoot("phone-light-pinned", Size.Phone, dark = false) {
            AgentsPreview(dark = false, state = pinnedState, safeArea = Size.Phone.safeArea)
        }
    }

    /** A long update in the feed: folded to a screen's worth, with the rest a tap away. */
    @Test
    fun longBody() {
        val long = (1..14).joinToString("\n\n") { i ->
            "**Step $i.** Cleared the old Gradle caches on the build box and checked the daemon logs for leaks after each run."
        }
        val state = FakeFeed.state.copy(
            openThread = null,
            sections = FakeFeed.state.sections.map { s ->
                s.copy(items = s.items.map { if (it is FeedItem.Update && it.id == "u3") it.copy(body = long) else it })
            },
        )
        shoot("phone-dark-long-body", Size.Phone, dark = true) { AgentsPreview(dark = true, state = state, safeArea = Size.Phone.safeArea, startAt = "u3") }
        shoot("desktop-light-long-body", Size.Desktop, dark = false) { AgentsPreview(dark = false, state = state, safeArea = Size.Desktop.safeArea, startAt = "u3") }
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

    /** Who is online and what their sessions say: the rail on a desktop, the drawer on a phone. */
    @Test
    fun agentDetails() {
        val facts = listOf(
            AgentDetails("claude-opus-5-5", "build-box", "/work/agent-dashboard", sessions = 2, lastSeen = "just now"),
            AgentDetails("claude-sonnet-5-5", "laptop", "/home/dev/ios-companion", sessions = 1, lastSeen = "12s ago"),
            AgentDetails("claude-haiku-5-5", "build-box", "/work/infra", sessions = 1, lastSeen = "1m ago"),
        )
        var i = 0
        val agents = FakeFeed.state.agents.map { a -> if (a.presence == Presence.Online) a.copy(details = facts[i++ % facts.size]) else a }
        val state = FakeFeed.state.copy(agents = agents)
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            shoot("desktop-$mode-agents", Size.Desktop, dark) {
                AgentsPreview(dark = dark, state = state, safeArea = Size.Desktop.safeArea)
            }
            shoot("phone-$mode-agents", Size.Phone, dark) {
                AgentsPreview(dark = dark, state = state, safeArea = Size.Phone.safeArea, preview = ScreenPreview(drawerOpen = true))
            }
        }
    }

    /** The bell open: two new rows, one read, a question, and the count on the bell. */
    @Test
    fun notificationBell() {
        val bell = Bell(
            unseen = 2,
            items = listOf(
                BellItem("n3", "relay is waiting on you", "Turn on reaction sounds for everyone?", "agent-dashboard", "10:14", unread = true, path = "/"),
                BellItem("n2", "scout replied", "Confirmed from my side: counts match after 30 minutes of real traffic.", "agent-dashboard", "10:06", unread = true, path = "/"),
                BellItem("n1", "forge posted an update", "Reading messages in one session no longer hides them from another", "ios-companion", "Yesterday", unread = false, path = "/"),
            ),
        )
        val state = FakeFeed.state.copy(bell = bell)
        for (size in Size.entries) {
            for (dark in listOf(false, true)) {
                shoot("${size.label}-${if (dark) "dark" else "light"}-bell", size, dark) {
                    AgentsPreview(dark = dark, state = state, safeArea = size.safeArea, preview = ScreenPreview(bellOpen = true))
                }
            }
        }
    }

    /** The alert settings: a phone that is pushed its alerts has every filter, a desktop two. */
    @Test
    fun alertSettings() {
        val alerts = AlertSettings(AlertPrefs(types = listOf("request", "message", "update"), levels = listOf("error", "warn")), loaded = true)
        val state = FakeFeed.state.copy(alerts = alerts)
        for (size in Size.entries) {
            for (dark in listOf(false, true)) {
                shoot("${size.label}-${if (dark) "dark" else "light"}-alerts", size, dark) {
                    CompositionLocalProvider(LocalPushAlerts provides (size == Size.Phone)) {
                        AgentsPreview(dark = dark, state = state, safeArea = size.safeArea, preview = ScreenPreview(alertsOpen = true))
                    }
                }
            }
        }
    }

    /** A card with a picture and a video side by side, a sound and a file. */
    @Test
    fun media() {
        val update = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().first { it.attachments.isNotEmpty() }
        val withMedia = update.copy(
            attachments = listOf(
                update.attachments.first(),
                update.attachments[1].copy(title = "Walkthrough", video = VideoSource("https://example.com/v.mp4", "1:24")),
            ),
            sounds = listOf(Sound("s1", "https://example.com/s.mp3", 9_000, "audio/mpeg")),
            files = listOf(FileAttachment("f1", "https://example.com/f", "crash-report.log", "48 KB", "text/plain")),
        )
        val state = FakeFeed.state.let { s -> s.copy(sections = s.sections.map { section -> section.copy(items = section.items.map { if (it.id == update.id) withMedia else it }) }) }
        for (size in Size.entries) {
            for (dark in listOf(false, true)) {
                shoot("${size.label}-${if (dark) "dark" else "light"}-media", size, dark, startAt = update.id) {
                    AgentsPreview(dark = dark, state = state, safeArea = size.safeArea, startAt = update.id)
                }
            }
        }
    }

    /** Work: the state choice open on a card, and the feed filtered to work in progress. */
    @Test
    fun workStatus() {
        for (size in Size.entries) {
            for (dark in listOf(false, true)) {
                val mode = "${size.label}-${if (dark) "dark" else "light"}"
                shoot("$mode-work-picker", size, dark, startAt = "u2", preview = ScreenPreview(statusPick = StatusPick("u2", WorkStatus.InProgress)))
                shoot("$mode-work-filter", size, dark, preview = ScreenPreview(filter = FeedFilter.InProgress))
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
