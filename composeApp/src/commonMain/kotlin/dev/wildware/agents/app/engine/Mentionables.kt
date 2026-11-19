package dev.wildware.agents.app.engine

import dev.wildware.agents.LiveAgentView
import dev.wildware.agents.app.feed.MentionKind
import dev.wildware.agents.app.feed.Mentionable
import dev.wildware.agents.app.feed.Presence

/** Who the @ list offers: every agent the dashboard knows, from any project, as a mention reaches them anywhere. */
object Mentionables {
    /**
     * [names] by id; online agents first (most recently heard from first), then the rest by when they
     * last wrote ([lastActive], newest first), then by name.
     */
    fun of(names: Map<String, String>, online: List<LiveAgentView>, lastActive: Map<String, Long>): List<Mentionable> {
        val live = online.associateBy { it.agentId }
        val all = (names + online.associate { it.agentId to it.name }).filterValues { it.isNotBlank() }
        val (on, off) = all.entries.partition { it.key in live }
        val ordered = on.sortedByDescending { live.getValue(it.key).lastHeartbeatAt } +
            off.sortedWith(compareByDescending<Map.Entry<String, String>> { lastActive[it.key] ?: Long.MIN_VALUE }.thenBy { it.value.lowercase() })
        return ordered.map { (id, name) ->
            Mentionable(MentionKind.Agent, name, id, if (id in live) Presence.Online else Presence.Offline, hue(id))
        }
    }
}
