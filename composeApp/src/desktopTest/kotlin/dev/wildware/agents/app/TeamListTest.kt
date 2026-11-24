package dev.wildware.agents.app

import dev.wildware.agents.MemoryOutboxStorage
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.Outbox
import dev.wildware.agents.OutboxFile
import dev.wildware.agents.TextStore
import dev.wildware.agents.app.session.MemorySessionStore
import dev.wildware.agents.app.session.SavedSession
import dev.wildware.agents.app.session.TeamList
import dev.wildware.agents.app.session.desktopTeamFiles
import dev.wildware.agents.app.session.moveOutbox
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The teams a device is signed in to: the one it had before teams, adding, opening and removing them, and their own files. */
class TeamListTest {
    private val a = SavedSession("https://a.test", "key-a", "Test device", deviceId = "d-a")
    private val b = SavedSession("https://b.test", "key-b", "Test device", deviceId = "d-b")
    private var now = 1_000L
    private var keys = 0

    private fun list(sessions: MemorySessionStore, store: TextStore = MemoryTextStore()) =
        TeamList(sessions, store, clock = { now++ }, newKey = { "k${++keys}" })

    @Test
    fun `a device from before teams keeps its one sign-in as the first team with its old files`() {
        val store = MemoryTextStore()
        val teams = list(MemorySessionStore(a), store).load()
        assertEquals(1, teams.teams.size)
        val first = teams.teams.single()
        assertEquals("", first.key, "the empty key: the files the device already has")
        assertEquals(a, first.session)
        assertEquals("a.test", first.name)
        assertEquals("", teams.active)
        assertTrue(store.read()!!.contains("key-a"), "the list is kept")
        // Read again from the kept list, it is the same.
        assertEquals(teams, list(MemorySessionStore(a), store).load())
    }

    @Test
    fun `a team new to the device is due the tour until it is done, and signing in again keeps that`() {
        val store = MemoryTextStore()
        val teams = list(MemorySessionStore(a), store)
        assertEquals(false, teams.load().teams.single().tour, "the team from before teams has seen the app already")
        val (added, _) = teams.add(b, "Beta")
        assertTrue(added.tour, "a first sign-in to a team shows the tour")
        teams.tourDone(added.key)
        assertEquals(false, list(MemorySessionStore(a), store).load().teams.first { it.key == added.key }.tour, "kept on the device")
        val (again, _) = teams.add(b.copy(token = "key-b2"), "Beta")
        assertEquals(false, again.tour, "signing in again to a team here doesn't bring it back")
    }

    @Test
    fun `adding a team keeps the first and marks the new one open`() {
        val sessions = MemorySessionStore(a)
        val teams = list(sessions)
        val (added, replaced) = teams.add(b, "Beta")
        assertNull(replaced)
        assertEquals("k1", added.key)
        assertEquals(listOf("a.test", "Beta"), teams.load().teams.map { it.name })
        assertEquals("k1", teams.load().active)
        assertEquals(a, sessions.load(), "the open key changes only when the team is opened")
        teams.activate("k1")
        assertEquals(b, sessions.load())
        teams.activate("")
        assertEquals(a, sessions.load(), "switching back puts the first team's key back")
    }

    @Test
    fun `signing in again to a team already here replaces its sign-in and keeps its files`() {
        val teams = list(MemorySessionStore(a))
        val again = a.copy(token = "key-a2", deviceId = "d-a2")
        val (team, replaced) = teams.add(again)
        assertEquals("", team.key)
        assertEquals(a, replaced, "the old device is handed back, to be ended")
        assertEquals(listOf(again), teams.load().teams.map { it.session })
    }

    @Test
    fun `removing teams forgets the open key only when none is left`() {
        val sessions = MemorySessionStore(a)
        val teams = list(sessions)
        teams.add(b)
        teams.activate("k1")
        teams.remove("k1")
        assertNull(teams.load().active)
        assertEquals(b, sessions.load(), "kept until the next team opens over it")
        teams.activate("")
        teams.remove("")
        assertTrue(teams.load().teams.isEmpty())
        assertNull(sessions.load())
    }

    @Test
    fun `a list that could not be read is never overwritten with nothing`() {
        val store = object : TextStore {
            var written = false
            override fun read(): String? = null
            override fun write(text: String) {
                written = true
            }
        }
        val teams = list(MemorySessionStore(), store).load()
        assertTrue(teams.teams.isEmpty())
        assertTrue(!store.written, "a locked Keychain reads as nothing; nothing is written over it")
    }

    @Test
    fun `a sign-in made elsewhere joins the list and opens`() {
        val store = MemoryTextStore()
        list(MemorySessionStore(a), store).load()
        // Something else (the old iPhone sign-in) put team B's key where the open key lives.
        val teams = list(MemorySessionStore(b), store).load()
        assertEquals(listOf(a, b), teams.teams.map { it.session })
        assertEquals(b, teams.activeTeam?.session)
    }

    @Test
    fun `an outbox moves with its files and empties where it was`() {
        val from = MemoryOutboxStorage()
        val to = MemoryOutboxStorage()
        val item = Outbox.message("o1", 1L, "Reply", mapOf("updateId" to "u1"), "hello", listOf(OutboxFile("f1", "a.png", "image/png")))
        from.save(Outbox.encode(listOf(item)))
        from.writeFile("f1", byteArrayOf(1, 2, 3))
        moveOutbox(from, to)
        assertTrue(Outbox.decode(from.load()).isEmpty())
        assertNull(from.readFile("f1"))
        assertEquals(listOf("o1"), Outbox.decode(to.load()).map { it.id })
        assertEquals(listOf<Byte>(1, 2, 3), to.readFile("f1")!!.toList())
    }

    @Test
    fun `each team's files on the desktop live apart and the first keeps the old ones`() {
        val dir = Files.createTempDirectory("teams").toFile()
        try {
            val first = desktopTeamFiles(dir, "")
            first.readMarks.write("first")
            assertEquals("first", File(dir, "read-marks.json").readText(), "the first team's read marks stay where they were")
            val other = desktopTeamFiles(dir, "k1")
            other.readMarks.write("other")
            assertEquals("other", File(dir, "teams/k1/read-marks.json").readText())
            assertEquals("first", first.readMarks.read(), "one team's marks never touch another's")
            other.clear()
            assertTrue(!File(dir, "teams/k1").exists(), "signing out of a team removes its folder")
            assertEquals("first", first.readMarks.read())
        } finally {
            dir.deleteRecursively()
        }
    }
}
