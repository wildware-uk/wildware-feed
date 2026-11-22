package dev.wildware.agents.app.engine

import dev.wildware.agents.AssigneeView
import dev.wildware.agents.CardRef
import dev.wildware.agents.DashboardClient
import dev.wildware.agents.DashboardException
import dev.wildware.agents.app.feed.AssignState
import dev.wildware.agents.app.feed.Assignee
import dev.wildware.agents.assignCard
import dev.wildware.agents.cardAssignees
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Assign picker while it is open (server migration 072): who the card is assigned to and who
 * could be, read when it opens, and the owner's choice saved in one go. [saved] runs after a save
 * goes through, so the board reads itself again.
 */
class AssignWatch(
    private val client: DashboardClient,
    private val scope: CoroutineScope,
    private val saved: () -> Unit,
) {
    private val mutable = MutableStateFlow<AssignState?>(null)
    val state: StateFlow<AssignState?> = mutable.asStateFlow()

    private var reading: Job? = null

    /** Opens the picker for [card], titled [title], and reads who could be assigned. */
    fun open(card: CardRef, title: String) {
        reading?.cancel()
        mutable.value = AssignState(card.kind, card.id, title)
        reading = scope.launch {
            try {
                val read = client.cardAssignees(card)
                if (mutable.value?.id != card.id) return@launch
                mutable.value = mutable.value?.copy(
                    loaded = true,
                    max = read.max,
                    assigned = read.assignees.map(::assigneeOf),
                    candidates = read.candidates.map(::assigneeOf),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                if (mutable.value?.id == card.id) mutable.value = mutable.value?.copy(loaded = true, problem = problemOf(failed, "read who can be assigned"))
            }
        }
    }

    fun close() {
        reading?.cancel()
        mutable.value = null
    }

    /** Makes the card's assignees exactly [principals]; the picker closes once the dashboard agrees. */
    fun save(principals: List<String>) {
        val open = mutable.value ?: return
        mutable.value = open.copy(saving = true, problem = null)
        scope.launch {
            try {
                client.assignCard(CardRef(open.kind, open.id), principals, "set")
                if (mutable.value?.id == open.id) mutable.value = null
                saved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Throwable) {
                if (mutable.value?.id == open.id) mutable.value = mutable.value?.copy(saving = false, problem = problemOf(failed, "assign it"))
            }
        }
    }

    private fun problemOf(failed: Throwable, what: String) =
        if (failed is DashboardException) "Couldn't $what. ${failed.message}" else "Couldn't $what. Check the connection and try again."
}

/** One assignee as the app draws it. */
internal fun assigneeOf(v: AssigneeView): Assignee =
    Assignee(v.principal, v.name.ifBlank { "Someone" }, isAgent = v.kind == "agent", handle = v.handle, canRead = v.canRead, hue = hue(v.id.ifEmpty { v.principal }))
