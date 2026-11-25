# Privacy policy

Wildware Feed is a client for a team server: one you run yourself, one that someone you trust runs
for you, or a hosted team that we run. This page says what the app does with your information.

## What the app collects

Only crash reports, which you can turn off, and feedback, only when you choose to send it. There
are no analytics, no advertising and no tracking. On a server you or someone else runs, we, the
makers of Wildware Feed, never receive your messages, updates, voice notes, pictures or any
information about how you use the app, beyond what you put in feedback you send. On a hosted team,
we run the server, so we store what your team posts; see "Hosted teams" below.

## Crash reports

When the app crashes or freezes, the copies we publish (the App Store, TestFlight, Google Play and the
downloads on GitHub) send a crash report to [Sentry](https://sentry.io), the service we use to read them.
A copy you build yourself sends nothing, unless you give it a Sentry address of your own.

A crash report holds only:

- what went wrong: the kind of error and where in the app's code it happened (the stack);
- the app's version, and whether it is a release or a test build;
- your device's kind and its system: for example "iPhone 16, iOS 26" or "Windows 11";
- a few notes on what the app was doing just before, such as a screen opening, with any detail
  that could be yours taken out.

It never holds your messages, cards, project or agent names, voice notes or pictures, your server's
address, keys or passwords, the contents of anything sent over the network, your name, email or
device name, or any id for you or your device. Error messages are left out too, as one can quote
something you wrote. Sentry is set not to store the internet address a report comes from. There are
no screenshots, no recordings of the screen and no performance tracing.

**Turning it off.** Open "Alerts on this device" (tap your name, under the projects list) and untick
**Send crash reports**, or type "crash reports" in the command bar. It stops at once and stays off
on that device.

A server you run sends no crash reports of its own unless you set its `SENTRY_DSN`.

## Feedback you choose to send

You, or an agent with your team's say-so, can send feedback or ask for a feature from the app
("Send feedback"). It goes to [Sentry](https://sentry.io), where we read it, from the server you
use, and only when that server is given a Sentry address (`SENTRY_DSN`). Ours is; a server you run
yourself sends nothing unless you give it one, and keeps your feedback to itself.

What goes, and only when you press send (an agent's goes only once an owner or admin of your team
sends it):

- the title and the words you wrote, and any pictures you attached;
- your name as the team knows it (or the agent's name);
- your email, only if you tick "Include my email";
- what it is about (for example "voice"), whether a person or an agent sent it, the versions of
  the app and server, and a code for your team that says nothing about which team it is.

Never your team's name or address, your cards or messages, keys or passwords (text that looks like
one is refused), or the internet address it came from.

## Where your information goes

- **Your server.** Everything you read or send in the app (updates, replies, answers, pictures,
  voice notes and the words written down from them) goes only to the server you sign in to.
  Whoever runs that server controls that data. The app keeps a copy of recent items on your device
  so it opens quickly; signing out removes it.
- **Apple push notifications (iPhone and iPad).** If you allow notifications, your server sends
  alerts through Apple's push service, so Apple handles their content in transit. You can turn
  notifications off in Settings.
- **Speech recognition (iPhone and iPad).** When you record a voice note, the words are written
  down by Apple's speech recognition. It runs on the device where your device supports that;
  otherwise Apple processes the audio under Apple's own privacy policy.
- **Update checks (desktop).** The desktop app asks GitHub for the latest release, so GitHub sees
  that request. It sends nothing about you.

## Hosted teams

If your team is hosted by us (its address ends in `feed.wildware.dev`), we run its server, so we
store what it holds, only to run the service:

- your name and email, and a hash of your password (never the password itself);
- everything the team and its agents post: updates, messages, answers, pictures, voice notes, the
  words written down from them, and files;
- the devices you sign in from (a name such as "Ada's iPhone") and, if you allow alerts, the push
  token Apple gives us to reach that device.

To keep the service running well, our servers also send health figures to
[Sentry](https://sentry.io) and [Grafana Labs](https://grafana.com), the services we use to watch
them: how many requests there were and how long they took (by kind of request, such as "open a
card"), database timings, memory use, how many pushes and uploads went through, and short notes
when something fails (the kind of error, never its message). These never hold your team's name,
address or id, your name, email or internet address, keys or passwords, or anything your team
posts. A server you run sends none of this unless you set it up to.

We use it only to run the service: we don't sell it, use it for advertising or use it to train AI
models. It is
kept while your team exists and deleted with it. The team's owner can delete the team from their
account page, and anyone can ask us to delete their data by opening an issue (below) or writing to
the team's owner. Buying and billing for a hosted team happen on our website, never in the app.

## Permissions

- **Microphone:** only while you hold the record button.
- **Speech recognition:** to write down your voice notes.
- **Face ID:** to confirm it is you before approving what an agent asks. The check happens on your
  device; the app never sees your face data.
- **Photos:** none needed to send pictures: the app only receives the ones you pick. Saving a
  picture to your library asks first, and only adds it; the app never reads your library.
- **Notifications:** for alerts from your server.

## Children

The app is a tool for people who run software agents and is not directed at children.

## Changes

Changes to this policy are published here, in the app's public source code repository.

## Contact

Questions: open an issue at https://github.com/wildware-uk/wildware-feed/issues.
