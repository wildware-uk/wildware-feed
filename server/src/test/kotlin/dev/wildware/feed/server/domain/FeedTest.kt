package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.events.AppEvent
import dev.wildware.feed.server.events.Bus
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedTest {
    private var now = 1_763_474_000_000L
    private val db = Database.memory()
    private val bus = Bus { now }
    private val feed = Feed(db, bus) { now }
    private val events = mutableListOf<AppEvent>()

    init {
        bus.subscribe { events += it }
    }

    @AfterTest fun close() = db.close()

    private fun agent(id: String, name: String, revoked: Boolean = false) = db.update(
        "INSERT INTO agents (id, name, token_hash, created_at, revoked_at) VALUES (?, ?, ?, 1, ?)", id, name, "hash-$id", if (revoked) 2L else null,
    )

    private fun types() = events.map { it.type }.also { events.clear() }

    private fun fails(message: String, block: () -> Unit) {
        val error = assertFailsWith<DomainError>(block = block)
        assertEquals(message, error.message)
    }

    // ---- text rules -------------------------------------------------------------------------------

    @Test
    fun `slugs follow the JavaScript chains`() {
        assertEquals("cafe-deja-vu", Slugs.slugify("Café Déjà Vu!"))
        assertEquals("hello-world", Slugs.slugify("  --Hello__World--  "))
        assertEquals("high-priority", Slugs.tag("  High Priority "))
        assertEquals("caf", Slugs.tag("café"))
        assertEquals("", Slugs.tag("!!!"))
        assertEquals("a-b", Slugs.tag("-a - b-"))
        assertEquals("v1.2_x", Slugs.field(".V1.2_x-"))
        fails("slug must be lowercase letters, digits and single hyphens, at most 64 characters") { Slugs.assertSlug("a--b") }
    }

    @Test
    fun `emoji take shortcodes and refuse plain words`() {
        assertEquals("✅", Emoji.assert(" :tick: "))
        assertEquals("👍", Emoji.assert("+1"))
        assertEquals("🦀", Emoji.assert("🦀"))
        fails("emoji is required") { Emoji.assert("  ") }
        fails("an emoji has no spaces in it") { Emoji.assert("👍 👍") }
        fails("an emoji is at most 24 characters") { Emoji.assert("👍".repeat(13)) }
        fails("\"ok\" is not an emoji. Send the character itself, or one of: eyes, tick, check, white_check_mark, done, +1, thumbsup, -1") {
            Emoji.assert("ok")
        }
    }

    @Test
    fun `JSON quoting is JavaScript's`() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", JsText.quote("a\"b\\c\n\u0001"))
        assertEquals("\"\\ud800x\"", JsText.quote("\ud800x"))
        assertEquals("\"😀\"", JsText.quote("😀"))
    }

    @Test
    fun `mentions ignore code, case and longer names`() {
        val names = listOf("scout", "Scout-2", "bob")
        assertEquals(setOf("scout"), Mentions.mentionedNames("hey @SCOUT, look", names))
        assertEquals(emptySet(), Mentions.mentionedNames("mail a@scout or `@scout` or @scoutx", names))
        assertEquals(setOf("scout-2"), Mentions.mentionedNames("@scout-2 go", names))
        assertEquals(emptySet(), Mentions.mentionedNames("```\n@bob\n", names), "an unclosed fence runs to the end")
        assertEquals(setOf("bob"), Mentions.mentionedNames("```x``` @bob", names))
    }

    // ---- projects ---------------------------------------------------------------------------------

    @Test
    fun `a project is created once per slug`() {
        val (project, created) = feed.projects.create("  Café Déjà Vu! ", null, " d ")
        assertTrue(created)
        assertEquals("cafe-deja-vu", project.slug)
        assertEquals("Café Déjà Vu!", project.name)
        assertEquals("d", project.description)
        assertEquals(listOf("project.created"), types())
        val (again, createdAgain) = feed.projects.create("Cafe deja vu", null, "other")
        assertEquals(false, createdAgain)
        assertEquals(project.id, again.id)
        assertEquals(emptyList(), types())
        fails("could not derive a slug from name; pass slug explicitly") { feed.projects.create("!!!", null, null) }
        fails("name is required") { feed.projects.create("  ", null, null) }
    }

    @Test
    fun `a project patch checks, merges the theme and always publishes`() {
        val a = feed.projects.create("A", null, null).first
        feed.projects.create("B", null, null)
        events.clear()
        fails("update requires at least one field to change") { feed.projects.update("a", Projects.Patch()) }
        fails("no such project: nope") { feed.projects.update(" nope ", Projects.Patch()) }
        fails("slug already in use: b") { feed.projects.update("a", Projects.Patch(slug = "B")) }

        val themed = feed.projects.update(a.id, Projects.Patch(theme = Given(Projects.ThemeInput(accent = Given("#ABC"), background = Given(" #101820 ")))))
        assertEquals("""{"background":"#101820","accent":"#aabbcc"}""", Wire.encode(themed.theme))
        val cleared = feed.projects.update("a", Projects.Patch(theme = Given(Projects.ThemeInput(accent = Given(null), background = Given(null)))))
        assertEquals(JsonNull, cleared.theme)
        fails("logoReplacesName needs a logo to show instead of the name") {
            feed.projects.update("a", Projects.Patch(theme = Given(Projects.ThemeInput(logoReplacesName = Given(true)))))
        }
        fails("accent must be a hex colour like #1a2b3c") {
            feed.projects.update("a", Projects.Patch(theme = Given(Projects.ThemeInput(accent = Given("red")))))
        }
        now += 5
        val same = feed.projects.update("a", Projects.Patch(name = "A"))
        assertEquals(now, same.updatedAt)
        assertEquals(listOf("project.updated", "project.updated", "project.updated"), types())
    }

    @Test
    fun `a board is checked in the TS order`() {
        fun board(vararg columns: JsonObject) = buildJsonObject { put("columns", buildJsonArray { columns.forEach { add(it) } }) }
        fun column(title: kotlinx.serialization.json.JsonElement, vararg states: String) = buildJsonObject {
            put("title", title); put("states", buildJsonArray { states.forEach { add(JsonPrimitive(it)) } })
        }
        fails("todo is already in another column") { Projects.assertBoard(board(column(JsonPrimitive("A"), "todo"), column(JsonPrimitive("B"), "todo"))) }
        fails("columns[0].title is required") { Projects.assertBoard(board(column(JsonNull, "todo"))) }
        fails("board.columns must be a list") { Projects.assertBoard(buildJsonObject { }) }
        fails("columns[0].states must be any of: todo, claimed, done, cancelled") { Projects.assertBoard(board(column(JsonPrimitive("A"), "nope"))) }
        assertEquals("""{"columns":[{"title":"123","states":["done"]}]}""", Wire.encode(Projects.assertBoard(board(column(JsonPrimitive(123), "done")))!!))
        assertNull(Projects.assertBoard(JsonNull))
    }

    // ---- messages ---------------------------------------------------------------------------------

    private fun human(body: String, project: String? = null, replyTo: String? = null, updateId: String? = null, answers: String? = null, request: String? = null) =
        feed.messages.post(Messages.Post(author = "human", body = body, project = project, replyTo = replyTo, updateId = updateId, answers = answers, requestId = request))

    private fun mentioned(messageId: String) =
        db.query("SELECT agent_id FROM message_mentions WHERE message_id = ? ORDER BY agent_id", messageId) { it.getString(1) }

    private fun online(agentId: String, beat: Long = now) = db.update(
        "INSERT INTO sessions (id, agent_id, started_at, last_heartbeat_at) VALUES (?, ?, 1, ?)", "S-$agentId", agentId, beat,
    )

    @Test
    fun `at-everyone reaches every agent and at-here only those online now`() {
        agent("A1", "scout")
        agent("A2", "forge")
        agent("A3", "quill")
        agent("A4", "gone", revoked = true)
        feed.projects.create("P", null, null)
        online("A1")
        online("A3", beat = now - 10 * 60_000)

        assertEquals(listOf("A1", "A2", "A3"), mentioned(human("Heads up @everyone", project = "p").id))
        assertEquals(listOf("A1"), mentioned(human("Anyone around? @HERE", project = "p").id))
        assertEquals(listOf("A1", "A2"), mentioned(human("@here and @forge please", project = "p").id))
        assertEquals(emptyList(), mentioned(human("mail me at a@everyone.example or `@here`", project = "p").id))
    }

    @Test
    fun `an agent saying at-everyone is not told about its own message`() {
        agent("A1", "scout")
        agent("A2", "forge")
        feed.projects.create("P", null, null)
        online("A1")
        online("A2")
        val sent = feed.messages.post(Messages.Post(author = "agent:A1", body = "Done, @everyone", project = "p"))
        assertEquals(listOf("A2"), mentioned(sent.id))
        val here = feed.messages.post(Messages.Post(author = "agent:A2", body = "@here look", project = "p"))
        assertEquals(listOf("A1"), mentioned(here.id))
    }

    @Test
    fun `an owner's answer naming an agent reaches it outside the projects it works in, and the channel too`() {
        agent("A1", "scout")
        agent("A2", "forge")
        feed.projects.create("P", null, null)
        feed.projects.create("Q", null, null)
        // forge works in q only.
        feed.updates.post(Updates.Post(project = "q", agentId = "A2", body = "elsewhere"))
        val update = feed.updates.post(Updates.Post(project = "p", agentId = "A1", body = "u"))
        val line = feed.messages.post(Messages.Post(author = "agent:A1", body = "done", updateId = update.id))

        val answer = human("@forge can you check this", updateId = update.id, answers = line.id)
        assertEquals(line.id, answer.answers)
        assertEquals(update.id, answer.updateId)
        assertEquals(listOf("A2"), mentioned(answer.id))
        assertEquals(listOf("@forge can you check this"), feed.messages.read("A2", null, null, false).messages.map { it.body })
        // A person's @name widens: scout, who follows p, hears it as well.
        assertTrue(feed.messages.read("A1", null, null, false).messages.any { it.id == answer.id }, "the channel hears a person's @name")
    }

    @Test
    fun `replies flatten to the post and anchors are checked`() {
        agent("A1", "scout")
        val project = feed.projects.create("P", null, null).first
        feed.projects.create("Q", null, null)
        val update = feed.updates.post(Updates.Post(project = "p", agentId = "A1", body = "u"))
        events.clear()

        val post = human("  hi @Scout  ", project = "p")
        assertEquals("hi @Scout", post.body)
        assertEquals(project.id, post.projectId)
        assertEquals(listOf("A1"), db.query("SELECT agent_id FROM message_mentions WHERE message_id = ?", post.id) { it.getString(1) })
        val r1 = human("r1", replyTo = post.id)
        val r2 = human("r2", replyTo = r1.id)
        assertEquals(post.id, r2.replyTo)
        assertNull(r2.answers)
        assertEquals(project.id, r2.projectId)

        val inThread = human("c", updateId = update.id)
        val answer = human("a", replyTo = inThread.id)
        assertEquals(update.id, answer.updateId)
        assertNull(answer.replyTo)
        assertEquals(inThread.id, answer.answers)

        fails("a reply answers a message, or hangs off an update, not both") { human("x", replyTo = post.id, updateId = update.id) }
        fails("that update, task or message belongs to a different project") { human("x", updateId = update.id, project = "q") }
        fails("no such message: ") { human("x", replyTo = "") }
        assertNull(human("loose").projectId)

        val created = events.first { it.type == "message.created" }
        assertEquals(listOf("messageId", "projectId", "author", "updateId", "replyTo", "requestId", "taskId"), created.payload.keys.toList())
        events.clear()

        val deleted = feed.messages.delete(post.id, "human")
        assertEquals(now, deleted.deletedAt)
        assertEquals(JsonPrimitive(2), events.single().payload["replies"])
        events.clear()
        feed.messages.delete(post.id, "human")
        assertEquals(emptyList(), types())
    }

    @Test
    fun `an agent edits and unsends only its own`() {
        agent("A1", "scout")
        agent("A2", "builder")
        val own = feed.messages.post(Messages.Post(author = "agent:A1", body = "mine"))
        val theirs = human("owner's")
        fails("that message was posted by somebody else") { feed.messages.edit(theirs.id, "agent:A1", "x") }
        fails("that message was posted by somebody else") { feed.messages.delete(own.id, "agent:A2") }
        now += 1
        assertEquals(now, feed.messages.edit(own.id, "agent:A1", " edited ").editedAt)
        feed.messages.delete(own.id, "agent:A1")
        assertTrue(feed.messages.delete(own.id, "agent:A1").deletedAt != null, "already deleted is fine for its author")
        fails("that message was posted by somebody else") { feed.messages.delete(own.id, "agent:A2") }
        fails("no such message: ${own.id}") { feed.messages.edit(own.id, "agent:A1", "x") }
    }

    @Test
    fun `get_messages hands over unread lines once and moves the cursor`() {
        agent("A1", "scout")
        agent("A2", "builder")
        feed.projects.create("P", null, null)
        val update = feed.updates.post(Updates.Post(project = "p", agentId = "A1", body = "u"))
        feed.messages.read("A1", null, null, null)
        events.clear()

        human("owner says", updateId = update.id)
        feed.messages.post(Messages.Post(author = "agent:A2", body = "beta says", updateId = update.id))
        feed.messages.post(Messages.Post(author = "agent:A1", body = "my own"))
        events.clear()

        val page = feed.messages.read("A1", null, null, null)
        assertEquals(listOf("owner says", "beta says"), page.messages.map { it.body })
        assertEquals(0, page.unread)
        assertEquals(page.messages.last().seq.toString(), page.cursor)
        assertEquals("messages.read", events.single().type)
        assertEquals(listOf("agentId", "cursor"), events.single().payload.keys.toList())

        val again = feed.messages.read("A1", null, null, null)
        assertEquals(emptyList(), again.messages)
        assertEquals("0", again.cursor)

        val since = feed.messages.read("A1", "0", null, false)
        assertEquals(emptyList(), since.messages)
        assertEquals(listOf("beta says"), feed.messages.read("A1", "1", null, false).messages.map { it.body }, "since hands over read lines too")
        fails("since is not a cursor this server issued") { feed.messages.read("A1", "abc", null, null) }
        assertEquals("builder", feed.messages.authorName("agent:A2"))
        assertNull(feed.messages.authorName("human"))
    }

    @Test
    fun `an agent's @name reaches only the agents it names, a person's reaches the channel too`() {
        agent("A1", "scout")
        agent("A2", "builder")
        agent("A3", "lead")
        val p = feed.projects.create("P", null, null).first.id
        feed.projects.create("Q", null, null)
        for (a in listOf("A1", "A2")) Subscriptions(db).followProject(Subscriptions.agent(a), p, chosen = true)
        feed.messages.post(Messages.Post(author = "agent:A3", body = "@builder only you", project = "p"))
        human("@builder and the channel", project = "p")
        human("everyone", project = "p")
        assertEquals(listOf("@builder and the channel", "everyone"), feed.messages.read("A1", null, null, false).messages.map { it.body })
        assertEquals(listOf("@builder only you", "@builder and the channel", "everyone"), feed.messages.read("A2", null, null, false).messages.map { it.body })
        // The same from a `since` cursor, and in the unread counts.
        assertEquals(listOf("@builder and the channel", "everyone"), feed.messages.read("A1", "0", null, false).messages.map { it.body })
        assertEquals(2, AgentWork(db, bus) { now }.countWork("A1").unreadMessages)
        assertEquals(3, AgentWork(db, bus) { now }.countWork("A2").unreadMessages)
        // A person's @name still reaches an agent that follows nothing there.
        human("@lead look", project = "q")
        assertTrue(feed.messages.read("A3", null, null, false).messages.any { it.body == "@lead look" })
        assertTrue(feed.messages.read("A1", null, null, false).messages.none { it.body == "@lead look" })
    }

    // ---- cards ------------------------------------------------------------------------------------

    @Test
    fun `cards pin, take a status, react and are acknowledged`() {
        agent("A1", "scout")
        feed.projects.create("P", null, null)
        val update = feed.updates.post(Updates.Post(project = "p", agentId = "A1", body = "the work"))
        val card = CardRef("update", update.id)
        events.clear()

        assertEquals(true, feed.cards.pin(card, true))
        assertEquals(true, feed.cards.pin(card, true))
        assertEquals("""{"updateId":"${update.id}","projectId":"${update.projectId}","pinned":true}""", Wire.encode(events.single().payload))
        events.clear()

        fails("a request cannot be marked as work") { feed.cards.setStatus(CardRef("request", "X"), "todo") }
        assertEquals("in_progress", feed.cards.setStatus(card, "in_progress"))
        feed.cards.setStatus(card, "in_progress")
        assertEquals(1, types().size)
        val work = feed.cards.listWork(null, null, 50).single()
        assertEquals("the work", work.summary)
        assertEquals("agent:A1", work.author)

        val (on, first) = feed.cards.react(card, "human", ":eyes:", null)
        assertTrue(first)
        assertEquals("👀", on.single().emoji)
        val (off, second) = feed.cards.react(card, "human", "👀", null)
        assertEquals(false, second)
        assertEquals(emptyList(), off)
        feed.cards.react(card, "human", "👀", false)
        assertEquals(listOf("reaction.updated", "reaction.updated"), types())
        fails("no such update: 01ZZZZZZZZZZZZZZZZZZZZZZZZ") { feed.cards.react(CardRef("update", "01ZZZZZZZZZZZZZZZZZZZZZZZZ"), "human", "nope", null) }

        val thinking = feed.cards.acknowledge("A1", card, "thinking")
        now += 1
        val done = feed.cards.acknowledge("A1", card, "done")
        assertEquals(thinking.id, done.id)
        assertEquals("done", done.state)
        assertEquals(listOf("ack.updated", "ack.updated"), types())
    }

    @Test
    fun `one card is named exactly once`() {
        fails("name one of: update, message, request") { Cards.oneCard(update = " ") }
        fails("name one of: update, message, request — not 2 of them") { Cards.oneCard(update = "a", message = "b") }
        assertEquals(CardRef("message", "m"), Cards.oneCard(message = " m "))
    }

    // ---- tags and fields --------------------------------------------------------------------------

    @Test
    fun `tags are defined once, worn at most eight to a card, and toggle`() {
        val project = feed.projects.create("P", null, null).first
        val tag = feed.tags.define("p", "High Priority", "rose", null)
        assertEquals("high-priority", tag.slug)
        assertEquals("rose", feed.tags.define("p", "high priority", "blue", null).colour)
        fails("\"!!!\" has no letters or digits in it") { feed.tags.define("p", "!!!", null, null) }
        fails("colour must be one of grey, rose, amber, green, blue, violet") { feed.tags.define("p", "x", "red", null) }
        feed.tags.define("p", "bug", null, null)
        fails("this project already has a tag called \"bug\"") { feed.tags.rename(tag.id, "Bug", null) }

        val post = human("tag me", project = "p")
        val card = CardRef("message", post.id)
        events.clear()
        fails("this project has no tag called \"wontfix\"") { feed.tags.tagCard(card, "wontfix", "human", null, false) }
        val (made, on) = feed.tags.tagCard(card, "wontfix", "human", null, true)
        assertTrue(on)
        assertEquals("wontfix", made.slug)
        assertEquals(listOf("tags.changed", "message.updated", "tags.changed"), types())
        assertEquals(false, feed.tags.tagCard(card, made.id, "human", null, false).second)
        for (i in 1..8) feed.tags.tagCard(card, "t$i", "human", true, true)
        fails("a card may carry at most 8 tags") { feed.tags.tagCard(card, "t9", "human", true, true) }
        assertTrue(feed.tags.list(project.slug).any { it.slug == "t9" }, "spec surprise: the tag was defined anyway")

        val loose = human("nowhere")
        fails("this card does not belong to a project, so it cannot be tagged") { feed.tags.tagCard(CardRef("message", loose.id), "x", "human", null, true) }

        assertEquals(tag.id to project.id, feed.tags.retire(tag.id))
        fails("no such tag: ${tag.id}") { feed.tags.retire(tag.id) }
    }

    @Test
    fun `fields upsert by slug and are quiet when unchanged`() {
        agent("A1", "scout")
        feed.projects.create("P", null, null)
        val update = feed.updates.post(Updates.Post(project = "p", agentId = "A1", body = "u"))
        val card = CardRef("update", update.id)
        events.clear()
        val (first, set) = feed.fields.set(card, " Branch ", "main", "human")
        assertTrue(set)
        assertEquals("Branch", first.single().name)
        assertEquals(listOf("update.updated", "fields.changed"), types())
        feed.fields.set(card, "Branch", " main ", "human")
        assertEquals(emptyList(), types())
        now += 1
        val renamed = feed.fields.set(card, "branch", "dev", "agent:A1").first.single()
        assertEquals(first.single().json["id"], renamed.json["id"])
        assertEquals(first.single().createdAt, renamed.createdAt)
        assertEquals("agent:A1", renamed.author)
        fails("this card has no field called \"nope\"") { feed.fields.set(card, " nope ", null, "human") }
        fails("a field needs a value, or null to remove it") { feed.fields.set(card, "x", " ", "human") }
        assertEquals(false, feed.fields.set(card, "BRANCH", null, "human").second)
        assertEquals(emptyList(), feed.fields.on(card))
    }

    // ---- updates ----------------------------------------------------------------------------------

    @Test
    fun `an update is posted, edited by its author and deleted softly`() {
        agent("A1", "scout")
        agent("A2", "builder")
        feed.projects.create("P", null, null)
        db.update("INSERT INTO sessions (id, agent_id, started_at, last_heartbeat_at) VALUES ('S1', 'A2', 1, 1)")
        fails("session belongs to another agent") { feed.updates.post(Updates.Post(project = "p", agentId = "A1", body = "x", sessionId = "S1")) }
        fails("no such session: bogus") { feed.updates.post(Updates.Post(project = "p", agentId = "A1", body = "x", sessionId = "bogus")) }
        val update = feed.updates.post(Updates.Post(project = "p", agentId = "A1", body = "hello", title = " T "))
        assertEquals("T", update.title)
        assertEquals("medium", update.json["priority"]?.let { (it as JsonPrimitive).content })
        events.clear()

        fails("that update was posted by another agent") { feed.updates.edit(update.id, "A2", Updates.Edit(body = "x")) }
        fails("an edit must change the title, the body, the level or the priority") { feed.updates.edit(update.id, "A1", Updates.Edit()) }
        val edited = feed.updates.edit(update.id, "A1", Updates.Edit(title = Given(null), body = "edited"))
        assertNull(edited.title)
        assertEquals(now, edited.editedAt)

        feed.updates.delete(update.id)
        feed.updates.delete(update.id)
        assertEquals(listOf("update.updated", "update.deleted"), types())
        fails("no such update: ${update.id}") { feed.updates.setPinned(update.id, true) }
    }
}
