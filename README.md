# Agents

The native app for the [Agent Dashboard](https://github.com/wildware-uk/agent-dashboard) on iPhone, iPad, Apple Watch, Mac, Windows and Linux. MIT licensed. Desktop installers are on the [Releases](https://github.com/wildware-uk/agent-dashboard-ios/releases) page; see [docs/INSTALL.md](docs/INSTALL.md).

## iPhone

A native iPhone and iPad app for the [Agent Dashboard](https://github.com/wildware-uk/agent-dashboard).
The app is the native Compose app from `composeApp/` (the same screens as the desktop app): sign in
with your dashboard password, then the live feed, threads, questions and a composer. Around it, the
iPhone app adds what only a native app can do:

- **Nothing lost offline.** Everything you send (what you write in the app, lock screen voice notes, Siri
  tickets and messages, the share sheet, replies and answers from alerts) goes through an outbox kept
  on the phone: one queue for all of them. With no signal it waits and sends itself when the network
  comes back, when you open the app, or in the background. The app's "Waiting to send" list shows all
  of it, wherever it came from; if the dashboard refuses something for good, you're told, and can try
  again or delete it there.
- **Share sheet.** Share a screenshot, photo, link or text from any app into a project's feed.
- **Voice notes.** Hold the composer's mic to talk; slide left to cancel. The phone writes down what
  you said (Apple's on-device speech model) and puts the words in the box for you to check and send.
  A lock screen, Control Center and Action button control records without unlocking and posts
  straight away, with a Stop button on the lock screen.
- **Apple Watch.** See what agents are waiting on and answer from your wrist: Yes/No, one button per
  option, or dictate a text answer. Dictate a message to a project, or reply in a question's thread.
  A watch face complication shows how many questions are waiting and opens the list.
- **Siri.** "Create a ticket in Agents", "What are my Agents doing", "Answer my Agents",
  "Message Garden Game in Agents", "Record a voice note in Agents", "What did Garden Game say in Agents".
  Siri asks for anything you didn't say.

## How it fits together

| Folder | What it is |
| --- | --- |
| `shared/` | Kotlin Multiplatform: the dashboard client, the data shapes, and the logic for notification buttons, the Inbox's order and time left, where the composer sends, spoken answers, Siri replies and project-name matching. Tested on the JVM. |
| `composeApp/` | The Compose Multiplatform screens. On iPhone the app links them as one `ComposeApp` framework that also exports `shared`. |
| `iosApp/App` | The SwiftUI shell around the Compose screens: Keychain sign-in store, deep links into the feed, push, Live Activity control, Siri. |
| `iosApp/Widgets` | Widgets and the Live Activity screens. |
| `iosApp/Share` | The share sheet. |
| `iosApp/Voice` | Voice notes: recording, transcription and sending, shared by the app, its controls and Siri. |
| `iosApp/NotificationService` | Picks each question's answer buttons as its alert arrives, and lists what numbered options mean. |
| `iosApp/NotificationContent` | Shows a held alert's markdown with its formatting, and that question's own answer buttons. |
| `iosApp/Common` | Swift shared by every target: stored login, the Live Activity shape, deep links. |
| `iosApp/Watch` | The Apple Watch app (watchOS 11+): the question list, answering, dictated messages. |
| `iosApp/WatchWidgets` | The watch face complication: how many questions are waiting. |
| `iosApp/WatchCommon` | Swift shared by the watch app and its complication. |

Apple only allows widgets, Live Activities, Siri and share sheets in Swift, so those parts are Swift
and call into the Kotlin layer for everything else.

**Signing in.** You sign in on the dashboard's normal login page inside the app. The app then
swaps that login for a key of its own (`POST /api/devices`), stored in the keychain where the
widgets, share sheet and Siri can read it. Revoke a phone with `DELETE /api/devices/<id>`.

**The watch.** The watch has no login page. Once the phone is linked, it hands the server address
and its key to the watch over WatchConnectivity, and again each time either side starts up. The
watch keeps them in its own keychain and uses the same key as the phone, so revoking the phone
signs the watch out too. Signing out on the phone signs the watch out. Until the key arrives, the
watch says "Open Agents on your iPhone".

**The feed engine.** `shared/` also holds what the cross-platform app runs on: `SignIn` (password to
device key), `Dashboard` (one live connection to `/api/stream`, and `StateFlow`s for each feed, the
questions waiting, who is online and the sidebar), and `OwnerActions` (posts, replies, answers,
reactions, deletes), which queue through `OutboxQueue` so nothing is lost without signal. It is a
port of the website's own stores (`src/web/stream.ts`, `timeline.svelte.ts` and friends), so it
resumes, re-reads and orders the feed the same way.

## Building

You need a Mac with Xcode, JDK 21 and [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
./gradlew :shared:assembleSharedDebugXCFramework
DEVELOPMENT_TEAM=<your team id> xcodegen generate --spec iosApp/project.yml
open iosApp/Agents.xcodeproj
```

The Agents scheme rebuilds the Kotlin framework before each build. The same framework carries the
watch's code, and the iPhone app carries the watch app, so building Agents builds both. The
AgentsWatch scheme builds the watch app on its own.

**Installing on the watch.** After installing a new build on the iPhone, open the Watch app on the
iPhone, find Agents under Available Apps and tap Install (or turn on automatic app install). Then
open Agents on the iPhone once so it sends the watch its key.

Kotlin tests run anywhere: `./gradlew :shared:jvmTest`.

CI (`.github/workflows/build.yml`) runs the Kotlin tests and builds the app, every extension and the
watch app for the simulator on a Mac runner.

## Setting up on the phone

- **Focus filter.** Settings → Focus → pick a Focus → Add Filter → Agents → choose the projects that may
  still alert you. Leave it empty to hear from every project. The phone tells the dashboard
  (`PUT /api/devices/current/alert-projects`) each time that Focus turns on or off.
- **Project widget.** Hold the home screen → Edit → Add Widget → Agents → Project. Then hold the widget →
  Edit Widget → choose the project. For StandBy, add it while the phone is on its side and charging.
- **Siri.** "What did *project* say in Agents?" works once the phone is linked; nothing to set up.
- **Spotlight.** Fills itself each time the app opens.

## Turning on push

Push needs a paid Apple developer account.

1. In the Apple developer site, create an APNs key (Keys → +, tick Apple Push Notifications service).
   Download the `.p8` file once; note its key id and your team id.
2. Give the dashboard server these settings (see its `.env.example`):
   `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_KEY_PATH` (the `.p8` file) and `APNS_BUNDLE_ID=dev.wildware.agents`.
3. Restart the dashboard. Debug builds register for Apple's sandbox, release and TestFlight builds for production.

The app ids, the app group `group.dev.wildware.agents` and the push, time-sensitive and app group
capabilities are all set in `iosApp/project.yml`. Xcode's automatic signing creates them on first build.
