#!/usr/bin/env bash
# Writes a release's notes: what to download and how to install it, per computer.
#   release-notes.sh <version> <mac signed> <mac notarized> <windows signed> [android signed]
set -euo pipefail
version="$1"; mac_signed="${2:-false}"; mac_notarized="${3:-false}"; windows_signed="${4:-false}"; android_signed="${5:-false}"

cat <<NOTES
## Download

| Computer | File |
| --- | --- |
| Mac with Apple silicon | \`Wildware-Feed-$version-mac-arm64.dmg\` |
| Windows | \`Wildware-Feed-$version-windows-x64.msi\` (or \`-portable.zip\`, no install) |
| Ubuntu, Debian | \`wildware-feed_${version}_amd64.deb\` |
| Fedora, openSUSE | \`wildware-feed-$version.x86_64.rpm\` |
| Any other Linux | \`Wildware-Feed-$version-x86_64.AppImage\` |
NOTES

# The Android files exist only when the release was signed with the real key.
if [ "$android_signed" = "true" ]; then
  echo "| Android phone or tablet | \`Wildware-Feed-$version-android.apk\` |"
fi

cat <<NOTES

\`SHA256SUMS.txt\` has each file's checksum.

## How to install

**Mac**: open the .dmg and drag Wildware Feed into Applications, then open it.
NOTES

if [ "$mac_notarized" = "true" ]; then
  echo "This build is signed and notarised by Apple, so it opens straight away."
elif [ "$mac_signed" = "true" ]; then
  echo "This build is signed but **not notarised**. The first time, open **System Settings › Privacy & Security** and click **Open Anyway** next to Wildware Feed."
else
  echo "This build is **not signed**. The first time it is refused: open **System Settings › Privacy & Security**, click **Open Anyway** next to Wildware Feed, then confirm. (macOS 14 or older: Control-click Wildware Feed in Applications, choose Open, then Open.)"
fi

cat <<NOTES

**Windows**: double-click the .msi. It installs for you alone, no administrator password needed.
NOTES

if [ "$windows_signed" = "true" ]; then
  echo "This build is signed."
else
  echo "This build is **not signed**, so SmartScreen says \"Windows protected your PC\" the first time: click **More info**, then **Run anyway**."
fi

cat <<NOTES

**Linux**:
- Ubuntu, Debian: \`sudo apt install ./wildware-feed_${version}_amd64.deb\`
- Fedora, openSUSE: \`sudo dnf install ./wildware-feed-$version.x86_64.rpm\`
- Anything else: \`chmod +x Wildware-Feed-$version-x86_64.AppImage\` and run it.
- On GNOME, the tray icon needs the "AppIndicator and KStatusNotifierItem Support" extension.
NOTES

if [ "$android_signed" = "true" ]; then
  cat <<NOTES

**Android** (8.0 or newer): open the .apk on the phone and let your browser or file manager install apps when it asks. Later versions install over the top the same way.
NOTES
fi

cat <<NOTES

Then sign in with your dashboard's address and owner password. More detail: [docs/INSTALL.md](https://github.com/wildware-uk/wildware-feed/blob/master/docs/INSTALL.md).
NOTES
