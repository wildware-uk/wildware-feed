package dev.wildware.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocLinksTest {
    @Test
    fun links_are_found_with_their_shown_words_and_never_inside_code() {
        val text = "See [[Release plan]] and [[Deploy steps|how to deploy]].\n```\n[[In a fence]]\n```\nAnd `[[inline]]`, [[ Spaced ]], [[]], [[Empty|]]."
        assertEquals(
            listOf(
                DocLinks.Link("Release plan", null),
                DocLinks.Link("Deploy steps", "how to deploy"),
                DocLinks.Link("Spaced", null),
                DocLinks.Link("Empty", null),
            ),
            DocLinks.parse(text),
        )
        assertEquals("how to deploy", DocLinks.parse("[[Deploy steps|how to deploy]]").single().label)
    }

    @Test
    fun links_become_markdown_links_the_app_opens_and_code_stays_as_it_is() {
        val markdown = DocLinks.toMarkdownLinks("Read [[Release plan]] or [[Q&A [draft]|the FAQ]]. `[[code]]`", null)
        assertEquals("Read [Release plan](wildware-doc:Release%20plan) or [[Q&A [draft]|the FAQ]]. `[[code]]`", markdown)
        val shown = DocLinks.toMarkdownLinks("[[Plan #2|the [new] plan]]", null)
        // A title with brackets is not a link, as the dashboard can't make one; shown words are escaped.
        assertEquals("[[Plan #2|the [new] plan]]", shown)
        assertEquals("[the plan](wildware-doc:Plan%20%232)", DocLinks.toMarkdownLinks("[[Plan #2|the plan]]", null))
        assertEquals("no links here", DocLinks.toMarkdownLinks("no links here", null))
    }

    @Test
    fun a_link_address_gives_back_its_title() {
        assertEquals("Release plan", DocLinks.titleOf("wildware-doc:Release%20plan"))
        assertEquals("Café & co", DocLinks.titleOf(DocLinks.urlOf("Café & co", null)))
        assertEquals("100%", DocLinks.titleOf(DocLinks.urlOf("100%", null)))
        // A message's link names its channel; a slash in a title stays in the title.
        assertEquals(DocLinks.Target("ops", "A/B plan"), DocLinks.targetOf(DocLinks.urlOf("A/B plan", "ops")))
        assertEquals(DocLinks.Target(null, "A/B plan"), DocLinks.targetOf(DocLinks.urlOf("A/B plan", null)))
        assertEquals("[the plan](wildware-doc:ops/Plan)", DocLinks.toMarkdownLinks("[[Plan|the plan]]", "ops"))
        assertNull(DocLinks.titleOf("https://example.com"))
        assertNull(DocLinks.titleOf("wildware-doc:"))
    }
}
