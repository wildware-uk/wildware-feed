package dev.wildware.agents.app.sound

import dev.wildware.agents.app.resources.Res
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

/** Plays [name], fetching and decoding it from [url] the first time; a first play that takes over a second is dropped. */
private fun soundsPlay(name: String, url: String): Unit = js(
    """{
    const s = globalThis.feedSounds;
    if (!s || s.ctx.state !== 'running') return;
    const start = (buf) => {
        const src = s.ctx.createBufferSource();
        src.buffer = buf;
        src.connect(s.ctx.destination);
        src.start();
    };
    if (s.buffers[name]) { start(s.buffers[name]); return; }
    if (s.buffers[name] === null) return;
    s.buffers[name] = null;
    const asked = Date.now();
    fetch(url).then(r => r.arrayBuffer()).then(b => s.ctx.decodeAudioData(b)).then(buf => {
        s.buffers[name] = buf;
        if (Date.now() - asked < 1000 && s.ctx.state === 'running') start(buf);
    }).catch(() => { delete s.buffers[name]; });
}""",
)

/**
 * Web Audio: each sound fetched and decoded the first time it plays, then played from memory, as
 * many at once as happen. Only the sounds that play are downloaded. Until the owner first taps or
 * types on the page the browser keeps it quiet.
 */
class WebSoundPlayer : SoundPlayer {
    init {
        runCatching { soundsInit() }
    }

    @OptIn(ExperimentalResourceApi::class)
    override fun play(sound: Sound) {
        runCatching { soundsPlay(sound.file, Res.getUri(sound.resource)) }
    }
}
