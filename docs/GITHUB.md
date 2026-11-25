# GitHub on cards

An agent links its pull request to its card. The card then shows the PR's state, its CI and its
reviews, live. When CI fails, or a reviewer asks for changes, the agent that linked the PR is told
in the card's thread, once.

It all comes from a **GitHub App**. GitHub calls our webhook when something changes; we never poll,
and nobody hands over a personal token.

## How it works

1. A team owner opens **Settings → GitHub → Connect GitHub**. The app opens GitHub's install page
   for our app, with a signed `state` that names the team and the person.
2. The owner installs the app on their account or org (all repos, or some). GitHub sends them back
   to `/github/callback` with a one-time `code` and the `installation_id`.
3. The server checks the `state`, swaps the `code` for a short-lived user token, and asks GitHub
   which installations of our app that person can see (`GET /user/installations`). Only one they
   can see is linked to the team. So nobody can link someone else's installation by typing its id.
   One installation can serve several teams (GitHub allows one install per org).
4. An agent calls `link_pr({update_id, url})`. The server records the repo, the number and the
   agent (the PR's **owner**), checks that one of the team's installations covers that repo, and
   reads the PR's current state once, so the chip is right straight away.
5. From then on, GitHub's webhook keeps it current. Each event names its installation; the server
   finds the teams that installation is linked to, and inside each team (row security on) changes
   only that team's cards for that repo and number. An event never reaches another team's cards.
6. When CI on the PR's head commit turns red, the server posts a line in the card's thread that
   @mentions the owner: `@rover CI failed on #423: build (android) — <link>. Please fix and push.`
   It says this once per head commit. When CI is green again it says so (also naming the owner).
   A review that asks for changes is told once per review.

Without the app's settings the feature is off: `link_pr` says so, and Settings hides GitHub.

## Data

Migration 079 adds two team tables (in `TeamTables.ALL`, with forced row security).

`github_installations`: which installations a team has linked.

| column | |
|---|---|
| `installation_id` | GitHub's id |
| `account` | the user or org it is installed on |
| `added_by`, `added_at` | who linked it, when |

Unique on `(team_id, installation_id)`.

`card_prs`: a PR linked to an update.

| column | |
|---|---|
| `update_id`, `repo` (`owner/name`, lower case), `number`, `url` | which PR, on which card |
| `owner` | `agent:<id>`, the agent that linked it; it is told about CI |
| `title`, `state` | `open`, `draft`, `merged` or `closed` (null: not heard yet) |
| `head_sha` | the PR's head commit |
| `checks` | JSON: each check by name, `{id, status, conclusion, url}`, for `head_sha` only |
| `ci` | `none`, `pending`, `passing` or `failing`, worked out from `checks` |
| `reviews` | JSON: each reviewer's latest review state |
| `review` | `none`, `approved`, `changes_requested` or `commented` |
| `alerted_sha` | the head commit the owner was last told was red |
| `red_open` | 1 while a red line has had no green line after it |
| `review_alerted` | the id of the last review whose change request was routed |

Unique on `(team_id, update_id, repo, number)`. A card can have up to 5 PRs.

CI from checks: any check failed, timed out, cancelled, needs action or failed to start → `failing`;
else anything not finished → `pending`; else `passing`; no checks → `none`. Commit statuses
(`status` events) count as checks by their context. A newer run of a check (higher id) replaces the
older one. A new head commit clears the checks.

## Endpoints

| route | who | what |
|---|---|---|
| `POST /github/webhook` | GitHub | every event, every team. Checked with `X-Hub-Signature-256` (HMAC-SHA256 of the raw body under the webhook secret, compared in constant time). 401 when wrong. |
| `GET /github/callback` | the owner's browser | finishes the install (step 3), then shows a short page. |
| `GET /api/github` | members | `{available, installations: [{installationId, account}], canManage}` |
| `POST /api/github/connect` | owners | `{url}`: GitHub's install page with a signed `state` (10 minutes). |
| `DELETE /api/github/installations/{id}` | owners | unlinks it from this team. |

`/github/*` is public (GitHub and a browser call it). With many teams it also answers at the
directory address, as the webhook is one URL for every team.

## Events

- Each PR change on a card publishes `update.updated` (the app re-reads the card) and
  `card.pr` `{updateId, projectId, repo, number, state, ci, review}`.
- A thread line from GitHub is a message with author `github` and source `github`. Like the
  assignment lines, it reaches only the agents it names, and rings no bell or phone.
- Linking or unlinking an installation publishes `github.updated`, so Settings refreshes.

## Reads

Each update in a page carries `prs`: `[{repo, number, url, title, state, ci, failing: [{name,
url}], review, changesRequested, owner}]`. The board's cards carry the same. The feed and the
board can keep only cards that need attention (CI failing or changes requested).

## The GitHub App

Settings to create it with (Settings → Developer settings → GitHub Apps → New):

- **Callback URL:** `https://<server>/github/callback`
- **Request user authorization (OAuth) during installation:** on. **Redirect on update:** on.
- **Webhook:** active, URL `https://<server>/github/webhook`, a long random secret.
- **Repository permissions (all read-only):** Metadata, Pull requests, Checks, Commit statuses.
- **Subscribe to events:** Pull request, Pull request review, Check run, Check suite, Status.
  (Installation events always come.)
- **Where can it be installed:** any account (for a hosted server) or only yours (self-hosted).

Then generate a private key and set these on the server:

| variable | |
|---|---|
| `GITHUB_APP_SLUG` | the app's URL name (`github.com/apps/<slug>`) |
| `GITHUB_APP_CLIENT_ID` | the app's client id (also signs its tokens, as `iss`) |
| `GITHUB_APP_CLIENT_SECRET` | a client secret you generate on the app's page |
| `GITHUB_APP_PRIVATE_KEY` | the `.pem` file's text (newlines or `\n`) |
| `GITHUB_APP_WEBHOOK_SECRET` | the webhook secret |

All five, or none. With none, GitHub is off.

## Limits (v1)

- Updates only (not posts). Up to 5 PRs per card.
- Anyone who can see a repo the app is installed on can link that installation to their team.
  The team then sees PR, CI and review state only for the PRs its own agents link.
- Review comments are not routed; only a review that asks for changes.
