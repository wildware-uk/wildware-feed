package dev.wildware.agents.app.session

import dev.wildware.agents.MemoryOutboxStorage
import dev.wildware.agents.MemoryTextStore
import dev.wildware.agents.Outbox
import dev.wildware.agents.OutboxStorage
import dev.wildware.agents.SignIn
import dev.wildware.agents.TextStore
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * One team this device is signed in to: its sign-in, what to call it, and when it was last open.
 * [key] names the team's own files on this device; the team the app had before it knew about
 * teams keeps the files it always had, and its key is empty.
 */
@Serializable
data class SavedTeam(
    val key: String,
    val session: SavedSession,
    val name: String,
    /** When it was last the open team, in milliseconds since 1970. */
    val lastUsed: Long = 0,
)

/** A team the web app opened in another tab: only where it is and what to call it, never a key. */
@Serializable
data class TeamLink(val server: String, val name: String)

/** Every team on this device, and which one is open. [links]: other teams' tabs, in the web app. */
@Serializable
data class SavedTeams(val active: String? = null, val teams: List<SavedTeam> = emptyList(), val links: List<TeamLink> = emptyList()) {
    val activeTeam: SavedTeam? get() = teams.firstOrNull { it.key == active }
}

/** What one team keeps on this device besides its key. [outbox] holds what waits to send while another team is open. */
class TeamFiles(
    val readMarks: TextStore,
    val feedCache: TextStore,
    val cardFolds: TextStore,
    val postTarget: TextStore,
    val outbox: OutboxStorage,
    /** Forgets all of it, when the device signs out of the team. */
    val clear: () -> Unit,
) {
    companion object {
        /** All in memory: for tests, and platforms that keep one team. */
        fun inMemory(): TeamFiles = TeamFiles(MemoryTextStore(), MemoryTextStore(), MemoryTextStore(), MemoryTextStore(), MemoryOutboxStorage(), clear = {})
    }
}

/**
 * The teams this device is signed in to, kept in [store] (keys included, so the store must be
 * private: a 0600 file, the Android Keystore's box, the iPhone's Keychain). The open team's
 * sign-in is kept in [sessions] as well, exactly as when the app knew only one: the iPhone's
 * widgets, share sheet, Siri, alerts and watch read it there.
 */
class TeamList(
    private val sessions: SessionStore,
    private val store: TextStore,
    private val clock: () -> Long,
    private val newKey: () -> String = { randomKey() },
) {
    private var cached: SavedTeams? = null

    /**
     * Every team, reading the store once. A device from before teams has only [sessions]: that
     * one becomes the first team, with the files it already has. If [sessions] holds a sign-in the
     * list doesn't (another part of the app signed in), that one is added and opened.
     */
    fun load(): SavedTeams {
        cached?.let { return it }
        val kept = store.read()?.takeIf { it.isNotBlank() }?.let { text ->
            runCatching { SessionJson.decodeFromString(SavedTeams.serializer(), text) }.getOrNull()
        }
        val current = runCatching { sessions.load() }.getOrNull()
        var teams = kept ?: SavedTeams(
            active = current?.let { "" },
            teams = listOfNotNull(current?.let { SavedTeam("", it, nameFor(it), clock()) }),
        )
        if (current != null && teams.activeTeam?.session != current) {
            val same = teams.teams.firstOrNull { sameServer(it.session.server, current.server) }
            teams = if (same != null) {
                teams.copy(active = same.key, teams = teams.teams.map { if (it.key == same.key) it.copy(session = current) else it })
            } else {
                val key = if (teams.teams.none { it.key == "" }) "" else newKey()
                teams.copy(active = key, teams = teams.teams + SavedTeam(key, current, nameFor(current), clock()))
            }
        }
        // Nothing kept and nothing signed in: write nothing, so a store that couldn't be read
        // (a locked Keychain) is never overwritten with an empty list. A missing [sessions] key with
        // teams kept is the same: the list stands, and opening a team puts its key back.
        if (teams != kept && teams.teams.isNotEmpty()) write(teams) else cached = teams
        return teams
    }

    /** The team signed in to [server], if any. */
    fun find(server: String): SavedTeam? = load().teams.firstOrNull { sameServer(it.session.server, server) }

    /**
     * Adds a sign-in, marked open; [activate] then puts its key in [sessions]. Signing in again to a team already here replaces that team's
     * sign-in and keeps its files; the sign-in it had is returned, so its device can be ended.
     */
    fun add(session: SavedSession, name: String? = null): Pair<SavedTeam, SavedSession?> {
        val teams = load()
        val same = teams.teams.firstOrNull { sameServer(it.session.server, session.server) }
        val team = same?.copy(session = session, name = name ?: same.name, lastUsed = clock())
            ?: SavedTeam(if (teams.teams.none { it.key == "" }) "" else newKey(), session, name ?: nameFor(session), clock())
        val others = teams.teams.filter { it.key != team.key }
        write(teams.copy(active = team.key, teams = others + team))
        return team to same?.session?.takeIf { it != session }
    }

    /** Opens the team [key]. */
    fun activate(key: String): SavedTeam? {
        val teams = load()
        val team = teams.teams.firstOrNull { it.key == key }?.copy(lastUsed = clock()) ?: return null
        write(teams.copy(active = key, teams = teams.teams.map { if (it.key == key) team else it }))
        sessions.save(team.session)
        return team
    }

    /** The open team's sign-in changed (the server on this computer moved to another port). */
    fun update(key: String, session: SavedSession) {
        val teams = load()
        write(teams.copy(teams = teams.teams.map { if (it.key == key) it.copy(session = session) else it }))
        if (teams.active == key) sessions.save(session)
    }

    /**
     * Forgets the team [key]. When it was the open one, none is open until the next [activate]; the
     * key in [sessions] is cleared only when no team is left, since the next one replaces it.
     */
    fun remove(key: String) {
        val teams = load()
        val left = teams.teams.filter { it.key != key }
        write(teams.copy(active = if (teams.active == key) null else teams.active, teams = left))
        if (left.isEmpty()) sessions.clear()
    }

    /** Remembers a team the web app opened in another tab. */
    fun addLink(server: String, name: String) {
        val teams = load()
        if (teams.links.any { sameServer(it.server, server) }) return
        write(teams.copy(links = teams.links + TeamLink(server, name)))
    }

    fun removeLink(server: String) {
        val teams = load()
        write(teams.copy(links = teams.links.filter { !sameServer(it.server, server) }))
    }

    private fun write(teams: SavedTeams) {
        cached = teams
        runCatching { store.write(SessionJson.encodeToString(SavedTeams.serializer(), teams)) }
    }

    companion object {
        /** A team's name from its address, when nothing better is known: `https://acme.example.com` is "acme.example.com". */
        fun nameFor(session: SavedSession): String {
            if (session.local) return "This computer"
            return hostOf(session.server)
        }

        internal fun hostOf(server: String): String =
            server.substringAfter("://").substringBefore('/').substringBefore('?').ifEmpty { server }

        fun sameServer(a: String, b: String): Boolean =
            runCatching { SignIn.normalise(a) == SignIn.normalise(b) }.getOrDefault(a == b)

        private fun randomKey(): String = (1..12).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")
    }
}

/**
 * Moves everything waiting in [from] to the end of [to], files and all: the open team's outbox
 * is parked in its own place while another team is open, and comes back when it is opened again.
 */
fun moveOutbox(from: OutboxStorage, to: OutboxStorage) {
    var moved = emptyList<dev.wildware.agents.OutboxItem>()
    from.update { text ->
        moved = Outbox.decode(text)
        if (moved.isEmpty()) text else Outbox.encode(emptyList())
    }
    if (moved.isEmpty()) return
    moved.flatMap { it.files }.forEach { file ->
        from.readFile(file.name)?.let { to.writeFile(file.name, it) }
        from.deleteFile(file.name)
    }
    to.update { text ->
        val there = Outbox.decode(text)
        val known = there.map { it.id }.toSet()
        Outbox.encode(there + moved.filter { it.id !in known })
    }
}

/** Empties [outbox], files and all. */
fun clearOutbox(outbox: OutboxStorage) {
    var gone = emptyList<dev.wildware.agents.OutboxItem>()
    outbox.update { text ->
        gone = Outbox.decode(text)
        if (gone.isEmpty()) text else Outbox.encode(emptyList())
    }
    gone.flatMap { it.files }.forEach { outbox.deleteFile(it.name) }
}
