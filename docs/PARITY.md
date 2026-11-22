# Web app vs native app: feature inventory

First audit 2026-11-18 against app 3347c01. **Refreshed 2026-11-18 against app master e450b05**, after the day's merge train; each row now has a Status. Rows 11, 12, 19, 31 and 36 marked Done after #53-#64 landed.

- **Web** = old Svelte app, `agent-dashboard` repo at 5887479. Refs are relative to `src/`.
- **App** = `wildware-feed` at origin/master e450b05 (first audit: 3347c01). `app/…` means `composeApp/src/commonMain/kotlin/dev/wildware/agents/app/…`; `shared/…` means `shared/src/commonMain/kotlin/dev/wildware/agents/…`.
- **Phone** is the layout below 700 dp (`app/feed/FeedScreen.kt:84`). **Desktop** is 700 dp and up. The side thread pane appears from 1100 dp.
- **Status** column: **Done** = in the app on phone and desktop. **Partial** = some of it is there; the Gap column says what is missing. **Open** = not started. **Dropped** = the owner decided against it. **App only** = the web never had it. **N/A** = neither has it.
- Words in the Phone and Desktop columns: **Yes** = done and wired. **Partial** = some of it is there. **Shown only** = it displays but you can't change it. **Dead** = the control is drawn but does nothing. **No** = missing. **App only** = the web never had it.
- Priority: **P0** = the owner relies on it daily. **P1** = matters, used often. **P2** = nice to have, or rare.

## The two direct questions

*Both answered by the work since: thread questions draw under their reply, the phone has a "Waiting on you" entry with an amber count, and question cards show the agent and project (rows 1-6, all Done). Kept for the record.*

### Where did "Waiting on you" go on the phone?

It is still in the code, but it is broken where it counts.

- The phone feed puts a "Waiting on you" section at the top (`app/engine/Mapping.kt:45-46`), and there is a "Waiting" filter chip (`app/feed/Panels.kt` FeedHeader).
- **Root cause: questions asked inside a thread are never drawn.** The engine keeps a question that has a `messageId` out of the top section on purpose, so it can be drawn under its message (`shared/Dashboard.kt:120,531`, `ThreadLine.questions` at `:29`). The app's mapper never reads `ThreadLine.questions` (`app/engine/Mapping.kt` `replies()`), so the owner can't see these questions at all. The web draws them under the message (`web/Thread.svelte:534-553`).
- **Live data shows this is happening now.** The live database (read-only check) has exactly 1 pending request. It is a `buttons` question asked inside a thread. So the app's "Waiting on you" shows nothing. Meanwhile the desktop sidebar badge and the tray still count 1, and pressing the sidebar row filters down to an empty feed. Historically 52 of 340 requests were asked in threads.
- **The phone has no Waiting on you entry or count.** The drawer has Projects, Agents and Sign out only (`app/feed/Overlays.kt` ProjectsDrawer). The top bar badge counts unread comments, not questions. The bell does nothing (`FeedScreen.kt:313`, no onClick). Per-project waiting counts exist in the data (`Project.waiting`) but are only read aloud by the screen reader. They never show on screen, on phone or desktop.
- **On a project, only that project's questions show.** This matches the web. But the desktop "Waiting on you" row shows the count for every project, then filters only the project on screen (`Panels.kt` NavRow "Waiting on you"), so the count and the list disagree.
- **A question card shows neither the agent's name nor the project** (`app/feed/Cards.kt` QuestionCard header: avatar, "WAITING ON YOU", time left). On All projects you can't tell who is asking, or about what. The web shows the project name on All projects (`web/RequestCard.svelte:311-404`).

### How does the web order "high priority" questions?

It doesn't, beyond oldest first.

- Requests have **no priority or urgency field** (`web/types.ts:314-352`). The settings panel says so itself: "a question is a blocked agent and has neither" (`web/NotifyToggle.svelte:192-193`).
- **Order is the server's: oldest first by `seq`**, so the agent that has waited longest is on top. Expired requests are dropped and the list is capped at 200 (`domain/requests.ts:585-598`, `db/approvals.ts:159-165`). The browser never re-sorts (`web/requests.svelte.ts:202-210`).
- **Expiry is display only.** "expires in 2h 10m" is worked out when the card draws and does not count down (`web/days.ts:91-102`).
- **"High" priority exists only on updates.** It shows as a red "High" chip and does not change the order (`web/UpdateCard.svelte:250-262`).
- The app keeps the server's order too (`shared/FeedReaders.kt:554`), so ordering is at parity. The watch Inbox sorts soonest-expiry first instead (`shared/Inbox.kt:28-35`). If the owner wants urgency ordering, that is a new feature, not a port.

## Inventory

| # | Feature | Status | Web (what it does, refs) | Phone | Desktop | Gap / notes | Pri |
|---|---|---|---|---|---|---|---|
| **Waiting on you / requests** |||||||
| 1 | Waiting on you section | Done | Sticky amber heading at top of feed, "(N)" when >1; all requests on All projects, own on a project page (`web/Timeline.svelte:805-849`, `Shell.svelte:430-442`) | Yes: Sticky amber heading with "(N)" | Yes | — | P0 |
| 2 | Questions asked inside a thread | Done | Drawn under their message in the thread (`web/Thread.svelte:534-553`) | Yes: Drawn under the reply they were asked under, inline and in the thread | Yes | — | P0 |
| 3 | Waiting count in nav | Done | Amber per-project badge + total on "All projects" (`web/Sidebar.svelte:91-110,157`) | Yes: Amber count in the phone top bar and drawer; per-project badges on both | Yes | — | P0 |
| 4 | "Waiting on you" view across all projects | Done | All projects page shows every request | Yes: "Waiting on you" row in the drawer and sidebar, across projects | Yes | — | P0 |
| 5 | Request ordering | Done | Oldest first by seq, expired dropped (`domain/requests.ts:585-598`) | Yes (server order) | Yes | Parity. No priority exists to sort by | P1 |
| 6 | Agent name + project on question card | Done | Avatar, agent, project (on All), unread, expiry (`RequestCard.svelte:311-404`) | Yes: Agent name on the card; project on All projects | Yes | — | P0 |
| 7 | Time left / expiry | Done | "expires in 2h 10m", static (`days.ts:91-102`) | Yes "N h left", refreshed ~20 s (`Mapping.kt` expiresIn) | Yes | App slightly better (re-maps on engine tick) | — |
| 8 | Kind `confirm` | Done | Approve (true) / Reject (false) (`RequestCard.svelte:571-587`) | Yes as "Yes"/"No" buttons + swipe (`Mapping.kt` Confirm) | Yes, plus Y/N keys | Labels differ (Yes/No vs Approve/Reject) | P2 |
| 9 | Kind `buttons` | Done | One button per option (`RequestCard.svelte:603-625`) | Yes, swipe right = first, left = last | Yes, number keys 1-5 | — | — |
| 10 | Kind `choice` | Done | Radio list, pre-picked from `config.default` (`:508-533`) | Yes: opens on the agent's default, picked and ready to send | Yes | — | P2 |
| 11 | Kind `multi_choice` | Done | Checkboxes, enforce `config.min`/`max`, "Choose between X and Y" (`:535-564`) | Yes: checkboxes; picks bounded by `config.min`/`max`; a "Choose between 2 and 3" style hint (#57) | Yes | — | P1 |
| 12 | Kind `text` | Done | Input/textarea (`multiline`), placeholder, maxlength, default, min chars (`:469-506`) | Yes: multiline, placeholder, default, min/max length, with a problem line (#60) | Yes | — | P1 |
| 13 | Kind `form` | Done | Labelled field + agent's own action buttons, sends `{action,text}` (`:588-602`) | Yes: Form answers (PR #12) | Yes | — | P0 |
| 14 | number / date kinds | N/A | Don't exist | — | — | Not a gap | — |
| 15 | Picture options | Done | Thumbnail on each option; excluded from card grid (`:298-305`) | Yes: PictureGrid, hold to look closer (`PictureGrid.kt`) | Yes | App richer | — |
| 16 | Dismiss | Done | "Dismiss" → agent told cancelled | Yes "Not now" / swipe left | Yes, N key | — | — |
| 17 | Answer error | Done | Alert, keeps typed text (`:566-568`) | Yes: the card says "Couldn't send your answer", why, and what was said, puts typed words back in the box, with Try again / Drop it | Yes | — | P2 |
| 18 | Offline answer | Done | — | Yes: outbox, "Answer waiting to send" | Yes | App only | — |
| 19 | Discussion thread on a request | Done | Reply/react/acks under the controls (`RequestCard.svelte:686-705`) | Yes: Discuss opens the question's own thread (#53) | Yes: inline under the controls (#53) | — | P1 |
| 20 | Reactions / tags / fields / pin on request | Done | Same as other cards | Yes: card menu (Pin, Tags…, Details…, react), tags, "Details (N)", reactions and a pin mark on the question | Yes | — | P2 |
| 21 | "edited" marker on request | Done | Shown when agent rewrote it (`:386-399`) | Yes: "edited" by the agent's name; read out with the exact time | Yes | — | P2 |
| 22 | Collapse request | Done | Fold to one line | Yes: the fold arrow keeps its header, question and one line; answers wait until it is opened; remembered with other folds | Yes | — | P2 |
| 23 | Answer from notification | Done | Web push SW: confirm/buttons/choice first two (`service-worker.ts:81-140`) | Yes, iOS: per-kind actions incl. text input, Face ID (`iosApp/Common/Notifications.swift`) | Partial: desktop notice opens card, no answer buttons (`desktop/NotificationRules.kt`) | iOS richer | — |
| 24 | Answer from widget / Live Activity / Watch / Siri | App only | — | App only: StatusWidget answer buttons, Live Activity Yes/No, Watch Inbox, "Answer My Agents" intent | — | App only | — |
| **Feed / timeline** |||||||
| 25 | Feed order | Done | Newest activity first (created, latest reply, statusAt) (`web/activity.ts:44-63`) | Yes (`shared/Dashboard.kt:520-523`) | Yes | Parity | — |
| 26 | Pinned section | Done | Pinned cards above day groups, newest created first (`Timeline.svelte:851-868`) | Yes (`Mapping.kt:47`) | Yes | Shown only; can't pin (row 44) | — |
| 27 | Day separators | Done | Sticky "Today/Yesterday/Monday 3 August" (`days.ts:27-81`) | Yes: sticky; weekday then "3 August", with the year when it isn't this year | Yes | — | P2 |
| 28 | Card time label | Done | Relative "5m ago", "updated …", tooltip with exact times (`days.ts:119-155`) | Yes: "5m ago", "updated 5m ago"; exact times read out | Yes, plus the exact times on hover | — | P2 |
| 29 | "N new updates" pill | Done | Holds live arrivals while scrolled down (`Timeline.svelte:686-707`) | Yes: away from the top, new updates wait; the top pill says "3 new updates" and takes you up to them | Yes | — | P2 |
| 30 | Back to top | Done | Round ↑ after one screen | Yes pill + status-bar tap | Yes pill + Home key | — | — |
| 31 | Collapse a card / fold memory | Done | Chevron or header click; remembered (500) (`CardActions.svelte:89-113`, `collapse.svelte.ts`) | Yes: chevron folds a card; remembered (500) across restarts (#62) | Yes | — | P1 |
| 32 | Long body clamp | Done | 420 px with fade, "Show the whole update" (`Clamp.svelte`) | Yes: Folds at 420 dp with "Show more" (`app/feed/Clamp.kt`) | Yes | — | P1 |
| 33 | Load older | Done | Button, pages of 50 | Yes, auto at end | Yes, auto | — | — |
| 34 | New-card / bump animation | Done | Enter animation, FLIP slide, bump ring (`bumps.svelte.ts`) | Yes: new cards fade in, moved cards slide (animateItem); a card that moves on wears a fading accent ring for 1.4 s | Yes | Off with reduced motion | P2 |
| 35 | Empty feed | Done | "Nothing here yet…" | Yes, loading vs empty vs filtered (`FeedScreen.kt` EmptyFeed) | Yes | — | — |
| 36 | Deep link / focus to card | Done | `?focus=id`, ring 4 s, loads up to 5 pages, "Could not find…" (`Timeline.svelte:159-310,753-763`) | Yes: a loaded card is scrolled to, ringed 4 s and unfolded; one not loaded opens on its own, read by itself with its thread (`GET /api/threads/{id}`), never paging the feed; or it says it can't (#64) | Yes | — | P1 |
| 37 | Pull to refresh | Done | — | Yes | F5 / Cmd-R | App only | — |
| 38 | Connection status | Done | Live/Reconnecting dot in header | Yes in feed header (`Panels.kt` LinkBadge) | Yes | — | — |
| 39 | Offscreen skipping (perf) | Done | `content-visibility:auto` (`app.css:441-457`) | LazyColumn (inherent) | LazyColumn; PR #5 memoises | — | — |
| **Card types and their controls** |||||||
| 40 | Update card basics | Done | Level bar + badge, title, markdown body (`UpdateCard.svelte`) | Yes: level chip, title, markdown | Yes | — | — |
| 41 | "High" priority chip on updates | Done | Red "High" when priority high (`UpdateCard.svelte:250-262`) | Yes: Red "High" chip | Yes | — | P1 |
| 42 | "edited" marker on updates/messages | Done | "edited" + tooltip | Yes: "edited" after the time on updates, posts and replies; read out with the exact time | Yes | No hover tooltip yet (see row 28) | P2 |
| 43 | Work status chip | Done | To do / In progress / Blocked / Done / Cancelled (`status.ts`) | Yes: Status chip, changeable (PR #18) | Yes | — | — |
| 44 | Mark as work / change status | Done | "Mark as work" menu of 5 states, "Not work" (`StatusChip.svelte:51-116`) | Yes: Mark as work and change status from the card menu (PR #18) | Yes | — | P0 |
| 45 | Pin / unpin card | Done | Pin button on every card (`UpdateActions.svelte`, `CardActions.svelte`) | Yes: Menu Pin / Unpin | Yes | — | P1 |
| 46 | Tags on cards | Done | Chips on every kind, project order, click to remove (`TagChips.svelte`) | Yes: Shown on cards | Yes | — | P1 |
| 47 | Tag picker | Done | Toggle, create (≤32 chars) with colour, recolour (`TagPicker.svelte`) | Yes: Tag picker from the card menu | Yes | — | P1 |
| 48 | Card fields ("Details") | Done | Expand list, links, add/edit/remove (`CardFields.svelte`) | Yes: "Details (N)" under the card | Yes | — | P1 |
| 49 | Delete update | Done | Inline "Delete this update?" confirm (`UpdateActions.svelte`) | Yes: Menu Delete, asks "Delete this update?" | Yes | — | P1 |
| 50 | Delete own post / message | Done | Confirm, "…and its N replies?" (`PostCard.svelte:253-265`, `Thread.svelte:555-603`) | Yes: Delete asks first, "…and its N replies?" | Yes | — | P1 |
| 51 | Share link (public) | Dropped | Mint link, Copy, Stop sharing, "Public · N views" (`UpdateActions.svelte:57-198`) | **No** ("Copy link" copies `agents://item/<id>`, `Overlays.kt`) | **No** | Dropped by the owner's decision: links stay private to the dashboard | P1 |
| 52 | Post card: mine styling | Done | Accent left border | Yes: the owner's posts have an accent rail down the left edge | Yes | — | P2 |
| 53 | "Not delivered to any agent" | Done | Red warning after 15 s with no ack/delivery (`acks.ts:66-93`) | Yes: Red warning after 15 s (PR #37) | Yes | — | P1 |
| 54 | Embeds (agent "looks") | Done | Blocks rendered with brand mark/palette (`Embed.svelte`, `embeds.ts`) | Yes: embeds show on cards in the look of where they came from (`Embeds.kt`) | Yes | — | P1 |
| 55 | Apps (MCP Apps iframe) | Dropped | Sandboxed app with live data/theme/state/tools (`AppFrame.svelte`, `appbridge.ts`) | Link instead: a card with an app shows its name; one with its own page opens in the browser, one the dashboard hosts says it can't be shown yet | Same | Dropped for now by the owner's decision: no frames in the app | P2 |
| 56 | Copy text | Done | Only code-block copy | Yes menu "Copy text" | Yes | App only | — |
| 57 | Text selection | Done | Browser native | Yes (`SelectableText.kt`) | Yes | — | — |
| **Threads and replies** |||||||
| 58 | Thread under every card | Done | Inline, newest first (`Thread.svelte:310-326`) | Yes: Phone: full-screen thread. Desktop: inline under the card. Newest first in PR #50 | Yes | — | — |
| 59 | Reply to a card | Done | "Reply" opens box; Cmd/Ctrl+Enter | Yes (thread composer) | Yes | Update with 0 replies has no thread button; reach via menu "Reply" only | P1 |
| 60 | Reply to one message ("answering X") | Done | Sets `answers`; line shows "answering {name}" (`Thread.svelte:440-453,523-531`) | Yes: A line says which line it answers | Yes | — | P2 |
| 61 | Optimistic send ("Sending…") | Done | Shows at once, 60 % opacity (`outgoing.ts`) | Yes: Shows at once, faded, "Sending…" | Yes | — | P0 |
| 62 | Unread divider / per-card unread | Done | Badge per card; mark read when viewed 700 ms (`unread.svelte.ts`, `visible.ts`) | Yes: "N new" line; marked read after 1.5 s on screen | Yes | — | P2 |
| 63 | Attachments in replies | Done | Paste/drop/Add file | Yes (paperclip, paste, drop) | Yes | — | — |
| 64 | Thread "More thread actions" | Done | — | Yes: the "…" at the top of a thread opens the card's own menu (pin, tags, details, work state, delete) | No thread pane: threads sit under their cards, each with its own menu | — | P2 |
| **Acks / receipts** |||||||
| 65 | "is thinking…" | Done | Sweeping bar, online + <10 min (`Ack.svelte`) | Yes on posts (`Content.kt` AckLine) | Yes | Only on post cards, not on replies in thread | P1 |
| 66 | "has read this" | Done | Tick + names | Yes on posts | Yes | Not on replies | P1 |
| 67 | "marked this done" | Done | Bold tick | Yes: "scout marked this done" with a tick in the success colour, apart from "has read this" | Yes | — | P2 |
| 68 | "delivered to X" | Done | Paper-plane per agent (`Ack.svelte:200-220`) | Yes: "delivered to lumen" with a paper plane, while that agent has said nothing yet | Yes | — | P2 |
| **Reactions** |||||||
| 69 | Reaction chips | Done | Grouped, count, accent if mine | Shown | Shown | — | — |
| 70 | Toggle by tapping chip | Done | Tap toggles yours (`Reactions.svelte:78-90`) | Yes: Tap toggles yours | Yes | — | P1 |
| 71 | "+" add reaction | Done | Quick picks 👀 ✅ 👍 👎 🎉 🤔 | Yes: "+" opens the picker | Yes | — | P1 |
| 72 | Reactions on thread messages | Done | Yes | Yes: On replies too | Yes | — | P2 |
| **Composer** |||||||
| 73 | Post to a project | Done | Textarea, Cmd/Ctrl+Enter, media-only OK | Yes (shown only when a project is open) | Yes | — | — |
| 74 | Post from All projects | Done | "Choose a project" select (`Composer.svelte:170-189`) | Yes: the message box shows on All projects, saying which project it posts to, and can change it; it remembers where you last worked | Yes | — | P1 |
| 75 | Attachments | Done | Paste, drop, Add file, progress bar, max 24 (`Attachments.svelte`, `uploads.svelte.ts`) | Yes; 25 MB cap; no progress bar | Yes | No upload progress | P2 |
| 76 | Tags at post time | Done | Pick tags before posting | Yes: "#" by the message box picks tags (or makes one); they show above the box and go on the post once it is up | Yes | — | P2 |
| 77 | Voice dictation | Done | — | Yes hold-to-talk (`Voice.kt`) | Yes if available | App only | — |
| 78 | Share into the app | Done | — | Yes, iOS share extension | Desktop drop | App only | — |
| 79 | @mention | Done | Plain text only, server routes | Yes: typing @ in any message box lists the agents, online first; letters filter, a tap picks (`Mentions.kt`) | Yes: same, with arrows, Enter or Tab, and an "@" button | App richer: the website had no list | P2 |
| **Media** |||||||
| 80 | Image grid | Done | 1/2/3 cols by count, aspect clamp (`media.ts:29-130`) | Yes (`Content.kt` MediaGrid) | Yes | — | — |
| 81 | Image viewer | Done | Lightbox: arrows, Home/End, "Original" link | Yes: pager, pinch zoom, prev/next (`Overlays.kt` ImageViewer) | Yes + ←/→/Esc | No "Original" or download | P2 |
| 82 | Video | Done | media-chrome player, frame step, fullscreen (`VideoPlayer.svelte`) | Yes: Video (PR #21) | Yes | — | P0 |
| 83 | Sounds | Done | `<audio>`, one at a time (`MediaSounds.svelte`) | Yes: Sounds (PR #21) | Yes | — | P1 |
| 84 | Files | Done | Name, size, download (`MediaFiles.svelte`) | Yes: Files (PR #21) | Yes | — | P1 |
| 85 | Processing / failed tiles | Done | "Processing…", "Media unavailable" (`MediaTile.svelte`) | Yes: a picture or video still being made says "Processing…" in its place; one that failed says "Media unavailable" | Yes | — | P2 |
| **Markdown** |||||||
| 86 | Markdown body | Done | markdown-it, linkify, line breaks, tables scroll | Yes (`Content.kt` MarkdownBody) | Yes | Tables/link handling not verified | P2 |
| 87 | Code blocks | Done | Highlight, language label, Copy | Yes (`Content.kt` CodeBlock) | Yes | — | — |
| **Filters and search** |||||||
| 88 | Work-status filter chips | Done | Everything / Open work / To do / In progress / Blocked / Done, remembered (`filters.svelte.ts:23-102`) | Yes: Work-status chips (PR #18) | Yes | — | P1 |
| 89 | Kind filters (Waiting/Updates/Errors/From you) | Done | — | Yes (From you hidden on phone) | Yes, with counts | App only | — |
| 90 | Search | Done | None | Yes command bar (`CommandBar.kt`, `Search.kt`) | Yes, Cmd/Ctrl+K | App only | — |
| **Projects** |||||||
| 91 | Project list + switch | Done | Sidebar/drawer, server order (pinned first) | Yes drawer | Yes sidebar | — | — |
| 92 | Pinned project glyph / pin project | Done | Pin icon; Pin/Unpin in menu (`ProjectActions.svelte`) | Yes: "Pin project" / "Unpin project" in the project's settings; a pin by its name in the drawer | Yes: same, and the pin takes the hash's place in the sidebar | — | P2 |
| 93 | Reorder projects | App only | — | Yes, hold to drag (device-local) | Event exists; no drag in sidebar | App only | — |
| 94 | Archived section | Done | "Archived (N)" collapsed | Yes (`Panels.kt` ArchivedProjects) | Yes | — | — |
| 95 | New project | Done | Inline form name + description (`NewProject.svelte`) | Yes: "+ New project" in the drawer and the command bar | Yes: in the sidebar and the command bar | — | P1 |
| 96 | Rename / describe project | Done | Inline form (`ProjectActions.svelte:364-397`) | Yes: the gear by the project's name opens its settings | Yes | — | P1 |
| 97 | Archive / unarchive | Done | Menu item | Yes: in the project's settings | Yes | — | P1 |
| 98 | Project colours (theme) | Done | Background + accent pickers, AA ink (`theme.ts`) | Yes: page and accent colours picked from a set, applied with readable text | Yes | No free hex entry (the website takes one) | P1 |
| 99 | Project logo / logo replaces name | Done | Pick from 10 images, checkbox (`ProjectActions.svelte:283-336`, `Shell.svelte:553-581`) | Yes: the logo sits by the name above the feed, or stands in for it; picked in the project's settings from up to 10 pictures posted there, with "Use the logo instead of the name" and "Remove logo" | Yes | — | P2 |
| 100 | Board columns editor | Dropped | Edit 1-6 columns (board view itself gone) | — | — | Dropped by the owner's decision: the board is gone | P2 |
| 101 | Project label on cards in All projects | Done | Project name on cards when on All | Yes: "# project" on All projects, phone too | Yes | — | P0 |
| **Agents / presence** |||||||
| 102 | Live agents list | Done | Name, sessions, model, host, cwd, last heartbeat (`RightRail.svelte:78-191`) | Yes: Model, host, folder, sessions, last seen (PR #39) | Yes | — | P1 |
| 103 | Rename agent | Done | Inline rename in rail (`RightRail.svelte:45-68`) | Yes: tap an agent in the drawer to open it and rename it | Yes: click an agent in the sidebar or the rail | — | P2 |
| 104 | Online avatars in header | Done | — | — | Yes stack + "N online" | App only | — |
| 105 | Agent row tap | Done | — | Yes: opens the agent: what its session says, and its name to change | Yes: same, from the sidebar and the rail | Opens the agent rather than filtering to it | P2 |
| **Notifications** |||||||
| 106 | Notification bell + list | Done | Unseen count, list, Mark all read, click to go (`NotificationBell.svelte`) | Yes: Bell lists notifications (PR #15) | Yes | — | P0 |
| 107 | Mark notifications seen | Done | `/api/notifications/seen` | Yes: Opening a notification marks it seen; "Mark all read" clears them | Yes | — | P1 |
| 108 | Push alerts | Done | Web Push "Notify me" | Yes APNs: requests, replies, messages; rich content ext | Desktop notices by same rules (`NotificationRules.kt`) + Do not disturb | Parity or better | — |
| 109 | Per-device alert prefs | Done | Types (Questions/Replies/Comments/Updates), levels, priority (`NotifyToggle.svelte:41-202`) | Yes: Per-device alert settings (PR #34, server migration 047) | Yes | — | P1 |
| 110 | Mark all read | Done | Bell only | Yes: Header button, bell and command bar | Yes | — | — |
| 122 | Notifications clear once dealt with | Done | — | Yes: read, answered, allowed/denied, withdrawn or deleted on any device clears the lock screen (background push, plus catch-up on open) and the bell | Yes: Android shade and Linux notices clear; Mac/Windows tray notices can't be taken down | Better | — |
| **Settings / account** |||||||
| 111 | Theme toggle | Done | Header Dark/Light, remembered | Command bar "Switch light or dark" only | Sidebar moon + command bar | No visible toggle on phone | P2 |
| 112 | Sign in / out | Done | Password page; logout confirm page | Yes sign-in screen; Sign out in drawer, no confirm | Yes, sidebar icon, no confirm | Add confirm | P2 |
| 113 | Account avatar button | Done | — | Yes: Opens "Alerts on this device" | Yes | — | P2 |
| 114 | Devices list / revoke | Done | No web UI (API only) | Yes: "Devices" in the account sheet (and the command bar) lists every signed-in device, this one first, with when each was last used; Revoke asks first, and revoking this device signs it out | Yes | App only: the website never had it | P2 |
| **Keyboard (desktop)** |||||||
| 115 | Escape closes drawers/menus/lightbox | Done | Yes | Drawer/thread/viewer Esc | Menus, viewer, command bar | Parity | — |
| 116 | Cmd/Ctrl+Enter sends | Done | Composer + reply | — | Yes | — | — |
| 117 | Cmd/Ctrl+K, F5, Home, Y/N/1-5 on questions | Done | — | — | Yes | App only | — |
| 123 | Back and Forward: mouse side buttons, Alt+←/→ (⌘[ ⌘] on a Mac), the browser's own Back (each channel and thread is a history entry); Android system back uses the same history | Done | — | Android system back | Yes; web through the tab's history | App only | — |
| 124 | Keyboard shortcuts sheet (? or Ctrl/⌘+/, also in the command bar): Ctrl/⌘+F or / search, Alt+↑/↓ channels (with Shift: unread ones), Ctrl/⌘+1–9, G then H/W/S, J/K cards, Enter or O open, R reply, N new post, Esc close; single keys never fire while typing | Done | — | — | Yes | App only | — |
| **Platform extras (app only)** |||||||
| 118 | iOS widgets, Live Activity, Watch app + complication, Siri intents, Spotlight, voice notes, Focus filter | App only | — | Yes (`iosApp/**`) | — | App only | — |
| 119 | Desktop tray: waiting count, Mark all read, keep running, start at login, DnD, updates | App only | — | — | Yes; tray updates in PR #6 | App only | — |
| 120 | Offline outbox with retry/drop; a retried send is stored once (send key) | App only | — | Yes | Yes | App only | — |
| 121 | Live after sleep (revive) | Done | Wake repair after 30 s hidden (`stream.ts:728-735`) | Yes: The stream revives after sleep | Yes | — | P0 |

## What is left

Nothing. Every row is Done, App only or N/A, except three the owner dropped: rows 51 (share links), 55 (app frames: a link instead) and 100 (board columns).

## Method and limits

- The refresh checked each row against app master e450b05 by reading the code and the tests for it, not by running the app.

- Web side: read the components, specs and e2e tests, with a helper's full table cross-checked against the code for the key claims. App side: I read FeedScreen, FeedModel, Interaction, Panels, Cards, Content, Overlays, CommandBar, Search, Mapping, DashboardSession, App and NotificationRules in full or close to it, and grepped the engine and iOS.
- Not verified by running: markdown tables and link taps (row 86). I also didn't check whether the server applies per-device push prefs to APNs (row 109).
- "Live data" in row 2 came from one read-only query of the live database: a count of pending requests by whether they have a `message_id`. Nothing was changed.
