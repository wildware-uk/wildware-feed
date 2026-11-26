#!/usr/bin/env bash
# Puts the agent-voice sounds into the apps: renders them with agentvoice.py, then converts them.
#   - Compose resources (desktop, web, phones in the app): 44.1 kHz 16-bit mono WAV
#   - iPhone push sounds (iosApp/Sounds): the same WAVs, the ones the server names
#   - Android notification channels (res/raw): Ogg Vorbis
# Needs python3 (see README.md) and ffmpeg. Run from anywhere.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

python3 "$here/agentvoice.py" --out "$out"

res="$root/composeApp/src/commonMain/composeResources/files/sounds"
ios="$root/iosApp/Sounds"
raw="$root/androidApp/src/main/res/raw"
mkdir -p "$res" "$ios" "$raw"

# Each moment's file name in the apps. The first five keep the old sounds' names, which older
# iPhone apps still have.
declare -A moment=(
  [01]=question [03]=message [06]=done [13]=error [09]=critical
  [02]=answered [04]=online [05]=offline [07]=mention [08]=permission
  [10]=reaction [11]=sent [12]=uploaded [14]=thinking
)

wav() {
  ffmpeg -nostdin -hide_banner -loglevel error -y -i "$1" \
    -af "aresample=44100:resampler=soxr:dither_method=triangular" -ac 1 -c:a pcm_s16le \
    -map_metadata -1 -fflags +bitexact -flags:a +bitexact "$2"
}

for f in "$out"/grid/*.wav; do wav "$f" "$res/$(basename "$f")"; done
for f in "$out"/moments/*.wav; do
  n="$(basename "$f")"
  wav "$f" "$res/${moment[${n:0:2}]}.wav"
done

# The sounds the server names in pushes (server: Apns.soundFor).
for n in question message done error critical permission mention; do cp "$res/$n.wav" "$ios/"; done
for p in low medium high critical; do
  for l in info success warning error; do cp "$res/$p-$l.wav" "$ios/"; done
done

# One per Android notification channel (androidApp: Channels.Sounds).
for n in question permission message mention critical; do
  ffmpeg -nostdin -hide_banner -loglevel error -y -i "$res/$n.wav" -c:a libvorbis -q:a 5 -map_metadata -1 "$raw/$n.ogg"
done

echo "Wrote $(ls "$res" | wc -l) app sounds, $(ls "$ios" | wc -l) iPhone push sounds and 5 Android channel sounds."
