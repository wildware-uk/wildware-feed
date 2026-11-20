package dev.wildware.agents.app.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** What one check found. */
sealed interface CheckResult {
    data class Newer(val release: Release) : CheckResult

    /** Nothing newer than this copy (or no release at all). */
    data object UpToDate : CheckResult

    data class Failed(val message: String) : CheckResult
}

/**
 * Asks [source] for the newest release at once, then every [interval], while [automatic] is on;
 * and whenever asked by hand. [available] holds a release newer than [current] that [offers]
 * says is for this copy, or null. A failed check (offline, GitHub rate-limiting) changes nothing
 * and says nothing: the next one will try again.
 */
class UpdateChecker(
    private val current: SemVer,
    private val source: StateFlow<ReleaseSource?>,
    private val automatic: StateFlow<Boolean>,
    private val interval: Duration = 6.hours,
    /**
     * Whether a release has this computer's file yet. A release can go out before one of its
     * files (the Mac one waits for Apple's notarisation): until it's there, this copy is up to date.
     */
    private val offers: (Release) -> Boolean = { true },
) {
    private val mutableAvailable = MutableStateFlow<Release?>(null)
    val available: StateFlow<Release?> = mutableAvailable.asStateFlow()

    private val lock = Mutex()

    fun start(scope: CoroutineScope): Job = scope.launch {
        combine(source, automatic) { s, on -> s.takeIf { on } }.collectLatest { s ->
            if (s == null) return@collectLatest
            while (true) {
                check(s)
                delay(interval)
            }
        }
    }

    /** Checks now, whatever the setting, and says what it found. */
    suspend fun checkNow(): CheckResult {
        val s = source.value ?: return CheckResult.Failed("There is nowhere to check for updates.")
        return check(s)
    }

    private suspend fun check(s: ReleaseSource): CheckResult = lock.withLock {
        val release = try {
            s.latest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CheckResult.Failed(e.message ?: "Couldn't check for updates.")
        }
        val newer = release?.takeIf { r -> r.semVer?.let { it > current } == true && offers(r) }
        mutableAvailable.value = newer
        if (newer != null) CheckResult.Newer(newer) else CheckResult.UpToDate
    }
}
