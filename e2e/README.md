# The web app, end to end

Browser tests of the web app against a real server in hosted mode, with Playwright. Each spec file
makes a team of its own through the signed admin API, as the billing control plane does, and
deletes it at the end, so files run side by side and a staging server stays clean.

| Spec | What it covers |
|---|---|
| `auth` | Setup link sets the owner's password (once); "Open" signs the browser in; the sign-in form: Email box, password alone, email and password, a wrong password, signing out; the home team |
| `apps` | The phone and desktop apps' sign-ins over HTTP (password alone, with email, a ticket, a member) and no team's password or key opening another |
| `people` | Invite from the People sheet; the invitee joins in their own browser and sees the feed; role change; removing someone signs them out |
| `channels` | Make a channel and post; an agent's update shows live; rename; remove an agent from a channel; projects |
| `arrange` | Drag a channel into a project with the mouse; the channel menu's Move to project; both stick after a reload |
| `permissions` | Make a channel private from its Permissions panel; a member stops seeing it (list, feed, API); give them Read: they read it and can't post; the change log |
| `messages` | Replies, threads, @mentions, reactions, edit and delete; a reply further down takes the feed to the top with its card |
| `search` | Search everything finds messages and cards; one from another channel opens on its own, quickly, and Back to feed leaves the channel as it was |
| `media` | Picture, sound and video: upload, post, view and play; Save downloads a picture's original |
| `cards` | Updates, pin, status, checklists and polls |
| `board` | The owner marks two posts as work, opens the Board, drags one to Done (the agent's `list_work` sees it), and removes it from the board; it stays in the feed |
| `requests` | An agent's questions: choice buttons, forms, text; Waiting on you |
| `secrets` | An agent attaches a secret to its post; the owner reveals it in place, and the opening is audited |
| `feedback` | The owner sends a feature request from the browser; an agent's feedback waits, the owner edits and sends it, the agent hears so (saved on the stack, which has no Sentry) |
| `agents` | Pairing: Allow, Deny; a removed agent's token stops working |
| `connect` | Connect an agent: copy the prompt, an agent asks to join at the address it names, Allow; nothing secret in it |
| `settings` | Alerts on this device, sounds, dark mode |
| `shortcuts` | The browser's Back and Forward walk channels; Alt+←/→; a reload keeps the channel; Alt+↓/↑; ? lists the shortcuts; / opens search |
| `teams` | Two teams from one browser: "Add a team" opens the other team's address in a new tab, which signs in on its own; the switcher opens a team signed in there in its own tab |
| `isolation` | Nothing of team B in team A's browser; no key, cookie, token, password or invite crossing teams |

## Run it locally

It needs a throwaway Postgres the stack may make a database and a role on, and the server built
(`./gradlew :server:installDist`):

    cd e2e && npm ci && cd ..
    PG_ADMIN_URL=postgres://postgres:pw@127.0.0.1:5432/postgres SKIP_DIST=1 \
        server/deploy/test-stack.sh bash -c 'cd e2e && npx playwright test'

`CHROME=/path/to/chrome` uses that browser; otherwise `npx playwright install chromium` first.
One file: add its path, e.g. `npx playwright test specs/people.spec.mjs`. Reports land in
`e2e/report-<port>/`, traces and screenshots of failures in `e2e/results-<port>/`.

## Against staging

The `staging-e2e` workflow runs the suite against a deployed server; see its header for the
secrets. By hand: `E2E_ORIGIN=https://<admin origin> E2E_ADMIN_KEY_FILE=<file> npx playwright test`.

## How it drives the app

The web app draws on one canvas. Compose mirrors what it draws into an accessibility tree (roles
and labels in a shadow root), so `lib/ui.mjs` finds a box or button there by its name, clicks the
canvas where it is drawn (scrolling it into view first), and reads what the screen says from the
tree. Two things to know: after a dialog closes the tree keeps the dialog until the page is drawn
afresh (`reload`), and a key typed straight after a tap or select-all can be lost (the helpers wait).

`signedIn()` signs a browser in without the sign-in form (the web app's own cookie and session
note), so specs other than `auth` don't depend on it.
