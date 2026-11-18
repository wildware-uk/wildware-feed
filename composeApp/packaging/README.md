# Desktop installers

| System | File | Built by |
| --- | --- | --- |
| Mac (Apple silicon) | `.dmg` | `./gradlew :composeApp:packageDmg` on a Mac |
| Windows | `.msi` (per-user, no admin), portable `.zip` | `packageMsi`, `createDistributable` on Windows |
| Linux | `.deb`, `.rpm` | `packageDeb`, `packageRpm` (needs `rpmbuild`) on Linux |
| Linux | `.AppImage` | `packageAppImage`, then `linux/make-appimage.sh` |

Each installer can only be built on its own system. The `desktop-release` workflow builds all of
them and, on a `v<version>` tag, publishes them as a GitHub Release with install notes
(`docs/release-notes.sh`). The version lives in one place: `agents.version` in `gradle.properties`.

Icons: `icons/agents.icns`, `icons/agents.ico` and `icons/agents.png` (from the app icon PR). The
window uses `agents.png`. The tray (`desktop/TrayIcon.kt`) crops `agents.png` to its tile for large
sizes, and draws a bold radar for tray sizes, where the icon's fine rings vanish.

Signing, notarisation and the secrets they need: see `docs/INSTALL.md`, "For whoever runs the
releases". Unsigned builds still install; the notes give the one-time steps.

Only built for Apple silicon Macs (an Intel Mac needs a second job on an Intel runner) and x86_64 Linux.
