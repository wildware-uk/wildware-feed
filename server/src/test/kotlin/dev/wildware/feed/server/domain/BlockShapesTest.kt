package dev.wildware.feed.server.domain

import dev.wildware.feed.server.domain.BlockShapes.Actor
import dev.wildware.feed.server.http.Wire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shapes of blocks (docs/BLOCKS.md): strict checks, the canonical form, and patches. */
class BlockShapesTest {
    private val agent = Actor.agent("A1")
    private val person = Actor.person("founder")
    private val t0 = 1_700_000_000_000L
    private val t1 = t0 + 60_000

    private fun j(text: String): JsonElement = Wire.json.parseToJsonElement(text)
    private fun block(text: String, at: Long = t0) = BlockShapes.normalize(j(text), "blocks[0]", agent, at)
    private fun patch(stored: JsonObject, text: String, by: Actor = agent, at: Long = t1) = BlockShapes.applyPatch(stored, j(text), by, at)
    private fun refused(message: String, run: () -> Unit) {
        val e = assertFailsWith<DomainError> { run() }
        assertEquals(message, e.message)
    }
    private fun JsonObject.list(key: String) = this[key]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.s(key: String) = this[key]?.jsonPrimitive?.content

    @Test
    fun `a checklist is written canonically and a tick records who and when`() {
        val list = block("""{"id":"ship","type":"checklist","title":" Release ","items":[{"id":"1","text":"Tests","owner":"you"},{"id":"2","text":"Notes","done":true}]}""")
        assertEquals("Release", list.s("title"))
        val items = list.list("items")
        assertEquals("false", items[0].s("done"))
        assertEquals("you", items[0].s("owner"))
        assertNull(items[0]["done_by"])
        assertEquals("agent:A1", items[1].s("done_by"), "a line posted done is the agent's")

        val ticked = BlockShapes.check(list, "1", true, person, t1)
        val line = ticked.list("items")[0]
        assertEquals("true", line.s("done"))
        assertEquals("person:founder", line.s("done_by"))
        assertEquals(Wire.isoOf(t1), line.s("done_at"))
        val again = BlockShapes.check(ticked, "1", true, Actor.person("other"), t1 + 5)
        assertEquals("person:founder", again.list("items")[0].s("done_by"), "ticking a ticked line keeps who ticked it")
        val unticked = BlockShapes.check(ticked, "1", false, person, t1 + 10)
        assertNull(unticked.list("items")[0]["done_by"])
        refused("no line \"9\" on that checklist") { BlockShapes.check(list, "9", true, person, t1) }
    }

    @Test
    fun `every type is checked strictly`() {
        refused("blocks[0].type must be one of: checklist, steps, diff, chart, table, log, poll") { block("""{"id":"a","type":"gauge"}""") }
        refused("blocks[0].colour is not something a checklist block has: it takes id, type, title, items") {
            block("""{"id":"a","type":"checklist","colour":"red","items":[{"id":"1","text":"x"}]}""")
        }
        refused("blocks[0].id must be 1 to 64 letters, digits, -, _ or ., starting with a letter or digit") { block("""{"id":"-a","type":"log"}""") }
        refused("blocks[0].items[1].id \"1\" is used twice") { block("""{"id":"a","type":"checklist","items":[{"id":"1","text":"x"},{"id":"1","text":"y"}]}""") }
        refused("blocks[0].items[0].text is required") { block("""{"id":"a","type":"checklist","items":[{"id":"1","text":"  "}]}""") }
        refused("blocks[0].items[0].done must be true or false") { block("""{"id":"a","type":"checklist","items":[{"id":"1","text":"x","done":"yes"}]}""") }
        refused("blocks[0].items may have at most 100 entries") {
            block("""{"id":"a","type":"checklist","items":[${(1..101).joinToString(",") { """{"id":"$it","text":"x"}""" }}]}""")
        }
        refused("blocks[0].stages[0].state must be one of: pending, running, done, failed, skipped") {
            block("""{"id":"a","type":"steps","stages":[{"id":"b","label":"Build","state":"ok"}]}""")
        }
        refused("blocks[0].progress must be a number from 0 to 1") { block("""{"id":"a","type":"steps","stages":[{"id":"b","label":"B"}],"progress":2}""") }
        refused("blocks[0].eta must be a time with its offset, like 2026-11-20T15:00:00Z") {
            block("""{"id":"a","type":"steps","stages":[{"id":"b","label":"B"}],"eta":"soon"}""")
        }
        refused("blocks[0].series[1].points[0]: every x in a chart is a number, or every x is a label, not a mix") {
            block("""{"id":"c","type":"chart","series":[{"id":"a","points":[[1,2]]},{"id":"b","points":[["Mon",2]]}]}""")
        }
        refused("blocks[0].series[0].points[0]: y must be a number") { block("""{"id":"c","type":"chart","series":[{"id":"a","points":[[1,"2"]]}]}""") }
        refused("blocks[0].kind must be line or bar") { block("""{"id":"c","type":"chart","kind":"pie","series":[{"id":"a","points":[]}]}""") }
        refused("blocks[0].rows[0] has 1 cells; the table has 2 columns") { block("""{"id":"t","type":"table","columns":["A","B"],"rows":[["x"]]}""") }
        refused("blocks[0].rows[0][1]: column \"N\" holds numbers") {
            block("""{"id":"t","type":"table","columns":["A",{"label":"N","kind":"number"}],"rows":[["x","7"]]}""")
        }
        refused("blocks[0].limit must be a whole number from 1 to 100") { block("""{"id":"t","type":"table","columns":["A"],"limit":0}""") }
        refused("blocks[0].options needs at least 2 options") { block("""{"id":"p","type":"poll","question":"Ship?","options":[{"id":"y","label":"Yes"}]}""") }
        refused("blocks[0] takes lines or text, not both") { block("""{"id":"l","type":"log","lines":["a"],"text":"b"}""") }
        refused("blocks[0] needs text (a unified diff) or files") { block("""{"id":"d","type":"diff"}""") }
        refused("blocks[0].text has no files in it: give a unified diff, as git diff prints it") { block("""{"id":"d","type":"diff","text":"hello"}""") }
        refused("a card may carry at most 8 blocks") {
            BlockShapes.normalizeAll((1..9).map { j("""{"id":"l$it","type":"log"}""") }, agent, t0)
        }
        refused("blocks[1].id \"l\" is used twice: each block on a card needs its own id") {
            BlockShapes.normalizeAll(listOf(j("""{"id":"l","type":"log"}"""), j("""{"id":"l","type":"log"}""")), agent, t0)
        }
        // What only the server writes cannot come from an agent.
        refused("blocks[0].votes is not something a poll block has: it takes id, type, title, question, options, multi, closes_at, closed") {
            block("""{"id":"p","type":"poll","question":"?","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}],"votes":[]}""")
        }
        refused("blocks[0].items[0].done_by is not something a checklist line has: it takes id, text, done, owner") {
            block("""{"id":"a","type":"checklist","items":[{"id":"1","text":"x","done":true,"done_by":"person:x"}]}""")
        }
    }

    @Test
    fun `a block too big written out is refused`() {
        val cells = (1..400).joinToString(",") { """["${"x".repeat(300)}"]""" }
        refused("blocks[0] is 122085 characters written out; a block may be at most 100000") {
            block("""{"id":"t","type":"table","columns":["A"],"rows":[$cells]}""")
        }
    }

    @Test
    fun `steps stamp when a stage started and ended`() {
        val steps = block("""{"id":"ci","type":"steps","stages":[{"id":"build","label":"Build","state":"running"},{"id":"test","label":"Test"}],"progress":0.25,"eta":"2026-11-20T16:00:00+01:00"}""")
        assertEquals("2026-11-20T15:00:00.000Z", steps.s("eta"), "written back as UTC")
        assertEquals(Wire.isoOf(t0), steps.list("stages")[0].s("started_at"))
        assertNull(steps.list("stages")[1]["started_at"])
        val next = patch(steps, """{"stages":[{"id":"build","state":"done"},{"id":"test","state":"running"}],"progress":0.5}""")
        val (build, test) = next.list("stages")
        assertEquals(Wire.isoOf(t0), build.s("started_at"), "kept from when it began")
        assertEquals(Wire.isoOf(t1), build.s("ended_at"))
        assertEquals(Wire.isoOf(t1), test.s("started_at"))
        assertEquals("0.5", next.s("progress"))
        val failed = patch(next, """{"stages":[{"id":"test","state":"failed","detail":"2 failed"}],"eta":null}""", at = t1 + 1000)
        assertEquals("2 failed", failed.list("stages")[1].s("detail"))
        assertEquals(Wire.isoOf(t1 + 1000), failed.list("stages")[1].s("ended_at"))
        assertNull(failed["eta"], "null clears it")
        val retried = patch(failed, """{"stages":[{"id":"test","state":"running","detail":null}]}""", at = t1 + 5000)
        assertEquals(Wire.isoOf(t1 + 5000), retried.list("stages")[1].s("started_at"), "a retry counts from when it started again")
        assertNull(retried.list("stages")[1]["ended_at"])
        val added = patch(failed, """{"stages":[{"id":"deploy","label":"Deploy"}]}""")
        assertEquals(listOf("build", "test", "deploy"), added.list("stages").map { it.s("id") })
        assertEquals("pending", added.list("stages")[2].s("state"))
        val removed = patch(added, """{"remove":["build"]}""")
        assertEquals(listOf("test", "deploy"), removed.list("stages").map { it.s("id") })
        refused("patch.remove: no stage \"nope\"") { patch(added, """{"remove":["nope"]}""") }
        refused("patch.stages[3].label is required") { patch(added, """{"stages":[{"id":"new"}]}""") }
    }

    @Test
    fun `a patch names only what its type has, and is checked again whole`() {
        val list = block("""{"id":"a","type":"checklist","items":[{"id":"1","text":"x"}]}""")
        refused("patch.append is not something a patch of a checklist block has: it takes title, items, remove") { patch(list, """{"append":"x"}""") }
        refused("patch is empty: name what to change") { patch(list, "{}") }
        refused("patch.items[0].done_by is the server's to write") { patch(list, """{"items":[{"id":"1","done_by":"person:x"}]}""") }
        refused("patch.items[1].id \"1\" is named twice in one patch") { patch(list, """{"items":[{"id":"1","done":true},{"id":"1","done":false}]}""") }
        refused("patch.items[0].text is required") { patch(list, """{"items":[{"id":"1","text":null}]}""") }
        val titled = patch(list, """{"title":"T","items":[{"id":"1","done":true,"owner":"alpha"}]}""")
        assertEquals("T", titled.s("title"))
        assertEquals("agent:A1", titled.list("items")[0].s("done_by"))
        assertEquals("alpha", titled.list("items")[0].s("owner"))
        val cleared = patch(titled, """{"title":null,"items":[{"id":"1","owner":null}]}""")
        assertNull(cleared["title"])
        assertNull(cleared.list("items")[0]["owner"])
    }

    @Test
    fun `a log keeps its newest lines and counts the rest`() {
        val log = block("""{"id":"l","type":"log","text":"one\ntwo\r\nthree\n","highlight":"two"}""")
        assertEquals(listOf("one", "two", "three"), log["lines"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("5", log.s("tail"))
        val more = patch(log, """{"append":["four", "five\nsix"]}""")
        assertEquals(6, more["lines"]!!.jsonArray.size)
        val many = patch(more, """{"append":[${(1..1000).joinToString(",") { "\"line $it\"" }}]}""")
        assertEquals(1000, many["lines"]!!.jsonArray.size)
        assertEquals("line 1000", many["lines"]!!.jsonArray.last().jsonPrimitive.content)
        assertEquals("6", many.s("dropped"))
        val again = patch(many, """{"append":"one more"}""")
        assertEquals("7", again.s("dropped"), "dropped adds up across patches")
        val reset = patch(again, """{"lines":["fresh"]}""")
        assertNull(reset["dropped"], "replacing the lines starts again")
        refused("patch.append must be text, or a list of lines") { patch(log, """{"append":3}""") }

        // Written out, escapes take room: a log of coloured lines sheds its oldest to fit, not refuses.
        val esc = ("""\u001b[31m""" + "xx").repeat(10)
        val coloured = patch(log, """{"lines":[${(1..1000).joinToString(",") { "\"$esc $it\"" }}]}""")
        assertTrue(Wire.encode(coloured).length <= BlockShapes.BLOCK_CHARS_MAX)
        assertTrue(coloured["dropped"]!!.jsonPrimitive.content.toLong() > 0)
        assertTrue(coloured["lines"]!!.jsonArray.last().jsonPrimitive.content.endsWith(" 1000"), "the newest is kept")
        val after = patch(coloured, """{"append":"$esc 1001"}""")
        assertTrue(after["lines"]!!.jsonArray.last().jsonPrimitive.content.endsWith(" 1001"), "and it still takes more")
    }

    @Test
    fun `a chart adds points to the end and keeps the newest`() {
        val chart = block("""{"id":"c","type":"chart","kind":"bar","unit":"ms","series":[{"id":"p95","label":"p95","points":[{"x":1,"y":300},[2,310]]}]}""")
        assertEquals("""[[1,300],[2,310]]""", chart.list("series")[0]["points"].toString())
        val more = patch(chart, """{"series":[{"id":"p95","points":[[3,320]]},{"id":"p50","points":[[1,100]]}]}""")
        assertEquals(3, more.list("series")[0]["points"]!!.jsonArray.size)
        assertEquals(2, more.list("series").size)
        val full = patch(more, """{"series":[{"id":"p50","points":[${(2..600).joinToString(",") { "[$it,1]" }}]}]}""")
        val p50 = full.list("series")[1]["points"]!!.jsonArray
        assertEquals(500, p50.size)
        assertEquals("[600,1]", p50.last().toString())
        refused("patch.series[0].points must be a list") { patch(chart, """{"series":[{"id":"p95","points":3}]}""") }
    }

    @Test
    fun `a table appends rows and refuses past its limit`() {
        val table = block("""{"id":"t","type":"table","columns":["Name",{"label":"Size","kind":"number"}],"rows":[["a",1]]}""")
        assertEquals("""[{"label":"Name","kind":"text"},{"label":"Size","kind":"number"}]""", table["columns"].toString())
        assertEquals("10", table.s("limit"))
        val more = patch(table, """{"append_rows":[["b",2],["c",null]]}""")
        assertEquals(3, more["rows"]!!.jsonArray.size)
        refused("patch.rows may have at most 1000 entries") { patch(more, """{"append_rows":[${(1..998).joinToString(",") { "[\"r\",1]" }}]}""") }
    }

    @Test
    fun `a unified diff is read into files and hunks`() {
        val text = """
            diff --git a/src/App.kt b/src/App.kt
            index 1..2 100644
            --- a/src/App.kt
            +++ b/src/App.kt
            @@ -1,3 +1,3 @@ fun main
             val a = 1
            --- removed comment line
            +val b = 2
             val c = 3
            diff --git a/README.md b/README.md
            new file mode 100644
            --- /dev/null
            +++ b/README.md
            @@ -0,0 +1 @@
            +# Hi
            diff --git a/old.txt b/new.txt
            similarity index 100%
            rename from old.txt
            rename to new.txt
        """.trimIndent()
        val diff = block("""{"id":"d","type":"diff","text":${Wire.json.encodeToString(JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(text))}}""")
        val files = diff.list("files")
        assertEquals(listOf("src/App.kt", "README.md", "new.txt"), files.map { it.s("path") })
        assertEquals(listOf("modified", "added", "renamed"), files.map { it.s("status") })
        assertEquals("1", files[0].s("additions"))
        assertEquals("1", files[0].s("deletions"), "a removed line that starts with -- is a line, not a file")
        assertEquals("@@ -1,3 +1,3 @@ fun main", files[0].list("hunks")[0].s("header"))
        assertEquals("old.txt", files[2].s("old_path"))
        assertTrue(files[2]["hunks"]!!.jsonArray.isEmpty())

        val given = block("""{"id":"d","type":"diff","files":[{"path":"a.kt","hunks":["@@ -1 +1 @@\n-a\n+b"]}]}""")
        assertEquals("""[{"header":"@@ -1 +1 @@","lines":["-a","+b"]}]""", given.list("files")[0]["hunks"].toString())
        refused("blocks[0].files[0].hunks[0] must start with an @@ header line") { block("""{"id":"d","type":"diff","files":[{"path":"a","hunks":["-a"]}]}""") }
        val retitled = patch(given, """{"title":"Fix"}""")
        assertEquals(given["files"], retitled["files"], "a patch that leaves the files keeps them as they were")
    }

    @Test
    fun `a poll takes votes, one each, until it closes`() {
        val poll = block("""{"id":"p","type":"poll","question":"Which?","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}],"closes_at":"2026-01-01T00:00:00Z"}""", at = t0)
        val voted = BlockShapes.vote(poll, listOf("a"), person, t1)
        val changed = BlockShapes.vote(voted, listOf("b"), person, t1)
        assertEquals(1, changed["votes"]!!.jsonArray.size, "a second vote replaces the first")
        val other = BlockShapes.vote(changed, listOf("a"), Actor.person("m2"), t1)
        assertEquals(2, other["votes"]!!.jsonArray.size)
        refused("that poll takes one choice") { BlockShapes.vote(poll, listOf("a", "b"), person, t1) }
        refused("no option \"z\" on that poll") { BlockShapes.vote(poll, listOf("z"), person, t1) }
        val back = BlockShapes.vote(other, emptyList(), person, t1)
        assertEquals(1, back["votes"]!!.jsonArray.size, "no options takes a vote back")
        // The agent drops an option: votes for it go, the rest stay.
        val trimmed = patch(other, """{"options":[{"id":"c","label":"C"}],"remove":["b"]}""")
        assertEquals(listOf("m2"), trimmed["votes"]!!.jsonArray.map { it.jsonObject.s("by")!!.removePrefix("person:") })
        val closed = patch(trimmed, """{"closed":true}""")
        refused("that poll has closed") { BlockShapes.vote(closed, listOf("a"), person, t1) }
        val late = 1_800_000_000_000L
        refused("that poll has closed") { BlockShapes.vote(poll, listOf("a"), person, late) }
        assertTrue(BlockShapes.pollClosed(poll, late))
    }

    @Test
    fun `a person's tick stands only for the words they ticked`() {
        val list = block("""{"id":"ship","type":"checklist","items":[{"id":"1","text":"Delete the test data"},{"id":"2","text":"Notes"}]}""")
        val ticked = BlockShapes.check(list, "1", true, person, t1)
        // Reworded by a patch: the line is open again, and the person's name is not on the new words.
        val patched = patch(ticked, """{"items":[{"id":"1","text":"Delete the live data"}]}""", at = t1 + 1)
        assertEquals("false", patched.list("items")[0].s("done"))
        assertNull(patched.list("items")[0]["done_by"])
        // Reworded by replacing the whole block, done still sent: open again too.
        val replaced = BlockShapes.normalize(
            j("""{"id":"ship","type":"checklist","items":[{"id":"1","text":"Delete the live data","done":true},{"id":"2","text":"Notes"}]}"""),
            "blocks[0]", agent, t1 + 2, previous = ticked,
        )
        assertEquals("false", replaced.list("items")[0].s("done"), "the agent can't carry the person's tick onto new words")
        assertNull(replaced.list("items")[0]["done_by"])
        // Other changes keep the person's tick.
        val retitled = patch(ticked, """{"title":"Release","items":[{"id":"1","owner":"you"}]}""", at = t1 + 3)
        assertEquals("person:founder", retitled.list("items")[0].s("done_by"))
    }

    @Test
    fun `a vote stands only for the question and option it was given to`() {
        val poll = block("""{"id":"p","type":"poll","question":"Ship on Friday?","options":[{"id":"a","label":"Yes"},{"id":"b","label":"No"}]}""")
        val voted = BlockShapes.vote(BlockShapes.vote(poll, listOf("a"), person, t1), listOf("b"), Actor.person("m2"), t1)
        val relabelled = patch(voted, """{"options":[{"id":"a","label":"Yes, and skip the tests"}]}""")
        assertEquals(listOf("m2"), relabelled["votes"]!!.jsonArray.map { it.jsonObject.s("by")!!.removePrefix("person:") }, "only the relabelled option's votes go")
        val newQuestion = patch(voted, """{"question":"Ship on Monday?"}""")
        assertNull(newQuestion["votes"], "a new question drops every vote")
        assertEquals(2, patch(voted, """{"closed":true}""")["votes"]!!.jsonArray.size, "other changes keep them")
    }

    @Test
    fun `replacing a block with another type starts it afresh`() {
        val list = block("""{"id":"x","type":"checklist","items":[{"id":"1","text":"a","done":true}]}""")
        val log = BlockShapes.normalize(j("""{"id":"x","type":"log","lines":["hi"]}"""), "block", agent, t1, previous = list)
        assertEquals("log", log.s("type"))
        assertEquals(JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("hi"))), log["lines"])
    }
}
