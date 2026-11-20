package dev.wildware.agents.app.sound

import dev.wildware.agents.app.resources.Res
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** Sets up one Web Audio context; the browser lets it sound only after the first tap or key, so that wakes it. */
private fun soundsInit(): Unit = js(
    """{
    if (globalThis.feedSounds) return;
    const AC = globalThis.AudioContext || globalThis.webkitAudioContext;
    if (!AC) return;
    const s = { ctx: new AC(), buffers: {} };
    globalThis.feedSounds = s;
    const wake = () => { if (s.ctx.state === 'suspended') s.ctx.resume().catch(() => {}); };
    ['pointerdown', 'keydown', 'touchstart'].forEach(e => globalThis.addEventListener(e, wake, { capture: true, passive: true }));
}""",
)

private fun soundsLoad(name: String, url: String): Unit = js(
    """{
    const s = globalThis.feedSounds;
    if (!s) return;
    fetch(url).then(r => r.arrayBuffer()).then(b => s.ctx.decodeAudioData(b)).then(buf => { s.buffers[name] = buf; }).catch(() => {});
}""",
)

private fun soundsPlay(name: String): Unit = js(
    """{
    const s = globalThis.feedSounds;
    if (!s) return;
    const buf = s.buffers[name];
    if (!buf || s.ctx.state !== 'running') return;
    const src = s.ctx.createBufferSource();
    src.buffer = buf;
    src.connect(s.ctx.destination);
    src.start();
}""",
)

/**
 * Web Audio: each sound fetched and decoded once, then played from memory, as many at once as
 * happen. Until the owner first taps or types on the page the browser keeps it quiet.
 */
class WebSoundPlayer : SoundPlayer {
    init {
        runCatching { soundsInit() }
        @OptIn(ExperimentalResourceApi::class)
        MainScope().launch {
            for (sound in Sound.entries) runCatching { soundsLoad(sound.file, Res.getUri(sound.resource)) }
        }
    }

    override fun play(sound: Sound) {
        runCatching { soundsPlay(sound.file) }
    }
}
