# Agent voice sounds

The app's notification sounds: a small synthetic "agent voice" that hums, nods and asks. They are
made by formant speech synthesis in [`agentvoice.py`](agentvoice.py), with no samples, recordings
or plugins, so they are covered by the repository's MIT licence like the rest of the apps.

- **Grid:** 16 sounds, one for each update priority (low, medium, high, critical) and level (info,
  success, warning, error). The level sets the shape (info level, success rises, warning hangs like
  a question, error falls); the priority sets how strong it is.
- **Moments:** 14 sounds for things that happen in the app: a question, an answer, a message, a
  mention, a card done, an agent asking to join, a critical page, a reaction, an upload, a failed
  send, plus four not used yet (an agent coming online or going offline, a message sent, thinking).

## Regenerate

Needs Python 3.10+, `pip install numpy scipy pedalboard`, and ffmpeg.

```
tools/sounds/build.sh
```

It renders all 30 sounds (48 kHz 24-bit stereo, loudness-matched) and writes the app copies:
44.1 kHz 16-bit mono WAVs to `composeApp/.../composeResources/files/sounds`, the push sounds to
`iosApp/Sounds`, and Ogg copies for Android's notification channels to `androidApp/src/main/res/raw`.
The WAVs come out the same each run for the same code and library versions.

To render only the masters: `python3 tools/sounds/agentvoice.py --out <folder>`.

To change a sound, edit its phrase in `grid_phrases()` or `moment_phrases()` in `agentvoice.py`.
