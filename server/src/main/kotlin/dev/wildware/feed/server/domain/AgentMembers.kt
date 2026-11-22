package dev.wildware.feed.server.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Which agents hear a channel or a project (a group of channels), and the owner adding or removing
 * one (migration 060, spec/12 "The owner adds and removes agents"). A removal sticks: posting there
 * does not follow it again and the agent's own `subscribe` is refused, until the owner adds it back.
 * An @mention still reaches it. Each change tells the agent, in a message only it hears.
 */
class AgentMembers(private val feed: Feed) {
    private val db get() = feed.db
    private val subs get() = Subscriptions(feed.db, feed.clock)

    /**
     * One agent of the team, for one channel or project. [via] says why it hears: `chosen`, `group`
     * or `posted`; null when it does not.
     * [removed]: the owner took it out, `channel` or `group` (through the channel's project).
     */
    class Member(val agentId: String, val name: String, val hears: Boolean, val via: String?, val removed: String?) {
        val json: JsonObject get() = buildJsonObject {
            put("agentId", agentId)
            put("name", name)
            put("hears", hears)
            put("via", via)
            put("removed", removed)
        }
    }

    private fun agents(): List<Pair<String, String>> =
        db.query("SELECT id, name FROM agents WHERE team_id = :team AND revoked_at IS NULL ORDER BY lower(name), seq") { it.getString(1) to it.getString(2) }

    private fun agentName(agentId: String): String =
        db.one("SELECT name FROM agents WHERE team_id = :team AND id = ? AND revoked_at IS NULL", agentId) { it.getString(1) }
            ?: throw notFound("no such agent: $agentId")

    /** Every live agent of the team, and whether it hears [projectId]. */
    fun ofChannel(projectId: String): List<Member> = agents().map { (id, name) -> channelMember(id, name, projectId) }

    private fun channelMember(agentId: String, name: String, projectId: String): Member {
        val removed = subs.removedFrom(agentId, projectId)
        val hears = projectId in subs.followedProjects(agentId)
        val via = when {
            !hears -> null
            else -> {
                val me = Subscriptions.agent(agentId)
                val chosen = db.one(
                    "SELECT 1 FROM project_subscriptions WHERE team_id = :team AND subscriber = ? AND project_id = ? AND chosen = 1 AND unsubscribed_at IS NULL", me, projectId,
                ) { true } == true
                val group = db.one("SELECT group_id FROM projects WHERE team_id = :team AND id = ?", projectId) { it.getString(1) }
                when {
                    chosen -> "chosen"
                    group != null && subs.groupState(me, group) == true -> "group"
                    else -> "posted"
                }
            }
        }
        return Member(agentId, name, hears, via, removed)
    }

    /** Every live agent of the team, and whether it hears the group as a whole. */
    fun ofGroup(groupId: String): List<Member> = agents().map { (id, name) -> groupMember(id, name, groupId) }

    private fun groupMember(agentId: String, name: String, groupId: String): Member {
        val hears = subs.hearsGroup(agentId, groupId)
        val via = if (hears) "chosen" else null
        return Member(agentId, name, hears, via, if (subs.removedFromGroup(agentId, groupId)) "group" else null)
    }

    /**
     * The owner ([by], a person) adds [agentId] to the channel or removes it. Tells the agent when
     * that changed anything, and answers the agent as the list now shows it.
     */
    fun setChannel(project: ProjectRow, agentId: String, hears: Boolean, by: String): Member = feed.op {
        // Which agents hear a channel is its settings: Manage (spec/15). Hearing needs access first.
        feed.access.requireManage(project.id) { notFound("no such project: ${project.id}") }
        val name = agentName(agentId)
        if (hears && !feed.access.canRead(Subscriptions.agent(agentId), project.id)) {
            throw conflict("$name has no access to #${project.slug}: give it Read or Post in Permissions first")
        }
        val changed = if (hears) subs.ownerAddChannel(agentId, project.id) else subs.ownerRemoveChannel(agentId, project.id)
        if (changed) {
            changedFor(agentId, buildJsonObject { put("projectId", project.id) })
            val body = if (hears) {
                "@$name I added you to #${project.slug}. New messages there reach you now."
            } else {
                "@$name I removed you from #${project.slug}. Messages there reach you only when they @mention you, " +
                    "and posting there will not add you back. Ask me if you need it again."
            }
            tell(body, project.slug, by)
        }
        channelMember(agentId, name, project.id)
    }

    /** As [setChannel], for a project (group of channels). */
    fun setGroup(group: GroupRow, agentId: String, hears: Boolean, by: String): Member = feed.op {
        feed.access.requireManageGroup(group.id) { notFound("no such group: ${group.id}") }
        val name = agentName(agentId)
        val changed = if (hears) subs.ownerAddGroup(agentId, group.id) else subs.ownerRemoveGroup(agentId, group.id)
        if (changed) {
            changedFor(agentId, buildJsonObject { put("groupId", group.id) })
            val body = if (hears) {
                "@$name I added you to project ${group.name}. New messages in its channels reach you now."
            } else {
                "@$name I removed you from project ${group.name} and its channels. Messages there reach you only when they " +
                    "@mention you, and posting there will not add you back. Ask me if you need it again."
            }
            tell(body, null, by)
        }
        groupMember(agentId, name, group.id)
    }

    /** The agent's stream reads its reach again; the apps read the list again. */
    private fun changedFor(agentId: String, where: JsonObject) {
        feed.publish("subscriptions.updated", JsonObject(where + buildJsonObject { put("subscriber", Subscriptions.agent(agentId)) }))
    }

    /** A message from the owner that @mentions only the agent: it reaches that agent wherever it is, and no other. */
    private fun tell(body: String, project: String?, by: String) {
        feed.messages.post(Messages.Post(author = Messages.HUMAN, body = body, project = project, userId = by))
    }
}

val Feed.agentMembers: AgentMembers get() = AgentMembers(this)
