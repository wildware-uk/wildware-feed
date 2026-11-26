#!/usr/bin/env python3
"""The Wildware Feed agent voice: notification sounds made by formant speech synthesis.

A small "voice" (a glottal-style harmonic source following a pitch contour, shaped by four
moving vowel formants) speaks short phrases: "hm.", "mm-HM!", "hm-mm?", "uh-oh" and so on.
No samples, recordings or plugins: everything is computed here with numpy and scipy, and
mastered with pedalboard's EQ and compressor.

This renders the final set ("down a fifth", the softer voice): 16 grid sounds
(priority x level) and 14 app moments.

  python agentvoice.py --out DIR            # writes DIR/grid/*.wav and DIR/moments/*.wav

Output: 48 kHz, 24-bit stereo WAV, loudness-matched (BS.1770), true peak under -1 dBTP.
"""
import argparse
import os
import wave

import numpy as np
from pedalboard import Compressor, HighShelfFilter, HighpassFilter, PeakFilter, Pedalboard
from scipy import signal
from scipy.ndimage import minimum_filter1d

SR = 48000
PRIORITIES = ["low", "medium", "high", "critical"]
LEVELS = ["info", "success", "warning", "error"]
# loudness per priority (LUFS) for the final, softer set
GRID_LUFS = {"low": -23.0, "medium": -19.0, "high": -19.0, "critical": -17.0}
# the moments table below carries the original levels; the final set plays 3 dB under them
MOMENT_OFFSET = -3.0


# ------------------------------------------------------------------ dsp helpers -----
def sec(s):
    return int(round(s * SR))


def place(buf, x, at):
    i = sec(at)
    x = x[:len(buf) - i]
    buf[i:i + len(x)] += x


def bp(x, lo, hi, order=2):
    return signal.sosfilt(signal.butter(order, [lo, hi], "band", fs=SR, output="sos"), x)


def hp(x, f, order=2):
    return signal.sosfilt(signal.butter(order, f, "high", fs=SR, output="sos"), x, axis=-1)


def lp(x, f, order=2):
    return signal.sosfilt(signal.butter(order, f, "low", fs=SR, output="sos"), x, axis=-1)


def crush(x, bits=6, hold=6):
    y = np.repeat(x[::hold], hold)[:len(x)]
    q = 2 ** (bits - 1)
    return np.round(y * q) / q


def stereo(m, width=0.0, delay_ms=0.0):
    if width <= 0:
        return np.stack([m, m])
    d = sec(delay_ms / 1000)
    side = np.concatenate([np.zeros(d), m[:len(m) - d]]) - m
    return np.stack([m + width * side, m - width * side])


_IR = {}


def reverb_ir(rt, seed=11):
    """Small, smooth room: early reflections + 3-band decaying noise, decorrelated L/R."""
    if (rt, seed) in _IR:
        return _IR[(rt, seed)]
    rng = np.random.default_rng(seed)
    n = int(rt * SR)
    t = np.arange(n) / SR
    ir = np.zeros((2, n))
    for ch in range(2):
        nz = rng.standard_normal(n)
        acc = np.zeros(n)
        for kind, fr, k in (("low", 600, 0.9), ("band", (600, 4500), 1.0), ("high", 4500, 0.45)):
            sos = signal.butter(2, fr, btype=kind, fs=SR, output="sos")
            acc += signal.sosfilt(sos, nz) * np.exp(-6.9 * t / (rt * k))
        acc *= np.clip(t / 0.015, 0, 1)
        for _ in range(8):
            d = int(rng.uniform(0.004, 0.030) * SR)
            acc[d] += rng.uniform(0.5, 1.2) * rng.choice([-1, 1]) * 4
        pre = int(0.012 * SR)
        ir[ch, pre:] = acc[:n - pre]
    ir /= np.sqrt((ir ** 2).sum(axis=1, keepdims=True))
    _IR[(rt, seed)] = ir
    return ir


def small_room(x, rt=0.35, wet=0.10):
    ir = reverb_ir(rt, seed=3)
    mono = x.mean(axis=0)
    w = np.stack([signal.fftconvolve(hp(mono, 500), ir[c])[:x.shape[1]] for c in range(2)])
    w *= np.abs(x).max() / (np.abs(w).max() + 1e-12)
    return x + w * wet


# ------------------------------------------------------------------ loudness --------
def kweight(x):
    b1 = [1.53512485958697, -2.69169618940638, 1.19839281085285]
    a1 = [1.0, -1.69065929318241, 0.73248077421585]
    b2 = [1.0, -2.0, 1.0]
    a2 = [1.0, -1.99004745483398, 0.99007225036621]
    return signal.lfilter(b2, a2, signal.lfilter(b1, a1, x, axis=-1), axis=-1)


def loudness(x):
    """ITU-R BS.1770-4 integrated loudness (LUFS), 48 kHz stereo."""
    y = kweight(x)
    blk, hop = int(0.4 * SR), int(0.1 * SR)
    if y.shape[1] < blk:
        y = np.pad(y, ((0, 0), (0, blk - y.shape[1])))
    z = np.array([(y[:, i:i + blk] ** 2).mean(axis=1).sum()
                  for i in range(0, y.shape[1] - blk + 1, hop)])
    z = z[(-0.691 + 10 * np.log10(z + 1e-20)) > -70]
    if not len(z):
        return -70.0
    rel = -0.691 + 10 * np.log10(z.mean()) - 10
    z = z[(-0.691 + 10 * np.log10(z)) > rel]
    return float(-0.691 + 10 * np.log10(z.mean()))


def limit(x, ceiling_db=-1.2):
    """Offline look-ahead true-peak limiter (4x oversampled detection)."""
    ceil = 10 ** (ceiling_db / 20)
    for _ in range(4):
        up = np.abs(signal.resample_poly(x, 4, 1, axis=1)).max(axis=0)
        n = x.shape[1]
        pk = up[:n * 4].reshape(n, 4).max(axis=1)
        need = np.minimum(1.0, ceil / np.maximum(pk, 1e-12))
        if need.min() >= 0.999:
            break
        L = int(0.002 * SR)
        g = minimum_filter1d(need, 2 * L + 1)
        rel = np.exp(-1 / (0.06 * SR))
        for i in range(1, n):
            if g[i] > g[i - 1]:
                g[i] = g[i - 1] * rel + g[i] * (1 - rel)
        win = np.hanning(L + 1)
        g = np.convolve(g, win / win.sum(), mode="same")
        x = x * g
    return x


def write_wav(path, x):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    if x.ndim == 1:
        x = np.stack([x, x])
    inter = np.clip(np.round(x.T * 8388607), -8388608, 8388607).astype("<i4")
    raw = inter.reshape(-1).view(np.uint8).reshape(-1, 4)[:, :3].tobytes()
    with wave.open(path, "wb") as w:
        w.setnchannels(x.shape[0])
        w.setsampwidth(3)
        w.setframerate(SR)
        w.writeframes(raw)


# ------------------------------------------------------------------ the voice -------
# F1-F4 (Hz) and the gains of F2-F4 relative to F1, per vowel; formant bandwidths
VOWELS = {
    "m":  ((300, 1250, 2600, 3500), (0.18, 0.10, 0.04)),
    "n":  ((300, 1650, 2600, 3500), (0.16, 0.09, 0.04)),
    "ng": ((300, 1100, 2400, 3400), (0.14, 0.07, 0.03)),
    "uh": ((700, 1500, 2700, 3600), (0.90, 0.55, 0.30)),
    "ah": ((850, 1300, 2800, 3700), (0.90, 0.50, 0.30)),
    "ee": ((330, 2700, 3300, 4100), (0.60, 0.60, 0.35)),
    "ih": ((430, 2400, 3050, 3900), (0.70, 0.50, 0.30)),
    "eh": ((560, 2250, 2950, 3800), (0.80, 0.50, 0.30)),
    "oo": ((360, 900, 2600, 3500), (0.50, 0.15, 0.08)),
    "oh": ((520, 1000, 2700, 3600), (0.70, 0.25, 0.10)),
}
BW = (85, 130, 200, 280)

BW = (85, 130, 200, 280)

# the original voice: A-flat 4, small, a touch robotic
VOICE1 = dict(force_voice=True, base=415.30, size=1.0, tilt=1.15, breath=0.03, step=0.25, step_ms=9, vib=0.010, vib_hz=7,
              sheen=0.10, ring=0.0, chorus=0.0, crush=None, air=0.0, drive=1.8, room=(0.3, 0.08),
              width=0.15, hp=200, presence=(2600, 3.0))


def interp_path(points, x):
    xs = [p[0] for p in points]
    return np.interp(x, xs, [p[1] for p in points])


def vowel_at(points, x):
    """Blend formant settings along a vowel path [(x, vowel), ...]."""
    xs = [p[0] for p in points]
    if x <= xs[0]:
        return VOWELS[points[0][1]]
    for (x0, v0), (x1, v1) in zip(points, points[1:]):
        if x0 <= x <= x1:
            w = (x - x0) / max(x1 - x0, 1e-9)
            w = 0.5 - 0.5 * np.cos(np.pi * w)
            f = tuple((1 - w) * a + w * b for a, b in zip(VOWELS[v0][0], VOWELS[v1][0]))
            g = tuple((1 - w) * a + w * b for a, b in zip(VOWELS[v0][1], VOWELS[v1][1]))
            return f, g
    return VOWELS[points[-1][1]]


def speak(phrase, v, length):
    """phrase: list of syllables dict(t, dur, pitch=[(x, semis)], vowels=[(x, vowel)], onset, amp,
    att, rel, mix). `mix` blends three sources per syllable:
      voice: the buzzy glottal source through the formant "mouth" (the agent voice)
      sine:  a pure sine plus gentle 2nd-5th harmonics, no mouth
      sform: that same sine spoken through the mouth formants
    Returns a stereo array (not yet mastered)."""
    n = sec(length)
    t = np.arange(n) / SR
    semis = np.zeros(n)
    amp = np.zeros(n)
    breath = np.full(n, v["breath"])
    owner = np.full(n, -1)
    wts = {k: np.zeros(n) for k in ("voice", "sine", "sform")}
    for k, s in enumerate(phrase):
        k0, k1 = sec(s["t"]), min(sec(s["t"] + s["dur"]), n)
        x = np.linspace(0, 1, k1 - k0)
        semis[k0:k1] = interp_path(s["pitch"], x)
        a = s.get("amp", 1.0) * np.minimum(1, x / s.get("att", 0.10)) * np.minimum(1, (1 - x) / s.get("rel", 0.35)) ** 1.5
        amp[k0:k1] = np.maximum(amp[k0:k1], a)
        owner[k0:k1] = k
        mix = {"voice": 1.0} if v.get("force_voice") else (s.get("mix") or v.get("mix") or {"voice": 1.0})
        for key in wts:
            wts[key][k0:k1] = mix.get(key, 0.0)
        if s.get("onset") == "h":
            breath[k0:k0 + sec(0.035)] = 1.2
    # hold pitch and mix through gaps so glides stay continuous, then smooth
    last, lastw = 0.0, {k: 0.0 for k in wts}
    for i in range(n):
        if owner[i] < 0:
            semis[i] = last
            for key in wts:
                wts[key][i] = lastw[key]
        else:
            last = semis[i]
            for key in wts:
                lastw[key] = wts[key][i]
    semis = signal.savgol_filter(semis, sec(0.02) | 1, 2)
    for key in wts:
        wts[key] = np.convolve(wts[key], np.ones(sec(0.015)) / sec(0.015), mode="same")
    f0 = v["base"] * 2 ** (semis / 12)
    if v["step"] > 0:
        st = sec(v["step_ms"] / 1000)
        held = np.repeat(f0[::st], st)[:n]
        f0 = (1 - v["step"]) * f0 + v["step"] * held
    f0 *= 1 + v["vib"] * np.sin(2 * np.pi * v["vib_hz"] * t) * np.clip((t - 0.05) / 0.1, 0, 1)
    amp = signal.savgol_filter(amp, sec(0.010) | 1, 2).clip(0)
    rng = np.random.default_rng(7)

    def buzz(f0c, seed):
        ph = 2 * np.pi * np.cumsum(f0c) / SR
        out = np.zeros(n)
        for k in range(1, int(9000 / f0c.max()) + 1):
            out += np.sin(k * ph) / k ** v["tilt"]
        return out + breath * np.random.default_rng(seed).standard_normal(n) * 0.3

    def sine(f0c):
        ph = 2 * np.pi * np.cumsum(f0c) / SR
        out = np.sin(ph)
        for k, h in enumerate(v.get("harm", (0.25, 0.12, 0.05, 0.02)), start=2):
            out += h * np.sin(k * ph)
        return out

    def mouth(src):
        out = np.zeros(n)
        blk = 96
        zi = [np.zeros(2) for _ in range(4)]
        size = v["size"]
        for i in range(0, n, blk):
            k = owner[min(i + blk // 2, n - 1)]
            if k < 0:
                k = owner[max(i - 1, 0)] if i > 0 else 0
            s = phrase[max(k, 0)]
            x = (t[i] - s["t"]) / s["dur"]
            (fs, gs) = vowel_at(s["vowels"], float(np.clip(x, 0, 1)))
            if s.get("onset") == "d" and 0 <= x < 0.12:    # "d": F2 locus high, F1 low
                fs = (fs[0] * 0.7, 1800 + (fs[1] - 1800) * x / 0.12, fs[2], fs[3])
            seg = src[i:i + blk]
            acc = np.zeros(len(seg))
            for j in range(4):
                f = fs[j] * size
                r = np.exp(-np.pi * BW[j] * size * v.get("bw", 1.0) / SR)
                th = 2 * np.pi * min(f, SR * 0.45) / SR
                a_ = [1, -2 * r * np.cos(th), r * r]
                b_ = [(1 - r * r) * 0.5, 0, -(1 - r * r) * 0.5]
                y_, zi[j] = signal.lfilter(b_, a_, seg, zi=zi[j])
                acc += (1.0 if j == 0 else gs[j - 1]) * y_
            out[i:i + blk] = acc
        return out

    def unit(x):
        return x / (np.abs(x).max() + 1e-12)

    y = np.zeros(n)
    if wts["voice"].max() > 0:
        src = buzz(f0, 1)
        if v["chorus"] > 0:   # a second, slightly detuned and delayed singer
            d = sec(0.012)
            src2 = buzz(f0 * 2 ** (v.get("chorus_cents", 9) / 1200), 2)
            src = src + v["chorus"] * np.concatenate([np.zeros(d), src2[:-d]])
        y += wts["voice"] * unit(mouth(src))
    if wts["sine"].max() > 0 or wts["sform"].max() > 0:
        sn = sine(f0)
        if wts["sine"].max() > 0:
            y += wts["sine"] * unit(sn)
        if wts["sform"].max() > 0:
            y += wts["sform"] * unit(mouth(sn + breath * rng.standard_normal(n) * 0.5))
    y = unit(y) * amp

    # plosive bursts for "b" and "d"
    for s in phrase:
        on = s.get("onset")
        if on in ("b", "d", "t"):
            m = sec(0.006 if on != "t" else 0.012)
            lo, hi = {"b": (600, 3000), "d": (2500, 7000), "t": (3500, 9000)}[on]
            burst = bp(rng.standard_normal(m), lo, hi) * np.linspace(1, 0, m)
            place(y, v.get("cons", 1.0) * (0.35 if on != "t" else 0.45) * s.get("amp", 1) * burst
                    / np.abs(burst).max(), s["t"])
        elif on in ("s", "f"):
            d = s.get("pre", 0.12 if on == "s" else 0.05)
            m = sec(d)
            lo, hi = v.get("sib", (4500, 11000)) if on == "s" else (900, 6000)
            hiss = bp(rng.standard_normal(m), lo, hi) * np.sin(np.pi * np.linspace(0, 1, m)) ** 1.5
            place(y, v.get("cons", 1.0) * (0.30 if on == "s" else 0.18) * hiss / np.abs(hiss).max(),
                    max(s["t"] - d * 0.85, 0))

    ph2 = 2 * np.pi * np.cumsum(2 * f0) / SR
    y += v["sheen"] * np.sin(ph2) * amp                    # robot sheen an octave up
    if v["ring"] > 0:                                       # ring-mod tint, harmonic carrier
        y = (1 - v["ring"]) * y + v["ring"] * y * np.sin(2 * np.pi * np.cumsum(f0 * v.get("ring_ratio", 1.5)) / SR) * 1.6
    if v["air"] > 0:
        air = hp(rng.standard_normal(n), 6000) * amp
        y += v["air"] * air / (np.abs(air).max() + 1e-12) * np.abs(y).max()
    y = np.tanh(v["drive"] * y / (np.abs(y).max() + 1e-12)) / np.tanh(v["drive"])
    if v["crush"]:
        bits, hold = v["crush"]
        y = crush(y, bits=bits, hold=hold)
        y = lp(y, 9000, 2)
    return small_room(stereo(y, v["width"], 0.4), *v["room"])


def syl(t, dur, pitch, vowels, onset=None, amp=1.0, att=0.10, rel=0.35, mix=None, pre=None):
    d = dict(t=t, dur=dur, pitch=pitch, vowels=vowels, onset=onset, amp=amp, att=att, rel=rel, mix=mix)
    if pre is not None:
        d["pre"] = pre     # how long an "s" or "f" hiss runs before the voiced part
    return d


MM = [(0, "m"), (1, "m")]
HM_OPEN = [(0, "m"), (0.25, "uh"), (0.6, "uh"), (1.0, "m")]
HM_OPEN = [(0, "m"), (0.25, "uh"), (0.6, "uh"), (1.0, "m")]


def mm_hm(speed=1.0, leap=7, settle=5, open_vowels=HM_OPEN, first=MM, onset2="h", amp1=0.7):
    """The success phrase. Semitones are above the voice's base note: start on the base,
    then a rise from +2 that overshoots to `leap` and settles on `settle`."""
    k = speed
    return [syl(0.0, 0.10 * k, [(0, -1), (0.3, 0), (1, 0)], first, amp=amp1, rel=0.35),
            syl(0.145 * k, 0.215 * k, [(0, 2), (0.42, leap), (0.74, settle), (1, settle)], open_vowels,
                onset=onset2, rel=0.45, att=0.08)]


def bend_phrase(s1=5, start=7, end=9, speed=1.0, vow1=MM, vow2=HM_OPEN, mix1=None, mix2=None, onset2="h"):
    """The two-syllable "mm-HM" rhythm with a sine-style glide on the second syllable: hold a note,
    then glide smoothly up (or down) to the next one. Semitones above the voice's base."""
    k = speed
    return [syl(0.0, 0.10 * k, [(0, s1 - 0.6), (0.3, s1), (1, s1)], vow1, amp=0.7, mix=mix1),
            syl(0.145 * k, 0.27 * k, [(0, start), (0.28, start), (0.68, end), (1, end)], vow2,
                onset=onset2, rel=0.42, att=0.08, mix=mix2)]


def grid_phrases(glide=True):
    """All 16 sounds: {(priority, level): (said, phrase, length)}. Semitones above A-flat 4 in
    D-flat major pentatonic (0 Ab4, 2 Bb4, 5 Db5, 7 Eb5, 9 F5, 12 Ab5, 14 Bb5, 17 Db6); error
    sits in B-flat minor. Contours: info flat, success up, warning hangs on the 2nd/6th, error
    falls. Priority: low is small and closed, high is brighter with bigger moves, critical is a
    quick double."""
    talk = [(0, "m"), (0.22, "ah"), (0.7, "ah"), (1, "m")]
    hm = [(0, "m"), (0.3, "uh"), (0.6, "uh"), (1, "m")]
    hah = [(0, "m"), (0.2, "ah"), (0.65, "ah"), (1, "m")]
    hey = [(0, "eh"), (0.6, "ee"), (1, "ee")]
    closed = [(0, "m"), (1, "m")]
    q = [(0, "m"), (0.5, "m"), (1, "n")]
    soft = {"sine": 0.65, "sform": 0.35}
    G = {}
    # info: flat
    G[("low", "info")] = ("hm", [syl(0.0, 0.2, [(0, 5), (1, 5)], closed, amp=0.85, rel=0.5, mix=soft)], 0.45)
    G[("medium", "info")] = ("hm.", [syl(0.0, 0.22, [(0, 5), (0.75, 5), (1, 4.7)], hm, onset="h", rel=0.45)], 0.45)
    G[("high", "info")] = ("hm!", [syl(0.0, 0.2, [(0, 9), (1, 9)], hah, onset="h", att=0.06, rel=0.4)], 0.45)
    G[("critical", "info")] = ("hm-hm.", [syl(0.0, 0.13, [(0, 9), (1, 9)], hah, onset="h", att=0.06, rel=0.3),
                                           syl(0.17, 0.17, [(0, 9), (1, 9)], hah, onset="h", att=0.06, rel=0.4)], 0.5)
    # success: up
    if glide:
        G[("low", "success")] = ("mm-hm", bend_phrase(vow2=closed, mix1=soft, mix2=soft), 0.5)
        G[("medium", "success")] = ("mm-HM!", bend_phrase(vow2=talk), 0.5)
        G[("high", "success")] = ("mm-HAH!", bend_phrase(start=7, end=12, speed=0.9, vow2=hah), 0.5)
    else:   # the agent voice's own contour: a rise that overshoots and settles, like a nod
        G[("low", "success")] = ("mm-hm", [syl(0.0, 0.10, [(0, 0), (1, 0)], closed, amp=0.7),
                                           syl(0.15, 0.20, [(0, 2), (0.6, 5), (1, 5)], closed, rel=0.5)], 0.45)
        G[("medium", "success")] = ("mm-HM!", mm_hm(), 0.5)
        G[("high", "success")] = ("mm-HAH!", [
            syl(0.0, 0.085, [(0, -1), (0.3, 0), (1, 0)], closed, amp=0.7),
            syl(0.12, 0.23, [(0, 4), (0.38, 10), (0.7, 9), (1, 9)], hah, onset="h", rel=0.4, att=0.06)], 0.5)
    G[("critical", "success")] = ("hey-hey!", [
        syl(0.0, 0.14, [(0, 12), (0.3, 12), (0.75, 17), (1, 17)], hey, onset="h", att=0.06, rel=0.3),
        syl(0.18, 0.18, [(0, 12), (0.3, 12), (0.75, 17), (1, 17)], hey, onset="h", att=0.06, rel=0.4)], 0.5)
    # warning: rises and hangs
    G[("low", "warning")] = ("mm?", [syl(0.0, 0.26, [(0, 5), (0.3, 5), (0.85, 7), (1, 7)], q, amp=0.85,
                                         rel=0.35, mix=soft)], 0.45)
    G[("medium", "warning")] = ("hm-mm?", [
        syl(0.0, 0.13, [(0, 5), (1, 5)], hm, onset="h"),
        syl(0.175, 0.28, [(0, 2), (0.25, 2), (0.8, 7), (1, 7.3)], q, rel=0.3)], 0.55)
    G[("high", "warning")] = ("hm-MM?", [
        syl(0.0, 0.12, [(0, 5), (1, 5)], hm, onset="h"),
        syl(0.16, 0.28, [(0, 7), (0.2, 7), (0.8, 14), (1, 14.3)], [(0, "m"), (0.3, "uh"), (0.75, "uh"), (1, "n")],
            rel=0.3)], 0.55)
    G[("critical", "warning")] = ("hm? hm?", [
        syl(0.0, 0.15, [(0, 9), (0.3, 9), (0.8, 14), (1, 14)], q, onset="h", att=0.06, rel=0.3),
        syl(0.2, 0.18, [(0, 9), (0.3, 9), (0.8, 14), (1, 14.3)], q, onset="h", att=0.06, rel=0.35)], 0.55)
    # error: falls, in B-flat minor
    G[("low", "error")] = ("mm-mm", [syl(0.0, 0.12, [(0, 5), (1, 5)], closed, amp=0.8, mix=soft),
                                     syl(0.17, 0.22, [(0, 2), (0.6, 2), (1, 1.5)], closed, rel=0.5, mix=soft)], 0.5)
    G[("medium", "error")] = ("uh-oh", [
        syl(0.0, 0.13, [(0, 5), (1, 5)], [(0, "uh"), (1, "uh")], att=0.15, rel=0.25),
        syl(0.175, 0.28, [(0, 2), (0.35, 2), (0.85, 0), (1, 0)], [(0, "oh"), (0.8, "oh"), (1, "oo")],
            att=0.12, rel=0.5)], 0.55)
    G[("high", "error")] = ("uh-OH", [
        syl(0.0, 0.12, [(0, 9), (1, 9)], [(0, "uh"), (1, "uh")], att=0.12, rel=0.25),
        syl(0.16, 0.30, [(0, 5), (0.3, 5), (0.85, 2), (1, 2)], [(0, "ah"), (0.4, "oh"), (1, "oo")],
            att=0.1, rel=0.5)], 0.55)
    G[("critical", "error")] = ("uh-oh, uh-oh", [
        syl(0.0, 0.1, [(0, 9), (1, 9)], [(0, "uh"), (1, "uh")], att=0.12, rel=0.25),
        syl(0.13, 0.13, [(0, 5), (1, 5)], [(0, "oh"), (1, "oh")], att=0.1, rel=0.3),
        syl(0.32, 0.1, [(0, 9), (1, 9)], [(0, "uh"), (1, "uh")], att=0.12, rel=0.25),
        syl(0.45, 0.26, [(0, 5), (0.35, 5), (0.85, 2), (1, 2)], [(0, "oh"), (0.8, "oh"), (1, "oo")],
            att=0.1, rel=0.5)], 0.8)
    return G


def moment_phrases():
    """The agent voice reacting to the app's events: (name, said, phrase, length, lufs).
    Semitones above A-flat 4, D-flat pentatonic (0 Ab4, 2 Bb4, 5 Db5, 7 Eb5, 9 F5, 12 Ab5,
    14 Bb5, 17 Db6); glides pass through other pitches but always land on these."""
    hm = [(0, "m"), (0.3, "uh"), (0.6, "uh"), (1, "m")]
    hey = [(0, "eh"), (0.6, "ee"), (1, "ee")]
    hi = [(0, "ah"), (0.55, "ah"), (1, "ee")]
    oh = [(0, "oh"), (0.8, "oh"), (1, "oo")]
    closed = [(0, "m"), (1, "m")]
    return [
        ("Agent asks you a question", "hm?",
         [syl(0.0, 0.34, [(0, 5), (0.25, 4.6), (0.75, 10.5), (1, 12)], hm, onset="h", att=0.08, rel=0.3)],
         0.5, -16.0),
        ("You answered", "oh! mm-hm",
         [syl(0.0, 0.10, [(0, 8.5), (1, 9)], [(0, "oh"), (1, "oh")], att=0.1, rel=0.3),
          syl(0.19, 0.09, [(0, 5), (1, 5)], closed, amp=0.75),
          syl(0.32, 0.20, [(0, 6), (0.45, 9.5), (0.75, 9), (1, 9)], hm, onset="h", rel=0.45)], 0.65, -16.0),
        ("New message", "bip-bip",
         [syl(0.0, 0.065, [(0, 12), (1, 12)], [(0, "ih"), (1, "ih")], onset="b", att=0.1, rel=0.4),
          syl(0.11, 0.075, [(0, 12), (1, 12.2)], [(0, "ih"), (1, "ih")], onset="b", att=0.1, rel=0.45)],
         0.35, -16.0),
        ("Agent came online", "hi!",
         [syl(0.0, 0.26, [(0, 7), (0.5, 12.5), (0.8, 12), (1, 12)], hi, onset="h", att=0.08, rel=0.4)],
         0.45, -16.0),
        ("Agent went offline", "bye-bye",
         [syl(0.0, 0.16, [(0, 9), (1, 9)], hi, onset="b", att=0.12, rel=0.35),
          syl(0.21, 0.26, [(0, 5), (0.6, 5), (1, 4.2)], hi, onset="b", amp=0.75, att=0.12, rel=0.6)],
         0.6, -16.0),
        ("Task done", "ta-DA!",
         [syl(0.0, 0.08, [(0, 5), (1, 5)], [(0, "ah"), (1, "ah")], onset="t", amp=0.8, rel=0.4),
          syl(0.12, 0.30, [(0, 9), (0.3, 12.6), (0.55, 12), (1, 12)], [(0, "ah"), (1, "ah")], onset="d",
              att=0.05, rel=0.45)], 0.55, -16.0),
        ("You were mentioned", "psst… hey!",
         [syl(0.16, 0.18, [(0, 9), (0.5, 12), (1, 12)], hey, onset="s", att=0.06, rel=0.35, pre=0.14)],
         0.45, -16.0),
        ("Permission request", "uh… may I?",
         [syl(0.0, 0.11, [(0, 5), (1, 4.7)], [(0, "uh"), (1, "uh")], amp=0.7, att=0.15, rel=0.4),
          syl(0.28, 0.14, [(0, 5), (1, 7)], [(0, "m"), (0.3, "eh"), (1, "ee")], att=0.12, rel=0.25),
          syl(0.45, 0.26, [(0, 7), (0.3, 7.5), (0.9, 14), (1, 14.2)], hi, rel=0.3)], 0.85, -16.0),
        ("Critical page", "hey-hey-HEY!",
         [syl(0.0, 0.11, [(0, 9), (0.5, 12), (1, 12)], hey, onset="h", att=0.06, rel=0.3),
          syl(0.15, 0.11, [(0, 9), (0.5, 12), (1, 12)], hey, onset="h", att=0.06, rel=0.3),
          syl(0.30, 0.20, [(0, 12), (0.4, 17), (1, 17)], hey, onset="h", att=0.05, rel=0.4)], 0.6, -14.0),
        ("Owner reacted", "oh!",
         [syl(0.0, 0.14, [(0, 9), (0.6, 12), (1, 12)], [(0, "oh"), (1, "oh")], att=0.1, rel=0.4)],
         0.3, -16.0),
        ("Message sent", "fwp",
         [syl(0.04, 0.08, [(0, 12), (1, 7)], [(0, "oo"), (1, "oo")], onset="f", amp=0.8, att=0.15, rel=0.5,
              pre=0.05)], 0.25, -20.0),
        ("Upload done", "pop-hm",
         [syl(0.0, 0.05, [(0, 12), (1, 12)], [(0, "oh"), (1, "oh")], onset="b", att=0.1, rel=0.5),
          syl(0.12, 0.20, [(0, 5), (0.5, 7.4), (1, 7)], hm, onset="h", rel=0.45)], 0.45, -16.0),
        ("Error or failed to send", "uh-oh…",
         [syl(0.0, 0.14, [(0, 5), (1, 5)], [(0, "uh"), (1, "uh")], att=0.15, rel=0.25),
          syl(0.19, 0.36, [(0, 2), (0.3, 2), (0.85, -0.3), (1, 0)], oh, att=0.12, rel=0.55)], 0.7, -16.0),
        ("Agent thinking", "hmmm…",
         [syl(0.0, 0.78, [(0, 5), (0.3, 4.6), (0.6, 5.6), (1, 5)], [(0, "m"), (0.5, "n"), (1, "m")],
              amp=0.85, att=0.2, rel=0.4)], 0.9, -20.0),
    ]


def soft_voice(semitones_down):
    return {**VOICE1,
            "base": VOICE1["base"] * 2 ** (-semitones_down / 12),
            "size": 0.90,            # formants 10% lower: a slightly bigger, rounder mouth
            "bw": 1.2,               # wider formants: rounder, less resonant ring
            "tilt": 1.25,            # softer glottal pulse: fewer bright harmonics
            "sheen": 0.05, "drive": 1.25,
            "cons": 0.5, "sib": (3500, 8000)}    # gentler b/d/t bursts, softer "s"


# The final voice: down a fifth, with its mastering EQ
FINAL_VOICE = soft_voice(7)
FINAL_EQ = dict(hp=120, hp_steep=150, body=(1500, 4.0), dip=(3500, -3.5), shelf=(4500, -6.0))


def master_soft(x, eq, lufs):
    """Gentle chain: high-pass, a body lift around 1.5 kHz, a soft dip in the 3-4 kHz presence
    band and a shelf off the top, light compression; then loudness and true-peak limit."""
    from pedalboard import Compressor, HighShelfFilter, HighpassFilter, PeakFilter, Pedalboard
    board = Pedalboard([HighpassFilter(cutoff_frequency_hz=eq["hp"]),
                        PeakFilter(cutoff_frequency_hz=eq["body"][0], gain_db=eq["body"][1], q=0.8),
                        PeakFilter(cutoff_frequency_hz=eq["dip"][0], gain_db=eq["dip"][1], q=1.0),
                        HighShelfFilter(cutoff_frequency_hz=eq["shelf"][0], gain_db=eq["shelf"][1], q=0.7),
                        Compressor(threshold_db=-20, ratio=2.0, attack_ms=5, release_ms=100)])
    if eq.get("hp_steep"):     # 4th-order high-pass: keeps low notes from turning to mud
        x = signal.sosfilt(signal.butter(4, eq["hp_steep"], "high", fs=SR, output="sos"), x, axis=1)
    x = x / (np.abs(x).max() + 1e-12) * 0.5
    y = board(np.ascontiguousarray(x.astype(np.float32)), SR).astype(np.float64)
    env = np.abs(y).max(axis=0)
    alive = np.nonzero(env > env.max() * 10 ** (-50 / 20))[0]
    end = max(min(y.shape[1], alive[-1] + sec(0.02)), min(y.shape[1], sec(0.45)))
    y = y[:, :end]
    fo = min(sec(0.06), end // 4)
    y[:, -fo:] *= (0.5 + 0.5 * np.cos(np.pi * np.arange(fo) / fo))
    for _ in range(3):
        y *= 10 ** ((lufs - loudness(y)) / 20)
        y = limit(y, -1.2)
    y[:, :sec(0.002)] *= np.linspace(0, 1, sec(0.002))
    y[:, -16:] *= np.linspace(1, 0, 16)
    return y


def render_all(out_dir):
    os.makedirs(os.path.join(out_dir, "grid"), exist_ok=True)
    os.makedirs(os.path.join(out_dir, "moments"), exist_ok=True)
    G = grid_phrases(glide=False)
    for level in LEVELS:
        for prio in PRIORITIES:
            said, phrase, length = G[(prio, level)]
            x = master_soft(speak(phrase, FINAL_VOICE, length), FINAL_EQ, GRID_LUFS[prio])
            path = os.path.join(out_dir, "grid", f"{prio}-{level}.wav")
            write_wav(path, x)
            print(path, said)
    for k, (name, said, phrase, length, lufs) in enumerate(moment_phrases()):
        x = master_soft(speak(phrase, FINAL_VOICE, length), FINAL_EQ, lufs + MOMENT_OFFSET)
        path = os.path.join(out_dir, "moments", f"{k + 1:02d}-{name.lower().replace(' ', '-')}.wav")
        write_wav(path, x)
        print(path, said)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="output folder")
    render_all(ap.parse_args().out)


if __name__ == "__main__":
    main()
