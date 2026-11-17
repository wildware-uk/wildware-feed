# Agents for iPhone

A native iPhone and iPad app for the [Agent Dashboard](https://github.com/wildware-uk/agent-dashboard).
The screens you use most are native; the rest of the dashboard runs in a web view, exactly as in the
browser. Around it, the app adds what only a native app can do:

- **Inbox.** Every question waiting on you, the one closing soonest first, with how long each has left.
  Swipe right for Yes and left for No; tap one to read it all and answer with its own buttons or a
  text box. Answering needs Face ID or your passcode. Forms and multi-picks open in the dashboard.
  A badge shows how many are waiting; it refreshes when the app opens and when an alert arrives.
- **Composer bar.** Along the bottom: type, hold the mic to talk, add photos, send. A chip says where it
  goes: the project on screen (else the last one you used), an agent's question you tapped Reply on,
  or the page's own text box you were using. Tap the chip to change it. The bar steps aside while you
  type in the page itself.
- **Nothing lost offline.** Everything you send (bar messages and photos, lock screen voice notes, Siri
  tickets and messages, the share sheet, replies and answers from alerts) goes through an outbox kept
  on the phone. With no signal it says "Waiting to send" and sends itself when the network comes back,
  when you open the app, or in the background. If the dashboard refuses something for good, you're
  told, and can try again or delete it.
- **iPad.** The Inbox sits in a sidebar beside the dashboard, in any orientation.
- **Answer from the lock screen.** Question alerts carry their own buttons (Yes/No, each option, or a
  reply field). Answering needs Face ID or your passcode.
- **Better alerts.** Apple push, with questions marked time-sensitive so they get through Focus.
  Replies to agents straight from message alerts.
- **Formatted alerts.** Press and hold an alert to read the agent's message as written: headings, bold, lists, quotes, code and tappable links.
- **Widgets.** Home screen and lock screen: who's online, what's in progress, what's waiting. The
  medium widget answers simple questions with a tap.
- **Project widget.** Pick one project: its latest update and the questions it's waiting on, in small,
  medium and large, on the lock screen, and big and readable in StandBy (phone on its side, charging).
- **Focus filters.** Choose which projects can still alert you during a Focus. The rest stay quiet
  until the Focus ends.
- **Spotlight.** Search finds your projects and their recent updates. Tap one to open it.
- **Live Activity.** A lock screen and Dynamic Island bar that the server keeps current, with
  Yes/No buttons for yes-or-no questions. The server can start it when a question arrives.
- **Share sheet.** Share a screenshot, photo, link or text from any app into a project's feed.
- **Voice notes.** Hold the bar's mic to talk. The phone writes down what you said (Apple's on-device
  speech model) and puts the words in the bar, or in the page's text box if that's where it goes, for
  you to check and send. A lock screen, Control Center and Action button control records without
  unlocking and posts straight away, with a Stop button on the lock screen.
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
| `iosApp/App` | The SwiftUI app: Inbox, composer bar, web view, sign-in linking, push, Live Activity control, Siri. |
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
