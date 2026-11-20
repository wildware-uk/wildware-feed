package dev.wildware.agents.app

import dev.wildware.agents.app.feed.CardFolds
import dev.wildware.agents.app.feed.LocalCardFolds
import dev.wildware.agents.app.feed.Choice
import dev.wildware.agents.app.feed.PickBounds
import dev.wildware.agents.app.feed.Reply
import dev.wildware.agents.app.feed.Unsent
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
import dev.wildware.agents.HostCheck
import dev.wildware.agents.SignInMethod
import dev.wildware.agents.app.signin.ConnectState
import dev.wildware.agents.app.signin.HostedField
import dev.wildware.agents.app.signin.HostedProblem
import dev.wildware.agents.app.signin.HostedState
import dev.wildware.agents.app.signin.HostedStep
import dev.wildware.agents.app.signin.NotAFeedServer
import dev.wildware.agents.app.signin.SignInChoice
import dev.wildware.agents.app.signin.SignInScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.agents.app.feed.AgentDetails
import dev.wildware.agents.app.feed.Bell
import dev.wildware.agents.app.feed.BellItem
import dev.wildware.agents.app.feed.Acks
import dev.wildware.agents.app.feed.Author
import dev.wildware.agents.app.feed.FeedSection
import dev.wildware.agents.app.feed.VoiceInput
import dev.wildware.agents.app.feed.LocalVoiceInput
import dev.wildware.agents.app.feed.Attachment
import dev.wildware.agents.app.feed.Scene
import dev.wildware.agents.app.feed.FakeFeed
import dev.wildware.agents.app.feed.Presence
import dev.wildware.agents.app.feed.FeedFilter
import dev.wildware.agents.app.feed.StatusPick
import dev.wildware.agents.app.feed.WorkStatus
import dev.wildware.agents.app.feed.FileAttachment
import dev.wildware.agents.app.feed.Sound
import dev.wildware.agents.app.feed.VideoSource
import dev.wildware.agents.AlertPrefs
import dev.wildware.agents.MediaRetention
import dev.wildware.agents.RetentionForm
import dev.wildware.agents.RetentionPeriod
import dev.wildware.agents.removedMediaLabel
import dev.wildware.agents.app.feed.RetentionSettings
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

    /**
     * "Connect to a host" on the sign-in screen: the address (step 1), the host's login (step 2),
     * and an address that isn't a Feed server. The desktop shows its tabs; a phone has none.
     * Nothing reaches a network.
     */
    @Test
    fun signIn() {
        val host = HostCheck.Feed("https://dashboard.example.com", listOf(SignInMethod.Password))
        for (size in Size.entries) {
            for (dark in listOf(false, true)) {
                val name = "${size.label}-${if (dark) "dark" else "light"}-sign-in"
                val desktop = size != Size.Phone
                val steps = listOf(
                    name to { ConnectState() },
                    "$name-step-2" to { ConnectState("dashboard.example.com", host = host) },
                    "$name-not-a-feed-server" to { ConnectState("example.com", problem = NotAFeedServer) },
                )
                for ((shot, start) in steps) {
                    shoot(shot, size, dark) {
                        AgentTheme(dark) {
                            SignInScreen(
                                deviceName = if (desktop) "Alex's MacBook" else "Alex's iPhone",
                                notice = null,
                                safeArea = size.safeArea,
                                tryDemo = {},
                                signInHere = if (desktop) ({}) else null,
                                startWith = SignInChoice.Host,
                                connect = remember { start() },
                                checkHost = { error("no network here") },
                            ) { _, _, _ -> }
                        }
                    }
                }
            }
        }
    }

    /**
     * "Log in", the hosted service: the email, the code, a wrong code, the team picker (one team
     * read-only) and an email with no team yet. Nothing reaches a network.
     */
    @Test
    fun hostedLogin() {
        val flow = dev.wildware.agents.HostedLoginFlow(
            dev.wildware.agents.HostedAccount("https://account.example", { error("no network here") }),
            { error("no network here") },
        )
        val teams = dev.wildware.agents.HostedTeams(
            listOf(
                dev.wildware.agents.HostedTeam("acme", "Acme", "https://acme.feed.wildware.dev", "owner"),
                dev.wildware.agents.HostedTeam("harbor", "Harbor Studio", "https://harbor.feed.wildware.dev", "member"),
                dev.wildware.agents.HostedTeam("globex", "Globex", "https://globex.feed.wildware.dev", "member", readOnly = true),
            ),
            partial = false,
        )
        val email = "ada@example.com"
        val steps = listOf<Pair<String, () -> HostedState>>(
            "email" to { HostedState() },
            "code" to { HostedState(email, HostedStep.Code) },
            "wrong-code" to { HostedState(email, HostedStep.Code, problem = HostedProblem(HostedField.Code, "That code isn't right. Check the email and try again.")) },
            "teams" to { HostedState(email, HostedStep.Teams, teams) },
            "no-teams" to { HostedState(email, HostedStep.Teams, dev.wildware.agents.HostedTeams(emptyList(), false)) },
            "too-many-tries" to { HostedState(email, problem = HostedProblem(HostedField.General, "Too many tries. Wait 15 minutes, then try again.")) },
        )
        for (size in listOf(Size.Phone, Size.Desktop)) {
            for (dark in listOf(false, true)) {
                val desktop = size != Size.Phone
                for ((step, start) in steps) {
                    shoot("${size.label}-${if (dark) "dark" else "light"}-hosted-login-$step", size, dark) {
                        AgentTheme(dark) {
                            SignInScreen(
                                deviceName = if (desktop) "Ada's MacBook" else "Ada's iPhone",
                                notice = null,
                                safeArea = size.safeArea,
                                tryDemo = {},
                                signInHere = if (desktop) ({}) else null,
                                checkHost = { error("no network here") },
                                hosted = flow,
                                joinHosted = { _, _ -> },
                                hostedState = remember { start() },
                            ) { _, _, _ -> }
                        }
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

    /** The phone's message box open, with its close button, and then the mic held with the words showing as they are said. */
    @Test
    fun composerOpenAndListening() {
        val talking = object : VoiceInput {
            override fun start(onHeard: (words: String) -> Unit, onStarted: (problem: String?) -> Unit) {
                onStarted(null)
                onHeard("Can you check why the Mac build got slower this week and")
            }
            override fun finish(onDone: (text: String?, problem: String?) -> Unit) = Unit
            override fun cancel() = Unit
        }
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            shoot("phone-$mode-composer-open", Size.Phone, dark) {
                AgentsPreview(dark = dark, safeArea = Size.Phone.safeArea, preview = ScreenPreview(composerOpen = true, composerText = "Half a thought about the release notes"))
            }
            shoot("phone-$mode-live-words", Size.Phone, dark) {
                CompositionLocalProvider(LocalVoiceInput provides talking) {
                    AgentsPreview(dark = dark, safeArea = Size.Phone.safeArea, preview = ScreenPreview(composerListening = true))
                }
            }
        }
    }

    /** The owner's post that no agent has, and a reply of theirs in the open thread that no agent has. */
    @Test
    fun notDelivered() {
        val base = FakeFeed.state
        val post = base.sections.flatMap { it.items }.filterIsInstance<FeedItem.Post>().first()
        val lonely = post.copy(acks = Acks(), undelivered = true)
        val thread = base.openThread as FeedItem.Update
        val mine = thread.replies.indexOfFirst { it.author is Author.Owner }
        val openThread = thread.copy(replies = thread.replies.mapIndexed { i, r -> if (i == mine) r.copy(undelivered = true) else r })
        val state = base.copy(
            sections = base.sections.map { s -> s.copy(items = s.items.map { if (it.id == post.id) lonely else if (it.id == thread.id) openThread else it }) },
            openThread = openThread,
        )
        for (size in Size.entries) {
            for (dark in listOf(false, true)) {
                shoot("${size.label}-${if (dark) "dark" else "light"}-not-delivered", size, dark) {
                    AgentsPreview(dark = dark, state = state, safeArea = size.safeArea, startAt = post.id)
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

    /** Media the owner's retention removed: two of three tiles, a sound and a file, each a quiet placeholder. */
    @Test
    fun removedMedia() {
        val update = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Update>().first { it.attachments.isNotEmpty() }
        val gone = update.copy(
            attachments = listOf(
                update.attachments[0].copy(altText = removedMediaLabel(30), removedAfterDays = 30),
                update.attachments[1],
                update.attachments[2].copy(title = "Walkthrough", altText = removedMediaLabel(30), removedAfterDays = 30),
            ),
            sounds = listOf(Sound("s1", "", 9_000, "audio/mpeg", removedAfterDays = 30)),
            files = listOf(FileAttachment("f1", "", "crash-report.log", "48 KB", "text/plain", removedAfterDays = 30)),
        )
        val state = FakeFeed.state.let { s -> s.copy(owner = Author.Owner("Alex"), sections = s.sections.map { section -> section.copy(items = section.items.map { if (it.id == update.id) gone else it }) }) }
        for (size in listOf(Size.Phone, Size.Desktop)) {
            for (dark in listOf(false, true)) {
                shoot("${size.label}-${if (dark) "dark" else "light"}-removed-media", size, dark) {
                    AgentsPreview(dark = dark, state = state, safeArea = size.safeArea, startAt = update.id)
                }
            }
        }
    }

    /** "Keep media": the way in from the account sheet, the setting, and its confirm step, on a phone and a desktop. */
    @Test
    fun keepMedia() {
        val state = FakeFeed.state.copy(
            owner = Author.Owner("Alex"),
            alerts = AlertSettings(loaded = true),
            retention = RetentionSettings(MediaRetention(), loaded = true, allowed = true),
        )
        val draft = RetentionForm.of(MediaRetention()).choose(RetentionPeriod.Preset(30)).toggle(MediaRetention.Audio)
        for (size in listOf(Size.Phone, Size.Desktop)) {
            for (dark in listOf(false, true)) {
                val mode = "${size.label}-${if (dark) "dark" else "light"}"
                shoot("$mode-keep-media-entry", size, dark) {
                    AgentsPreview(dark = dark, state = state, safeArea = size.safeArea, preview = ScreenPreview(alertsOpen = true))
                }
                shoot("$mode-keep-media", size, dark) {
                    AgentsPreview(dark = dark, state = state, safeArea = size.safeArea, preview = ScreenPreview(retentionOpen = true, retentionDraft = draft))
                }
                shoot("$mode-keep-media-confirm", size, dark) {
                    AgentsPreview(dark = dark, state = state, safeArea = size.safeArea, preview = ScreenPreview(retentionOpen = true, retentionDraft = draft.copy(confirming = true)))
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

    /** Replies, each its own item: inline under its card on a desktop, and the phone's thread. */
    @Test
    fun threadReplies() {
        val thread = FakeFeed.state.openThread!!.id
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            shoot("desktop-$mode-replies", Size.Desktop, dark, startAt = thread)
            // Not replying yet: the Reply action sits above the newest reply.
            shoot("desktop-$mode-replies-closed", Size.Desktop, dark) {
                AgentsPreview(dark = dark, state = FakeFeed.state.copy(openThread = null), startAt = thread)
            }
            // A short head card, so the replies are on screen.
            val head = (FakeFeed.state.openThread as FeedItem.Update).copy(body = "Two sessions on one token shared a \"last read\" marker. Now each connection has its own.")
            val state = FakeFeed.state.copy(
                openThread = head,
                sections = FakeFeed.state.sections.map { sec -> sec.copy(items = sec.items.map { if (it.id == head.id) head else it }) },
            )
            shoot("phone-$mode-replies", Size.Phone, dark) {
                AgentsPreview(dark = dark, state = state, safeArea = Size.Phone.safeArea, preview = ScreenPreview(threadOpen = true))
            }
        }
    }

    /** A post with a video, a sound and a file, and a reply with a video: as the feed and the phone thread show them. */
    @Test
    fun postMedia() {
        val post = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Post>().first()
        val clip = Attachment("Screen recording", "Screen recording", 16f / 9f, scene = Scene.Dusk, video = VideoSource("https://dash.test/media/v1/video", "0:12"))
        val withMedia = post.copy(
            attachments = listOf(clip),
            sounds = listOf(Sound("s1", "https://dash.test/media/s1/original", 9_000, "audio/mpeg")),
            files = listOf(FileAttachment("f1", "https://dash.test/media/f1/original", "crash.log", "1.5 KB", "text/plain")),
        )
        val fix = FakeFeed.state.openThread as FeedItem.Update
        // A short head card, so the reply with the video is on screen in the phone's thread.
        val thread = fix.copy(
            body = "Two sessions on one token shared a \"last read\" marker. Now each connection has its own.",
            replies = fix.replies.mapIndexed { i, r -> if (i == fix.replies.lastIndex) r.copy(attachments = listOf(clip.copy(scene = Scene.Night))) else r },
        )
        val state = FakeFeed.state.copy(
            openThread = thread,
            sections = FakeFeed.state.sections.map { s -> s.copy(items = s.items.map { if (it.id == post.id) withMedia else if (it.id == thread.id) thread else it }) },
        )
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            for (size in listOf(Size.Phone, Size.Desktop)) {
                shoot("${size.label}-$mode-post-media", size, dark) { AgentsPreview(dark = dark, state = state, safeArea = size.safeArea, startAt = post.id) }
            }
            shoot("phone-$mode-reply-media", Size.Phone, dark) {
                AgentsPreview(dark = dark, state = state, safeArea = Size.Phone.safeArea, preview = ScreenPreview(threadOpen = true, replySwipe = null))
            }
        }
    }

    /** A question being talked over before it is answered: inline on a desktop; the card and its thread on a phone. */
    @Test
    fun questionDiscussion() {
        val asked = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first()
        val talk = listOf(
            Reply("d1", Author.AgentAuthor(asked.agent), "10:01", "Happy to wait if you want the numbers first."),
            Reply("d2", FakeFeed.state.owner, "10:03", "What did the test group say about the volume?", acks = Acks(thinking = listOf(asked.agent))),
        )
        val q = asked.copy(replies = talk)
        val state = FakeFeed.state.copy(
            sections = FakeFeed.state.sections.map { sec -> sec.copy(items = sec.items.map { if (it.id == q.id) q else it }) },
            openThread = q,
        )
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            shoot("desktop-$mode-question-talk", Size.Desktop, dark) {
                AgentsPreview(dark = dark, state = state, safeArea = Size.Desktop.safeArea)
            }
            shoot("phone-$mode-question-talk", Size.Phone, dark) {
                AgentsPreview(dark = dark, state = state, safeArea = Size.Phone.safeArea)
            }
            shoot("phone-$mode-question-thread", Size.Phone, dark) {
                AgentsPreview(dark = dark, state = state, safeArea = Size.Phone.safeArea, preview = ScreenPreview(threadOpen = true))
            }
        }
    }



    /** A multi-choice question with bounds: the hint above Send, one pick short (Send off) and then too many (hint in red). */
    @Test
    fun multiChoiceBounds() {
        val base = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first()
        val q = base.copy(
            prompt = "Which platforms should the beta go to first?", body = "Pick two or three; the rest wait a week.",
            kind = QuestionKind.MultiChoice, choices = listOf("iPhone", "Android", "Mac", "Windows").map { Choice(it) },
            picks = PickBounds(2, 3, bounded = true),
        )
        val state = FakeFeed.state.copy(sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(q))))
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            shoot("phone-$mode-multi-bounds", Size.Phone, dark) {
                AgentsPreview(dark = dark, state = state, safeArea = Size.Phone.safeArea, preview = ScreenPreview(picked = mapOf(q.id to setOf("iPhone"))))
            }
            shoot("desktop-$mode-multi-bounds", Size.Desktop, dark) {
                AgentsPreview(dark = dark, state = state, safeArea = Size.Desktop.safeArea, preview = ScreenPreview(picked = mapOf(q.id to setOf("iPhone", "Android", "Mac", "Windows"))))
            }
        }
    }



    /** Text questions as the agent set them up: a placeholder in a several-line box, and an answer that is too short. */
    @Test
    fun textAnswerSettings() {
        val base = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first()
        fun text(id: String, prompt: String, field: FormField) =
            base.copy(id = id, prompt = prompt, body = "", kind = QuestionKind.Text, choices = emptyList(), allowsText = true, text = field)
        val notes = text("qt1", "What should go in the release notes?", FormField(placeholder = "A few lines for the App Store", multiline = true, min = 1))
        val name = text("qt2", "What should the release be called?", FormField(initial = "Jun", min = 5, max = 20))
        val state = FakeFeed.state.copy(sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(notes, name))))
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            shoot("phone-$mode-text-answer", Size.Phone, dark) { AgentsPreview(dark = dark, state = state, safeArea = Size.Phone.safeArea) }
            shoot("desktop-$mode-text-answer", Size.Desktop, dark) { AgentsPreview(dark = dark, state = state, safeArea = Size.Desktop.safeArea) }
        }
    }



    /** Folded cards: the long update and a post, each down to its header and one line, beside open ones. */
    @Test
    fun foldedCards() {
        val fix = FakeFeed.state.openThread!!
        val post = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Post>().first()
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            for (size in listOf(Size.Phone, Size.Desktop)) {
                val folds = CardFolds(dev.wildware.agents.MemoryTextStore()).apply { set(fix.id, true); set(post.id, true) }
                // A folded question keeps its header and question; its answers wait until it is opened.
                val question = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first()
                val foldedQuestion = CardFolds(dev.wildware.agents.MemoryTextStore()).apply { set(question.id, true) }
                shoot("${size.label}-$mode-folded-question", size, dark) {
                    CompositionLocalProvider(LocalCardFolds provides foldedQuestion) { AgentsPreview(dark = dark, safeArea = size.safeArea) }
                }
                shoot("${size.label}-$mode-folded", size, dark) {
                    CompositionLocalProvider(LocalCardFolds provides folds) {
                        AgentsPreview(dark = dark, safeArea = size.safeArea, startAt = fix.id)
                    }
                }
            }
        }
    }



    /** A link's card wearing its ring, and the notice when a link points at nothing. */
    @Test
    fun deepLinkFocus() {
        val fix = FakeFeed.state.openThread!!.id
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            for (size in listOf(Size.Phone, Size.Desktop)) {
                shoot("${size.label}-$mode-link-ring", size, dark, startAt = fix, preview = ScreenPreview(highlighted = fix))
                shoot("${size.label}-$mode-link-missing", size, dark, preview = ScreenPreview(missingLink = true))
            }
        }
    }



    /** A choice question opens on the agent's default: that option is picked and Send is ready. */
    @Test
    fun choiceDefault() {
        val base = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first()
        val q = base.copy(
            prompt = "Which branch should the release go out from?", body = "I'd go with main unless you say otherwise.",
            kind = QuestionKind.Choice, choices = listOf("main", "release/2.4", "hotfix").map { Choice(it) }, preset = "main",
        )
        val state = FakeFeed.state.copy(sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(q))))
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            shoot("phone-$mode-choice-default", Size.Phone, dark) { AgentsPreview(dark = dark, state = state, safeArea = Size.Phone.safeArea) }
            shoot("desktop-$mode-choice-default", Size.Desktop, dark) { AgentsPreview(dark = dark, state = state, safeArea = Size.Desktop.safeArea) }
        }
    }

    /** An answer the dashboard refused: the card says why, keeps the words in the box, and offers Try again or Drop it. */
    @Test
    fun unsentAnswer() {
        val base = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first()
        val q = base.copy(
            prompt = "What should the release notes lead with?", body = "", kind = QuestionKind.Text, choices = emptyList(), allowsText = true,
            text = FormField(placeholder = "A line or two"),
            unsent = Unsent("o1", "The question was already answered on another device.", "Faster sync on slow networks", "Faster sync on slow networks"),
        )
        val state = FakeFeed.state.copy(sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(q))))
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            shoot("phone-$mode-unsent-answer", Size.Phone, dark) { AgentsPreview(dark = dark, state = state, safeArea = Size.Phone.safeArea) }
            shoot("desktop-$mode-unsent-answer", Size.Desktop, dark) { AgentsPreview(dark = dark, state = state, safeArea = Size.Desktop.safeArea) }
        }
    }

    /** Cards and a reply the agent rewrote say "edited" after their time, as on the website. */
    @Test
    fun editedMarks() {
        val items = FakeFeed.state.sections.flatMap { it.items }
        val q = items.filterIsInstance<FeedItem.Question>().first().copy(edited = "Edited Wednesday 18 November 2026 at 10:05")
        val u = items.filterIsInstance<FeedItem.Update>().first { it.replies.isNotEmpty() }.let { u ->
            u.copy(edited = "Edited Wednesday 18 November 2026 at 10:20", replies = u.replies.mapIndexed { i, r -> if (i == 0) r.copy(edited = "Edited Wednesday 18 November 2026 at 10:21") else r })
        }
        val state = FakeFeed.state.copy(sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(q, u))))
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            shoot("phone-$mode-edited", Size.Phone, dark) { AgentsPreview(dark = dark, state = state, safeArea = Size.Phone.safeArea) }
            shoot("desktop-$mode-edited", Size.Desktop, dark) { AgentsPreview(dark = dark, state = state, safeArea = Size.Desktop.safeArea) }
        }
    }

    /** The owner's own post carries an accent rail down its left edge; an agent's post does not. */
    @Test
    fun ownPosts() {
        val posts = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Post>()
        val mine = posts.first { it.author is Author.Owner }
        val theirs = mine.copy(
            id = "p-agent", author = Author.AgentAuthor(FakeFeed.state.agents.first()), acks = Acks(),
            body = "Found it: the Mac build re-signs every framework twice. A fix is on its way.",
        )
        val state = FakeFeed.state.copy(sections = listOf(FeedSection("Today", listOf(mine, theirs))))
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            shoot("phone-$mode-own-post", Size.Phone, dark) { AgentsPreview(dark = dark, state = state, safeArea = Size.Phone.safeArea) }
            shoot("desktop-$mode-own-post", Size.Desktop, dark) { AgentsPreview(dark = dark, state = state, safeArea = Size.Desktop.safeArea) }
        }
    }

    /**
     * Times as the website says them ("updated 5m ago", "3h ago"), and a day heading held at the top
     * while its cards scroll under it.
     */
    @Test
    fun dayHeadingsAndTimes() {
        val times = mapOf("u1" to "updated 5m ago", "u2" to "2h ago", "o1" to "3h ago", "u3" to "updated 40m ago", "u4" to "1d ago")
        val sections = FakeFeed.state.sections.filter { s -> s.items.none { it is FeedItem.Question } }.map { section ->
            section.copy(items = section.items.map { item ->
                val time = times[item.id] ?: return@map item
                when (item) {
                    is FeedItem.Update -> item.copy(time = time)
                    is FeedItem.Post -> item.copy(time = time)
                    else -> item
                }
            })
        }
        val state = FakeFeed.state.copy(sections = sections)
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            shoot("phone-$mode-day-heading", Size.Phone, dark) { AgentsPreview(dark = dark, state = state, safeArea = Size.Phone.safeArea, startAt = "u2") }
            shoot("desktop-$mode-day-heading", Size.Desktop, dark) { AgentsPreview(dark = dark, state = state, safeArea = Size.Desktop.safeArea, startAt = "o1") }
        }
    }

    /** A question wearing what any card can: a pin, tags, details and reactions. */
    @Test
    fun questionExtras() {
        val base = FakeFeed.state.sections.flatMap { it.items }.filterIsInstance<FeedItem.Question>().first()
        val q = base.copy(
            pinned = true,
            tags = listOf(dev.wildware.agents.app.feed.CardTag("t1", "release", "#2563eb"), dev.wildware.agents.app.feed.CardTag("t2", "sounds")),
            fields = listOf(dev.wildware.agents.app.feed.CardField("branch", "feat/reaction-sounds"), dev.wildware.agents.app.feed.CardField("test group", "120 people")),
            reactions = listOf(dev.wildware.agents.app.feed.Reaction("👀", 1, mine = true, spoken = "eyes"), dev.wildware.agents.app.feed.Reaction("🎉", 2, mine = false, spoken = "party")),
        )
        val state = FakeFeed.state.copy(sections = listOf(FakeFeed.state.sections.first().copy(items = listOf(q))))
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            shoot("phone-$mode-question-extras", Size.Phone, dark) { AgentsPreview(dark = dark, state = state, safeArea = Size.Phone.safeArea) }
            shoot("desktop-$mode-question-extras", Size.Desktop, dark) { AgentsPreview(dark = dark, state = state, safeArea = Size.Desktop.safeArea) }
        }
    }

    /** Reading further down while three updates arrived: they wait, and the pill says so. */
    @Test
    fun newUpdatesPill() {
        val state = FakeFeed.state.copy(newCount = 3)
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            shoot("phone-$mode-new-updates", Size.Phone, dark) { AgentsPreview(dark = dark, state = state, safeArea = Size.Phone.safeArea, startAt = "u2") }
            shoot("desktop-$mode-new-updates", Size.Desktop, dark) { AgentsPreview(dark = dark, state = state, safeArea = Size.Desktop.safeArea, startAt = "o1") }
        }
    }

    /** A card that just moved to the top, wearing the ring that fades a moment later. */
    @Test
    fun bumpedCard() {
        for (dark in listOf(false, true)) {
            val mode = if (dark) "dark" else "light"
            shoot("phone-$mode-bumped", Size.Phone, dark, startAt = "u1", preview = ScreenPreview(bumped = setOf("u1")))
            shoot("desktop-$mode-bumped", Size.Desktop, dark, startAt = "u1", preview = ScreenPreview(bumped = setOf("u1")))
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
