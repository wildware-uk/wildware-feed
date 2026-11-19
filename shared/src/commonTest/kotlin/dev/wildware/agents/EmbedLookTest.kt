package dev.wildware.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A look's blocks filled with one embed's values, the way the website draws them. */
class EmbedLookTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private val jira = obj(
        """{"theme":{"accent":"#0052cc","background":"#ffffff","foreground":"#172b4d","muted":"#626f86","border":"#dfe1e6","font":"sans","radius":"sm"},
           "frame":{"border":true,"stripe":false,"padding":"normal"},
           "chrome":{"mark":"jira"},
           "blocks":[{"type":"text","value":"{{key}}","tone":"accent","size":"sm"},{"type":"heading","value":"{{summary}}","level":2},
             {"type":"chips","value":"{{status}}","style":"lozenge"},{"type":"divider"},
             {"type":"fields","layout":"rows","items":[{"label":"Assignee","value":"{{assignee}}"},{"label":"Priority","value":"{{priority}}"}]},
             {"type":"divider"},{"type":"footer","value":"Open in Jira","url":"{{url}}"}]}""",
    )

    private fun look(spec: JsonObject, data: String, source: String? = null) =
        EmbedLook.resolve(EmbedView(id = "e1", type = "jira-issue", typeName = "Jira issue", spec = spec, data = obj(data), sourceUrl = source))

    @Test
    fun `placeholders fill and a block whose values never arrived is left out`() {
        val drawn = look(jira, """{"key":"FEED-12","summary":"Badge shows 0","status":"In progress","assignee":"Lumen"}""")
        assertEquals(
            listOf(
                EmbedBlock.Text("FEED-12", EmbedBlock.Tone.Accent, EmbedBlock.Size.Sm),
                EmbedBlock.Heading("Badge shows 0", 2),
                EmbedBlock.Chips(listOf("In progress"), EmbedBlock.ChipStyle.Lozenge),
                EmbedBlock.Divider,
                EmbedBlock.Fields(listOf("Assignee" to "Lumen"), inline = false),
                EmbedBlock.Divider,
                EmbedBlock.Footer("Open in Jira", null),
            ),
            drawn.blocks,
        )
    }

    @Test
    fun `dividers that separate nothing go and a list joins into chips`() {
        val spec = obj("""{"blocks":[{"type":"divider"},{"type":"chips","value":"{{labels}}"},{"type":"divider"},{"type":"divider"},{"type":"text","value":"{{none}}"},{"type":"divider"}]}""")
        val drawn = look(spec, """{"labels":["bug"," ios ",""]}""")
        assertEquals(listOf<EmbedBlock>(EmbedBlock.Chips(listOf("bug", "ios"), EmbedBlock.ChipStyle.Pill)), drawn.blocks)
    }

    @Test
    fun `links are http or https only and the source url is offered to the look`() {
        val spec = obj("""{"blocks":[{"type":"footer","value":"View","url":"{{source_url}}"},{"type":"footer","url":"javascript:alert(1)"}]}""")
        val drawn = look(spec, "{}", source = "https://github.com/x/y/issues/1")
        assertEquals(listOf<EmbedBlock>(EmbedBlock.Footer("View", "https://github.com/x/y/issues/1")), drawn.blocks)
    }

    @Test
    fun `pictures are an upload or an https address and nothing else`() {
        assertEquals(EmbedImage.Media("01JABCDEFGHJKMNPQRSTVWXYZ0"), EmbedLook.image("01JABCDEFGHJKMNPQRSTVWXYZ0"))
        assertEquals(EmbedImage.Url("https://a.example/x.png"), EmbedLook.image(" https://a.example/x.png "))
        assertNull(EmbedLook.image("http://a.example/x.png"))
        assertNull(EmbedLook.image("data:image/png;base64,AAAA"))
    }

    @Test
    fun `a header with only an avatar is not drawn and the body indents past a real one`() {
        val spec = obj("""{"frame":{"indent":true},"blocks":[{"type":"header","avatar":"{{avatar}}","title":"{{name}}","size":"lg","shape":"rounded"},{"type":"markdown","value":"{{body}}"}]}""")
        val lone = look(spec, """{"avatar":"https://a.example/a.png","body":"hi"}""")
        assertEquals(listOf<EmbedBlock>(EmbedBlock.Markdown("hi", EmbedBlock.Size.Md)), lone.blocks)
        assertFalse(lone.indent)
        val full = look(spec, """{"avatar":"https://a.example/a.png","name":"Rover","body":"hi"}""")
        assertTrue(full.indent)
        assertEquals(EmbedBlock.Size.Lg, (full.blocks.first() as EmbedBlock.Header).size)
    }

    @Test
    fun `the mark and badge come from the chrome and an image beats a drawn mark`() {
        assertEquals(EmbedMark.Drawn("jira", "jira"), look(jira, "{}").mark)
        val both = obj("""{"chrome":{"mark":"slack","image":"https://a.example/m.png","label":"{{team}}"},"blocks":[]}""")
        assertEquals(EmbedMark.Image(EmbedImage.Url("https://a.example/m.png"), "Wildware"), look(both, """{"team":"Wildware"}""").mark)
        assertEquals("Wildware", look(both, """{"team":"Wildware"}""").badge)
        assertNull(look(jira, "{}").badge)
    }

    @Test
    fun `a light background is a light card and none at all is dark`() {
        assertFalse(look(jira, "{}").dark)
        assertTrue(look(obj("""{"blocks":[]}"""), "{}").dark)
        assertTrue(look(obj("""{"theme":{"background":"#14182b"},"blocks":[]}"""), "{}").dark)
    }

    @Test
    fun `anything it does not understand is ignored rather than breaking the card`() {
        val odd = obj("""{"theme":"nope","blocks":[{"type":"wobble","value":"x"},{"type":"heading"},{"type":"quote","value":"{{q}}"}]}""")
        assertEquals(listOf<EmbedBlock>(EmbedBlock.Quote("said")), look(odd, """{"q":"said"}""").blocks)
    }
}
