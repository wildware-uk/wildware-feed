package dev.wildware.feed.server.db

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

/**
 * Who the work on this thread is shown to (spec/14): a person (`member:<membership id>`) or an
 * agent (`agent:<agent id>`). Set once for each owner request (the guard, from the caller's key),
 * each agent request (its token) and each stream, the same way [Tenant] carries the team.
 *
 * Reads that hand channel content to someone ask [dev.wildware.feed.server.domain.ChannelAccess]
 * what this viewer may not see, and leave it out in their SQL. With no viewer set (jobs, bus
 * listeners, the server's own bookkeeping) nothing is left out: such code decides per reader.
 */
object Viewer {
    /** One viewer, with what was worked out for it, kept for a moment ([dev.wildware.feed.server.domain.ChannelAccess]). */
    class Who(val principal: String) {
        @Volatile internal var memo: Any? = null

        val isAgent: Boolean get() = principal.startsWith(AGENT)
        val isMember: Boolean get() = principal.startsWith(MEMBER)
        val id: String get() = principal.substringAfter(':')
    }

    const val AGENT = "agent:"
    const val MEMBER = "member:"

    @PublishedApi internal val current = ThreadLocal<Who?>()

    /** The viewer of this work, or null for the server's own. */
    val currentOrNull: Who? get() = current.get()

    fun member(id: String) = Who(MEMBER + id)
    fun agent(id: String) = Who(AGENT + id)

    /** Run [block] shown to [who]; null runs it as the server's own work. */
    inline fun <T> with(who: Who?, block: () -> T): T {
        val before = current.get()
        current.set(who)
        try {
            return block()
        } finally {
            current.set(before)
        }
    }

    /** Run [block] as the server's own work, seeing everything (fan-out to each reader decides per reader). */
    inline fun <T> system(block: () -> T): T = with(null, block)

    /** A coroutine context that carries [who] to whichever thread the coroutine resumes on. */
    fun element(who: Who): ThreadContextElement<Who?> = current.asContextElement(who)
}
