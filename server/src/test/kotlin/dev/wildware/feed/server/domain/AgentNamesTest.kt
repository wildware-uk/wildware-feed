package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** An agent's name never passes for the owner, the server or an @group, and holds nothing that breaks out of a label. */
class AgentNamesTest {
    private val agents = Agents(Database.memory(), "t".repeat(32))

    @Test
    fun `reserved words and odd characters are refused at every way in`() {
        for (name in listOf("owner", "Owner", " OWNER ", "@owner", "human", "system", "server", "everyone", "here")) {
            assertEquals("invalid_argument", assertFailsWith<DomainError>(name) { agents.mint(name) }.code)
        }
        for (name in listOf("a\"b", "a<b", "a>b", "a`b", "a\nb", "a\u0007b", "a‮b")) {
            assertEquals("invalid_argument", assertFailsWith<DomainError>(name) { agents.mint(name) }.code)
        }
        val (scout, _) = agents.mint("scout")
        assertEquals("invalid_argument", assertFailsWith<DomainError> { agents.rename(scout.id, "owner") }.code)
        // Ordinary names, a machine's own among them, still work.
        assertEquals("Sam's MacBook", agents.mint("Sam's MacBook").first.name)
        assertEquals("owner-helper", agents.rename(scout.id, "owner-helper").name)
    }
}
