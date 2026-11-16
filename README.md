# Agents for iPhone

A native iPhone app for the [Agent Dashboard](https://github.com/wildware-uk/agent-dashboard).
The dashboard itself runs in a web view, exactly as in the browser. Around it, the app adds what
only a native app can do:

- **Answer from the lock screen.** Question alerts carry their own buttons (Yes/No, each option, or a
  reply field). Answering needs Face ID or your passcode.
- **Better alerts.** Apple push, with questions marked time-sensitive so they get through Focus.
  Replies to agents straight from message alerts.
- **Widgets.** Home screen and lock screen: who's online, what's in progress, what's waiting. The
  medium widget answers simple questions with a tap.
- **Live Activity.** A lock screen and Dynamic Island bar that the server keeps current, with
  Yes/No buttons for yes-or-no questions. The server can start it when a question arrives.
- **Share sheet.** Share a screenshot, photo, link or text from any app into a project's feed.
- **Siri.** "Create a ticket in Agents", "What are my Agents doing", "Answer my Agents",
  "Message Garden Game in Agents". Siri asks for anything you didn't say.

## How it fits together

| Folder | What it is |
| --- | --- |
| `shared/` | Kotlin Multiplatform: the dashboard client, the data shapes, and the logic for notification buttons, spoken answers, Siri replies and project-name matching. Tested on the JVM. |
| `iosApp/App` | The SwiftUI app: web view, sign-in linking, push, Live Activity control, Siri. |
| `iosApp/Widgets` | Widgets and the Live Activity screens. |
| `iosApp/Share` | The share sheet. |
| `iosApp/NotificationService` | Builds each question's answer buttons as its alert arrives. |
| `iosApp/Common` | Swift shared by every target: stored login, the Live Activity shape, deep links. |

Apple only allows widgets, Live Activities, Siri and share sheets in Swift, so those parts are Swift
and call into the Kotlin layer for everything else.

**Signing in.** You sign in on the dashboard's normal login page inside the app. The app then
swaps that login for a key of its own (`POST /api/devices`), stored in the keychain where the
widgets, share sheet and Siri can read it. Revoke a phone with `DELETE /api/devices/<id>`.

## Building

You need a Mac with Xcode, JDK 21 and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
./gradlew :shared:assembleSharedDebugXCFramework
DEVELOPMENT_TEAM=<your team id> xcodegen generate --spec iosApp/project.yml
open iosApp/Agents.xcodeproj
```

The Agents scheme rebuilds the Kotlin framework before each build.

Kotlin tests run anywhere: `./gradlew :shared:jvmTest`.

CI (`.github/workflows/build.yml`) runs the Kotlin tests and builds the app and every extension for
the simulator on a Mac runner.

## Turning on push

Push needs a paid Apple developer account.

1. In the Apple developer site, create an APNs key (Keys → +, tick Apple Push Notifications service).
   Download the `.p8` file once; note its key id and your team id.
2. Give the dashboard server these settings (see its `.env.example`):
   `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_KEY_PATH` (the `.p8` file) and `APNS_BUNDLE_ID=dev.wildware.agents`.
3. Restart the dashboard. Debug builds register for Apple's sandbox, release and TestFlight builds for production.

The app ids, the app group `group.dev.wildware.agents` and the push, time-sensitive and app group
capabilities are all set in `iosApp/project.yml`. Xcode's automatic signing creates them on first build.
