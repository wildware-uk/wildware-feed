package dev.wildware.agents.app

import dev.wildware.agents.FeedCard
import dev.wildware.agents.FeedState
import dev.wildware.agents.MessageView
import dev.wildware.agents.PostCard
import dev.wildware.agents.RequestCard
import dev.wildware.agents.RequestView
import dev.wildware.agents.StreamStatus
import dev.wildware.agents.ThreadLine
import dev.wildware.agents.UpdateCard
import dev.wildware.agents.UpdateView
import dev.wildware.agents.app.desktop.DesktopNotice
import dev.wildware.agents.app.desktop.DesktopOs
import dev.wildware.agents.app.desktop.DesktopSettings
import dev.wildware.agents.app.desktop.DesktopSettingsStore
import dev.wildware.agents.app.desktop.NotificationRules
import dev.wildware.agents.app.desktop.NotifySendNotifier
import dev.wildware.agents.app.desktop.StartAtLogin
import dev.wildware.agents.app.desktop.TrayIconImage
import dev.wildware.agents.app.desktop.TrayLook
import dev.wildware.agents.app.desktop.TrayStyle
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopIntegrationTest {
    // --- Building engine state ----------------------------------------------------------------

    private fun message(id: String, author: String, body: String, at: Long, answers: String? = null, updateId: String? = null, replyTo: String? = null) =
        MessageView(id = id, author = author, body = body, createdAt = at, answers = answers, updateId = updateId, replyTo = replyTo, projectId = "p1")

    private fun line(message: MessageView, name: String = if (message.isOwner) "You" else "Builder") =
        ThreadLine(message, name, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    private fun update(id: String, vararg replies: MessageView) = UpdateCard(
        UpdateView(id = id, seq = 1, projectId = "p1", agentId = "a1", body = "Shipped", createdAt = 1_000),
        "Builder", "Mega", false, 1_000, replies.map { line(it) }, 0, emptyList(), emptyList(), emptyList(),
    )

    private fun post(head: MessageView, vararg replies: MessageView) =
        PostCard(line(head), "Mega", head.createdAt, replies.map { line(it) }, 0, emptyList(), emptyList(), emptyList())

    private fun question(id: String, text: String = "May I deploy?", discussion: List<MessageView> = emptyList()) = RequestCard(
        RequestView(id = id, agentId = "a1", projectId = "p1", kind = "confirm", question = text),
        "Builder", "Mega", discussion.map { line(it) }, 0, false, emptyList(), emptyList(),
    )

    private fun feed(vararg cards: FeedCard) = FeedState(
        null, null, StreamStatus.Live, true, false, false, emptyList(), emptyList(), cards.toList(), 0, false, emptyList(),
    )

    // --- Notification rules -------------------------------------------------------------------

    @Test
    fun `nothing already there when the dashboard is first read is announced`() {
        val rules = NotificationRules()
        val existing = feed(update("u1", message("m1", "human", "Go", 2_000), message("m2", "agent:a1", "Done", 3_000)))

        assertTrue(rules.next(ready = false, listOf(question("r1")), existing).isEmpty())
        assertTrue(rules.next(ready = true, listOf(question("r1")), existing).isEmpty())
        assertTrue(rules.next(ready = true, listOf(question("r1")), existing).isEmpty())
    }

    @Test
    fun `a new question is announced once, titled with agent and project, in plain text`() {
        val rules = NotificationRules()
        rules.next(true, emptyList(), feed())

        val notices = rules.next(true, listOf(question("r1", "Ship **v2** to [prod](https://x)?")), feed())
        assertEquals(listOf(DesktopNotice("request-r1", "Builder · Mega", "Ship v2 to prod?", "r1")), notices)
        assertTrue(rules.next(true, listOf(question("r1")), feed()).isEmpty())
    }

    @Test
    fun `an agent's reply in a thread the owner wrote in is announced, a comment where the owner never spoke is not`() {
        val rules = NotificationRules()
        val talked = message("m1", "human", "Can you check?", 2_000)
        rules.next(true, emptyList(), feed(update("u1", talked), update("u2")))

        val notices = rules.next(true, emptyList(), feed(
            update("u1", talked, message("m2", "agent:a1", "Checked, all green", 5_000, updateId = "u1")),
            update("u2", message("m3", "agent:a1", "Also did this", 5_001, updateId = "u2")),
        ))

        assertEquals(listOf("message-m2"), notices.map { it.key })
        assertEquals("u1", notices.single().focusId)
        assertEquals("Builder · Mega", notices.single().title)
    }

    @Test
    fun `replies under the owner's post and answers to the owner's line are announced`() {
        val rules = NotificationRules()
        val mine = message("p1", "human", "Look at this", 1_000)
        val theirs = message("p2", "agent:a2", "FYI", 1_500)
        val ownerLine = message("o1", "human", "Why?", 1_600, replyTo = "p2")
        rules.next(true, emptyList(), feed(post(mine), post(theirs, ownerLine)))

        val notices = rules.next(true, emptyList(), feed(
            post(mine, message("r1", "agent:a1", "Looked", 2_000, replyTo = "p1")),
            post(theirs, ownerLine, message("r2", "agent:a2", "Because", 2_100, replyTo = "p2", answers = "o1")),
        ))

        assertEquals(listOf("message-r1", "message-r2"), notices.map { it.key })
        assertEquals(listOf("p1", "p2"), notices.map { it.focusId })
    }

    @Test
    fun `a message that mentions the owner is announced anywhere`() {
        val rules = NotificationRules()
        rules.next(true, emptyList(), feed(update("u1")))

        val notices = rules.next(true, emptyList(), feed(update("u1", message("m1", "agent:a1", "Need you here @you", 2_000))))
        assertEquals(listOf("message-m1"), notices.map { it.key })

        assertTrue(NotificationRules.mentionsOwner("@owner please look"))
        assertFalse(NotificationRules.mentionsOwner("mail me at you@example.com"))
        assertFalse(NotificationRules.mentionsOwner("@builder only"))
    }

    @Test
    fun `the owner's own lines and older history paged in are never announced`() {
        val rules = NotificationRules()
        val talked = message("m1", "human", "Go", 5_000)
        rules.next(true, emptyList(), feed(update("u1", talked)))

        val notices = rules.next(true, emptyList(), feed(
            update("u1", talked, message("m2", "human", "And this @you", 6_000)),
            update("u0", message("h1", "human", "Old", 100), message("h2", "agent:a1", "Old answer @you", 200)),
        ))
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `talk on a question the owner joined is announced`() {
        val rules = NotificationRules()
        val asked = message("q1", "human", "Why do you need it?", 2_000)
        rules.next(true, listOf(question("r1", discussion = listOf(asked))), feed())

        val notices = rules.next(true, listOf(question("r1", discussion = listOf(asked, message("q2", "agent:a1", "To migrate", 3_000)))), feed())
        assertEquals(listOf("message-q2"), notices.map { it.key })
        assertEquals("r1", notices.single().focusId)
    }

    @Test
    fun `a long body is cut with an ellipsis`() {
        val rules = NotificationRules(maxBody = 10)
        rules.next(true, emptyList(), feed())
        assertEquals("abcdefghij…", rules.next(true, listOf(question("r1", "abcdefghijklmnop")), feed()).single().body)
    }

    // --- Tray ---------------------------------------------------------------------------------

    @Test
    fun `the tray shows the waiting count, capped at 9+`() {
        assertEquals(TrayLook(null, "Agents — signed out"), TrayLook.of(null))
        assertEquals(TrayLook(null, "Agents — nothing waiting"), TrayLook.of(0))
        assertEquals(TrayLook("1", "Agents — 1 question waiting"), TrayLook.of(1))
        assertEquals(TrayLook("9+", "Agents — 12 questions waiting (offline)"), TrayLook.of(12, offline = true))
    }

    @Test
    fun `the Mac menu bar icon is the radar on nothing`() {
        val icon = TrayIconImage.draw(32, style = TrayStyle.MenuBar)
        assertEquals(0, icon.getRGB(0, 0) ushr 24, "no tile behind it")
        assertTrue(icon.getRGB(16, 16) ushr 24 > 0, "the radar's centre")
    }

    @Test
    fun `the tray icon draws with and without a badge`() {
        val plain = TrayIconImage.draw(32)
        val badged = TrayIconImage.draw(32, "3")
        assertEquals(32, plain.width)
        // The badge sits in the top right corner, where the plain icon is transparent.
        assertEquals(0, plain.getRGB(30, 1) ushr 24)
        assertTrue(badged.getRGB(26, 6) ushr 24 > 0)
    }

    // --- Start at login -----------------------------------------------------------------------

    private fun tempHome(): File = Files.createTempDirectory("agents-home").toFile().apply { deleteOnExit() }

    @Test
    fun `start at login on Linux writes an autostart entry that opens in the tray`() {
        val home = tempHome()
        val login = StartAtLogin(DesktopOs.Linux, home, launcher = "/opt/agents/bin/Agents")

        assertFalse(login.isEnabled())
        assertTrue(login.setEnabled(true))
        val entry = File(home, ".config/autostart/dev.wildware.agents.desktop.desktop").readText()
        assertTrue("Exec=/opt/agents/bin/Agents --background" in entry, entry)
        assertTrue(login.isEnabled())
        assertTrue(login.setEnabled(false))
        assertFalse(login.isEnabled())
    }

    @Test
    fun `start at login on a Mac writes a LaunchAgent`() {
        val home = tempHome()
        val login = StartAtLogin(DesktopOs.Mac, home, launcher = "/Applications/Agents.app/Contents/MacOS/Agents")

        assertTrue(login.setEnabled(true))
        val plist = File(home, "Library/LaunchAgents/dev.wildware.agents.desktop.plist").readText()
        assertTrue("<string>/Applications/Agents.app/Contents/MacOS/Agents</string>" in plist, plist)
        assertTrue("<string>--background</string>" in plist)
        assertTrue("<key>RunAtLoad</key>" in plist)
    }

    @Test
    fun `start at login on Windows sets the Run key`() {
        val commands = mutableListOf<List<String>>()
        val login = StartAtLogin(DesktopOs.Windows, tempHome(), launcher = "C:\\Program Files\\Agents\\Agents.exe") { commands += it; 0 }

        assertTrue(login.setEnabled(true))
        assertEquals(
            listOf("reg", "add", StartAtLogin.RunKey, "/v", "Agents", "/t", "REG_SZ", "/d", "\"C:\\Program Files\\Agents\\Agents.exe\" --background", "/f"),
            commands.single(),
        )
    }

    @Test
    fun `start at login is not offered when the app isn't installed`() {
        val login = StartAtLogin(DesktopOs.Linux, tempHome(), launcher = null)
        assertFalse(login.available)
        assertFalse(login.setEnabled(true))
    }

    // --- Notifications and settings ------------------------------------------------------------

    @Test
    fun `notify-send is asked to wait for a click on the notification`() {
        val notice = DesktopNotice("request-r1", "Builder · Mega", "-rf everything?", "r1")
        assertEquals(
            listOf("notify-send", "--app-name=Agents", "--icon=/tmp/i.png", "--action=default=Open", "--wait", "--", "Builder · Mega", "-rf everything?"),
            NotifySendNotifier.command(notice, "/tmp/i.png"),
        )
    }

    @Test
    fun `settings default to keep running, no login start, notifications on, and survive a bad file`() {
        val file = File(tempHome(), "desktop.json")
        val store = DesktopSettingsStore(file)
        assertEquals(DesktopSettings(keepRunningInTray = true, startAtLogin = false, doNotDisturb = false), store.load())

        store.save(DesktopSettings(keepRunningInTray = false, doNotDisturb = true))
        assertEquals(DesktopSettings(keepRunningInTray = false, doNotDisturb = true), store.load())

        file.writeText("{not json")
        assertEquals(DesktopSettings(), store.load())
    }
}
