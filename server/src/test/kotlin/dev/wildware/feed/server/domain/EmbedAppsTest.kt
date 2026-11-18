package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.events.AppEvent
import dev.wildware.feed.server.events.Bus
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Registering embed looks and apps, and pushing data into an app on a card (spec/05 §7.8-7.12). */
class EmbedAppsTest {
    private var now = 1_763_474_000_000L
    private val db = Database.memory()
    private val bus = Bus { now }
    private val feed = Feed(db, bus) { now }
    private val events = mutableListOf<AppEvent>()

    init {
        bus.subscribe { events += it }
        db.update("INSERT INTO agents (id, name, token_hash, created_at) VALUES ('A1', 'scout', 'h1', 1), ('A2', 'other', 'h2', 1)")
    }

    @AfterTest fun close() = db.close()

    private fun json(text: String): JsonElement = Js.parse(text)

    private fun fails(message: String, code: String = "invalid_argument", block: () -> Unit) {
        val error = assertFailsWith<DomainError>(block = block)
        assertEquals(message, error.message)
        assertEquals(code, error.code)
    }

    private val mini = """{"blocks":[{"type":"text","value":"{{text}}"}]}"""

    // ---- embed types ------------------------------------------------------------------------------

    @Test
    fun `the built-in looks come first, then registered ones by name`() {
        feed.embedTypes.register("A1", "zz-last", "alpha look", json(mini))
        feed.embedTypes.register("A1", "aa-first", "Beta look", json(mini))
        assertEquals(
            listOf("confluence-page", "github-comment", "jira-issue", "linked-page", "slack-message", "zz-last", "aa-first"),
            feed.embedTypes.list().map { it.slug },
        )
        assertEquals(listOf(true, true, true, true, true, false, false), feed.embedTypes.list().map { it.builtIn })
    }

    @Test
    fun `a look that cannot be read is left out of the list`() {
        db.update("INSERT INTO embed_types (id, slug, name, agent_id, spec, created_at, updated_at) VALUES ('T1', 'broken', 'Broken', 'A1', '{\"blocks\":[]}', 1, 1)")
        assertTrue(feed.embedTypes.list().none { it.slug == "broken" })
    }

    @Test
    fun `placeholders are read from chrome then blocks, first seen, once each`() {
        val spec = Embeds.parseSpec(json(
            """{"chrome":{"label":"{{ site }}"},"blocks":[
                {"type":"header","title":"{{user}} and {{site}}","meta":"{{time}}"},
                {"type":"fields","items":[{"label":"{{k-1}}","value":"{{v_2}}"}]},
                {"type":"text","value":"{{not a name}} {{user}} {{ nbsp }}"}]}""",
        ))
        assertEquals(listOf("site", "user", "time", "k-1", "v_2", "nbsp"), EmbedTypes.placeholders(spec))
        assertEquals(listOf("header", "fields", "text"), EmbedTypes.blockTypes(spec))
    }

    @Test
    fun `registering checks slug, name, spec, then whose it is`() {
        fails("slug must be lowercase words joined by hyphens, e.g. \"slack-message\", at most 60 characters") {
            feed.embedTypes.register("A1", "two--hyphens", "X", json(mini))
        }
        fails("slug must be lowercase words joined by hyphens, e.g. \"slack-message\", at most 60 characters") {
            feed.embedTypes.register("A1", "a".repeat(61), "X", json(mini))
        }
        fails("name is required, and at most 80 characters") { feed.embedTypes.register("A1", "bad", "  ", json(mini)) }
        fails("spec.blocks must be a list of blocks") { feed.embedTypes.register("A1", "bad", "Bad", json("{}")) }
        fails("blocks[0].value is required") { feed.embedTypes.register("A1", "bad", "Bad", json("""{"blocks":[{"type":"text","text":"x"}]}""")) }
        fails("\"slack-message\" is one of the looks this dashboard ships, so it cannot be redefined. Register your own under a different slug.", "conflict") {
            feed.embedTypes.register("A1", "slack-message", "Mine", json(mini))
        }
        feed.embedTypes.register("A1", "mine", "Mine", json(mini))
        fails("\"mine\" was registered by another agent. Pick a different slug.", "conflict") {
            feed.embedTypes.register("A2", "mine", "Theirs", json(mini))
        }
        assertTrue(events.isEmpty(), "registering a look publishes nothing")
    }

    @Test
    fun `registering again replaces the look and keeps its id and creation time`() {
        val first = feed.embedTypes.register("A1", "  Mini-One ", " Mini ", json(mini))
        assertEquals("mini-one", first.slug)
        assertEquals("Mini", first.name)
        assertEquals(false, first.builtIn)
        val before = db.one("SELECT id, created_at FROM embed_types WHERE slug = 'mini-one'") { it.getString(1) to it.getLong(2) }
        now += 5_000
        val again = feed.embedTypes.register("A1", "mini-one", "Mini 2", json("""{"blocks":[{"type":"divider"},{"type":"quote","value":"q"}]}"""))
        assertEquals("Mini 2", again.name)
        assertEquals(listOf("divider", "quote"), EmbedTypes.blockTypes(again.spec))
        val after = db.one("SELECT id, created_at, updated_at, spec FROM embed_types WHERE slug = 'mini-one'") {
            listOf(it.getString(1), it.getLong(2), it.getLong(3), it.getString(4))
        }!!
        assertEquals(listOf(before!!.first, before.second, now), after.take(3))
        assertEquals("""{"theme":{},"chrome":{},"frame":{},"blocks":[{"type":"divider"},{"type":"quote","value":"q"}]}""", after[3])
    }

    // ---- apps -------------------------------------------------------------------------------------

    private fun register(
        uri: String = "ui://board",
        name: String = "Board",
        agentId: String = "A1",
        description: String? = null,
        html: String? = "<p>hi</p>",
        url: String? = null,
        csp: JsonElement? = null,
    ) = feed.apps.register(Apps.Register(agentId, uri, name, description, html, url, csp))

    @Test
    fun `an app is registered with its handle lowercased and its size in UTF-8 bytes`() {
        val app = register(uri = " UI://Acme/Board ", name = " Board ", description = "  ", html = " <p>é</p> ")
        assertEquals("ui://acme/board", app.uri)
        assertEquals("Board", app.name)
        assertNull(app.description)
        assertNull(app.url)
        assertEquals(11, app.bytes)
        assertEquals("""{"connect":[],"resource":[],"frame":[]}""", Wire.encode(app.csp))
        assertEquals(" <p>é</p> ", db.one("SELECT html FROM apps") { it.getString(1) })
        assertTrue(events.isEmpty(), "registering an app publishes nothing")
    }

    @Test
    fun `registering an app refuses in the TS order with its words`() {
        fails("uri must start with ui://, e.g. \"ui://review-board\"") { register(uri = "http://x") }
        fails("uri may be at most 120 characters") { register(uri = "ui://" + "a".repeat(116)) }
        fails("uri must be ui:// followed by lowercase words, e.g. \"ui://review-board\" or \"ui://acme/review-board\"") { register(uri = "ui://a//b") }
        fails("name is required, and at most 80 characters") { register(name = " ") }
        fails("description may be at most 400 characters") { register(description = "d".repeat(401)) }
        fails("give html or url, not both: an app is a document, or a page to frame") { register(url = "https://fix.example.com/") }
        fails("an app needs html — a self-contained document — or url, an https page to frame") { register(html = "  ") }
        fails("html may be at most 250000 characters. An app is one self-contained document, so split the work between the app and tool calls rather than shipping a bundle.") {
            register(html = "x".repeat(250_001))
        }
        fails("url must be an absolute https URL, e.g. \"https://fix.example.com/embed\"") { register(html = null, url = "not a url") }
        fails("url must use https: the dashboard is served over it, and a browser refuses to frame an insecure page inside a secure one") {
            register(html = null, url = "http://fix.example.com/")
        }
        fails("url must not carry credentials") { register(html = null, url = "https://sam:pw@fix.example.com/") }
        fails("url may be at most 2000 characters") { register(html = null, url = "https://fix.example.com/" + "a".repeat(2000)) }
        fails("csp must be an object of { connect, resource, frame }") { register(csp = json("[]")) }
        fails("csp.connect must be a list of https origins") { register(csp = json("""{"connect":"https://a.example.com"}""")) }
        fails("csp.frame may name at most 10 origins") { register(csp = json("""{"frame":[${(1..11).joinToString(",") { "\"https://a$it.example.com\"" }}]}""")) }
        fails("csp.connect[0] must be an https origin") { register(csp = json("""{"connect":[1]}""")) }
        fails("csp.connect[0] must be an https origin, e.g. \"https://api.example.com\"") { register(csp = json("""{"connect":["nope"]}""")) }
        fails("csp.resource[1] must use https") { register(csp = json("""{"resource":["https://a.example.com","http://b.example.com"]}""")) }
        fails("csp.connect[0] must not carry credentials") { register(csp = json("""{"connect":["https://u@a.example.com"]}""")) }
        fails("csp.connect[0] must be an origin — a host, with no path") { register(csp = json("""{"connect":["https://a.example.com/v1"]}""")) }
        fails("csp.connect[0] must be an origin — a host, with no path") { register(csp = json("""{"connect":["https://a.example.com/?q=1"]}""")) }
    }

    @Test
    fun `a framed page and declared origins are stored as a browser prints them`() {
        val app = register(
            html = null,
            url = " https://Fix.Example.com:443/embed?query=open ",
            csp = json("""{"connect":["https://API.example.com/","https://api.example.com:8443"],"frame":null}"""),
        )
        assertEquals("https://fix.example.com/embed?query=open", app.url)
        assertEquals("""{"connect":["https://api.example.com","https://api.example.com:8443"],"resource":[],"frame":[]}""", Wire.encode(app.csp))
        assertEquals("https://fix.example.com/", register(uri = "ui://two", html = null, url = "https://fix.example.com").url)
        assertEquals(
            """{"connect":["https://api.example.com","https://api.example.com:8443"],"resource":[],"frame":[]}""",
            db.one("SELECT csp FROM apps WHERE uri = 'ui://board'") { it.getString(1) },
        )
    }

    @Test
    fun `an app is only its registrant's to replace, and replacing keeps its id`() {
        register()
        val id = db.one("SELECT id, created_at FROM apps") { it.getString(1) to it.getLong(2) }
        now += 1_000
        val again = register(name = "Board 2", html = "<b>new</b>")
        assertEquals("Board 2", again.name)
        assertEquals(now, again.updatedAt)
        assertEquals(id, db.one("SELECT id, created_at FROM apps") { it.getString(1) to it.getLong(2) })
        fails("\"ui://board\" was registered by another agent. Pick a different URI.", "conflict") { register(agentId = "A2") }
        db.update("INSERT INTO apps (id, uri, name, agent_id, html, csp, created_at, updated_at) VALUES ('P1', 'ui://shipped', 'Shipped', NULL, '<p/>', '{}', 1, 1)")
        fails("\"ui://shipped\" is one of the apps this dashboard ships, so it cannot be redefined.", "conflict") { register(uri = "ui://shipped") }
    }

    @Test
    fun `the list is by name, says whose each one is, and reads a broken csp as empty`() {
        register(uri = "ui://zed", name = "beta")
        register(uri = "ui://alpha", name = "Beta", agentId = "A2")
        register(uri = "ui://mid", name = "Alpha")
        db.update("UPDATE apps SET csp = 'not json' WHERE uri = 'ui://mid'")
        val list = feed.apps.list("A1")
        assertEquals(listOf("ui://mid", "ui://alpha", "ui://zed"), list.map { it.uri })
        assertEquals(listOf(true, false, true), list.map { it.mine })
        assertEquals("""{"connect":[],"resource":[],"frame":[]}""", Wire.encode(list[0].csp))
    }

    private fun postWithApp(title: String? = "B"): Pair<String, String> {
        feed.projects.create("P", null, null)
        register()
        val update = feed.updates.post(
            Updates.Post(
                project = "p", agentId = "A1", body = "board",
                apps = listOf(FeedAttachments.AppInput(JsonPrimitive("ui://board"), json("""{"a":1}"""), title?.let(::JsonPrimitive))),
            ),
        )
        val view = FeedAttachments.appsOn(db, CardRef("update", update.id)).single().id
        events.clear()
        return update.id to view
    }

    @Test
    fun `fresh data replaces the old, leaves the app's own state, and redraws the card`() {
        val (update, view) = postWithApp()
        db.update("UPDATE app_views SET state = '{\"tab\":2}' WHERE id = ?", view)
        now += 2_000
        val set = feed.apps.setData(view, json("""{"b":[1.0,"x"]}"""), null)
        assertEquals(view, set.id)
        assertEquals("ui://board", set.uri)
        assertEquals("B", set.title)
        assertEquals(now, set.updatedAt)
        assertEquals(
            listOf("""{"b":[1,"x"]}""", """{"tab":2}""", "B"),
            db.one("SELECT data, state, title FROM app_views WHERE id = ?", view) { listOf(it.getString(1), it.getString(2), it.getString(3)) },
        )
        val projectId = db.one("SELECT id FROM projects") { it.getString(1) }
        assertEquals(listOf("update.updated", "apps.changed"), events.map { it.type })
        assertEquals("""{"updateId":"$update","projectId":"$projectId","pinned":false}""", Wire.encode(events[0].payload))
        assertEquals("""{"projectId":"$projectId"}""", Wire.encode(events[1].payload))
    }

    @Test
    fun `a title can be changed, cleared with an empty one, or left alone`() {
        val (_, view) = postWithApp()
        assertEquals("New", feed.apps.setData(view, json("{}"), Given(JsonPrimitive(" New "))).title)
        assertEquals("New", feed.apps.setData(view, json("{}"), null).title)
        assertNull(feed.apps.setData(view, json("{}"), Given(JsonPrimitive(""))).title)
    }

    @Test
    fun `setting data refuses an unknown view, a deleted card and bad data`() {
        fails("no app view \"nope\"", "not_found") { feed.apps.setData("nope", json("{}"), null) }
        val (update, view) = postWithApp()
        fails("data must be a JSON object", "invalid_argument") { feed.apps.setData(view, json("[1]"), null) }
        fails("data may be at most 100000 characters of JSON") { feed.apps.setData(view, json("""{"a":"${"x".repeat(100_000)}"}"""), null) }
        fails("title may be at most 120 characters") { feed.apps.setData(view, json("{}"), Given(JsonPrimitive("t".repeat(121)))) }
        db.update("UPDATE updates SET deleted_at = 1 WHERE id = ?", update)
        fails("no such update: $update", "not_found") { feed.apps.setData(view, json("{}"), null) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `an app's document is served under a policy built from its declared origins`() {
        assertEquals(
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; " +
                "media-src data: blob:; connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'; frame-ancestors 'self'",
            Apps.policyFor(json("""{"connect":[],"resource":[],"frame":[]}""") as JsonObject),
        )
        assertEquals(
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline' https://r.example.com https://s.example.com; " +
                "img-src data: blob: https://r.example.com https://s.example.com; font-src data: https://r.example.com https://s.example.com; " +
                "media-src data: blob: https://r.example.com https://s.example.com; connect-src https://c.example.com; " +
                "frame-src https://f.example.com; form-action 'none'; base-uri 'none'; frame-ancestors 'self'",
            Apps.policyFor(json("""{"connect":["https://c.example.com"],"resource":["https://r.example.com","https://s.example.com"],"frame":["https://f.example.com"]}""") as JsonObject),
        )
        val (_, view) = postWithApp()
        assertEquals("<p>hi</p>", feed.apps.document(view)!!.first)
        assertNull(feed.apps.document("nope"))
        register(uri = "ui://board", html = null, url = "https://fix.example.com/")
        assertNull(feed.apps.document(view), "an app that frames a page has no document here")
    }
}
