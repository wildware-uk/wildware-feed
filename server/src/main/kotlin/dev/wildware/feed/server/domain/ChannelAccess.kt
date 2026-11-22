package dev.wildware.feed.server.domain

import dev.wildware.feed.server.db.Database
import dev.wildware.feed.server.db.Tenant
import dev.wildware.feed.server.db.Ulid
import dev.wildware.feed.server.db.Viewer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Who may read, post in and manage each channel and project (migration 068, spec/14).
 *
 * A person (`member:<membership id>`) or an agent (`agent:<agent id>`) has one level on each
 * channel, the most specific that applies:
 *
 * 1. a team owner always has [Level.MANAGE], whatever any row says;
 * 2. a row for them on the channel;
 * 3. else a row for them on the channel's project (group);
 * 4. else, when the channel or its project is private, [Level.NONE];
 * 5. else today's rule: admins manage, everyone else (members, agents) posts.
 *
 * With no rows and nothing private, that is exactly what the server did before: nobody loses access
 * when this arrives. An agent's [Level.MANAGE] is limited: it never changes permissions.
 *
 * What a viewer may not see ([hidden]) is worked out once for it and kept for a moment, then left
 * out of every read that hands channel content to it ([notHidden], [visible]). Writes check
 * [requirePost]; a channel the caller can't see answers as if it did not exist.
 */
class ChannelAccess(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Level(val rank: Int) {
        NONE(0), READ(1), POST(2), MANAGE(3);

        val wire: String get() = name.lowercase()

        companion object {
            fun of(text: String?): Level? = entries.firstOrNull { it.wire == text }
        }
    }

    /** How a level was reached: `owner`, `channel`, `group`, `private` or `default`. */
    data class Grant(val level: Level, val via: String)

    /** What one viewer may do everywhere, worked out at once. */
    class View(
        val principal: String,
        /** Every channel's level, by id. */
        val channels: Map<String, Grant>,
        /** Every project's (group's) own level, by id: what a channel in it with no row of its own gets. */
        val groups: Map<String, Grant>,
        /** Each channel's project, by channel id. */
        private val groupOf: Map<String, String?>,
        /** What a channel made after this view gives: the default. */
        private val baseline: Level,
        val at: Long,
        val version: Long,
    ) {
        /** Channels this viewer can't read. */
        val hidden: Set<String> = channels.filterValues { it.level == Level.NONE }.keys

        /** Projects this viewer can't see: no read on the project itself and none on any channel in it. */
        val hiddenGroups: Set<String> = groups.filterValues { it.level == Level.NONE }.keys -
            groupOf.filter { (id, g) -> g != null && id !in hidden }.values.filterNotNull().toSet()

        /** The level on [channelId]; content in no channel is everyone's to read and post. */
        fun level(channelId: String?): Level = when (channelId) {
            null -> if (baseline == Level.NONE) Level.NONE else Level.POST
            else -> channels[channelId]?.level ?: baseline
        }
    }

    private class Target(val id: String, val groupId: String?, val private: Boolean, val groupPrivate: Boolean)

    // ---- working it out -----------------------------------------------------------------------------

    /** Everything [principal] may do, now. */
    fun view(principal: String): View {
        val role = roleOf(principal)
        val channels = db.query(
            "SELECT p.id, p.group_id, p.private, coalesce(g.private, 0) FROM projects AS p " +
                "LEFT JOIN project_groups AS g ON g.team_id = p.team_id AND g.id = p.group_id WHERE p.team_id = :team",
        ) { Target(it.getString(1), it.getString(2), it.getInt(3) == 1, it.getInt(4) == 1) }
        val groups = db.query("SELECT id, private FROM project_groups WHERE team_id = :team") { it.getString(1) to (it.getInt(2) == 1) }
        val rows = db.query("SELECT target_kind, target_id, level FROM channel_access WHERE team_id = :team AND principal = ?", principal) {
            (it.getString(1) to it.getString(2)) to Level.of(it.getString(3))!!
        }.toMap()
        val base = baseLevel(principal, role)
        val owner = role == People.Role.OWNER
        fun grant(own: Level?, group: Level?, private: Boolean): Grant = when {
            owner -> Grant(Level.MANAGE, OWNER)
            own != null -> Grant(own, CHANNEL)
            group != null -> Grant(group, GROUP)
            private -> Grant(Level.NONE, PRIVATE)
            else -> Grant(base, DEFAULT)
        }
        val byChannel = channels.associate { t ->
            t.id to grant(rows["channel" to t.id], t.groupId?.let { rows["group" to it] }, t.private || t.groupPrivate)
        }
        val byGroup = groups.associate { (id, private) -> id to grant(null, rows["group" to id], private) }
        return View(principal, byChannel, byGroup, channels.associate { it.id to it.groupId }, if (owner) Level.MANAGE else base, clock(), version())
    }

    /** The level everyone of [role] has where nothing says otherwise: today's rule. */
    private fun baseLevel(principal: String, role: People.Role?): Level = when {
        principal.startsWith(Viewer.AGENT) -> if (agentLive(principal.removePrefix(Viewer.AGENT))) Level.POST else Level.NONE
        role == null -> Level.NONE
        role.rank >= People.Role.ADMIN.rank -> Level.MANAGE
        else -> Level.POST
    }

    private fun roleOf(principal: String): People.Role? {
        if (!principal.startsWith(Viewer.MEMBER)) return null
        return db.one("SELECT role FROM memberships WHERE team_id = :team AND id = ? AND removed_at IS NULL", principal.removePrefix(Viewer.MEMBER)) {
            People.Role.of(it.getString(1))
        }
    }

    private fun agentLive(agentId: String): Boolean =
        db.one("SELECT 1 FROM agents WHERE team_id = :team AND id = ? AND revoked_at IS NULL", agentId) { true } == true

    /** The viewer's [View], kept for a moment so one request asks the database once. Null: the server's own work. */
    fun current(): View? {
        val who = Viewer.currentOrNull ?: return null
        val kept = who.memo as? View
        if (kept != null && kept.version == version() && clock() - kept.at in 0 until KEEP_MS) return kept
        return view(who.principal).also { who.memo = it }
    }

    /** The channels this work's viewer can't read; none for the server's own work. */
    fun hidden(): Set<String> = current()?.hidden.orEmpty()

    /** The projects this work's viewer can't see at all. */
    fun hiddenGroups(): Set<String> = current()?.hiddenGroups.orEmpty()

    /**
     * SQL to add to a WHERE: ` AND (<column> IS NULL OR <column> NOT IN (…))`, leaving out what this
     * work's viewer can't read; empty when it can read everything. A row in no channel is everyone's.
     */
    fun notHidden(column: String): String {
        val hidden = hidden()
        if (hidden.isEmpty()) return ""
        return " AND ($column IS NULL OR $column NOT IN (${hidden.joinToString(", ") { literal(it) }}))"
    }

    /** [notHidden] for a project (group) id column. */
    fun groupNotHidden(column: String): String {
        val hidden = hiddenGroups()
        if (hidden.isEmpty()) return ""
        return " AND ($column IS NULL OR $column NOT IN (${hidden.joinToString(", ") { literal(it) }}))"
    }

    /** The channels [principal] can't read: this work's viewer's own when it is them, else worked out now. */
    fun hiddenFor(principal: String): Set<String> {
        val who = Viewer.currentOrNull
        if (who != null && who.principal == principal) return hidden()
        return view(principal).hidden
    }

    /** [notHidden] for [principal] rather than this work's viewer. */
    fun notHiddenFor(principal: String, column: String): String {
        val hidden = hiddenFor(principal)
        if (hidden.isEmpty()) return ""
        return " AND ($column IS NULL OR $column NOT IN (${hidden.joinToString(", ") { literal(it) }}))"
    }

    /** [groupNotHidden] for [principal] rather than this work's viewer. */
    fun groupNotHiddenFor(principal: String, column: String): String {
        val who = Viewer.currentOrNull
        val hidden = if (who != null && who.principal == principal) hiddenGroups() else view(principal).hiddenGroups
        if (hidden.isEmpty()) return ""
        return " AND ($column IS NULL OR $column NOT IN (${hidden.joinToString(", ") { literal(it) }}))"
    }

    /** Whether this work's viewer may read [channelId] (always, for no channel or the server's own work). */
    fun visible(channelId: String?): Boolean = channelId == null || channelId !in hidden()

    fun groupVisible(groupId: String?): Boolean = groupId == null || groupId !in hiddenGroups()

    /** This work's viewer's level on [channelId]; [Level.MANAGE] for the server's own work. */
    fun level(channelId: String?): Level = current()?.level(channelId) ?: Level.MANAGE

    /** [principal]'s level on [channelId], for fan-out to each reader (alerts, the bell). */
    fun levelFor(principal: String, channelId: String?): Level {
        val who = Viewer.currentOrNull
        if (who != null && who.principal == principal) return level(channelId)
        return view(principal).level(channelId)
    }

    /** Whether [principal] may read [channelId]. */
    fun canRead(principal: String, channelId: String?): Boolean = channelId == null || levelFor(principal, channelId) >= Level.READ

    /** Refuse, as missing, a channel this work's viewer can't read. */
    fun requireRead(channelId: String?, missing: () -> DomainError) {
        if (!visible(channelId)) throw missing()
    }

    /**
     * Refuse a write in [channelId]: as missing when the viewer can't read it, `forbidden` when it may
     * only read.
     */
    fun requirePost(channelId: String?, missing: () -> DomainError) {
        val level = level(channelId)
        if (level == Level.NONE) throw missing()
        if (level < Level.POST) throw forbidden("you can read this channel but not post in it: ask someone who manages it for Post")
    }

    /** Refuse changing [channelId]'s settings unless the viewer manages it. */
    fun requireManage(channelId: String, missing: () -> DomainError) {
        val level = level(channelId)
        if (level == Level.NONE) throw missing()
        if (level < Level.MANAGE) throw forbidden("only people who manage this channel can change it")
    }

    /** This work's viewer's own level on project [groupId]: what its channels with no row of their own get. */
    fun groupLevel(groupId: String): Level = current()?.groups?.get(groupId)?.level ?: Level.MANAGE

    fun requireManageGroup(groupId: String, missing: () -> DomainError) {
        if (!groupVisible(groupId)) throw missing()
        if (groupLevel(groupId) < Level.MANAGE) throw forbidden("only people who manage this project can change it")
    }

    /**
     * Whether a live event may go to this work's viewer: one about a channel (its `projectId`, or
     * the card it names) or a project (`groupId`) they can't see does not (spec/14).
     */
    fun eventVisible(payload: JsonObject): Boolean {
        if (hidden().isEmpty() && hiddenGroups().isEmpty()) return true
        fun text(key: String) = (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        text("groupId")?.let { if (!groupVisible(it)) return false }
        text("projectId")?.let { return visible(it) }
        val card = payload["card"] as? JsonObject
        val (table, id) = when {
            text("updateId") != null -> "updates" to text("updateId")
            text("requestId") != null -> "approvals" to text("requestId")
            text("messageId") != null -> "messages" to text("messageId")
            card != null -> when ((card["kind"] as? JsonPrimitive)?.content) {
                "update" -> "updates"
                "request" -> "approvals"
                else -> "messages"
            } to (card["id"] as? JsonPrimitive)?.content
            else -> return true
        }
        if (id == null) return true
        val channel = db.one("SELECT project_id FROM $table WHERE team_id = :team AND id = ?", id) { it.getString(1) }
        return visible(channel)
    }

    // ---- the Permissions panel ---------------------------------------------------------------------

    /** One person or agent on a channel's or project's panel. */
    data class Entry(
        val principal: String,
        val kind: String,
        val id: String,
        val name: String,
        val role: String?,
        val level: Level,
        val via: String,
        /** The row on this channel or project itself, when there is one. */
        val own: Level?,
        /** On a channel: the row on its project, which it inherits when it has no own row. */
        val inherited: Level?,
    ) {
        val json: JsonObject get() = buildJsonObject {
            put("principal", principal)
            put("kind", kind)
            put("id", id)
            put("name", name)
            put("role", role?.let(::JsonPrimitive) ?: JsonNull)
            put("level", level.wire)
            put("via", via)
            put("own", own?.let { JsonPrimitive(it.wire) } ?: JsonNull)
            put("inherited", inherited?.let { JsonPrimitive(it.wire) } ?: JsonNull)
            put("editable", via != OWNER)
        }
    }

    private data class Who(val principal: String, val kind: String, val id: String, val name: String, val role: People.Role?)

    private fun everyone(): List<Who> {
        val people = db.query(
            "SELECT m.id, u.name, m.role FROM memberships AS m JOIN users AS u ON u.id = m.user_id WHERE m.team_id = :team AND m.removed_at IS NULL ORDER BY m.seq",
        ) { Who(Viewer.MEMBER + it.getString(1), "member", it.getString(1), it.getString(2), People.Role.of(it.getString(3))) }
        val agents = db.query("SELECT id, name FROM agents WHERE team_id = :team AND revoked_at IS NULL ORDER BY lower(name), seq") {
            Who(Viewer.AGENT + it.getString(1), "agent", it.getString(1), it.getString(2), null)
        }
        return people + agents
    }

    private fun rowsOn(kind: String, targetId: String): Map<String, Level> =
        db.query("SELECT principal, level FROM channel_access WHERE team_id = :team AND target_kind = ? AND target_id = ?", kind, targetId) {
            it.getString(1) to Level.of(it.getString(2))!!
        }.toMap()

    /** Everyone's level on channel [projectId], people first. */
    fun channelEntries(projectId: String): List<Entry> {
        val t = target("channel", projectId) ?: throw notFound("no such project: $projectId")
        val own = rowsOn("channel", projectId)
        val group = t.groupId?.let { rowsOn("group", it) }.orEmpty()
        return everyone().map { w ->
            val g = view(w.principal).channels[projectId] ?: Grant(Level.NONE, PRIVATE)
            Entry(w.principal, w.kind, w.id, w.name, w.role?.wire, g.level, g.via, own[w.principal], group[w.principal])
        }
    }

    /** Everyone's level on project [groupId] itself. */
    fun groupEntries(groupId: String): List<Entry> {
        val own = rowsOn("group", groupId)
        return everyone().map { w ->
            val g = view(w.principal).groups[groupId] ?: Grant(Level.NONE, PRIVATE)
            Entry(w.principal, w.kind, w.id, w.name, w.role?.wire, g.level, g.via, own[w.principal], null)
        }
    }

    /** Whether the channel is private on its own, and whether its project is. */
    fun privacy(kind: String, targetId: String): Pair<Boolean, Boolean> {
        val t = target(kind, targetId) ?: throw notFound("no such ${if (kind == "group") "group" else "project"}: $targetId")
        return t.private to t.groupPrivate
    }

    private fun target(kind: String, id: String): Target? = if (kind == "group") {
        db.one("SELECT id, private FROM project_groups WHERE team_id = :team AND id = ?", id) { Target(it.getString(1), null, it.getInt(2) == 1, false) }
    } else {
        db.one(
            "SELECT p.id, p.group_id, p.private, coalesce(g.private, 0) FROM projects AS p " +
                "LEFT JOIN project_groups AS g ON g.team_id = p.team_id AND g.id = p.group_id WHERE p.team_id = :team AND p.id = ?",
            id,
        ) { Target(it.getString(1), it.getString(2), it.getInt(3) == 1, it.getInt(4) == 1) }
    }

    private fun targetName(kind: String, id: String): String =
        db.one("SELECT name FROM ${if (kind == "group") "project_groups" else "projects"} WHERE team_id = :team AND id = ?", id) { it.getString(1) } ?: id

    /**
     * Give [principal] [level] on a channel or project, or take its row away (null: inherit again).
     * [actor] must manage it; a team owner's level can't be changed (they always manage).
     */
    fun setLevel(kind: String, targetId: String, principal: String, level: Level?, actor: People.Person) {
        checkKind(kind)
        requireActorManages(kind, targetId, actor)
        val who = everyone().firstOrNull { it.principal == principal } ?: throw notFound("no such person or agent: $principal")
        if (who.role == People.Role.OWNER) throw DomainError("conflict", "a team owner always manages every channel and project", 409)
        val before = rowsOn(kind, targetId)[principal]
        if (before == level) return
        val now = clock()
        if (level == null) {
            db.update("DELETE FROM channel_access WHERE team_id = :team AND target_kind = ? AND target_id = ? AND principal = ?", kind, targetId, principal)
        } else {
            db.update(
                "INSERT INTO channel_access (team_id, id, target_kind, target_id, principal, level, created_at, updated_at, updated_by) " +
                    "VALUES (:team, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (team_id, target_kind, target_id, principal) " +
                    "DO UPDATE SET level = excluded.level, updated_at = excluded.updated_at, updated_by = excluded.updated_by",
                Ulid.next(now), kind, targetId, principal, level.wire, now, now, Viewer.MEMBER + actor.id,
            )
        }
        audit(actor, kind, targetId, principal, "level", before?.wire, level?.wire)
        bump()
    }

    /**
     * Make a channel or project private (only those listed see it), or open it to the team again.
     * The person making it private keeps their own access: they are listed as managing it.
     */
    fun setPrivate(kind: String, targetId: String, private: Boolean, actor: People.Person) {
        checkKind(kind)
        requireActorManages(kind, targetId, actor)
        val (was, _) = privacy(kind, targetId)
        if (was == private) return
        // Listed first, while they still can be: the person making it private keeps managing it.
        if (private && actor.role != People.Role.OWNER && rowsOn(kind, targetId)[Viewer.MEMBER + actor.id] == null) {
            setLevel(kind, targetId, Viewer.MEMBER + actor.id, Level.MANAGE, actor)
        }
        val table = if (kind == "group") "project_groups" else "projects"
        db.update("UPDATE $table SET private = ?, updated_at = ? WHERE team_id = :team AND id = ?", if (private) 1 else 0, clock(), targetId)
        audit(actor, kind, targetId, null, "private", was.toString(), private.toString())
        bump()
    }

    private fun checkKind(kind: String) {
        if (kind != "channel" && kind != "group") throw invalid("kind must be channel or group")
    }

    /** Permissions are people's to change, never agents'; only someone who manages the thing. */
    private fun requireActorManages(kind: String, targetId: String, actor: People.Person) {
        if (actor.role == People.Role.OWNER) return
        val v = view(Viewer.MEMBER + actor.id)
        val level = if (kind == "group") v.groups[targetId]?.level else v.channels[targetId]?.level
        val missing = { notFound("no such ${if (kind == "group") "group" else "project"}: $targetId") }
        if (level == null || level == Level.NONE) {
            if (kind != "group" || targetId in v.hiddenGroups) throw missing()
        }
        if (level == null || level < Level.MANAGE) throw forbidden("only people who manage this ${if (kind == "group") "project" else "channel"} can change who has access")
    }

    /**
     * A channel leaving project [groupId] (moved out, or the project deleted) keeps the access it had
     * through it: the project's rows are copied onto the channel where it has none, and a private
     * project leaves the channel private. Nothing hidden comes into view by being moved.
     */
    fun keepThroughMove(channelId: String, groupId: String) {
        val group = target("group", groupId) ?: return
        val own = rowsOn("channel", channelId)
        val now = clock()
        for ((principal, level) in rowsOn("group", groupId)) {
            if (principal in own) continue
            db.update(
                "INSERT INTO channel_access (team_id, id, target_kind, target_id, principal, level, created_at, updated_at, updated_by) " +
                    "VALUES (:team, ?, 'channel', ?, ?, ?, ?, ?, 'server')",
                Ulid.next(now), channelId, principal, level.wire, now, now,
            )
        }
        if (group.private) db.update("UPDATE projects SET private = 1 WHERE team_id = :team AND id = ?", channelId)
        bump()
    }

    /** A project going away takes its rows with it (after [keepThroughMove] for each channel). */
    fun forgetGroup(groupId: String) {
        db.update("DELETE FROM channel_access WHERE team_id = :team AND target_kind = 'group' AND target_id = ?", groupId)
        bump()
    }

    // ---- the log ----------------------------------------------------------------------------------

    private fun audit(actor: People.Person, kind: String, targetId: String, principal: String?, change: String, before: String?, after: String?) {
        val now = clock()
        db.update(
            "INSERT INTO access_audit (team_id, id, at, actor, target_kind, target_id, target_name, principal, change, old_value, new_value) " +
                "VALUES (:team, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            Ulid.next(now), now, Viewer.MEMBER + actor.id, kind, targetId, targetName(kind, targetId), principal, change, before, after,
        )
    }

    /** The newest changes first, for owners and admins: who changed what, from what to what. */
    fun auditLog(limit: Int, before: Long?): List<JsonObject> {
        val names = everyoneNames()
        return db.query(
            "SELECT seq, id, at, actor, target_kind, target_id, target_name, principal, change, old_value, new_value FROM access_audit " +
                "WHERE team_id = :team AND (? IS NULL OR seq < ?) ORDER BY seq DESC LIMIT ?",
            before, before, limit,
        ) { rs ->
            buildJsonObject {
                put("seq", rs.getLong(1))
                put("id", rs.getString(2))
                put("at", dev.wildware.feed.server.http.Wire.isoOf(rs.getLong(3)))
                put("actor", rs.getString(4))
                put("actorName", names[rs.getString(4)]?.let(::JsonPrimitive) ?: JsonNull)
                put("kind", rs.getString(5))
                put("targetId", rs.getString(6))
                put("targetName", rs.getString(7))
                put("principal", rs.getString(8)?.let(::JsonPrimitive) ?: JsonNull)
                put("principalName", rs.getString(8)?.let { names[it] }?.let(::JsonPrimitive) ?: JsonNull)
                put("change", rs.getString(9))
                put("from", rs.getString(10)?.let(::JsonPrimitive) ?: JsonNull)
                put("to", rs.getString(11)?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
    }

    private fun everyoneNames(): Map<String, String> =
        db.query("SELECT m.id, u.name FROM memberships AS m JOIN users AS u ON u.id = m.user_id WHERE m.team_id = :team") { Viewer.MEMBER + it.getString(1) to it.getString(2) }.toMap() +
            db.query("SELECT id, name FROM agents WHERE team_id = :team") { Viewer.AGENT + it.getString(1) to it.getString(2) }.toMap()

    // ---- what one person or agent can reach ---------------------------------------------------------

    /**
     * Every channel [principal] can read, with its level and why, as the member and agent pages show
     * it. Only channels this work's viewer can see themselves are listed.
     */
    fun reachOf(principal: String): List<JsonObject> {
        val theirs = view(principal)
        val mine = hidden()
        return db.query("SELECT id, slug, name, group_id FROM projects WHERE team_id = :team AND status = 'active' ORDER BY lower(name), seq") {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4))
        }.filter { it[0]!! !in mine }.mapNotNull { (id, slug, name, groupId) ->
            val g = theirs.channels[id!!] ?: return@mapNotNull null
            buildJsonObject {
                put("projectId", id)
                put("slug", slug)
                put("name", name)
                put("groupId", groupId?.let(::JsonPrimitive) ?: JsonNull)
                put("level", g.level.wire)
                put("via", g.via)
            }
        }
    }

    // ---- keeping it fresh ---------------------------------------------------------------------------

    private fun version(): Long = versions.getOrPut(teamKey()) { AtomicLong() }.get()

    /** Something changed: every kept view of this team is stale. */
    fun bump() {
        versions.getOrPut(teamKey()) { AtomicLong() }.incrementAndGet()
        Viewer.currentOrNull?.memo = null
    }

    private fun teamKey(): String = if (Tenant.isAllTeams) Tenant.ALL else Tenant.id

    companion object {
        const val OWNER = "owner"
        const val CHANNEL = "channel"
        const val GROUP = "group"
        const val PRIVATE = "private"
        const val DEFAULT = "default"

        /** How long a worked-out view is kept: a change on another server is seen within this. */
        const val KEEP_MS = 1_000L

        private val versions = ConcurrentHashMap<String, AtomicLong>()

        private val SAFE = Regex("^[A-Za-z0-9_.:-]+$")

        /** A channel id as an SQL literal; ids are ULIDs or slugs, but quote anything else safely. */
        private fun literal(id: String): String = if (SAFE.matches(id)) "'$id'" else "'" + id.replace("'", "''") + "'"
    }
}

val Feed.access: ChannelAccess get() = ChannelAccess(db, clock)
