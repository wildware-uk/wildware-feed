package dev.wildware.feed.server.domain

import dev.wildware.feed.server.auth.Passwords
import dev.wildware.feed.server.auth.Tokens
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.db.longOrNull

/**
 * The people of the team (migration 055, docs/TEAMS.md): an account each (email, name, password)
 * and a membership with a role. Today's one owner is the founding membership, [FOUNDER], who signs
 * in with ADMIN_PASSWORD_HASH until they set a password of their own.
 *
 * Roles: an owner can do anything; an admin also runs projects, agents and members; a member uses
 * the feed. Nobody changes their own role, admins never touch owners or other admins, and the team
 * always keeps at least one owner.
 */
class People(
    private val db: Database,
    private val tokenSecret: String,
    private val adminPasswordHash: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Role(val rank: Int) {
        MEMBER(1), ADMIN(2), OWNER(3);

        val wire get() = name.lowercase()

        companion object {
            fun of(text: String?): Role? = entries.firstOrNull { it.wire == text }
        }
    }

    data class Person(
        val id: String,
        val accountId: String,
        val name: String,
        val email: String?,
        val role: Role,
        val handle: String,
        val founder: Boolean,
        val createdAt: Long,
        val removedAt: Long?,
        val hasPassword: Boolean,
        /** Their role line and description (migration 077, [Profiles]); null for none yet. */
        val profileRole: String? = null,
        val description: String? = null,
        /** Their picture's media id, served as `/media/<id>/avatar`; null for the letter avatar. */
        val avatarMediaId: String? = null,
    ) {
        val active get() = removedAt == null
    }

    data class Invite(
        val id: String,
        val email: String?,
        val role: Role,
        val createdBy: String,
        val createdAt: Long,
        val expiresAt: Long,
        val acceptedAt: Long?,
        val acceptedBy: String?,
        val revokedAt: Long?,
    )

    // ---- reading ----------------------------------------------------------------------------------

    fun find(id: String): Person? = db.one("$SELECT AND m.id = ?", id, row = ::person)

    /** Everyone who has ever been in the team, removed people too (their names still label their work). */
    fun list(): List<Person> = db.query("$SELECT ORDER BY m.seq", row = ::person)

    fun active(): List<Person> = db.query("$SELECT AND m.removed_at IS NULL ORDER BY m.seq", row = ::person)

    /**
     * Whether the team has ever had more than one person. Until it has, nothing about people goes on
     * the wire, so a team of one reads exactly as a server did before teams.
     */
    fun isTeam(): Boolean = (db.one("SELECT count(*) FROM memberships WHERE team_id = :team") { it.getLong(1) } ?: 0) > 1

    /** Names by membership id, for labelling what people did. */
    fun names(): Map<String, String> = db.query("SELECT m.id, u.name FROM memberships AS m JOIN users AS u ON u.id = m.user_id WHERE m.team_id = :team") {
        it.getString(1) to it.getString(2)
    }.toMap()

    fun nameOf(id: String?): String? = id?.let { db.one("SELECT u.name FROM memberships AS m JOIN users AS u ON u.id = m.user_id WHERE m.team_id = :team AND m.id = ?", it) { rs -> rs.getString(1) } }

    /** Whether any account has an email, so signing in by email is worth offering. */
    fun anyEmail(): Boolean = db.one("SELECT 1 FROM users AS u JOIN memberships AS m ON m.user_id = u.id WHERE m.team_id = :team AND u.email IS NOT NULL LIMIT 1") { true } == true

    fun activeCount(): Int = db.one("SELECT count(*) FROM memberships WHERE team_id = :team AND removed_at IS NULL") { it.getInt(1) } ?: 0

    // ---- signing in -------------------------------------------------------------------------------

    /**
     * The person with this email and password, or null. An unknown email costs the same as a wrong
     * password, so the answer says nothing about who has an account.
     */
    fun signIn(email: String, password: String): Person? {
        val clean = email.jsTrim().lowercase()
        val account = db.one("SELECT id, password_hash FROM users WHERE email = ?", clean) { it.getString(1) to it.getString(2) }
        if (account == null) {
            Passwords.verify(dummyHash, password)
            return null
        }
        if (!accountPassword(account.first, account.second, password)) return null
        return db.one("$SELECT AND m.user_id = ? AND m.removed_at IS NULL", account.first, row = ::person)
    }

    /**
     * Sign-in with no email: the team's founding owner. On the home team that is [FOUNDER], by
     * ADMIN_PASSWORD_HASH or a password they set. A team the admin API made has no [FOUNDER]
     * membership: its founder is its first owner, by the password they set from their setup link.
     */
    fun signInFounder(password: String): Person? {
        val founder = founder() ?: run {
            Passwords.verify(dummyHash, password)
            return null
        }
        return founder.takeIf { verify(it, password) }
    }

    /** Whom a sign-in with no email is for: the home team's [FOUNDER], or another team's first owner. */
    private fun founder(): Person? = (if (Tenant.id == HOME) find(FOUNDER) else firstOwner())?.takeIf { it.active }

    /**
     * The key one account's wrong passwords are counted under, however it is signed in to: by its
     * email, or with no email as a team's founder. An account is one budget across every team it is
     * in, so guesses can't be spread over its teams or over the two ways in. An unknown email is
     * counted under itself, as a known one is.
     */
    fun signInKey(email: String?): String {
        if (email != null) return "email:" + email.jsTrim().lowercase()
        val founder = founder() ?: return "founder:" + Tenant.id
        val own = db.one("SELECT email FROM users WHERE id = ?", founder.accountId) { it.getString(1) }
        return if (own != null) "email:" + own.jsTrim().lowercase() else "account:" + founder.accountId
    }

    /** [People.signInKey] for [person]: the budget their own password checks are charged to. */
    fun accountKey(person: Person): String {
        val own = db.one("SELECT email FROM users WHERE id = ?", person.accountId) { it.getString(1) }
        return if (own != null) "email:" + own.jsTrim().lowercase() else "account:" + person.accountId
    }

    /** Whether [password] is this person's. */
    fun verify(person: Person, password: String): Boolean {
        val hash = db.one("SELECT password_hash FROM users WHERE id = ?", person.accountId) { it.getString(1) }
        return accountPassword(person.accountId, hash, password)
    }

    /**
     * The founder's account takes ADMIN_PASSWORD_HASH until they set a password of their own; then
     * only theirs, so a leaked server password stops working once they change it. Whoever has the
     * server can still get in with `reset-password`. One hash check either way.
     */
    private fun accountPassword(accountId: String, hash: String?, password: String): Boolean {
        // ADMIN_PASSWORD_HASH is the home team's founder's, and opens no other team.
        val against = hash ?: adminPasswordHash.takeIf { accountId == FOUNDER && Tenant.id == HOME }
        if (against == null || password.isEmpty()) {
            // An account with no password yet costs what a wrong one does, so the time taken
            // tells nobody which emails have an account.
            Passwords.verify(dummyHash, password)
            return false
        }
        return Passwords.verify(against, password)
    }

    /** The hash [person] signs in with: their own, or ADMIN_PASSWORD_HASH for the home founder without one; null for none. */
    fun passwordHashOf(person: Person): String? =
        db.one("SELECT password_hash FROM users WHERE id = ?", person.accountId) { it.getString(1) }
            ?: adminPasswordHash.takeIf { person.accountId == FOUNDER && Tenant.id == HOME }

    /** The founder's own password hash, or null while they still use ADMIN_PASSWORD_HASH. */
    fun founderHash(): String? = db.one("SELECT password_hash FROM users WHERE id = ?", FOUNDER) { it.getString(1) }

    /**
     * On Postgres, holds the team's row for the rest of the transaction, so two changes to who is
     * an owner can't both pass the last-owner check. SQLite already runs one write at a time.
     */
    private fun lockTeam() {
        if (db.dialect == dev.wildware.feed.server.db.Dialect.POSTGRES) db.one("SELECT id FROM teams WHERE id = ? FOR UPDATE", Tenant.id) { it.getString(1) }
    }

    /** Refuses one more login when the team has none free (the team's limit, docs/HOSTING.md §7). Set by the app. */
    @Volatile var checkSeat: () -> Unit = {}

    private val dummyHash by lazy { Passwords.hash(Tokens.random()) }

    private fun badInvite() = DomainError("invalid_invite", "That invite has expired, was used already, or isn't right. Ask for a new one.", 400)

    // ---- invites ----------------------------------------------------------------------------------

    /** A new invite and its token, shown once. Owners invite any role; admins invite members. */
    fun invite(by: Person, role: Role, email: String?): Pair<Invite, String> = db.transaction { _ ->
        requireActive(by)
        if (!canGrant(by, role)) throw forbidden("only an owner can invite an ${role.wire}")
        val clean = email?.let(::checkedEmail)
        if (clean != null) {
            val taken = db.one(
                "SELECT 1 FROM users AS u JOIN memberships AS m ON m.user_id = u.id WHERE m.team_id = :team AND u.email = ? AND m.removed_at IS NULL", clean,
            ) { true }
            if (taken == true) throw conflict("$clean is already in the team")
        }
        val now = clock()
        val recent = db.one("SELECT count(*) FROM invites WHERE team_id = :team AND created_by = ? AND created_at > ?", by.id, now - DAY_MS) { it.getInt(1) } ?: 0
        if (recent >= INVITES_PER_DAY) throw DomainError("rate_limited", "That is $INVITES_PER_DAY invites today. Try again tomorrow.", 429)
        // A waiting invite takes a login, so the team's last free one can't go to two invites.
        checkSeat()
        val token = Tokens.random()
        val id = Ulid.next(now)
        db.update(
            "INSERT INTO invites (id, team_id, email, role, token_hash, created_by, created_at, expires_at) VALUES (?, :team, ?, ?, ?, ?, ?, ?)",
            id, clean, role.wire, inviteHash(token), by.id, now, now + INVITE_TTL_MS,
        )
        findInvite(id)!! to token
    }

    fun findInvite(id: String): Invite? = db.one("SELECT $INVITE FROM invites WHERE team_id = :team AND id = ?", id, row = ::invite)

    /** Invites still waiting: not used, not revoked, not expired. */
    fun pendingInvites(): List<Invite> = db.query(
        "SELECT $INVITE FROM invites WHERE team_id = :team AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > ? ORDER BY seq", clock(), row = ::invite,
    )

    /** Revoke a waiting invite; only someone who could have made it may. False when it was not waiting. */
    fun revokeInvite(by: Person, id: String): Boolean {
        requireActive(by)
        val invite = findInvite(id) ?: throw notFound("no such invite: $id")
        if (!canGrant(by, invite.role)) throw forbidden("only an owner can revoke an invite for an ${invite.role.wire}")
        return db.update("UPDATE invites SET revoked_at = ? WHERE team_id = :team AND id = ? AND accepted_at IS NULL AND revoked_at IS NULL", clock(), id) == 1
    }

    /** The waiting invite this token opens, or null for any other token (used, revoked, expired or made up). */
    fun peek(token: String): Invite? {
        val trimmed = token.jsTrim()
        if (!Tokens.wellFormed(trimmed)) return null
        return db.one(
            "SELECT $INVITE FROM invites WHERE team_id = :team AND token_hash = ? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > ?",
            inviteHash(trimmed), clock(), row = ::invite,
        )
    }

    /**
     * Join with an invite: a new account (or an existing one, by its email and password) and a
     * membership with the invite's role. The invite is used up in the same transaction, so two
     * people racing on one link can't both join.
     */
    fun accept(token: String, name: String, email: String, password: String): Person {
        val cleanName = requiredText(name, "name", NAME_MAX)
        val cleanEmail = checkedEmail(email)
        checkNewPassword(password)
        // A bad link is refused before any password work. It is checked again below, where it is used up.
        peek(token) ?: throw badInvite()
        val hashed = Passwords.hash(password)
        return db.transaction { _ ->
            val invite = peek(token) ?: throw badInvite()
            if (invite.email != null && invite.email != cleanEmail) throw invalid("this invite is for another email address")
            val existing = db.one("SELECT id, password_hash FROM users WHERE email = ?", cleanEmail) { it.getString(1) to it.getString(2) }
            val accountId = if (existing != null) {
                if (!accountPassword(existing.first, existing.second, password)) {
                    throw WrongPassword("invalid_argument", "$cleanEmail already has an account here: use its password")
                }
                existing.first
            } else {
                Ulid.next(clock()).also {
                    db.update("INSERT INTO users (id, email, name, password_hash, created_at) VALUES (?, ?, ?, ?, ?)", it, cleanEmail, cleanName, hashed, clock())
                }
            }
            val already = db.one("SELECT 1 FROM memberships WHERE team_id = :team AND user_id = ? AND removed_at IS NULL", accountId) { true }
            if (already == true) throw conflict("$cleanEmail is already in the team")
            val id = Ulid.next(clock())
            db.update(
                "INSERT INTO memberships (id, team_id, user_id, role, handle, founder, created_at) VALUES (?, :team, ?, ?, ?, 0, ?)",
                id, accountId, invite.role.wire, freeHandle(cleanName, null), clock(),
            )
            val used = db.update(
                "UPDATE invites SET accepted_at = ?, accepted_by = ? WHERE team_id = :team AND id = ? AND accepted_at IS NULL AND revoked_at IS NULL",
                clock(), id, invite.id,
            )
            if (used != 1) throw DomainError("invalid_invite", "That invite was used already. Ask for a new one.", 400)
            find(id)!!
        }
    }

    // ---- changing people --------------------------------------------------------------------------

    /**
     * Change someone else's role. Only owners change roles (admins would only ever be allowed to
     * make a member a member), and the last owner stays an owner.
     */
    fun setRole(by: Person, targetId: String, role: Role): Person = db.transaction { _ ->
        requireActive(by)
        lockTeam()
        val target = find(targetId)?.takeIf { it.active } ?: throw notFound("no such person: $targetId")
        if (target.id == by.id) throw forbidden("nobody changes their own role")
        if (by.role != Role.OWNER) throw forbidden("only an owner changes roles")
        if (target.role == Role.OWNER && role != Role.OWNER && ownerCount() <= 1) throw conflict("the team needs at least one owner")
        db.update("UPDATE memberships SET role = ? WHERE team_id = :team AND id = ?", role.wire, target.id)
        // Invites they made that their new role could not make are withdrawn with it.
        if (role.rank < target.role.rank) revokeInvitesBeyond(target.id, role)
        // A member can't let agents in, so what they let in as an admin goes with the role.
        if (role.rank < Role.ADMIN.rank) revokeWhatTheyLetIn(Tenant.id, target.id)
        find(target.id)!!
    }

    /**
     * Remove someone (or leave, when [targetId] is [by] themselves). Owners remove anyone; admins
     * remove members. Their keys stop working at once, their waiting invites are withdrawn, and what
     * they wrote stays, under their name. Answers the ids of the devices that were signed out.
     */
    fun remove(by: Person, targetId: String): Pair<Person, List<String>> = db.transaction { _ ->
        requireActive(by)
        lockTeam()
        val target = find(targetId)?.takeIf { it.active } ?: throw notFound("no such person: $targetId")
        if (target.id != by.id) {
            when (by.role) {
                Role.OWNER -> Unit
                Role.ADMIN -> if (target.role != Role.MEMBER) throw forbidden("only an owner removes an ${target.role.wire}")
                Role.MEMBER -> throw forbidden("only an owner or an admin removes people")
            }
        }
        if (target.role == Role.OWNER && ownerCount() <= 1) throw conflict("the team needs at least one owner")
        db.update("UPDATE memberships SET removed_at = ? WHERE team_id = :team AND id = ? AND removed_at IS NULL", clock(), target.id)
        revokeInvitesBeyond(target.id, null)
        revokeWhatTheyLetIn(Tenant.id, target.id)
        val devices = db.query("SELECT id FROM owner_devices WHERE team_id = :team AND user_id = ?", target.id) { it.getString(1) }
        db.update("DELETE FROM owner_devices WHERE team_id = :team AND user_id = ?", target.id)
        find(target.id)!! to devices
    }

    /** What deleting an account did: for each team it left, the devices that were signed out there. */
    data class DeletedAccount(val accountId: String, val devicesByTeam: Map<String, List<String>>, val agentsByTeam: Map<String, List<String>> = emptyMap())

    /**
     * A person deletes their own account (App Store guideline 5.1.1(v)): here, and in every team on
     * this server. Refused while they own any team: an owner first makes someone else an owner or
     * deletes the team, so no team is left without one and none disappears under the people still in
     * it. Otherwise each membership ends as [remove] ends one (devices signed out, invites
     * withdrawn), their one-time codes go, and the account forgets its name, email and password. What
     * they wrote stays, under [DELETED_NAME]. A row in account_deletions is the audit, and gives the
     * control plane a hash of the email so it can forget it too. [password] is checked when the
     * account has one.
     */
    fun deleteAccount(me: Person, password: String?): DeletedAccount {
        requireActive(me)
        // The slow password work happens before the write, so it never holds the database.
        if (passwordHashOf(me) != null && (password == null || !verify(me, password))) {
            throw WrongPassword("wrong_password", "That password is not right.")
        }
        return Tenant.allTeams { db.transaction { _ -> eraseAccount(me.accountId) } }
    }

    /**
     * Revokes the agents [membershipId] let in (pairing, OAuth) and the OAuth connections they
     * allowed, in [team]: someone who leaves keeps no way in through them. The ids of the agents
     * ([Agents.letInBy] lists them first, for the routes to say so). Agents from before migration
     * 073 don't say who let them in, and stay.
     */
    private fun revokeWhatTheyLetIn(team: String, membershipId: String): List<String> {
        val now = clock()
        val agents = db.query("SELECT id FROM agents WHERE team_id = ? AND created_by = ? AND revoked_at IS NULL", team, membershipId) { it.getString(1) }
        db.update("UPDATE agents SET revoked_at = ? WHERE team_id = ? AND created_by = ? AND revoked_at IS NULL", now, team, membershipId)
        db.update("UPDATE oauth_grants SET revoked_at = ? WHERE team_id = ? AND created_by = ? AND revoked_at IS NULL", now, team, membershipId)
        return agents
    }

    /**
     * Signs [me]'s account out on every device but [keepDeviceId], in every team it is in: after a
     * password change, a key someone else holds must stop working too. The ids gone, by team.
     */
    fun signOutOtherDevices(me: Person, keepDeviceId: String): Map<String, List<String>> = Tenant.allTeams {
        db.transaction { _ ->
            val gone = db.query(
                "SELECT d.team_id, d.id FROM owner_devices AS d JOIN memberships AS m ON m.team_id = d.team_id AND m.id = d.user_id " +
                    "WHERE m.user_id = ? AND d.id <> ?",
                me.accountId, keepDeviceId,
            ) { it.getString(1) to it.getString(2) }
            for ((team, id) in gone) db.update("DELETE FROM owner_devices WHERE team_id = ? AND id = ?", team, id)
            gone.groupBy({ it.first }, { it.second })
        }
    }

    private fun eraseAccount(accountId: String): DeletedAccount {
        data class Membership(val id: String, val team: String, val role: String, val teamName: String)
        val memberships = db.query(
            "SELECT m.id, m.team_id, m.role, t.name FROM memberships AS m JOIN teams AS t ON t.id = m.team_id " +
                "WHERE m.user_id = ? AND m.removed_at IS NULL ORDER BY m.seq",
            accountId,
        ) { Membership(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
        val owned = memberships.filter { it.role == Role.OWNER.wire }
        if (owned.isNotEmpty()) {
            val names = owned.joinToString(", ") { it.teamName }
            throw DomainError(
                "owner_must_hand_over",
                "You own $names. Make someone else an owner, or delete the team, before you delete your account.",
                409,
            )
        }
        val now = clock()
        val devices = linkedMapOf<String, List<String>>()
        val agents = linkedMapOf<String, List<String>>()
        for (m in memberships) {
            db.update("UPDATE memberships SET removed_at = ? WHERE team_id = ? AND id = ? AND removed_at IS NULL", now, m.team, m.id)
            // What they said about themselves, and their picture, go with the account (migration 077).
            db.update(
                "UPDATE memberships SET profile_role = NULL, profile_description = NULL, avatar_media_id = NULL WHERE team_id = ? AND id = ?", m.team, m.id,
            )
            db.update(
                "UPDATE invites SET revoked_at = ? WHERE team_id = ? AND created_by = ? AND accepted_at IS NULL AND revoked_at IS NULL",
                now, m.team, m.id,
            )
            db.update("DELETE FROM auth_codes WHERE team_id = ? AND membership_id = ?", m.team, m.id)
            agents[m.team] = revokeWhatTheyLetIn(m.team, m.id)
            devices[m.team] = db.query("SELECT id FROM owner_devices WHERE team_id = ? AND user_id = ?", m.team, m.id) { it.getString(1) }
            db.update("DELETE FROM owner_devices WHERE team_id = ? AND user_id = ?", m.team, m.id)
        }
        val email = db.one("SELECT email FROM users WHERE id = ?", accountId) { it.getString(1) }
        db.update(
            "UPDATE users SET name = ?, email = NULL, email_verified_at = NULL, password_hash = NULL, deleted_at = ? WHERE id = ?",
            DELETED_NAME, now, accountId,
        )
        db.update(
            "INSERT INTO account_deletions (id, account_id, email_hash, teams, deleted_at) VALUES (?, ?, ?, ?, ?)",
            Ulid.next(now), accountId, email?.let(::emailHash), memberships.joinToString(",") { it.team }, now,
        )
        return DeletedAccount(accountId, devices, agents)
    }

    /**
     * A person changes their own name, email or password. Email and password need the current
     * password. A new name gives a new @handle when it is free.
     */
    fun updateMe(me: Person, name: String?, email: String?, password: String?, currentPassword: String?): Person {
        // The slow password work happens before the write, so it never holds the database.
        if ((email != null || password != null) && (currentPassword == null || !verify(me, currentPassword))) {
            throw WrongPassword("wrong_password", "That password is not right.")
        }
        password?.let(::checkNewPassword)
        val hashed = password?.let(Passwords::hash)
        return db.transaction { _ -> changeMe(me, name, email, hashed) }
    }

    private fun changeMe(me: Person, name: String?, email: String?, hashed: String?): Person {
        requireActive(me)
        if (name != null) {
            val clean = requiredText(name, "name", NAME_MAX)
            db.update("UPDATE users SET name = ? WHERE id = ?", clean, me.accountId)
            db.update("UPDATE memberships SET handle = ? WHERE team_id = :team AND id = ?", freeHandle(clean, me.id), me.id)
        }
        if (email != null) {
            val clean = checkedEmail(email)
            val taken = db.one("SELECT id FROM users WHERE email = ?", clean) { it.getString(1) }
            if (taken != null && taken != me.accountId) throw conflict("$clean belongs to another account")
            // A new email is only typed in, not proved (docs/HOSTING.md §5).
            db.update("UPDATE users SET email = ?, email_verified_at = NULL WHERE id = ?", clean, me.accountId)
            // The setup links and sign-in tickets already out went to the old email: none of them stands for the new one.
            if (clean != me.email) {
                Tenant.allTeams {
                    db.update("DELETE FROM auth_codes WHERE membership_id IN (SELECT id FROM memberships WHERE user_id = ?)", me.accountId)
                }
            }
        }
        if (hashed != null) db.update("UPDATE users SET password_hash = ? WHERE id = ?", hashed, me.accountId)
        return find(me.id)!!
    }

    /**
     * `feed-server reset-password --founder`: the founding owner's password, for a founder who set
     * their own and has no email to name them by. Their devices stay signed in.
     */
    fun setFounderPassword(password: String): Person {
        checkNewPassword(password)
        db.update("UPDATE users SET password_hash = ? WHERE id = ?", Passwords.hash(password), FOUNDER)
        return find(FOUNDER)!!
    }

    /** `feed-server reset-password`: whoever has server access sets an account's password. */
    fun setPassword(email: String, password: String): Person {
        checkNewPassword(password)
        val account = db.one("SELECT id FROM users WHERE email = ?", checkedEmail(email)) { it.getString(1) } ?: throw notFound("no account with that email")
        db.update("UPDATE users SET password_hash = ? WHERE id = ?", Passwords.hash(password), account)
        return db.one("$SELECT AND m.user_id = ? ORDER BY m.removed_at IS NULL DESC, m.seq DESC", account, row = ::person)!!
    }

    /**
     * `feed-server create-owner`: whoever has server access adds an owner, for when every owner is
     * locked out. An existing account becomes an owner again; otherwise a new one is made.
     */
    fun createOwner(email: String, name: String, password: String): Person {
        val cleanEmail = checkedEmail(email)
        val cleanName = requiredText(name, "name", NAME_MAX)
        checkNewPassword(password)
        val hashed = Passwords.hash(password)
        return db.transaction { _ -> makeOwner(cleanEmail, cleanName, hashed) }
    }

    /**
     * The admin API's new team (docs/HOSTING.md §7): [email]'s account as this team's owner. The
     * control plane has just proved the email is this person's. An existing account whose email was
     * proved before is linked, its password untouched. One whose email was only ever typed in (an
     * invite, a profile change) may be someone else's claim on it: the proof wins. That account
     * keeps its teams and devices but loses the email, and the owner gets a new account, with no
     * password until they set one from the one-time link. The second value says whether the account is new.
     */
    fun addOwner(email: String, name: String): Pair<Person, Boolean> {
        val cleanEmail = checkedEmail(email)
        val cleanName = requiredText(name, "name", NAME_MAX)
        return db.transaction { _ ->
            val claimed = db.one("SELECT id, email_verified_at FROM users WHERE email = ?", cleanEmail) { it.getString(1) to it.longOrNull("email_verified_at") }
            if (claimed != null && claimed.second == null) db.update("UPDATE users SET email = NULL WHERE id = ?", claimed.first)
            val existing = claimed?.takeIf { it.second != null }
            val accountId = existing?.first ?: Ulid.next(clock()).also {
                db.update("INSERT INTO users (id, email, name, password_hash, created_at, email_verified_at) VALUES (?, ?, ?, NULL, ?, ?)", it, cleanEmail, cleanName, clock(), clock())
            }
            val membership = db.one("SELECT id FROM memberships WHERE team_id = :team AND user_id = ? AND removed_at IS NULL", accountId) { it.getString(1) }
            val person = if (membership != null) {
                db.update("UPDATE memberships SET role = 'owner' WHERE team_id = :team AND id = ?", membership)
                find(membership)!!
            } else {
                val id = Ulid.next(clock())
                db.update(
                    "INSERT INTO memberships (id, team_id, user_id, role, handle, founder, created_at) VALUES (?, :team, ?, 'owner', ?, 0, ?)",
                    id, accountId, freeHandle(cleanName, null), clock(),
                )
                find(id)!!
            }
            person to (existing == null)
        }
    }

    /** The person still in this team with [email], or null; with [proved], only if that email was proved theirs. */
    fun findByEmail(email: String, proved: Boolean = false): Person? {
        val clean = runCatching { checkedEmail(email) }.getOrNull() ?: return null
        val sql = "$SELECT AND u.email = ? AND m.removed_at IS NULL" + if (proved) " AND u.email_verified_at IS NOT NULL" else ""
        return db.one(sql, clean, row = ::person)
    }

    /**
     * Set [person]'s own password from a one-time setup link, which the control plane sent to [sentTo]: that proves
     * it too. Null, and nothing changed, unless the account's email is still [sentTo]: a link proves only the email
     * it went to.
     */
    fun setOwnPassword(person: Person, password: String, sentTo: String?): Person? {
        checkNewPassword(password)
        if (sentTo == null) return null
        val hashed = Passwords.hash(password)
        val changed = db.update(
            "UPDATE users SET password_hash = ?, email_verified_at = coalesce(email_verified_at, ?) WHERE id = ? AND email = ?",
            hashed, clock(), person.accountId, sentTo,
        )
        return if (changed == 1) find(person.id)!! else null
    }

    /** The team's first owner still in it: who the admin API's invites are from. */
    fun firstOwner(): Person? = db.one("$SELECT AND m.role = 'owner' AND m.removed_at IS NULL ORDER BY m.seq LIMIT 1", row = ::person)

    /** A waiting invite's link made again: a new token, so the one shown before stops working. Null when it isn't waiting. */
    fun reissueInvite(id: String): Pair<Invite, String>? {
        val token = Tokens.random()
        val changed = db.update(
            "UPDATE invites SET token_hash = ? WHERE team_id = :team AND id = ? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > ?",
            inviteHash(token), id, clock(),
        )
        return if (changed == 1) findInvite(id)!! to token else null
    }

    private fun makeOwner(cleanEmail: String, cleanName: String, hashed: String): Person {
        val existing = db.one("SELECT id FROM users WHERE email = ?", cleanEmail) { it.getString(1) }
        val accountId = existing ?: Ulid.next(clock()).also {
            db.update("INSERT INTO users (id, email, name, password_hash, created_at) VALUES (?, ?, ?, ?, ?)", it, cleanEmail, cleanName, hashed, clock())
        }
        if (existing != null) db.update("UPDATE users SET password_hash = ? WHERE id = ?", hashed, accountId)
        val membership = db.one("SELECT id FROM memberships WHERE team_id = :team AND user_id = ? AND removed_at IS NULL", accountId) { it.getString(1) }
        return if (membership != null) {
            db.update("UPDATE memberships SET role = 'owner' WHERE team_id = :team AND id = ?", membership)
            find(membership)!!
        } else {
            val id = Ulid.next(clock())
            db.update(
                "INSERT INTO memberships (id, team_id, user_id, role, handle, founder, created_at) VALUES (?, :team, ?, 'owner', ?, 0, ?)",
                id, accountId, freeHandle(cleanName, null), clock(),
            )
            find(id)!!
        }
    }

    // ---- rules ------------------------------------------------------------------------------------

    /** Whether [by] may hand out [role]: owners any, admins members only, members nothing. */
    fun canGrant(by: Person, role: Role): Boolean = when (by.role) {
        Role.OWNER -> true
        Role.ADMIN -> role == Role.MEMBER
        Role.MEMBER -> false
    }

    private fun requireActive(person: Person) {
        if (find(person.id)?.active != true) throw forbidden("you are no longer in this team")
    }

    private fun ownerCount(): Int =
        db.one("SELECT count(*) FROM memberships WHERE team_id = :team AND role = 'owner' AND removed_at IS NULL") { it.getInt(1) } ?: 0

    /** Withdraw [personId]'s waiting invites; with [role], only those that role could not make. */
    private fun revokeInvitesBeyond(personId: String, role: Role?) {
        val keep = when (role) {
            Role.OWNER -> return
            Role.ADMIN -> listOf("member")
            Role.MEMBER, null -> emptyList()
        }
        val roles = listOf("owner", "admin", "member").filter { it !in keep }
        db.update(
            "UPDATE invites SET revoked_at = ? WHERE team_id = :team AND created_by = ? AND accepted_at IS NULL AND revoked_at IS NULL AND role IN (${roles.joinToString(", ") { "?" }})",
            clock(), personId, *roles.toTypedArray(),
        )
    }

    /** A handle made from [name], unique among the people still here, never a word mentions reserve. */
    private fun freeHandle(name: String, self: String?): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(HANDLE_MAX).trim('-').ifEmpty { "person" }
        val taken = db.query("SELECT handle FROM memberships WHERE team_id = :team AND removed_at IS NULL AND id <> ?", self ?: "") { it.getString(1) }.toSet()
        fun free(h: String) = h !in taken && h !in RESERVED_HANDLES
        if (free(base)) return base
        return (2..Int.MAX_VALUE).asSequence().map { "$base-$it" }.first(::free)
    }

    private fun checkedEmail(raw: String): String {
        val clean = raw.jsTrim().lowercase()
        if (clean.length > EMAIL_MAX || !EMAIL.matches(clean)) throw invalid("that doesn't look like an email address")
        return clean
    }

    private fun checkNewPassword(password: String) {
        if (password.length < PASSWORD_MIN) throw invalid("a password needs at least $PASSWORD_MIN characters")
        if (password.length > PASSWORD_MAX) throw invalid("a password has at most $PASSWORD_MAX characters")
    }

    private fun inviteHash(token: String) = Tokens.hex(Tokens.hmac(tokenSecret, "invite:$token"))

    private fun person(rs: java.sql.ResultSet) = Person(
        id = rs.getString("id"),
        accountId = rs.getString("user_id"),
        name = rs.getString("name"),
        email = rs.getString("email"),
        role = Role.of(rs.getString("role"))!!,
        handle = rs.getString("handle"),
        founder = rs.getInt("founder") == 1,
        createdAt = rs.getLong("created_at"),
        removedAt = rs.longOrNull("removed_at"),
        hasPassword = rs.getString("password_hash") != null,
        profileRole = rs.getString("profile_role"),
        description = rs.getString("profile_description"),
        avatarMediaId = rs.getString("avatar_media_id"),
    )

    private fun invite(rs: java.sql.ResultSet) = Invite(
        id = rs.getString("id"),
        email = rs.getString("email"),
        role = Role.of(rs.getString("role"))!!,
        createdBy = rs.getString("created_by"),
        createdAt = rs.getLong("created_at"),
        expiresAt = rs.getLong("expires_at"),
        acceptedAt = rs.longOrNull("accepted_at"),
        acceptedBy = rs.getString("accepted_by"),
        revokedAt = rs.longOrNull("revoked_at"),
    )

    companion object {
        /** The founding membership (and its account), made by migration 055: today's owner. */
        const val FOUNDER = "founder"

        /** The one team a server holds until many-team hosting (docs/HOSTING.md). */
        const val HOME = "home"

        /** How a person follows things, beside `agent:<id>` (migration 052). */
        /** What a deleted account is called, on everything it wrote. */
        const val DELETED_NAME = "Deleted person"

        /** How account_deletions names an email: SHA-256 of it in lower case, hex. The control plane hashes the same way. */
        fun emailHash(email: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(email.trim().lowercase().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun subscriber(memberId: String) = "member:$memberId"

        /**
         * The `subscriptions.updated` payload for [memberId]'s follows: on a team, theirs alone; on a
         * team of one, the `{"subscriber":"human"}` it always was.
         */
        fun followsChanged(db: Database, memberId: String): kotlinx.serialization.json.JsonObject =
            kotlinx.serialization.json.buildJsonObject {
                if ((db.one("SELECT count(*) FROM memberships WHERE team_id = :team") { it.getLong(1) } ?: 0) > 1) {
                    put("subscriber", kotlinx.serialization.json.JsonPrimitive(subscriber(memberId)))
                    put("memberId", kotlinx.serialization.json.JsonPrimitive(memberId))
                } else {
                    put("subscriber", kotlinx.serialization.json.JsonPrimitive("human"))
                }
            }

        /**
         * [memberId]'s name, but only on a team: what agents are shown about people. On a team of
         * one it is null, so an agent reads exactly what it always has.
         */
        fun teamName(db: Database, memberId: String?): String? {
            if (memberId == null) return null
            if ((db.one("SELECT count(*) FROM memberships WHERE team_id = :team") { it.getLong(1) } ?: 0) < 2) return null
            return db.one("SELECT u.name FROM memberships AS m JOIN users AS u ON u.id = m.user_id WHERE m.team_id = :team AND m.id = ?", memberId) { it.getString(1) }
        }

        const val INVITE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val DAY_MS = 24L * 60 * 60 * 1000
        const val INVITES_PER_DAY = 50
        const val NAME_MAX = 100
        private const val EMAIL_MAX = 254
        private const val HANDLE_MAX = 30
        const val PASSWORD_MIN = 8
        private const val PASSWORD_MAX = 200
        private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

        /** Words `@` already means something by: groups of agents, and the owner. */
        private val RESERVED_HANDLES = setOf("here", "everyone", "you", "owner", "all")

        private const val SELECT = "SELECT m.id, m.user_id, u.name, u.email, m.role, m.handle, m.founder, m.created_at, m.removed_at, u.password_hash, m.profile_role, m.profile_description, m.avatar_media_id " +
            "FROM memberships AS m JOIN users AS u ON u.id = m.user_id WHERE m.team_id = :team"
        private const val INVITE = "id, email, role, created_by, created_at, expires_at, accepted_at, accepted_by, revoked_at"
    }
}

fun forbidden(message: String) = DomainError("forbidden", message, 403)
