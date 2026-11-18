package dev.wildware.feed.server.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** Realistic project names, in the order Node 24 sorts them with `localeCompare` (en-GB, ICU 78). */
class LocaleCompareTest {
    private val nodeOrder = listOf(
        "🚀 Launch",
        "1-2-3",
        "123 Go",
        "a b",
        "a_b",
        "a-b",
        "ab",
        "AB",
        "agent dashboard",
        "Agent Dashboard",
        "agent_dashboard",
        "Agent-Dashboard",
        "Alpha",
        "Alpha 10",
        "alpha 2",
        "alpha-1",
        "Äpfel",
        "apple",
        "Apple",
        "cafe",
        "Cafe",
        "Café",
        "co-op",
        "Co-Op",
        "coop",
        "delta",
        "e",
        "E",
        "é",
        "emile",
        "Émile",
        "launch",
        "Launch 🚀",
        "mod engine",
        "mod-engine",
        "modengine",
        "ModEngine",
        "naive",
        "naïve",
        "o",
        "ss",
        "Ss",
        "ß",
        "v10",
        "v2",
        "V2",
        "web",
        "Web",
        "Web App",
        "web-app",
        "webapp",
        "Webapp",
        "x",
        "X",
        "Zebra",
        "zeta",
        "Δelta",
        "Ελληνικά",
        "Ω",
        "日本語",
    )

    /** A second set, made after the rule above was written, so the rule is not tuned to one list. */
    private val nodeOrder2 = listOf(
        "‐dash",
        "–en",
        "—em",
        "(draft) Plan",
        "@team",
        "#ops",
        "✅ done",
        "😀",
        "\$money",
        "5 things",
        "50% off",
        "a",
        "Ⓐ",
        "angstrom",
        "Ångström",
        "api v2",
        "API_v2",
        "api-v2",
        "api.v2",
        "apiv2",
        "dashboard 2",
        "Dashboard 2.0",
        "Dashboard-2",
        "done",
        "Done ✅",
        "file",
        "ﬁle",
        "five",
        "foo bar",
        "Foo Bar",
        "foo.bar",
        "foo/bar",
        "foo\\bar",
        "foobar",
        "istanbul",
        "İstanbul",
        "iv",
        "Ⅳ",
        "money",
        "moskva",
        "oeuvre",
        "Œuvre",
        "ops",
        "Ops",
        "ore",
        "Øre",
        "plan",
        "Plan (draft)",
        "Plan!",
        "plan?",
        "strasse",
        "straße",
        "team",
        "Uni-code",
        "unicode",
        "Ünicode",
        "Москва",
        "东京",
        "東京",
    )

    /** The one known difference: Node puts `Strasse` before `straße`, the JDK after (spec/README.md). */
    @Test
    fun `a known difference - case against sharp s`() {
        assertEquals(listOf("strasse", "straße", "Strasse"), listOf("Strasse", "straße", "strasse").sortedWith(LocaleCompare))
    }

    @Test
    fun `a second set of names sorts the same`() {
        assertEquals(nodeOrder2, nodeOrder2.reversed().sortedWith(LocaleCompare))
    }

    @Test
    fun `names sort as localeCompare sorts them`() {
        assertEquals(nodeOrder, nodeOrder.reversed().sortedWith(LocaleCompare))
        assertEquals(nodeOrder, nodeOrder.shuffled(kotlin.random.Random(7)).sortedWith(LocaleCompare))
    }
}
