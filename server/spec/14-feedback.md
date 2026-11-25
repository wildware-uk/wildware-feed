# 14. Feedback and feature requests

Migration 067 (`feedback`, and `media.feedback_id`). Anyone on a team, and any agent, can send
feedback about something that exists, or ask for a feature that's missing. Sent feedback goes to
Sentry's user feedback, on the server's own `SENTRY_DSN` (the one crash reports use). A server
without one sends nothing anywhere: feedback is only saved there, for the team.

## Rules

- **Two kinds.** `feedback` and `feature_request`. Optional `area`: `feed`, `threads`, `voice`,
  `secrets`, `apps`, `agents` (agents and the plugin), `billing`, `other`. `title` 1-120
  characters, `body` markdown 1-5000, up to 6 media, and optionally the card it is about
  (`{kind, id}`, an update, message or request the sender can see).
- **A person's goes at once.** `sent` once Sentry has it (`sentry_event_id`), or `saved` where
  there is no `SENTRY_DSN` (or Sentry refused it: never the sender's problem).
- **An agent's waits for its team.** `send_feedback` keeps it as `pending` and asks the team a
  question (`request_input` kind `form`, priority `low`, 24 hours): actions `["Send to <recipient>",
  "Discard"]` (`["Save it", "Discard"]` with no `SENTRY_DSN`), the text editable with the body as
  its default, the card linked as a ref, the media on the question. The question's
  `config.feedbackId` names the item, and its buttons can't be edited. Only an owner or an admin
  may answer or dismiss it (a member gets `403 forbidden`). Answered with the first action, the
  item goes with the text as answered (`approvedBy` is who). Discard, dismissing it, the agent
  withdrawing it or the 24 hours running out make it `discarded`, and it never goes. The agent
  hears the outcome as any answer: an `answer` event, or `get_messages({request_id})`.
- **No secrets.** A title, body or approved text that looks like a key (private key blocks, `sk-…`,
  `sk_live_…`, `ghp_…`, `github_pat_…`, `AKIA…`, `xox?-…`, `AIza…`, or a 32+ character run of token
  characters mixing upper case, lower case and digits) is refused with `400 invalid_argument`.
- **Limits.** 20 a day per person and per agent, 200 a day per team (`429 rate_limited`). The same
  author sending the same title again within a day, unless it was discarded, is `409 conflict`.
- **Statuses.** `pending` → `sent`, `saved` or `discarded`. Each change publishes
  `feedback.updated {feedbackId, status}` in the item's team.
- **Teams apart.** Rows carry `team_id` and Postgres row security; a team reads only its own.
- **Channels apart (spec/15).** An item is listed to its author, and to anyone else only when they
  can read the channel of its card and of its question; one tied to no channel is the team's. Its
  `card` is `null` to a viewer who can't read that card. Its media answer `404` to anyone the item
  is not listed to.
- **Media.** Uploads sent with feedback carry `feedback_id`, so the unattached-media sweep leaves
  them. An agent's also sit on its question (`request_id`), so the owner sees them there.

## What Sentry gets

One feedback event (`Sentry.captureFeedback`):

- `message`: the title, a blank line, the body (at most 4096 characters);
- `name`: the person's name, or the agent's; `contact_email` only when the person ticked "Include
  my email" (`includeEmail: true`); never an agent's owner's email;
- tags: `feedback.kind`, `feedback.area`, `feedback.source` (`person` or `agent`),
  `feedback.team` (an HMAC of the team's id under `TOKEN_SECRET`, 16 hex characters: the same for
  every item of one team, and no way back to its id, slug or name), `server.version`, and
  `client.release` (the app's, when it says);
- up to 3 pictures sent with it, each at most 2 MB, as attachments (no links to the server);
- `user.ip_address` `0.0.0.0`, and no request, breadcrumbs, extras or server name
  (`FeedbackReports.scrub`, as `beforeSendFeedback`).

## Owner routes

| Route | Who | What |
|---|---|---|
| `GET /api/feedback` | member | `{recipient, sends, kinds, areas, feedback: [item]}`, the team's own, newest first, at most 200; `?status=` narrows |
| `POST /api/feedback` | member | `{kind, area?, title, body, mediaIds?, card?: {kind, id}, includeEmail?, client?}` → `201 {feedback: item}`. Open while the team is suspended |

An item: `{id, kind, area, title, body, status, author: {kind: "person"|"agent", id, name}, card,
requestId, approvedBy: {id, name}|null, media: [media], createdAt, sentAt, updatedAt}` (times in
ms). `recipient` is who the app says it goes to: `FEEDBACK_RECIPIENT`, or "the people who run this
server". `sends` is whether feedback leaves the server.

## MCP tools

- `send_feedback {kind, area?, title, body, media_ids?, update_id? | message_id? | request_id?,
  channel?}` → `{feedback: {...status: "pending"}, request_id, state: "pending"}`.
- `list_feedback {mine?, status?, limit? (1-100, 50)}` → `{feedback: [{id, kind, area, title,
  body (cut to 300), status, author: {kind, name}, card, request_id, created_at, sent_at}]}`.
  Allowed while the team is suspended.
