# Quick replies

A quick reply is up to 4 buttons, or a small form, that an agent puts on an ordinary message. The
person taps one, and that tap is sent as **their own reply in the same thread**, answering the
message. If nobody taps, nothing happens.

Think of the suggested replies under a chat message. The agent says "Sounds are in 1.0.32" and
offers "👍 Ship it" and "Try another". Saying yes is one tap instead of typing it.

## Not a question

`request_input` is for when an agent is stuck and must have an answer. It goes to the top of the
screen and into "Waiting on you", it can ring the phone, and it times out.

Quick replies are none of that:

- No "Waiting on you" entry, no request, no extra alert. The message alerts exactly as a plain
  message would.
- Nothing waits. The agent carries on, and an ignored button is just an ignored button.
- The answer is an ordinary message, so the agent hears it the way it hears any reply. There is no
  answer event to learn.

Use quick replies to make the likely answer one tap. Use `request_input` when you are blocked.

## Where they go

`post_message` takes:

- `buttons`: up to 4 labels, each 1 to 80 characters, all different.
  `["👍 Ship it", "Try another"]`.
- `fields`: a small form instead, with `request_input`'s form fields, checked by the same rules
  (`FormFields.kt`): `{ id, label, type, hint?, required?, default?, options?, min?, max?,
  placeholder? }`, at most 20. With `fields`, `buttons` are the form's submit buttons, and "Send"
  when left out.

Any message an agent posts can carry them: a reply on an update, a line in a thread, or a post of
its own in a channel. A person's message cannot. A bad label or field refuses the whole post.

## Answering

The app draws the buttons under the message (or the form, with the same controls a form question
uses). A tap calls:

```
POST /api/messages/{id}/reply
{ "button": "👍 Ship it", "values"?: { "<field id>": … }, "sendKey"?: "…" }
```

The server checks the button is one offered and, for a form, checks the values exactly as a form
question would. Then it posts the person's reply, answering the message (`answers` is its id), in
the same thread. Its body is readable text:

- buttons: the label, `👍 Ship it`;
- a form: one line per field filled in, `- **Name:** Sam`, with the button first when there are
  several (lists joined with commas, ticks as yes or no). A form sent with nothing filled in is the
  button alone.

The reply also carries what was chosen, for the agent: `reply { to, button, values? }` in
`get_messages` and in the channel's message event (the owner's app reads `replyFor` and
`replyValue`).

It answers `201 { message, answered }`: the reply, and the agent's message as it now stands.

### Once each

Each person answers a message once. A second answer is refused with `409 conflict` ("you have
already answered that message"), whichever server or device it comes from: a unique index on
`(team_id, reply_for, user_id)` holds it.

A double tap sends once. The app makes one `sendKey` per message and sends it with every try, and the
same key again gets the first reply back. The buttons also go quiet the moment one is tapped.

Unsending the reply takes the choice back: the buttons come back for that person.

## Who chose what

The agent's message carries `replies { buttons, fields?, answers }`. Each answer is
`{ userId, name?, button, values?, messageId, at }` (`name` only on a team of several). The server
works the list out again from the live replies after every answer and every unsend, and publishes
`message.updated` for the message, so every app showing it re-reads it.

Once you have answered, the app shows "You chose 👍 Ship it" (or "You sent it" for a one-button
form) in place of the buttons; other people's answers are listed under it. Until then you still see
the buttons, along with who else has answered.

An agent reads the same list on its own message, in snake case:
`replies { buttons, fields?, answers: [{ user_id, name?, button, values?, message_id, answered_at }] }`.

## Older apps

The fields are additive. An app from before this ignores them, so it shows the message text and,
after someone answers, their reply in the thread. Nothing breaks.

## Storage

Migration 081 adds three columns to `messages`:

- `replies`: on the agent's message, the offer and its answers as JSON;
- `reply_for`: on a person's answer, the message it answers;
- `reply_value`: on that answer, `{ button, values? }`.

and the index `messages_one_reply`, unique on `(team_id, reply_for, user_id)` for live
(`deleted_at IS NULL`) answers.
