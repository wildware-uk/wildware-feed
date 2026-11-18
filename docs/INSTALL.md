# Installing Wildware Feed on your computer

Download the file for your computer from the
[latest release](https://github.com/wildware-uk/wildware-feed/releases/latest), install it,
then sign in with your dashboard's address and owner password.

| Computer | File |
| --- | --- |
| Mac with Apple silicon (M1 or later) | `Wildware-Feed-<version>-mac-arm64.dmg` |
| Windows 10 or 11 | `Wildware-Feed-<version>-windows-x64.msi` (or the `-portable.zip`, no install) |
| Ubuntu, Debian | `wildware-feed_<version>_amd64.deb` |
| Fedora, openSUSE | `wildware-feed-<version>.x86_64.rpm` |
| Any other Linux | `Wildware-Feed-<version>-x86_64.AppImage` |

## Mac

1. Open the `.dmg` and drag **Wildware Feed** into **Applications**.
2. Open Wildware Feed from Applications.

If the release notes say the Mac app is **not signed**, the first open is refused ("Apple could
not verify…"). Once only: open **System Settings › Privacy & Security**, scroll down, click
**Open Anyway** next to Wildware Feed, then confirm. On macOS 14 or older you can instead Control-click
Wildware Feed in Applications, choose **Open**, then **Open** again.

## Windows

1. Double-click the `.msi`. It installs for you alone, with no administrator password, and adds
   Wildware Feed to the Start menu.

If the release notes say the Windows app is **not signed**, SmartScreen shows "Windows protected
your PC". Once only: click **More info**, then **Run anyway**.

The portable `.zip` needs no install: unzip it anywhere and run `Wildware Feed.exe`.

## Linux

- **Ubuntu, Debian**: `sudo apt install ./wildware-feed_<version>_amd64.deb`
- **Fedora, openSUSE**: `sudo dnf install ./wildware-feed-<version>.x86_64.rpm` (or `sudo zypper install …`)
- **Anything else**: `chmod +x Wildware-Feed-<version>-x86_64.AppImage`, then run it. If it says FUSE is
  missing, install `libfuse2` (or run it with `--appimage-extract-and-run`).

The packages put Wildware Feed in your app menu. On GNOME the tray icon needs the
"AppIndicator and KStatusNotifierItem Support" extension; without it Wildware Feed quits when you
close its window instead of staying in the tray.

## After installing

- Closing the window keeps Wildware Feed running in the tray (the menu bar on a Mac), counting the
  questions waiting for you and showing notifications. Its menu turns this off, turns on
  **Start at login**, or turns on **Do not disturb**.
- **Updates.** Wildware Feed looks for a new version when it starts and every 6 hours, from this
  repo's GitHub releases. When there is one, **UPDATE AVAILABLE** shows at the top right of the
  window (and **Update to …** in the tray menu). Click it, read what's new, then
  **Install and restart**.
  Nothing installs without that click. **Check for updates…** in the tray menu or the ⌘K / Ctrl+K
  bar checks now; **Check for updates automatically** in the tray menu turns the timed checks off.
  - Mac: Wildware Feed must be somewhere your account can change (Applications is fine). If not, it
    opens the download for you to drag in.
  - Linux .deb / .rpm: your system asks for your password to install it.
- **Coming from "Agents"?** Wildware Feed is the same app, renamed in 1.0.2. Updating from inside
  the app replaces Agents in place: same settings, same sign-in. On Linux the package is still
  called `agents`, so apt and dnf upgrade it rather than adding a second app.
- To remove it: drag it to the Bin (Mac), use **Apps › Installed apps** (Windows), or
  `sudo apt remove agents` / `sudo dnf remove agents` (Linux).

## For whoever runs the releases

The `desktop-release` workflow builds all of these. Push a tag `v<version>` matching
`agents.version` in `gradle.properties` to publish a release; run it by hand from the Actions tab
for a test build (its files stay on the run as artifacts).

Signing turns on by itself when these repository secrets exist. Without them the builds are
unsigned and the release notes say so.

| Secret | What it is |
| --- | --- |
| `MACOS_CERT_P12_BASE64` | The **Developer ID Application** certificate and its private key, exported from Keychain Access as a `.p12`, then base64-encoded (`base64 -i cert.p12 \| pbcopy`). |
| `MACOS_CERT_PASSWORD` | The password chosen when exporting that `.p12`. |
| `APPLE_TEAM_ID` | The 10-character Team ID from developer.apple.com › Membership. |
| `ASC_KEY_ID` | An App Store Connect API key's Key ID (App Store Connect › Users and Access › Integrations › Team Keys, role Developer). |
| `ASC_ISSUER_ID` | The Issuer ID shown above that key list. |
| `ASC_KEY_P8_BASE64` | That key's `AuthKey_<id>.p8` file, base64-encoded. |
| `WINDOWS_CERT_PFX_BASE64` | Optional: an Authenticode code-signing certificate as a `.pfx`, base64-encoded. |
| `WINDOWS_CERT_PASSWORD` | Optional: its password. |

The Mac needs the first three to sign, and all six to notarise. Notarising is what stops the
"could not verify" message for good.
