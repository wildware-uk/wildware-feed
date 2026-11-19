package dev.wildware.feed.server.domain

import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.auth.Tokens
import dev.wildware.feed.server.db.Database
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Accounts, roles and invites (migration 055, docs/TEAMS.md §5). */
class PeopleTest {
    private var now = 1_700_000_000_000L
    private val db = Database.memory()
    private val people = People(db, "t".repeat(32), Passwords.hash("owner password"), clock = { now })
    private val founder get() = people.find(People.FOUNDER)!!

    @Test
    fun `a fresh server is a team of one, the founder, an owner, signing in with the old password`() {
        assertEquals(listOf(People.FOUNDER), people.list().map { it.id })
        assertEquals(People.Role.OWNER, founder.role)
        assertTrue(!people.isTeam())
        assertEquals(People.FOUNDER, people.signInFounder("owner password")?.id)
        assertNull(people.signInFounder("wrong"))
        assertNull(people.signInFounder(""))
    }

    @Test
    fun `an invite works once, only with its own code, and not after it expires`() {
        val (invite, token) = people.invite(founder, People.Role.MEMBER, null)
        assertTrue(Tokens.wellFormed(token))
        // Only the hash is kept.
        assertEquals(0, db.one("SELECT count(*) FROM invites WHERE token_hash = ?", token) { it.getInt(1) })
        assertNull(people.peek(Tokens.random()), "a made-up code opens nothing")
        assertNull(people.peek(token.dropLast(1) + if (token.last() == 'A') "B" else "A"))
        assertEquals(invite.id, people.peek(token)?.id)
        val sam = people.accept(token, "Sam", "Sam@Example.com", "a long password")
        assertEquals("sam@example.com", sam.email)
        assertEquals(People.Role.MEMBER, sam.role)
        assertEquals("sam", sam.handle)
        assertTrue(people.isTeam())
        val again = assertFailsWith<DomainError> { people.accept(token, "Eve", "eve@example.com", "a long password") }
        assertEquals("invalid_invite", again.code)

        val (_, late) = people.invite(founder, People.Role.MEMBER, null)
        now += People.INVITE_TTL_MS + 1
        assertNull(people.peek(late))
        assertEquals("invalid_invite", assertFailsWith<DomainError> { people.accept(late, "Eve", "eve@example.com", "a long password") }.code)
    }

    @Test
    fun `a revoked invite is dead, and an invite for an email takes only that email`() {
        val (invite, token) = people.invite(founder, People.Role.MEMBER, "kim@example.com")
        assertFailsWith<DomainError> { people.accept(token, "Eve", "eve@example.com", "a long password") }
        assertTrue(people.revokeInvite(founder, invite.id))
        assertNull(people.peek(token))
        val (_, second) = people.invite(founder, People.Role.MEMBER, "kim@example.com")
        assertEquals("kim@example.com", people.accept(second, "Kim", "KIM@example.com", "a long password").email)
    }

    @Test
    fun `invited people sign in with their email, and a wrong or unknown one gets the same answer`() {
        val (_, token) = people.invite(founder, People.Role.ADMIN, null)
        people.accept(token, "Ada", "ada@example.com", "a long password")
        assertEquals("ada@example.com", people.signIn("ADA@example.com ", "a long password")?.email)
        assertNull(people.signIn("ada@example.com", "wrong password"))
        assertNull(people.signIn("nobody@example.com", "a long password"))
        // The founder's old password is not anyone else's.
        assertNull(people.signIn("ada@example.com", "owner password"))
    }

    @Test
    fun `a short password, a bad email or a blank name is refused`() {
        val (_, token) = people.invite(founder, People.Role.MEMBER, null)
        assertFailsWith<DomainError> { people.accept(token, "Sam", "sam@example.com", "short") }
        assertFailsWith<DomainError> { people.accept(token, "Sam", "not an email", "a long password") }
        assertFailsWith<DomainError> { people.accept(token, "  ", "sam@example.com", "a long password") }
        assertNotNull(people.peek(token), "a refused attempt does not use the invite up")
    }

    @Test
    fun `the team always keeps an owner`() {
        val (_, token) = people.invite(founder, People.Role.OWNER, null)
        val second = people.accept(token, "Ada", "ada@example.com", "a long password")
        // Two owners: either may step down or be removed; the last may not.
        people.setRole(second, People.FOUNDER, People.Role.ADMIN)
        assertEquals(People.Role.ADMIN, founder.role)
        val leave = assertFailsWith<DomainError> { people.remove(second, second.id) }
        assertEquals(409, leave.status)
        val demote = assertFailsWith<DomainError> { people.setRole(people.find(second.id)!!, second.id, People.Role.MEMBER) }
        assertEquals(403, demote.status)
    }

    @Test
    fun `handles are unique among the people here and never a reserved word`() {
        fun join(name: String, email: String): People.Person {
            val (_, token) = people.invite(founder, People.Role.MEMBER, null)
            return people.accept(token, name, email, "a long password")
        }
        assertEquals("sam", join("Sam", "a@example.com").handle)
        assertEquals("sam-2", join("sam", "b@example.com").handle)
        assertEquals("everyone-2", join("Everyone", "c@example.com").handle)
        assertEquals("person", join("😀", "d@example.com").handle)
    }

    @Test
    fun `changing your email or password needs your password, and the founder can set one`() {
        assertFailsWith<DomainError> { people.updateMe(founder, null, "me@example.com", null, "wrong") }
        val me = people.updateMe(founder, "Shaun", "me@example.com", "a new long password", "owner password")
        assertEquals("Shaun", me.name)
        assertEquals("shaun", me.handle)
        assertEquals(People.FOUNDER, people.signIn("me@example.com", "a new long password")?.id)
        assertEquals(People.FOUNDER, people.signInFounder("a new long password")?.id, "old apps sign in with the new password")
        // The server's password stops working once they have their own (reset-password is the way back in).
        assertNull(people.signInFounder("owner password"))
        assertNull(people.signIn("me@example.com", "owner password"))
    }

    @Test
    fun `removing someone signs out their devices and withdraws their invites`() {
        val devices = Devices(db, "t".repeat(32), { now })
        val (_, token) = people.invite(founder, People.Role.ADMIN, null)
        val ada = people.accept(token, "Ada", "ada@example.com", "a long password")
        val (_, key) = devices.mint("Ada's phone", ada.id)
        val (_, pending) = people.invite(ada, People.Role.MEMBER, null)
        assertNotNull(devices.authenticate(key))
        val (_, signedOut) = people.remove(founder, ada.id)
        assertEquals(1, signedOut.size)
        assertNull(devices.authenticate(key))
        assertNull(people.peek(pending))
        assertNull(people.signIn("ada@example.com", "a long password"), "a removed person can't sign in")
        // Invited again, the same account comes back with its own password.
        val (_, back) = people.invite(founder, People.Role.MEMBER, null)
        assertFailsWith<DomainError> { people.accept(back, "Ada", "ada@example.com", "a guess at it") }
        assertEquals(ada.accountId, people.accept(back, "Ada", "ada@example.com", "a long password").accountId)
    }

    @Test
    fun `setting a password keeps devices signed in, and server access can always reset it`() {
        val devices = Devices(db, "t".repeat(32), { now })
        val (_, key) = devices.mint("Phone")
        // Deploying changes nothing: the server's password and the old key both work.
        assertEquals(People.FOUNDER, people.signInFounder("owner password")?.id)
        assertNotNull(devices.authenticate(key))
        people.updateMe(founder, null, null, "my own password", "owner password")
        assertNotNull(devices.authenticate(key), "the founder's devices stay signed in")
        // Locked out with no email: the server's CLI sets a new one.
        people.setFounderPassword("found it again")
        assertEquals(People.FOUNDER, people.signInFounder("found it again")?.id)
        assertNull(people.signInFounder("my own password"))
        assertNotNull(devices.authenticate(key))
    }
}
