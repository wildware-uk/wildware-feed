# Sending a build to TestFlight

GitHub builds the iPhone, iPad and Watch app on a Mac in the cloud, signs it, and uploads it to
App Store Connect. It only runs when you start it, because Mac minutes are expensive.

## Cut a build

1. GitHub, **Actions**, **iOS release**, **Run workflow**.
2. Fill in the boxes, or leave them:
   - **Version**: empty uses `agents.version` in `gradle.properties` (the desktop apps use it too).
   - **What to Test**: optional text for testers.
   - **Upload to TestFlight**: untick it for a dry run. It still builds and signs, and keeps the
     `.ipa` as a download on the run for 7 days, but sends nothing to Apple.
3. Wait about 40 to 60 minutes. The build number is the run number (`12.1` is run 12, first try),
   so every run gets a new one.
4. Apple then processes it for 5 to 30 minutes, and it shows in TestFlight.

## One-time setup

**Create the app in App Store Connect.** Apps, **+**, New App, iOS, bundle id `dev.wildware.agents`.
The API can't do this; an upload fails without it.

**Make an Admin API key.** App Store Connect, Users and Access, Integrations, App Store Connect API,
Team Keys, **+**. Pick the **Admin** role: signing in the cloud needs it. Download the `.p8` file
(you only get one chance) and note the Key ID and the Issuer ID at the top of the page.

**Add four secrets** in GitHub: the repo's Settings, Secrets and variables, Actions.

| Secret | What it is |
| --- | --- |
| `ASC_KEY_ID` | The key's Key ID, e.g. `2X9R4HXF34` |
| `ASC_ISSUER_ID` | The Issuer ID, a long id with dashes |
| `ASC_KEY_P8_BASE64` | The `.p8` file as base64: `base64 -i AuthKey_2X9R4HXF34.p8 \| pbcopy` |
| `APPLE_TEAM_ID` | Your 10-character team id (developer.apple.com, Account, Membership details) |

The desktop release uses the same four to notarise the Mac app, so they may already be there.

No certificates or profiles go in the repo. Xcode makes what it needs with the key on each run, and
the run revokes its throwaway development certificate at the end.

## Capabilities each bundle id needs

Xcode turns these on by itself the first time. If a run says a capability or app group is missing,
tick them on developer.apple.com, Certificates, Identifiers and Profiles, Identifiers.

| Bundle id | Capabilities |
| --- | --- |
| `dev.wildware.agents` | Push Notifications, Time Sensitive Notifications, App Groups |
| `dev.wildware.agents.widgets` | App Groups |
| `dev.wildware.agents.share` | App Groups |
| `dev.wildware.agents.notifications` | App Groups |
| `dev.wildware.agents.notificationcontent` | App Groups |
| `dev.wildware.agents.watchkitapp` | App Groups |
| `dev.wildware.agents.watchkitapp.widgets` | App Groups |

Every App Groups one uses the group `group.dev.wildware.agents`. The shared keychain group needs no
capability.

## When it fails

The first red line in the run says what is wrong. The full Xcode logs are on the run as
`xcodebuild-logs`.

- **"The secret … is missing"** or **"refused the key (401)"**: a secret is missing or the three key
  secrets come from different keys. Fix them and run again. This fails in seconds, before the Mac
  starts.
- **"has no app for dev.wildware.agents"**: create the app in App Store Connect (above).
- **"403" or "Cloud signing permission error"**: the key isn't Admin. Make a new Admin key.
- **"No profiles for …" or "doesn't support the … capability"**: tick the capabilities above for
  that bundle id, then run again.
- **"Your team has no devices"**: development signing needs at least one registered iPhone and
  one Apple Watch. Register them on developer.apple.com, Devices.
- **"The build number has already been used"**: run the workflow again; the new run gets a new number.
- **"Maximum number of certificates"**: revoke old "Created via API" development certificates on
  developer.apple.com, Certificates.
- **Gradle runs out of memory, or a step times out**: run it again once. If it keeps happening, raise
  the step's `timeout-minutes` or `-Xmx` in `.github/workflows/ios-release.yml`.
- **What to Test didn't appear**: Apple took longer than 20 minutes to process the build. The build
  itself is fine; add the text by hand in TestFlight.
