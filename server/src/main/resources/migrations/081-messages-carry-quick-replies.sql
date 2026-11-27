-- Migration 081: quick replies on a message (docs/QUICK_REPLIES.md). An agent's message may carry up
-- to 4 buttons, or a small form, that a person answers with one tap. replies is that offer as JSON
-- ({ buttons, fields?, answers }), answers being who chose what, kept in step by the server. The
-- answer itself is an ordinary message by that person in the same thread: reply_for names the
-- message it answers and reply_value holds what they chose ({ button, values? }). One live answer per
-- person per message; unsending it frees the choice again.
ALTER TABLE messages ADD COLUMN replies TEXT;
ALTER TABLE messages ADD COLUMN reply_for TEXT;
ALTER TABLE messages ADD COLUMN reply_value TEXT;
CREATE UNIQUE INDEX messages_one_reply ON messages (team_id, reply_for, user_id) WHERE reply_for IS NOT NULL AND deleted_at IS NULL;
