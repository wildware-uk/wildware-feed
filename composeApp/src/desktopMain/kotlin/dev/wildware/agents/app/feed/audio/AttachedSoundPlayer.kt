package dev.wildware.agents.app.feed.audio

import dev.wildware.agents.app.feed.SoundPlayer
import dev.wildware.agents.app.feed.SoundState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A sound on a card, played on a desktop: downloaded once when it is first played, decoded by
 * [SoundDecoding] and heard through [output]. Play, pause, seek and the end all show in [state]; a
 * sound that can't be fetched, decoded or heard says why in [SoundState.problem] rather than doing
 * nothing.
 */
class AttachedSoundPlayer(
    knownDurationMs: Long?,
    private val scope: CoroutineScope,
    /** The sound's bytes, with the device key where the dashboard needs it. */
    private val load: suspend () -> ByteArray,
    private val output: () -> SoundOutput = { JavaSoundOutput() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** How often the position is read while it plays. */
    private val tickMs: Long = 100,
) : SoundPlayer {
    private val mutableState = MutableStateFlow(SoundState(durationMs = knownDurationMs?.takeIf { it > 0 } ?: 0))
    override val state: StateFlow<SoundState> = mutableState.asStateFlow()

    /** One play through, from [startMs] to the end or until it is stopped. */
    private inner class Session(val startMs: Long) {
        val paused = MutableStateFlow(false)

        @Volatile
        var out: SoundOutput? = null

        @Volatile
        var sampleRate = 0
        lateinit var job: Job
        var ticker: Job? = null

        /** Pauses or carries on, output and all, in step with [attach] on the playing side. */
        fun setPaused(value: Boolean) = synchronized(this) {
            paused.value = value
            out?.let { if (value) it.pause() else it.resume() }
        }

        /** Plays into [output] from now on, paused if a pause came while it was loading. */
        fun attach(output: SoundOutput, rate: Int) = synchronized(this) {
            sampleRate = rate
            out = output
            if (paused.value) output.pause()
        }

        fun positionMs(): Long {
            val rate = sampleRate.takeIf { it > 0 } ?: return startMs
            return startMs + (out?.framesPlayed ?: 0) * 1000 / rate
        }

        fun stop() {
            job.cancel()
            ticker?.cancel()
            out?.close()
        }
    }

    @Volatile
    private var bytes: ByteArray? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var released = false

    override fun play() {
        if (released) return
        val current = session
        if (current != null) {
            if (current.paused.value) {
                current.setPaused(false)
                update(current) { it.copy(playing = true) }
            }
            return
        }
        val now = mutableState.value
        val from = if (now.durationMs > 0 && now.positionMs >= now.durationMs) 0 else now.positionMs
        start(from)
    }

    override fun pause() {
        val current = session ?: return
        if (current.paused.value) return
        current.setPaused(true)
        update(current) { it.copy(playing = false, positionMs = current.positionMs()) }
    }

    override fun seek(ms: Long) {
        if (released) return
        val at = ms.coerceAtLeast(0)
        val current = session
        if (current == null) {
            mutableState.value = mutableState.value.copy(positionMs = at)
            return
        }
        val wasPlaying = !current.paused.value
        current.stop()
        session = null
        mutableState.value = mutableState.value.copy(positionMs = at, playing = wasPlaying)
        if (wasPlaying) start(at)
    }

    override fun release() {
        released = true
        session?.stop()
        session = null
        mutableState.value = mutableState.value.copy(playing = false)
    }

    private fun start(fromMs: Long) {
        val s = Session(fromMs)
        session = s
        mutableState.value = mutableState.value.copy(playing = true, failed = false, problem = null, positionMs = fromMs)
        s.job = scope.launch(io) { run(s) }
    }

    private suspend fun run(s: Session) {
        var source: PcmSource? = null
        val out = output()
        try {
            val data = bytes ?: try {
                load().also { bytes = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return fail(s, "Couldn't download")
            }
            // Nothing said how long it is: measure it alongside, so playing doesn't wait for it.
            if (mutableState.value.durationMs <= 0) {
                scope.launch(io) {
                    val measured = runCatching { SoundDecoding.durationMs(data) }.getOrNull()
                    if (measured != null && measured > 0) mutableState.value = mutableState.value.copy(durationMs = measured)
                }
            }
            source = try {
                SoundDecoding.open(data)
            } catch (e: UnplayableSound) {
                return fail(s, e.message ?: "Can't play")
            }
            try {
                out.open(source.sampleRate, source.channels)
            } catch (e: Exception) {
                return fail(s, "No sound output")
            }
            s.attach(out, source.sampleRate)
            s.ticker = scope.launch {
                while (isActive) {
                    if (!s.paused.value) update(s) { it.copy(positionMs = s.positionMs()) }
                    delay(tickMs)
                }
            }
            var skip = s.startMs * source.sampleRate / 1000 * source.channels
            while (scope.isActive && session === s) {
                s.paused.first { !it }
                var block = try {
                    source.read()
                } catch (e: Exception) {
                    // A broken frame partway through: play what came before it.
                    null
                } ?: break
                if (skip > 0) {
                    val dropped = minOf(skip, block.size.toLong()).toInt()
                    skip -= dropped
                    if (dropped == block.size) continue
                    block = block.copyOfRange(dropped, block.size)
                }
                out.write(block)
            }
            // Let the last of it be heard, waiting out any pause on the way.
            while (session === s) {
                s.paused.first { !it }
                out.drain()
                if (!s.paused.value) break
            }
            s.ticker?.cancel()
            if (session === s) {
                session = null
                mutableState.value = mutableState.value.copy(playing = false, positionMs = 0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The output went away partway through (a headset pulled out): say so, never crash the app.
            fail(s, "Couldn't play")
        } finally {
            s.ticker?.cancel()
            runCatching { source?.close() }
            out.close()
        }
    }

    private fun fail(s: Session, problem: String) {
        if (session !== s) return
        session = null
        mutableState.value = mutableState.value.copy(playing = false, failed = true, problem = problem)
    }

    /** Changes [state] only while [s] is still the sound playing, so a stopped one can't overwrite a newer one. */
    private inline fun update(s: Session, change: (SoundState) -> SoundState) {
        if (session === s) mutableState.value = change(mutableState.value)
    }
}
