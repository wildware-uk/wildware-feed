package dev.wildware.feed.server.db

/**
 * The tables whose rows belong to one team, and the rule that keeps teams apart in SQL
 * (docs/HOSTING.md §2): every statement on one of them names `team_id`. Code writes `:team` for
 * the work's team ([Tenant.id]); [expand] turns it into a bound `?`.
 *
 * The lint ([lint], on in the test build) fails any statement that touches one of these tables and
 * never says `team_id`, unless it runs inside [Tenant.allTeams], the deliberate cross-team reads
 * and sweeps. On Postgres, row security ([setRowSecurity]) catches what the lint can't see.
 */
object TeamTables {
    /** Every team-owned table (058 gave each a `team_id`; 055 made memberships and invites, 059 auth_codes, with one; 067 made feedback with one; 072 card_assignees). */
    val ALL: List<String> = listOf(
        "access_audit", "acknowledgements", "agent_pairings", "agents", "app_views", "approvals", "apps", "auth_codes", "card_blocks", "card_fields", "card_refs", "card_secrets",
        "card_assignees", "card_tags", "channel_access", "derivatives", "embed_types", "embeds", "feedback", "group_subscriptions", "invites", "media", "memberships",
        "message_deliveries", "message_mentions", "messages", "notifications", "oauth_clients", "oauth_grants",
        "oauth_tokens", "owner_devices", "project_groups", "project_subscriptions", "projects", "reactions",
        "read_cursors", "secret_agents", "secret_audit", "secret_grants", "secrets", "server_settings", "sessions",
        "tags", "tasks", "thread_subscriptions", "update_shares", "updates", "upload_tokens",
    )

    private val tables = ALL.toSet()

    /** Whether the test build checks every statement ([lint]). */
    @Volatile var lintOn: Boolean = System.getProperty("feed.sqlLint") == "true"

    private val TEAM_MARK = Regex("""(?<![:\w]):team\b""")
    private val TABLE_USE = Regex("""\b(?:FROM|JOIN|INTO|UPDATE)\s+"?([a-z_]+)"?""", RegexOption.IGNORE_CASE)

    /** [sql] with each `:team` made a `?`, and [args] with the team put in at the same places. */
    fun expand(sql: String, args: Array<out Any?>): Pair<String, Array<out Any?>> {
        if (lintOn) lint(sql)
        if (!sql.contains(":team")) return sql to args
        val out = StringBuilder()
        val bound = ArrayList<Any?>(args.size + 2)
        var next = 0
        var i = 0
        val team by lazy { Tenant.id }
        while (i < sql.length) {
            val ch = sql[i]
            if (ch == '?') {
                out.append('?')
                bound += args.getOrElse(next++) { error("too few arguments for: $sql") }
                i++
                continue
            }
            val m = TEAM_MARK.matchAt(sql, i)
            if (m != null) {
                out.append('?')
                bound += team
                i += m.value.length
                continue
            }
            out.append(ch)
            i++
        }
        check(next == args.size) { "too many arguments (${args.size}, used $next) for: $sql" }
        return out.toString() to bound.toTypedArray()
    }

    /** The team tables [sql] touches. */
    fun touched(sql: String): Set<String> =
        TABLE_USE.findAll(sql).map { it.groupValues[1].lowercase() }.filter { it in tables }.toSet()

    /**
     * Fail a statement that touches a team table and never names `team_id`, outside [Tenant.allTeams].
     * Statements written by the tests themselves (seeding rows, reading them back) are theirs to
     * write as they like: the rule is for the server's own code.
     */
    fun lint(sql: String, exemptTests: Boolean = true) {
        if (Tenant.isAllTeams) return
        val hit = touched(sql)
        if (hit.isNotEmpty() && !sql.contains("team_id") && !(exemptTests && fromTestCode())) {
            throw IllegalStateException("SQL lint: a statement on ${hit.sorted()} must name team_id (or run in Tenant.allTeams): $sql")
        }
    }

    private val LAYER = listOf("dev.wildware.feed.server.db.Database", "dev.wildware.feed.server.db.TeamTables", "dev.wildware.feed.server.db.Tenant", "java.", "kotlin.")

    private val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)

    /** Whether the first caller outside the database layer is a test's own class. */
    private fun fromTestCode(): Boolean = walker.walk { frames ->
        frames.map { it.declaringClass }
            .filter { c -> LAYER.none { c.name.startsWith(it) } }
            .findFirst()
            .map { c -> c.protectionDomain?.codeSource?.location?.path?.let { "/test/" in it } == true }
            .orElse(false)
    }

    /** Whether row security is forced on any team table of [db] (Postgres), as a server with many teams leaves it. */
    fun rowSecurityForced(db: Database): Boolean {
        if (db.dialect != Dialect.POSTGRES) return false
        return tableStates(db).values.any { it.second }
    }

    /** The team tables of [db] whose row security is not enabled and forced (Postgres); none on SQLite. */
    fun notForced(db: Database): List<String> {
        if (db.dialect != Dialect.POSTGRES) return emptyList()
        val states = tableStates(db)
        return ALL.filter { states[it] != (true to true) }
    }

    /** Each team table's (enabled, forced) row security. */
    private fun tableStates(db: Database): Map<String, Pair<Boolean, Boolean>> = db.withConnection { c ->
        c.prepareStatement(
            "SELECT relname, relrowsecurity, relforcerowsecurity FROM pg_class " +
                "WHERE relnamespace = current_schema()::regnamespace AND relkind = 'r' AND relname = ANY (?)",
        ).use { st ->
            st.setArray(1, c.createArrayOf("text", ALL.toTypedArray()))
            st.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getBoolean(2) to rs.getBoolean(3)) }
            }
        }
    }

    /**
     * Whether the database login skips row security whatever the tables say: a superuser, or a role
     * with BYPASSRLS. Then the policies do nothing, silently. False on SQLite.
     */
    fun loginSkipsRowSecurity(db: Database): Boolean {
        if (db.dialect != Dialect.POSTGRES) return false
        return db.withConnection { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user").use { it.next() && it.getBoolean(1) }
            }
        }
    }

    /**
     * Turn Postgres row security on or off for every team table. On: `ENABLE` and `FORCE` (the
     * database user owns the tables, and owners skip row security without `FORCE`), so a statement
     * sees only rows of the team the connection was told ([Database.rowSecurity]). Off: as before.
     * Only tables not already so are altered, so running it again is cheap and safe: a server with
     * row security on runs it at every start, and that is what turns it on for a table a later
     * migration added. Nothing on SQLite, which only ever holds one team.
     */
    fun setRowSecurity(db: Database, on: Boolean) {
        if (db.dialect != Dialect.POSTGRES) return
        db.rowSecurity = false
        val states = tableStates(db)
        Tenant.allTeams {
            db.withConnection { c ->
                c.createStatement().use { st ->
                    for (t in ALL) {
                        val (enabled, forced) = states[t] ?: (false to false)
                        if (on) {
                            if (!enabled) st.execute("ALTER TABLE \"$t\" ENABLE ROW LEVEL SECURITY")
                            if (!forced) st.execute("ALTER TABLE \"$t\" FORCE ROW LEVEL SECURITY")
                        } else {
                            if (forced) st.execute("ALTER TABLE \"$t\" NO FORCE ROW LEVEL SECURITY")
                            if (enabled) st.execute("ALTER TABLE \"$t\" DISABLE ROW LEVEL SECURITY")
                        }
                    }
                }
            }
        }
        db.rowSecurity = on
    }
}
