package dev.wildware.feed.server.domain

import dev.wildware.feed.server.http.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmbedsTest {
    @Test
    fun `a spec is rebuilt in parseEmbedSpec's key order, dropping what it does not know`() {
        val stored = """{"blocks":[
            {"value":"Hi","type":"heading","level":2.0},
            {"type":"text","value":"x","tone":null,"size":"sm","extra":1},
            {"type":"header","title":"","bar":false,"avatar":"a","shape":"circle"},
            {"type":"footer","value":"","url":"https://example.com"},
            {"type":"code","value":"x","language":""},
            {"type":"fields","layout":"rows","items":[{"value":"v","label":"l"}]}
          ],
          "frame":{"padding":"tight","indent":true,"border":false,"stripe":"yes"},
          "chrome":{"mark":"github","label":""},
          "theme":{"radius":"md","accent":" #ABC ","font":null}}"""
        assertEquals(
            """{"theme":{"accent":"#aabbcc","radius":"md"},"chrome":{"label":"","mark":"github"},""" +
                """"frame":{"border":false,"indent":true,"padding":"tight"},"blocks":[""" +
                """{"type":"heading","value":"Hi","level":2},{"type":"text","value":"x","size":"sm"},""" +
                """{"type":"header","avatar":"a","title":"","shape":"circle"},{"type":"footer","url":"https://example.com"},""" +
                """{"type":"code","value":"x"},{"type":"fields","items":[{"label":"l","value":"v"}],"layout":"rows"}]}""",
            Wire.encode(Embeds.specOf(stored)!!),
        )
    }

    @Test
    fun `a spec that would be refused today cannot be read, so its embeds are dropped`() {
        assertNull(Embeds.specOf("""{"blocks":[]}"""))
        assertNull(Embeds.specOf("""{"blocks":[{"type":"text"}]}"""))
        assertNull(Embeds.specOf("""{"blocks":[{"type":"text","value":"x","tone":"loud"}]}"""))
        assertNull(Embeds.specOf("""{"blocks":[{"type":"divider"}],"theme":{"accent":"red"}}"""))
        assertNull(Embeds.specOf("not json"))
        assertNull(Embeds.specOf("nope"))
    }

    @Test
    fun `data keeps strings, prints numbers and booleans, skips nulls, and anything else is empty`() {
        assertEquals(
            """{"1":"one","b":"2.5","c":"true","d":["x","1e+21"]}""",
            Wire.encode(Embeds.dataOf("""{"b":2.50,"1":"one","c":true,"n":null,"d":["x",1e21]}""")),
        )
        assertEquals("{}", Wire.encode(Embeds.dataOf("""{"a":{"nested":1}}""")))
        assertEquals("{}", Wire.encode(Embeds.dataOf("""["a"]""")))
        assertEquals("{}", Wire.encode(Embeds.dataOf("null")))
        assertEquals("{}", Wire.encode(Embeds.dataOf((1..41).joinToString(",", "{", "}") { "\"k$it\":\"v\"" })))
    }
}
