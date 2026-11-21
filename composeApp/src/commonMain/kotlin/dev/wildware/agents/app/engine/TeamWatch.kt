package dev.wildware.agents.app.engine

import dev.wildware.agents.DashboardClient
import dev.wildware.agents.DashboardException
import dev.wildware.agents.PendingRequest
import dev.wildware.agents.SignIn
import dev.wildware.agents.app.session.SavedTeam
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A question that came up in a team that isn't open: what a desktop or Android notice is made from. */
data class TeamQuestion(val teamKey: String, val teamName: String, val server: String, val request: PendingRequest)

/**
 * Keeps an eye on the teams that aren't open, cheaply: no live feed, just the dashboard's small
 * summary every [intervalMillis] (and when the app comes back to the front), for how many
 * questions wait there and which are new. Only the open team holds a live connection.
 */
class TeamWatch(
    private val engine: () -> HttpClientEngine,
    private val scope: CoroutineScope,
    private val foreground: Flow<Unit> = emptyFlow(),
    private val intervalMillis: Long = DefaultIntervalMillis,
) {
    private val mutableWaiting = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** How many questions wait in each watched team, by key, once its summary has been read. */
    val waiting: StateFlow<Map<String, Int>> = mutableWaiting.asStateFlow()

    private val mutableQuestions = MutableSharedFlow<TeamQuestion>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Each question asked in a watched team after watching began (not the ones already waiting). */
    val questions: SharedFlow<TeamQuestion> = mutableQuestions.asSharedFlow()

    private val mutableSignedOut = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The key of a watched team whose dashboard no longer takes this device's key. */
    val signedOut: SharedFlow<String> = mutableSignedOut.asSharedFlow()

    private class Watching(val team: SavedTeam, val job: Job)

    private val watching = mutableMapOf<String, Watching>()

    /**
     * Watches exactly [teams]: starts on new ones, stops on ones gone or changed. A browser's
     * cookie can't be sent to another address, so a cookie sign-in is never watched.
     */
    fun watch(teams: List<SavedTeam>) {
        val wanted = teams.filter { it.session.token != SignIn.CookieSession }.associateBy { it.key }
        val stale = watching.filter { (key, w) -> wanted[key]?.session != w.team.session }.keys
        stale.forEach { key -> watching.remove(key)?.job?.cancel() }
        mutableWaiting.update { counts -> counts.filterKeys { it in wanted } }
        wanted.values.filter { it.key !in watching }.forEach { team ->
            watching[team.key] = Watching(team, scope.launch { follow(team) })
        }
    }

    fun stop() = watch(emptyList())

    private suspend fun follow(team: SavedTeam) {
        val client = DashboardClient(team.session.server, team.session.token, engine())
        try {
            var known: Set<String>? = null
            while (true) {
                val summary = try {
                    client.summary()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (refused: DashboardException) {
                    if (refused.status == 401) {
                        mutableSignedOut.tryEmit(team.key)
                        return
                    }
                    null
                } catch (failed: Throwable) {
                    // No signal, or the server is down: the badge keeps what it last knew.
                    null
                }
                if (summary != null) {
                    mutableWaiting.update { it + (team.key to summary.requests.size) }
                    val before = known
                    if (before != null) {
                        summary.requests.filter { it.id !in before }.forEach { mutableQuestions.tryEmit(TeamQuestion(team.key, team.name, team.session.server, it)) }
                    }
                    known = summary.requests.mapTo(HashSet()) { it.id }
                }
                // Until the next turn, or the app coming back to the front (where there is no such signal, just the turn).
                withTimeoutOrNull(intervalMillis) { foreground.firstOrNull() ?: awaitCancellation() }
                // Back in front: a moment for the network to wake before asking.
                delay(ForegroundSettleMillis)
            }
        } finally {
            client.close()
        }
    }

    companion object {
        /** A minute: a badge a little late is fine, and many teams stay cheap. */
        const val DefaultIntervalMillis = 60_000L
        private const val ForegroundSettleMillis = 500L
    }
}
