package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.PostgresMigrations
import dev.wildware.feed.server.db.Migrations
import dev.wildware.feed.server.events.Bus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Channels and projects (migration 053, spec/12): groups, and following at three levels. */
class ChannelsTest {
    private var now = 1_763_474_000_000L
    private val db = Database.memory()
    private val bus = Bus { now }
    private val feed = Feed(db, bus) { now }
    private val work = AgentWork(db, bus) { now }
    private val subs = Subscriptions(db) { now }

    @AfterTest fun close() = db.close()

    private fun agent(id: String, name: String) =
        db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES (?, ?, ?, 1)", id, name, "hash-$id")

    private fun say(body: String, channel: String) = feed.messages.post(Messages.Post(author = "human", body = body, project = channel))

    private fun unread(agentId: String) = feed.messages.read(agentId, null, null, null).messages.map { it.body }

    private fun id(slug: String) = feed.projects.resolve(slug).id

    /** Channels a and b in group "g", c in none. Alpha has posted in a and c, so follows them from posting. */
    private fun setup(): GroupRow {
        agent("A", "alpha")
        for (name in listOf("A", "B", "C")) feed.projects.create(name, null, null)
        val group = feed.groups.create("Group", "g").first
        feed.projects.update("a", Projects.Patch(group = Given("g")))
        feed.projects.update("b", Projects.Patch(group = Given(group.id)))
        feed.updates.post(Updates.Post(project = "a", agentId = "A", body = "alpha in a"))
        feed.updates.post(Updates.Post(project = "c", agentId = "A", body = "alpha in c"))
        unread("A")
        return group
    }

    // ---- groups ---------------------------------------------------------------------------------

    @Test
    fun `a group is made once per slug, renamed, coloured, archived, and deleting it only ungroups its channels`() {
        val group = setup()
        assertEquals(group.id, feed.groups.create("Other name", "g").first.id, "idempotent on the slug")
        assertEquals(listOf(group.id, group.id, null), listOf("a", "b", "c").map { feed.projects.resolve(it).groupId })

        val renamed = feed.groups.update("g", Groups.Patch(name = "Renamed", slug = "r", status = "archived",
            theme = Given(Projects.ThemeInput(accent = Given("#f80")))))
        assertEquals(listOf("Renamed", "r", "archived"), listOf(renamed.name, renamed.slug, renamed.status))
        assertEquals("#ff8800", (renamed.theme as kotlinx.serialization.json.JsonObject)["accent"]!!.jsText())

        subs.setGroupLevel(FOUNDER_SUB, group.id, "muted")
        feed.groups.delete("r")
        assertNull(feed.groups.find(group.id))
        assertEquals(listOf(null, null, null), listOf("a", "b", "c").map { feed.projects.resolve(it).groupId })
        assertEquals(3, feed.projects.list(null).size, "the channels and their history stay")
        assertEquals("all", subs.level(FOUNDER_SUB, id("a")), "the group's level went with it")
    }

    @Test
    fun `a channel moves into a group and out again`() {
        setup()
        feed.projects.update("c", Projects.Patch(group = Given("g")))
        assertEquals("g", feed.groups.byId(feed.projects.resolve("c").groupId!!)!!.slug)
        feed.projects.update("c", Projects.Patch(group = Given(null)))
        assertNull(feed.projects.resolve("c").groupId)
        val missing = runCatching { feed.projects.update("c", Projects.Patch(group = Given("nope"))) }.exceptionOrNull() as DomainError
        assertEquals("not_found", missing.code)
    }

    // ---- the owner ------------------------------------------------------------------------------

    @Test
    fun `the owner's channel level beats the group's, and a followed thread beats both`() {
        val group = setup()
        assertEquals("all", subs.level(FOUNDER_SUB, id("a")))
        subs.setGroupLevel(FOUNDER_SUB, group.id, "muted")
        assertEquals(listOf("muted", "muted", "all"), listOf("a", "b", "c").map { subs.level(FOUNDER_SUB, id(it)) })
        assertFalse(subs.hearsUpdate(FOUNDER_SUB, id("a"), "medium"))
        assertTrue(subs.hearsUpdate(FOUNDER_SUB, id("a"), "critical"), "critical is always heard")

        subs.setLevel(FOUNDER_SUB, id("a"), "all")
        assertEquals("all", subs.level(FOUNDER_SUB, id("a")), "the channel's own setting wins")
        assertEquals("muted", subs.level(FOUNDER_SUB, id("b")))

        subs.setLevel(FOUNDER_SUB, id("a"), null)
        assertEquals("muted", subs.level(FOUNDER_SUB, id("a")), "cleared, it follows its group again")
        assertNull(subs.channelLevel(FOUNDER_SUB, id("a")))

        val update = feed.updates.post(Updates.Post(project = "b", agentId = "A", body = "card in b"))
        val thread = Subscriptions.Thread("update", update.id)
        assertFalse(subs.hears(FOUNDER_SUB, "message", id("b"), thread))
        subs.setFollow(FOUNDER_SUB, thread, id("b"), true)
        assertTrue(subs.hears(FOUNDER_SUB, "message", id("b"), thread), "a followed thread is heard in a muted group")

        subs.setGroupLevel(FOUNDER_SUB, group.id, "replies")
        assertTrue(subs.hears(FOUNDER_SUB, "request", id("b"), null))
        assertFalse(subs.hears(FOUNDER_SUB, "update", id("b"), null))
        subs.setGroupLevel(FOUNDER_SUB, group.id, null)
        assertEquals("all", subs.level(FOUNDER_SUB, id("b")))

        val choices = subs.choices(FOUNDER_SUB)
        assertEquals(emptyList<Pair<String, String>>(), choices.levels, "a cleared channel level is not a choice")
        assertEquals(emptyList<Pair<String, String>>(), choices.groups)
    }

    // ---- agents ---------------------------------------------------------------------------------

    @Test
    fun `following a group hears every channel in it, including one added later, from now on`() {
        val group = setup()
        say("before", "b")
        subs.followGroup(Subscriptions.agent("A"), group.id)
        say("in b", "b")
        assertEquals(listOf(id("a"), id("c"), id("b")).sorted(), subs.followedProjects("A")!!.sorted())
        feed.projects.create("D", null, null)
        feed.projects.update("d", Projects.Patch(group = Given("g")))
        say("in d", "d")
        assertEquals(2, work.countWork("A").unreadMessages)
        assertEquals(listOf("in b", "in d"), unread("A"), "not the history before it followed")
    }

    @Test
    fun `leaving a group quiets the channels the agent only posted in, but not one it chose`() {
        val group = setup()
        subs.unfollowGroup(Subscriptions.agent("A"), group.id)
        assertEquals(listOf(id("c")), subs.followedProjects("A"), "a, which alpha only posted in, gives way to the group")
        say("in a", "a")
        say("in c", "c")
        assertEquals(listOf("in c"), unread("A"))

        subs.followProject(Subscriptions.agent("A"), id("a"), chosen = true)
        say("in a again", "a")
        assertEquals(listOf("in a again"), unread("A"), "a channel it chose beats the group it left")

        // Posting in a channel of the group it left does not follow the channel back.
        feed.updates.post(Updates.Post(project = "b", agentId = "A", body = "alpha in b"))
        say("in b", "b")
        assertEquals(emptyList<String>(), unread("A"))
    }

    @Test
    fun `a channel the agent unsubscribed from stays quiet inside a group it follows`() {
        val group = setup()
        subs.followGroup(Subscriptions.agent("A"), group.id)
        assertTrue(id("b") in subs.followedProjects("A")!!)
        subs.unfollowProject(Subscriptions.agent("A"), id("b"))
        assertFalse(id("b") in subs.followedProjects("A")!!)
        say("in b", "b")
        say("in a", "a")
        assertEquals(listOf("in a"), unread("A"))
    }

    @Test
    fun `an agent that has followed nothing hears everything, and leaving a group then changes nothing`() {
        agent("N", "newcomer")
        feed.projects.create("A", null, null)
        val group = feed.groups.create("G", null).first
        assertNull(subs.followedProjects("N"))
        assertFalse(subs.unfollowGroup(Subscriptions.agent("N"), group.id))
        assertFalse(subs.unfollowProject(Subscriptions.agent("N"), id("a")))
        assertNull(subs.followedProjects("N"), "still hears every channel")
        say("hello", "a")
        assertEquals(listOf("hello"), unread("N"))
    }

    @Test
    fun `the agent's channel list and its unread messages agree`() {
        val group = setup()
        subs.followGroup(Subscriptions.agent("A"), group.id)
        subs.unfollowProject(Subscriptions.agent("A"), id("a"))
        for (c in listOf("a", "b", "c")) say("in $c", c)
        val heard = subs.followedProjects("A")!!.map { feed.projects.byId(it)!!.slug }.sorted()
        assertEquals(listOf("b", "c"), heard)
        assertEquals(listOf("in b", "in c"), unread("A"))
    }

    // ---- migration 053 on existing data ---------------------------------------------------------

    @Test
    fun `migration 053 keeps every project as a channel and marks the choices it can be sure of`() {
        for (engine in listOfNotNull("sqlite", "postgres".takeIf { !System.getenv("PG_TEST_URL").isNullOrBlank() })) {
            atMigration52(engine).use { old ->
                old.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A', 'alpha', 'h', 1)")
                old.update(
                    "INSERT INTO projects (id, slug, name, description, status, pinned, created_at, updated_at, theme) " +
                        "VALUES ('P1', 'one', 'One', 'd', 'archived', 1, 1, 2, '{\"accent\":\"#ff8800\"}'), ('P2', 'two', 'Two', NULL, 'active', 0, 3, 4, NULL)",
                )
                old.update(
                    "INSERT INTO project_subscriptions (id, subscriber, project_id, level, after_seq, created_at, unsubscribed_at) VALUES " +
                        "('s1', 'human', 'P1', 'muted', 0, 1, NULL), ('s2', 'agent:A', 'P1', 'all', 0, 1, NULL), ('s3', 'agent:A', 'P2', 'all', 0, 1, 5)",
                )
                val before = old.query("SELECT id, slug, name, description, status, pinned, created_at, updated_at, theme FROM projects ORDER BY id") {
                    (1..9).map { i -> it.getString(i) }
                }
                if (engine == "sqlite") Migrations.run(old) else PostgresMigrations.run(old)

                assertEquals(before, old.query("SELECT id, slug, name, description, status, pinned, created_at, updated_at, theme FROM projects ORDER BY id") {
                    (1..9).map { i -> it.getString(i) }
                }, "$engine: every project is a channel, untouched")
                assertEquals(listOf(null, null), old.query("SELECT group_id FROM projects ORDER BY id") { it.getString(1) })
                assertEquals(listOf("s1" to 1, "s2" to 0, "s3" to 1), old.query("SELECT id, chosen FROM project_subscriptions ORDER BY id") {
                    it.getString(1) to it.getInt(2)
                }, "$engine: the owner's levels and an agent's unsubscribe were chosen; following from posting was not")
                assertEquals(0, old.one("SELECT count(*) FROM project_groups") { it.getInt(1) })

                val s = Subscriptions(old) { now }
                assertEquals("muted", s.level(FOUNDER_SUB, "P1"))
                assertEquals(listOf("P1"), s.followedProjects("A"))
            }
        }
    }

    private fun atMigration52(engine: String): Database {
        if (engine == "sqlite") return Database.sqliteMemory(upTo = 52)
        val url = System.getenv("PG_TEST_URL")!!
        val schema = "t_" + java.util.UUID.randomUUID().toString().replace("-", "")
        Database.postgresConnection(url, null).use { c -> c.createStatement().use { it.execute("CREATE SCHEMA \"$schema\"") } }
        return Database.openPostgres(url, migrate = false, schema = schema, onClose = {
            Database.postgresConnection(url, null).use { c -> c.createStatement().use { it.execute("DROP SCHEMA \"$schema\" CASCADE") } }
        }).also { PostgresMigrations.run(it, upTo = 52) }
    }
}

private fun kotlinx.serialization.json.JsonElement.jsText() = (this as kotlinx.serialization.json.JsonPrimitive).content

/** The founding owner, as a subscriber (migration 055). */
private const val FOUNDER_SUB = "member:founder"
