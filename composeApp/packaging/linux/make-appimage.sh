#!/usr/bin/env bash
# Packs the app folder jpackage made (./gradlew :composeApp:packageAppImage) into one
# Agents-<version>-x86_64.AppImage file.
#
#   make-appimage.sh <app folder> <version> <out dir> [appimagetool]
#
# appimagetool is downloaded (a pinned release) when not given.
set -euo pipefail

app="$1"; version="$2"; out="$3"; tool="${4:-}"
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if [ -z "$tool" ]; then
  tool="$work/appimagetool"
  curl -fsSL --retry 3 -o "$tool" \
    https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage
  chmod +x "$tool"
fi

dir="$work/Agents.AppDir"
mkdir -p "$dir/usr/lib"
cp -a "$app" "$dir/usr/lib/agents"
cp "$here/../icons/agents.png" "$dir/agents.png"
cat > "$dir/agents.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Agents
Comment=Agent Dashboard: questions and updates from your agents
Exec=Agents
Icon=agents
Categories=Network;
Terminal=false
DESKTOP
cat > "$dir/AppRun" <<'RUN'
#!/bin/sh
here="$(dirname "$(readlink -f "$0")")"
exec "$here/usr/lib/agents/bin/Agents" "$@"
RUN
chmod +x "$dir/AppRun"

mkdir -p "$out"
# --appimage-extract-and-run: CI machines have no FUSE to mount the tool itself.
ARCH=x86_64 "$tool" --appimage-extract-and-run --no-appstream "$dir" "$out/Agents-$version-x86_64.AppImage"
