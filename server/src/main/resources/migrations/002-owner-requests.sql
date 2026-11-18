
ALTER TABLE approvals ADD COLUMN kind TEXT NOT NULL DEFAULT 'confirm';
ALTER TABLE approvals ADD COLUMN detail TEXT;
ALTER TABLE approvals ADD COLUMN config TEXT;
ALTER TABLE approvals ADD COLUMN answer TEXT;

-- The heartbeat's count: this agent's pending requests (design §5).
CREATE INDEX approvals_agent_state ON approvals (agent_id, state);
