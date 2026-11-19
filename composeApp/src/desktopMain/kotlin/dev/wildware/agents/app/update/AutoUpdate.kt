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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Where the background download of a newer version has got to. */
sealed interface BackgroundUpdate {
    /** Nothing to do: the setting is off, nothing newer is out, or this copy can't install it alone. */
    data object Off : BackgroundUpdate

    /** [total] is -1 when the size isn't known. [attempt] counts from 1. */
    data class Downloading(val release: Release, val done: Long, val total: Long, val attempt: Int) : BackgroundUpdate

    /** The last try failed with [message]; another starts after a wait. */
    data class Retrying(val release: Release, val message: String, val attempt: Int) : BackgroundUpdate

    /** Downloaded and checked: one click restarts into it, and quitting puts it in. */
    data class Staged(val release: Release) : BackgroundUpdate
}

/**
 * Downloads a newer version as soon as one is found, while [enabled] is on, so that updating is a
 * single quick restart. It works quietly: nothing opens and nothing takes focus. A failed download
 * waits a while (longer each time) and tries again, carrying on from where it stopped.
 * [paused] holds it off while an install the owner started is running.
 */
class AutoUpdater(
    private val updater: Updater,
    private val available: StateFlow<Release?>,
    private val enabled: StateFlow<Boolean>,
    private val source: StateFlow<ReleaseSource?>,
    private val paused: StateFlow<Boolean>,
    /** The wait before each retry; the last one repeats. */
    private val retryWaits: List<Duration> = RetryWaits,
) {
    private val mutableState = MutableStateFlow<BackgroundUpdate>(BackgroundUpdate.Off)
    val state: StateFlow<BackgroundUpdate> = mutableState.asStateFlow()

    /** Whether [release] would be downloaded in the background, with the setting as it is now. */
    fun handles(release: Release): Boolean = enabled.value && updater.canStage(release)

    fun start(scope: CoroutineScope): Job = scope.launch {
        combine(available, enabled, source, paused) { release, on, s, held -> Inputs(release, on, s, held) }
            .collectLatest { (release, on, s, held) ->
                // An install the owner started has the download now; what this shows stays as it was.
                if (held) return@collectLatest
                if (!on || release == null || s == null || !updater.canStage(release)) {
                    mutableState.value = BackgroundUpdate.Off
                    return@collectLatest
                }
                var attempt = 1
                while (true) {
                    mutableState.value = BackgroundUpdate.Downloading(release, 0, -1, attempt)
                    try {
                        updater.stage(release, s) { done, total -> mutableState.value = BackgroundUpdate.Downloading(release, done, total, attempt) }
                        mutableState.value = BackgroundUpdate.Staged(release)
                        return@collectLatest
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        mutableState.value = BackgroundUpdate.Retrying(release, e.message ?: "The download failed.", attempt)
                        delay(retryWaits[(attempt - 1).coerceAtMost(retryWaits.lastIndex)])
                        attempt++
                    }
                }
            }
    }

    companion object {
        /** A minute, five, fifteen, then hourly. */
        val RetryWaits = listOf(1.minutes, 5.minutes, 15.minutes, 1.hours)

        /** After this many failed tries the pill offers the update the old way, so it's never stuck out of sight. */
        const val ShowAfterFailures = 3
    }

    private data class Inputs(val release: Release?, val on: Boolean, val source: ReleaseSource?, val paused: Boolean)
}
