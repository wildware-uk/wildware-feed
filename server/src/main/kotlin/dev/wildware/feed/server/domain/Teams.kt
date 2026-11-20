package dev.wildware.feed.server.domain

import dev.wildware.feed.server.Config
import dev.wildware.feed.server.auth.Tokens
import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Dialect
import dev.wildware.feed.server.db.TeamTables
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.db.longOrNull
import dev.wildware.feed.server.media.MediaPaths

/**
 * The teams on this server (docs/HOSTING.md §4, §7). A self-hosted server has one, the home team,
 * with no limits. A server with many ([Config.teamDomain]) gives each its own address and the
 * limits the control plane sets through the admin API: logins (seats), storage, a suspension.
 *
 * The server knows only these numbers and switches, never what a plan or a price is.
 */
class Teams(
    private val db: Database,
    private val config: Config,
    private val paths: MediaPaths? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class State(val wire: String) { ACTIVE("active"), DELETING("deleting"), DELETED("deleted") }

    data class Team(
        val id: String,
        val slug: String,
        val name: String,
        val externalRef: String?,
        val seatLimit: Long?,
        val storageLimit: Long?,
        val suspendedAt: Long?,
        val suspendedReason: String?,
        val suspendedMessage: String?,
        val state: State,
        val createdAt: Long,
        val deletedAt: Long?,
        val updatedAt: Long,
    ) {
        val suspended: Boolean get() = suspendedAt != null
        val open: Boolean get() = state == State.ACTIVE
    }

    fun find(id: String): Team? = db.one("SELECT $COLUMNS FROM teams WHERE id = ?", id, row = ::team)

    /** The team at [slug] that still answers: not being deleted, not gone. */
    fun bySlug(slug: String): Team? = db.one("SELECT $COLUMNS FROM teams WHERE slug = ? AND state = 'active'", slug, row = ::team)

    fun byExternalRef(ref: String): List<Team> =
        db.query("SELECT $COLUMNS FROM teams WHERE external_ref = ? AND state <> 'deleted' ORDER BY seq", ref, row = ::team)

    /** Every team that isn't gone, oldest first. */
    fun all(): List<Team> = db.query("SELECT $COLUMNS FROM teams WHERE state <> 'deleted' ORDER BY seq", row = ::team)

    /** This work's team ([Tenant.id]). */
    fun current(): Team? = find(Tenant.id)

    /**
     * A team's own address, where its links point: `PUBLIC_BASE_URL` for the home team, and
     * `<scheme>://<slug>.<TEAM_DOMAIN>` for any other, with the scheme of `PUBLIC_BASE_URL`.
     */
    fun address(team: Team): String = addressOf(team.id, team.slug)

    fun addressOf(id: String, slug: String): String {
        val domain = config.teamDomain
        if (id == Tenant.HOME || domain == null) return config.publicBaseUrl
        val scheme = if (config.publicBaseUrl.startsWith("http://")) "http" else "https"
        return "$scheme://$slug.$domain"
    }

    /** This work's team's address. */
    fun address(): String {
        val id = Tenant.id
        if (id == Tenant.HOME || config.teamDomain == null) return config.publicBaseUrl
        return find(id)?.let(::address) ?: config.publicBaseUrl
    }

    // ---- making and changing teams ------------------------------------------------------------------

    /** Why [slug] can't be a new team's, or null when it can. */
    fun slugProblem(slug: String): DomainError? {
        if (!SLUG.matches(slug) || "--" in slug) {
            return DomainError("invalid_slug", "A team's address is 3 to 30 of a-z, 0-9 and -, starting with a letter, with no -- and no - at the end.", 422)
        }
        if (slug in RESERVED_SLUGS) return DomainError("slug_reserved", "That address is reserved.", 409)
        val held = db.one("SELECT state, deleted_at FROM teams WHERE slug = ?", slug) { it.getString(1) to it.longOrNull("deleted_at") }
            ?: return null
        return if (held.first == State.DELETED.wire) {
            DomainError("slug_reserved", "That address belonged to a team deleted in the last $SLUG_HOLD_DAYS days.", 409)
        } else {
            DomainError("slug_taken", "That address is taken.", 409)
        }
    }

    /** A new, empty team with the built-in apps and embed types every team has. */
    fun create(slug: String, name: String, externalRef: String?, seats: Long?, storageBytes: Long?): Team {
        slugProblem(slug)?.let { throw it }
        val now = clock()
        val id = Ulid.next(now)
        db.update(
            "INSERT INTO teams (id, slug, name, created_at, external_ref, seat_limit, storage_limit, state, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'active', ?)",
            id, slug, name, now, externalRef, seats, storageBytes, now,
        )
        // (SQLite keeps names unique across the file, and only ever holds one team: Config refuses more.)
        if (db.dialect == Dialect.POSTGRES) Tenant.allTeams { copyBuiltIns(id) }
        return find(id)!!
    }

    /**
     * The built-in apps and embed types (rows with no agent, made by migrations in the home team):
     * every team starts with its own copy, so a team can't take one of their names for itself.
     */
    private fun copyBuiltIns(to: String) {
        val now = clock()
        val apps = db.query("SELECT uri, name, description, html, url, csp FROM apps WHERE team_id = ? AND agent_id IS NULL ORDER BY seq", Tenant.HOME) { rs ->
            listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))
        }
        for (a in apps) {
            db.update(
                "INSERT INTO apps (team_id, id, uri, name, description, html, url, csp, agent_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)",
                to, Ulid.next(now), a[0], a[1], a[2], a[3], a[4], a[5], now, now,
            )
        }
        val types = db.query("SELECT slug, name, spec FROM embed_types WHERE team_id = ? AND agent_id IS NULL ORDER BY seq", Tenant.HOME) { rs ->
            Triple(rs.getString(1), rs.getString(2), rs.getString(3))
        }
        for ((slug, name, spec) in types) {
            db.update(
                "INSERT INTO embed_types (team_id, id, slug, name, spec, agent_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, NULL, ?, ?)",
                to, Ulid.next(now), slug, name, spec, now, now,
            )
        }
    }

    /** Set either limit; null leaves it. A limit below what is used is kept: new seats or bytes are refused until under it. */
    fun setLimits(id: String, seats: Long?, storageBytes: Long?): Team? {
        val now = clock()
        if (seats != null) db.update("UPDATE teams SET seat_limit = ?, updated_at = ? WHERE id = ? AND state <> 'deleted'", seats, now, id)
        if (storageBytes != null) db.update("UPDATE teams SET storage_limit = ?, updated_at = ? WHERE id = ? AND state <> 'deleted'", storageBytes, now, id)
        return find(id)?.takeIf { it.state != State.DELETED }
    }

    /** Suspend (read-only) or resume; setting the state it is already in is fine. */
    fun setSuspended(id: String, suspended: Boolean, reason: String?, message: String?): Team? {
        val now = clock()
        if (suspended) {
            db.update(
                "UPDATE teams SET suspended_at = coalesce(suspended_at, ?), suspended_reason = ?, suspended_message = ?, updated_at = ? WHERE id = ? AND state <> 'deleted'",
                now, reason, message, now, id,
            )
        } else {
            db.update(
                "UPDATE teams SET suspended_at = NULL, suspended_reason = NULL, suspended_message = NULL, updated_at = ? WHERE id = ? AND state <> 'deleted'",
                now, id,
            )
        }
        return find(id)?.takeIf { it.state != State.DELETED }
    }

    /** Start deleting: the address stops answering at once and [purgeDue] removes everything. */
    fun markDeleting(id: String): Team? {
        if (id == Tenant.HOME) throw DomainError("invalid_request", "The home team can't be deleted.", 400)
        db.update("UPDATE teams SET state = 'deleting', updated_at = ? WHERE id = ? AND state = 'active'", clock(), id)
        return find(id)?.takeIf { it.state == State.DELETING }
    }

    /**
     * The deletion job: every row of each team being deleted, child tables first, then its files
     * (`t/<team id>/` in the store), then accounts left in no team. The team's row stays, marked
     * deleted, so its address is held for [SLUG_HOLD_DAYS] days; then it goes too.
     */
    fun purgeDue() {
        val due = db.query("SELECT id FROM teams WHERE state = 'deleting' ORDER BY seq") { it.getString(1) }
        for (id in due) {
            if (id == Tenant.HOME) continue
            Tenant.allTeams {
                db.transaction { c ->
                    // Links are checked once, at the end, when every row of the team has gone.
                    c.createStatement().use { it.execute(if (db.dialect == Dialect.POSTGRES) "SET CONSTRAINTS ALL DEFERRED" else "PRAGMA defer_foreign_keys = ON") }
                    for (table in deleteOrder()) db.update("DELETE FROM \"$table\" WHERE team_id = ?", id)
                    db.update(
                        "DELETE FROM users WHERE id <> ? AND NOT EXISTS (SELECT 1 FROM memberships AS m WHERE m.user_id = users.id)",
                        People.FOUNDER,
                    )
                }
            }
            val p = paths
            if (p != null) {
                val gone = runCatching { p.store.deletePrefix(p.teamPrefix(id)) }
                    .onFailure { System.err.println("team purge: files not removed yet, trying again later: $it") }.isSuccess
                if (!gone) continue
            }
            db.update("UPDATE teams SET state = 'deleted', deleted_at = ?, updated_at = ? WHERE id = ? AND state = 'deleting'", clock(), clock(), id)
            println("team purge: removed a team's rows and files")
        }
        db.update("DELETE FROM teams WHERE state = 'deleted' AND deleted_at < ?", clock() - SLUG_HOLD_DAYS * DAY_MS)
    }

    /** The team tables, each after every table that points at it, so deletes never break a link. */
    private fun deleteOrder(): List<String> {
        val parents: Map<String, Set<String>> = db.withConnection { c ->
            val schema = c.schema
            TeamTables.ALL.associateWith { table ->
                c.metaData.getImportedKeys(null, if (db.dialect == Dialect.POSTGRES) schema else null, table).use { rs ->
                    buildSet { while (rs.next()) add(rs.getString("PKTABLE_NAME").lowercase()) }
                }.filter { it in TeamTables.ALL && it != table }.toSet()
            }
        }
        // Children first: a table goes once nothing left still points at it.
        val left = TeamTables.ALL.toMutableSet()
        val order = mutableListOf<String>()
        while (left.isNotEmpty()) {
            val next = left.filter { t -> left.none { other -> t in parents[other].orEmpty() } }.ifEmpty { left.toList() }
            order += next
            left -= next.toSet()
        }
        return order
    }

    // ---- limits -------------------------------------------------------------------------------------

    /** Logins taken in this team: people still in it, and invites still waiting. */
    fun seatsUsed(): Pair<Long, Long> {
        val people = db.one("SELECT count(*) FROM memberships WHERE team_id = :team AND removed_at IS NULL") { it.getLong(1) } ?: 0
        val waiting = db.one(
            "SELECT count(*) FROM invites WHERE team_id = :team AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > ?", clock(),
        ) { it.getLong(1) } ?: 0
        return people to waiting
    }

    /** Refuse one more login when this team has none free. */
    fun checkSeat() {
        val limit = current()?.seatLimit ?: return
        val (people, waiting) = seatsUsed()
        if (people + waiting >= limit) {
            throw DomainError("seat_limit", "This team has no free logins left ($limit). Remove someone or a waiting invite, or add logins.", 409)
        }
    }

    /** Bytes this team's media and their derivatives take. */
    fun storageUsed(): Long {
        val media = db.one("SELECT coalesce(sum(bytes), 0) FROM media WHERE team_id = :team") { it.getLong(1) } ?: 0
        val derived = db.one("SELECT coalesce(sum(bytes), 0) FROM derivatives WHERE team_id = :team") { it.getLong(1) } ?: 0
        return media + derived
    }

    /** Refuse [bytes] more when they would take this team past its storage. */
    fun checkStorage(bytes: Long) {
        val limit = current()?.storageLimit ?: return
        if (storageUsed() + bytes > limit) {
            throw DomainError("storage_full", "This team's storage is full ($limit bytes). Delete media, or add storage.", 413)
        }
    }

    // ---- one-time codes -----------------------------------------------------------------------------

    /** A one-time code of [kind] for [membershipId] in this team, valid [ttlMs]; earlier ones of that kind go. Shown once. */
    fun mintCode(kind: String, membershipId: String, ttlMs: Long, replaceEarlier: Boolean = true): Pair<String, Long> {
        val now = clock()
        val code = Tokens.random()
        val expires = now + ttlMs
        db.transaction { _ ->
            if (replaceEarlier) db.update("DELETE FROM auth_codes WHERE team_id = :team AND membership_id = ? AND kind = ?", membershipId, kind)
            db.update(
                "INSERT INTO auth_codes (team_id, id, kind, code_hash, membership_id, created_at, expires_at) VALUES (:team, ?, ?, ?, ?, ?, ?)",
                Ulid.next(now), kind, codeHash(code), membershipId, now, expires,
            )
        }
        return code to expires
    }

    /** The membership a code of [kind] in this team was for, used up now; null for any other code. */
    fun redeemCode(kind: String, code: String): String? {
        if (!Tokens.wellFormed(code)) return null
        return db.one(
            "DELETE FROM auth_codes WHERE team_id = :team AND kind = ? AND code_hash = ? AND expires_at > ? RETURNING membership_id",
            kind, codeHash(code), clock(),
        ) { it.getString(1) }
    }

    /** Whether a code of [kind] in this team is still good, without using it. */
    fun peekCode(kind: String, code: String): String? {
        if (!Tokens.wellFormed(code)) return null
        return db.one(
            "SELECT membership_id FROM auth_codes WHERE team_id = :team AND kind = ? AND code_hash = ? AND expires_at > ?",
            kind, codeHash(code), clock(),
        ) { it.getString(1) }
    }

    private fun codeHash(code: String) = Tokens.hex(Tokens.hmac(config.tokenSecret, "auth-code:$code"))

    fun sweepCodes() {
        db.update("DELETE FROM auth_codes WHERE team_id = :team AND expires_at <= ?", clock())
    }

    private fun team(rs: java.sql.ResultSet) = Team(
        id = rs.getString("id"), slug = rs.getString("slug"), name = rs.getString("name"),
        externalRef = rs.getString("external_ref"), seatLimit = rs.longOrNull("seat_limit"), storageLimit = rs.longOrNull("storage_limit"),
        suspendedAt = rs.longOrNull("suspended_at"), suspendedReason = rs.getString("suspended_reason"), suspendedMessage = rs.getString("suspended_message"),
        state = State.entries.first { it.wire == rs.getString("state") }, createdAt = rs.getLong("created_at"),
        deletedAt = rs.longOrNull("deleted_at"), updatedAt = rs.getLong("updated_at"),
    )

    companion object {
        private const val COLUMNS =
            "id, slug, name, external_ref, seat_limit, storage_limit, suspended_at, suspended_reason, suspended_message, state, created_at, deleted_at, updated_at"
        private val SLUG = Regex("^[a-z][a-z0-9-]{1,28}[a-z0-9]$")
        const val SLUG_HOLD_DAYS = 30L
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val SETUP_TTL_MS = 24L * 60 * 60 * 1000
        const val TICKET_TTL_MS = 60_000L
        const val EXTERNAL_REF_MAX = 64

        /** Addresses no team may have: the service's own names, and ones easy to pass off as it. */
        val RESERVED_SLUGS = setOf(
            "home", "www", "api", "admin", "app", "apps", "mail", "email", "smtp", "imap", "pop", "mx", "ns", "ns1", "ns2", "ftp",
            "feed", "feeds", "help", "status", "docs", "doc", "blog", "billing", "pay", "payments", "support", "static", "cdn", "assets",
            "auth", "login", "signin", "signup", "sign-in", "sign-up", "account", "accounts", "dashboard", "wildware", "team", "teams",
            "root", "system", "internal", "staging", "dev", "test", "mcp", "oauth", "media", "security", "abuse", "postmaster",
            // The mail provider's records live at these names under the team domain.
            "send", "rsend", "resend", "bounce", "bounces", "dkim", "dmarc", "spf",
        )
    }
}
