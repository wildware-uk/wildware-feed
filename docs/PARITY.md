# Web app vs native app: feature inventory

Audit date 2026-11-18. Read-only.

- **Web** = old Svelte app, `agent-dashboard` repo at 5887479. Refs are relative to `src/`.
- **App** = `wildware-feed` at origin/master 3347c01. `app/…` means `composeApp/src/commonMain/kotlin/dev/wildware/agents/app/…`; `shared/…` means `shared/src/commonMain/kotlin/dev/wildware/agents/…`.
- **Phone** is the layout below 700 dp (`app/feed/FeedScreen.kt:84`). **Desktop** is 700 dp and up. The side thread pane appears from 1100 dp.
- **In progress** means open PR #5 (inline threads on desktop, agents rail, instant replies, revive after sleep) or #6 (tray updates). Work on the mobile project label and on gestures is also under way but has no PR yet.
- Status words: **Yes** = done and wired. **Partial** = some of it is there. **Shown only** = it displays but you can't change it. **Dead** = the control is drawn but does nothing. **No** = missing. **App only** = the web never had it.
- Priority: **P0** = the owner relies on it daily. **P1** = matters, used often. **P2** = nice to have, or rare.

## The two direct questions

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

| # | Feature | Web (what it does, refs) | Phone | Desktop | Gap / notes | Pri |
|---|---|---|---|---|---|---|
| **Waiting on you / requests** ||||||
| 1 | Waiting on you section | Sticky amber heading at top of feed, "(N)" when >1; all requests on All projects, own on a project page (`web/Timeline.svelte:805-849`, `Shell.svelte:430-442`) | Partial: top section exists (`Mapping.kt:45`), not sticky, no count | Partial: same, not sticky, no count | Not sticky; no count in heading; thread questions missing (row 2) | P0 |
| 2 | Questions asked inside a thread | Drawn under their message in the thread (`web/Thread.svelte:534-553`) | **No**: `ThreadLine.questions` never mapped | **No** | **This is why Waiting on you looks empty today.** Map them into the thread and also list them in a global Waiting view | P0 |
| 3 | Waiting count in nav | Amber per-project badge + total on "All projects" (`web/Sidebar.svelte:91-110,157`) | **No** (drawer rows show unread only; top bar badge is unread) | Partial: one global "Waiting on you" row with amber count (`Panels.kt`); per-project shows unread, waiting only spoken | Show amber waiting badge per project, both layouts; add a phone entry point | P0 |
| 4 | "Waiting on you" view across all projects | All projects page shows every request | No dedicated view; only All projects feed + "Waiting" chip | Sidebar row toggles Waiting chip on *current* feed | Row count is global but filter is per-project: count and list disagree | P0 |
| 5 | Request ordering | Oldest first by seq, expired dropped (`domain/requests.ts:585-598`) | Yes (server order) | Yes | Parity. No priority exists to sort by | P1 |
| 6 | Agent name + project on question card | Avatar, agent, project (on All), unread, expiry (`RequestCard.svelte:311-404`) | **No**: avatar only, no name, no project | **No** | Add agent name and project label (ties to mobile project label work) | P0 |
| 7 | Time left / expiry | "expires in 2h 10m", static (`days.ts:91-102`) | Yes "N h left", refreshed ~20 s (`Mapping.kt` expiresIn) | Yes | App slightly better (re-maps on engine tick) | — |
| 8 | Kind `confirm` | Approve (true) / Reject (false) (`RequestCard.svelte:571-587`) | Yes as "Yes"/"No" buttons + swipe (`Mapping.kt` Confirm) | Yes, plus Y/N keys | Labels differ (Yes/No vs Approve/Reject) | P2 |
| 9 | Kind `buttons` | One button per option (`RequestCard.svelte:603-625`) | Yes, swipe right = first, left = last | Yes, number keys 1-5 | — | — |
| 10 | Kind `choice` | Radio list, pre-picked from `config.default` (`:508-533`) | Partial: pick then Send; no default pre-pick | Partial | Pre-pick `config.default` | P2 |
| 11 | Kind `multi_choice` | Checkboxes, enforce `config.min`/`max`, "Choose between X and Y" (`:535-564`) | Partial: any number; no min/max | Partial | Enforce min/max + hint | P1 |
| 12 | Kind `text` | Input/textarea (`multiline`), placeholder, maxlength, default, min chars (`:469-506`) | Partial: one-line box only; ignores config | Partial | Honour multiline/placeholder/default/min/max | P1 |
| 13 | Kind `form` | Labelled field + agent's own action buttons, sends `{action,text}` (`:588-602`) | **No**: "Answer this one on the website" (`Cards.kt`) | **No** | Web is deprecated, so this is a dead end. Must port | P0 |
| 14 | number / date kinds | Don't exist | — | — | Not a gap | — |
| 15 | Picture options | Thumbnail on each option; excluded from card grid (`:298-305`) | Yes: PictureGrid, hold to look closer (`PictureGrid.kt`) | Yes | App richer | — |
| 16 | Dismiss | "Dismiss" → agent told cancelled | Yes "Not now" / swipe left | Yes, N key | — | — |
| 17 | Answer error | Alert, keeps typed text (`:566-568`) | Partial: outbox "couldn't send" + Retry/Drop in header (`Panels.kt` OutboxLine) | Partial | Not shown on the card itself | P2 |
| 18 | Offline answer | — | Yes: outbox, "Answer waiting to send" | Yes | App only | — |
| 19 | Discussion thread on a request | Reply/react/acks under the controls (`RequestCard.svelte:686-705`) | **No** (QuestionCard has no replies) | **No** | Port: can't discuss a question before answering | P1 |
| 20 | Reactions / tags / fields / pin on request | Same as other cards | No | No | See rows 40-48 | P2 |
| 21 | "edited" marker on request | Shown when agent rewrote it (`:386-399`) | No | No | — | P2 |
| 22 | Collapse request | Fold to one line | No | No | See row 31 | P2 |
| 23 | Answer from notification | Web push SW: confirm/buttons/choice first two (`service-worker.ts:81-140`) | Yes, iOS: per-kind actions incl. text input, Face ID (`iosApp/Common/Notifications.swift`) | Partial: desktop notice opens card, no answer buttons (`desktop/NotificationRules.kt`) | iOS richer | — |
| 24 | Answer from widget / Live Activity / Watch / Siri | — | App only: StatusWidget answer buttons, Live Activity Yes/No, Watch Inbox, "Answer My Agents" intent | — | App only | — |
| **Feed / timeline** ||||||
| 25 | Feed order | Newest activity first (created, latest reply, statusAt) (`web/activity.ts:44-63`) | Yes (`shared/Dashboard.kt:520-523`) | Yes | Parity | — |
| 26 | Pinned section | Pinned cards above day groups, newest created first (`Timeline.svelte:851-868`) | Yes (`Mapping.kt:47`) | Yes | Shown only; can't pin (row 44) | — |
| 27 | Day separators | Sticky "Today/Yesterday/Monday 3 August" (`days.ts:27-81`) | Yes, not sticky; weekday then "3 August" | Yes, not sticky | Not sticky; no year for old dates | P2 |
| 28 | Card time label | Relative "5m ago", "updated …", tooltip with exact times (`days.ts:119-155`) | Partial: "14:05" today, else day name; no "updated" | Partial | No relative time, no "updated", no exact-time tooltip | P2 |
| 29 | "N new updates" pill | Holds live arrivals while scrolled down (`Timeline.svelte:686-707`) | No (arrivals insert live; engine supports hold, unused) | No | Works differently; low risk | P2 |
| 30 | Back to top | Round ↑ after one screen | Yes pill + status-bar tap | Yes pill + Home key | — | — |
| 31 | Collapse a card / fold memory | Chevron or header click; remembered (500) (`CardActions.svelte:89-113`, `collapse.svelte.ts`) | No | No | Port | P1 |
| 32 | Long body clamp | 420 px with fade, "Show the whole update" (`Clamp.svelte`) | No (full height) | No | Long updates take over the phone screen | P1 |
| 33 | Load older | Button, pages of 50 | Yes, auto at end | Yes, auto | — | — |
| 34 | New-card / bump animation | Enter animation, FLIP slide, bump ring (`bumps.svelte.ts`) | No | No | Polish | P2 |
| 35 | Empty feed | "Nothing here yet…" | Yes, loading vs empty vs filtered (`FeedScreen.kt` EmptyFeed) | Yes | — | — |
| 36 | Deep link / focus to card | `?focus=id`, ring 4 s, loads up to 5 pages, "Could not find…" (`Timeline.svelte:159-310,753-763`) | Partial: reveal scrolls, waits 10 s, clears filter (`FeedScreen.kt` ScrollToRevealed) | Partial | No highlight ring, no older-page loading, no "not found" message | P1 |
| 37 | Pull to refresh | — | Yes | F5 / Cmd-R | App only | — |
| 38 | Connection status | Live/Reconnecting dot in header | Yes in feed header (`Panels.kt` LinkBadge) | Yes | — | — |
| 39 | Offscreen skipping (perf) | `content-visibility:auto` (`app.css:441-457`) | LazyColumn (inherent) | LazyColumn; PR #5 memoises | — | — |
| **Card types and their controls** ||||||
| 40 | Update card basics | Level bar + badge, title, markdown body (`UpdateCard.svelte`) | Yes: level chip, title, markdown | Yes | — | — |
| 41 | "High" priority chip on updates | Red "High" when priority high (`UpdateCard.svelte:250-262`) | **No** (`UpdateView.priority` read but not mapped) | **No** | Port: cheap | P1 |
| 42 | "edited" marker on updates/messages | "edited" + tooltip | No | No | — | P2 |
| 43 | Work status chip | To do / In progress / Blocked / Done / Cancelled (`status.ts`) | Shown only (`Cards.kt` StatusChip) | Shown only | — | — |
| 44 | Mark as work / change status | "Mark as work" menu of 5 states, "Not work" (`StatusChip.svelte:51-116`) | **No** (client has `markAsWork` for Siri ticket only) | **No** | Port: owner can't move work | P0 |
| 45 | Pin / unpin card | Pin button on every card (`UpdateActions.svelte`, `CardActions.svelte`) | No | No | Port | P1 |
| 46 | Tags on cards | Chips on every kind, project order, click to remove (`TagChips.svelte`) | Shown only, updates only (`Cards.kt` Tag) | Shown only, updates only | Not on posts/requests; no colours; can't add/remove | P1 |
| 47 | Tag picker | Toggle, create (≤32 chars) with colour, recolour (`TagPicker.svelte`) | No | No | Port (engine already has `FeedState.tags`) | P1 |
| 48 | Card fields ("Details") | Expand list, links, add/edit/remove (`CardFields.svelte`) | **No** (`fields` in engine, unmapped) | **No** | Port | P1 |
| 49 | Delete update | Inline "Delete this update?" confirm (`UpdateActions.svelte`) | **No** (menu Delete only on own posts; client has `deleteUpdate`) | **No** | Port with confirm | P1 |
| 50 | Delete own post / message | Confirm, "…and its N replies?" (`PostCard.svelte:253-265`, `Thread.svelte:555-603`) | Partial: menu Delete, **no confirm** (`Overlays.kt` menuRows) | Partial, no confirm | Add confirm | P1 |
| 51 | Share link (public) | Mint link, Copy, Stop sharing, "Public · N views" (`UpdateActions.svelte:57-198`) | **No** ("Copy link" copies `agents://item/<id>`, `Overlays.kt`) | **No** | Port: copied link only works in the app | P1 |
| 52 | Post card: mine styling | Accent left border | No | No | — | P2 |
| 53 | "Not delivered to any agent" | Red warning after 15 s with no ack/delivery (`acks.ts:66-93`) | **No** | **No** | Port: owner can't tell a post went nowhere | P1 |
| 54 | Embeds (agent "looks") | Blocks rendered with brand mark/palette (`Embed.svelte`, `embeds.ts`) | **No** (EmbedView in engine, unmapped) | **No** | Port | P1 |
| 55 | Apps (MCP Apps iframe) | Sandboxed app with live data/theme/state/tools (`AppFrame.svelte`, `appbridge.ts`) | No | No | Needs a webview; big | P2 |
| 56 | Copy text | Only code-block copy | Yes menu "Copy text" | Yes | App only | — |
| 57 | Text selection | Browser native | Yes (`SelectableText.kt`) | Yes | — | — |
| **Threads and replies** ||||||
| 58 | Thread under every card | Inline, newest first (`Thread.svelte:310-326`) | Full-screen thread, oldest first, swipe back (`Overlays.kt` PhoneThreadScreen) | Side pane ≥1100 dp; PR #5: inline under each card, newest 5 | PR #5 in progress for desktop | — |
| 59 | Reply to a card | "Reply" opens box; Cmd/Ctrl+Enter | Yes (thread composer) | Yes | Update with 0 replies has no thread button; reach via menu "Reply" only | P1 |
| 60 | Reply to one message ("answering X") | Sets `answers`; line shows "answering {name}" (`Thread.svelte:440-453,523-531`) | Partial: swipe/menu sets it on send; the line never shows "answering X" (`ReplyView`) | Partial | Show "answering X" on lines (`ThreadLine.answersName` exists) | P2 |
| 61 | Optimistic send ("Sending…") | Shows at once, 60 % opacity (`outgoing.ts`) | Shows only after stream echo on master; PR #5 adds | Same | In progress (#5) | P0 |
| 62 | Unread divider / per-card unread | Badge per card; mark read when viewed 700 ms (`unread.svelte.ts`, `visible.ts`) | Partial: "N new" divider + thread badge; marked read only when the thread opens | Partial | Not marked read by scrolling past | P2 |
| 63 | Attachments in replies | Paste/drop/Add file | Yes (paperclip, paste, drop) | Yes | — | — |
| 64 | Thread "More thread actions" | — | **Dead** button (`Overlays.kt`) | **Dead** (`Panels.kt` ThreadPane) | Wire or remove | P2 |
| **Acks / receipts** ||||||
| 65 | "is thinking…" | Sweeping bar, online + <10 min (`Ack.svelte`) | Yes on posts (`Content.kt` AckLine) | Yes | Only on post cards, not on replies in thread | P1 |
| 66 | "has read this" | Tick + names | Yes on posts | Yes | Not on replies | P1 |
| 67 | "marked this done" | Bold tick | **No** (done folded into "read", `Mapping.kt` acks) | **No** | Show separately | P2 |
| 68 | "delivered to X" | Paper-plane per agent (`Ack.svelte:200-220`) | No (`deliveries` unmapped) | No | — | P2 |
| **Reactions** ||||||
| 69 | Reaction chips | Grouped, count, accent if mine | Shown | Shown | — | — |
| 70 | Toggle by tapping chip | Tap toggles yours (`Reactions.svelte:78-90`) | **Dead**: chip `onClick {}` (`Content.kt` ReactionRow) | **Dead** | Wire it | P1 |
| 71 | "+" add reaction | Quick picks 👀 ✅ 👍 👎 🎉 🤔 | **Dead** smile button; works via long-press sheet (6 emoji) and double-tap 👍 | **Dead** button; works via right-click menu | Wire "+" to the picker | P1 |
| 72 | Reactions on thread messages | Yes | Shown only on replies (`showAdd=false`); long-press works | Same | — | P2 |
| **Composer** ||||||
| 73 | Post to a project | Textarea, Cmd/Ctrl+Enter, media-only OK | Yes (shown only when a project is open) | Yes | — | — |
| 74 | Post from All projects | "Choose a project" select (`Composer.svelte:170-189`) | **No**: composer hidden on All projects (`FeedScreen.kt`) | **No** | Port the project picker | P1 |
| 75 | Attachments | Paste, drop, Add file, progress bar, max 24 (`Attachments.svelte`, `uploads.svelte.ts`) | Yes; 25 MB cap; no progress bar | Yes | No upload progress | P2 |
| 76 | Tags at post time | Pick tags before posting | No | No | With row 47 | P2 |
| 77 | Voice dictation | — | Yes hold-to-talk (`Voice.kt`) | Yes if available | App only | — |
| 78 | Share into the app | — | Yes, iOS share extension | Desktop drop | App only | — |
| 79 | @mention | Plain text only, server routes | "@" button inserts @ (desktop only, `Panels.kt`) | Same | No autocomplete either side | P2 |
| **Media** ||||||
| 80 | Image grid | 1/2/3 cols by count, aspect clamp (`media.ts:29-130`) | Yes (`Content.kt` MediaGrid) | Yes | — | — |
| 81 | Image viewer | Lightbox: arrows, Home/End, "Original" link | Yes: pager, pinch zoom, prev/next (`Overlays.kt` ImageViewer) | Yes + ←/→/Esc | No "Original" or download | P2 |
| 82 | Video | media-chrome player, frame step, fullscreen (`VideoPlayer.svelte`) | **No** (`Mapping.kt` keeps `kind == "image"` only) | **No** | Videos are invisible | P0 |
| 83 | Sounds | `<audio>`, one at a time (`MediaSounds.svelte`) | **No** | **No** | Audio invisible | P1 |
| 84 | Files | Name, size, download (`MediaFiles.svelte`) | **No** | **No** | Files invisible | P1 |
| 85 | Processing / failed tiles | "Processing…", "Media unavailable" (`MediaTile.svelte`) | No (not-ready media hidden) | No | Card shows nothing until ready | P2 |
| **Markdown** ||||||
| 86 | Markdown body | markdown-it, linkify, line breaks, tables scroll | Yes (`Content.kt` MarkdownBody) | Yes | Tables/link handling not verified | P2 |
| 87 | Code blocks | Highlight, language label, Copy | Yes (`Content.kt` CodeBlock) | Yes | — | — |
| **Filters and search** ||||||
| 88 | Work-status filter chips | Everything / Open work / To do / In progress / Blocked / Done, remembered (`filters.svelte.ts:23-102`) | **No**: chips are All / Waiting / Updates / Errors (`Interaction.kt` FeedFilter) | **No**: same + From you | Status filters missing; choice not remembered | P1 |
| 89 | Kind filters (Waiting/Updates/Errors/From you) | — | Yes (From you hidden on phone) | Yes, with counts | App only | — |
| 90 | Search | None | Yes command bar (`CommandBar.kt`, `Search.kt`) | Yes, Cmd/Ctrl+K | App only | — |
| **Projects** ||||||
| 91 | Project list + switch | Sidebar/drawer, server order (pinned first) | Yes drawer | Yes sidebar | — | — |
| 92 | Pinned project glyph / pin project | Pin icon; Pin/Unpin in menu (`ProjectActions.svelte`) | No | No | Order honoured, no glyph or action | P2 |
| 93 | Reorder projects | — | Yes, hold to drag (device-local) | Event exists; no drag in sidebar | App only | — |
| 94 | Archived section | "Archived (N)" collapsed | Yes (`Panels.kt` ArchivedProjects) | Yes | — | — |
| 95 | New project | Inline form name + description (`NewProject.svelte`) | **No** | **No** | Port | P1 |
| 96 | Rename / describe project | Inline form (`ProjectActions.svelte:364-397`) | No | No | Port | P1 |
| 97 | Archive / unarchive | Menu item | No | No | Port | P1 |
| 98 | Project colours (theme) | Background + accent pickers, AA ink (`theme.ts`) | **No**: neither set nor applied (hue dot only) | **No** | Port at least *applying* the theme | P1 |
| 99 | Project logo / logo replaces name | Pick from 10 images, checkbox (`ProjectActions.svelte:283-336`, `Shell.svelte:553-581`) | No | No | Port applying it, then picker | P2 |
| 100 | Board columns editor | Edit 1-6 columns (board view itself gone) | No | No | Board is gone, so skip | P2 |
| 101 | Project label on cards in All projects | Project name on cards when on All | **No** (hidden in compact, `Cards.kt` CardHeader) | Yes "# project" | Being built (mobile project label) | P0 |
| **Agents / presence** ||||||
| 102 | Live agents list | Name, sessions, model, host, cwd, last heartbeat (`RightRail.svelte:78-191`) | Partial: drawer list, "On host" | Partial: sidebar list; PR #5 adds right rail | No model, cwd, session count or last-seen | P1 |
| 103 | Rename agent | Inline rename in rail (`RightRail.svelte:45-68`) | No | No | Port | P2 |
| 104 | Online avatars in header | — | — | Yes stack + "N online" | App only | — |
| 105 | Agent row tap | — | — | No-op pressable (`Panels.kt` AgentRow) | Could filter to that agent | P2 |
| **Notifications** ||||||
| 106 | Notification bell + list | Unseen count, list, Mark all read, click to go (`NotificationBell.svelte`) | **Dead** bell (`FeedScreen.kt:313`) | **Dead** bell (`Panels.kt:156`) | Port: no in-app history of alerts | P0 |
| 107 | Mark notifications seen | `/api/notifications/seen` | No | No | With row 106 | P1 |
| 108 | Push alerts | Web Push "Notify me" | Yes APNs: requests, replies, messages; rich content ext | Desktop notices by same rules (`NotificationRules.kt`) + Do not disturb | Parity or better | — |
| 109 | Per-device alert prefs | Types (Questions/Replies/Comments/Updates), levels, priority (`NotifyToggle.svelte:41-202`) | **No** settings screen (only project choice via Focus filter, `iosApp/App/FocusFilter.swift`) | **No** (DnD only) | Port prefs screen | P1 |
| 110 | Mark all read | Bell only | Command bar only (no button on phone) | Header button + command bar + tray | App broader (card unread) | — |
| **Settings / account** ||||||
| 111 | Theme toggle | Header Dark/Light, remembered | Command bar "Switch light or dark" only | Sidebar moon + command bar | No visible toggle on phone | P2 |
| 112 | Sign in / out | Password page; logout confirm page | Yes sign-in screen; Sign out in drawer, no confirm | Yes, sidebar icon, no confirm | Add confirm | P2 |
| 113 | Account avatar button | — | **Dead** (`FeedScreen.kt` "Your account") | — | Wire to settings/account | P2 |
| 114 | Devices list / revoke | No web UI (API only) | No | No | Not a gap vs web | P2 |
| **Keyboard (desktop)** ||||||
| 115 | Escape closes drawers/menus/lightbox | Yes | Drawer/thread/viewer Esc | Menus, viewer, command bar | Parity | — |
| 116 | Cmd/Ctrl+Enter sends | Composer + reply | — | Yes | — | — |
| 117 | Cmd/Ctrl+K, F5, Home, Y/N/1-5 on questions | — | — | Yes | App only | — |
| **Platform extras (app only)** ||||||
| 118 | iOS widgets, Live Activity, Watch app + complication, Siri intents, Spotlight, voice notes, Focus filter | — | Yes (`iosApp/**`) | — | App only | — |
| 119 | Desktop tray: waiting count, Mark all read, keep running, start at login, DnD, updates | — | — | Yes; tray updates in PR #6 | App only | — |
| 120 | Offline outbox with retry/drop | — | Yes | Yes | App only | — |
| 121 | Live after sleep (revive) | Wake repair after 30 s hidden (`stream.ts:728-735`) | Missing on master; PR #5 adds | Same | In progress (#5) | P0 |

## What to do first (P0)

1. Draw questions asked inside threads (row 2), and add a real phone "Waiting on you" entry with an amber count. Show per-project waiting badges on both layouts, and make the count match the list (rows 1, 3, 4).
2. Show the agent name and project on question cards (rows 6, 101).
3. Answer `form` requests in the app (row 13).
4. Change work status / mark as work (row 44).
5. Show video, and also sound and files (rows 82-84).
6. Wire the notification bell to `/api/notifications` (row 106).
7. Merge PR #5 (instant replies and revive after sleep, rows 61, 121).

Then the P1s: fix the dead reaction controls, high-priority chip, tags/fields/pin/share/delete, collapse and clamp, status filters, composer on All projects, project management and themes, embeds, alert prefs, the "not delivered" warning, and the extra agent details.

## Method and limits

- Web side: read the components, specs and e2e tests, with a helper's full table cross-checked against the code for the key claims. App side: I read FeedScreen, FeedModel, Interaction, Panels, Cards, Content, Overlays, CommandBar, Search, Mapping, DashboardSession, App and NotificationRules in full or close to it, and grepped the engine and iOS.
- Not verified by running: markdown tables and link taps (row 86). I also didn't check whether the server applies per-device push prefs to APNs (row 109).
- "Live data" in row 2 came from one read-only query of the live database: a count of pending requests by whether they have a `message_id`. Nothing was changed.
