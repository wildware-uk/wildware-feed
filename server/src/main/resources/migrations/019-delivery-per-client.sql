
ALTER TABLE message_deliveries ADD COLUMN client_id TEXT;

DROP INDEX message_deliveries_message_agent;
-- One delivery per message per client. Rows with no client are per connection
-- and are deliberately allowed to repeat: NULL never equals NULL here.
CREATE UNIQUE INDEX message_deliveries_message_agent_client
	ON message_deliveries (message_id, agent_id, client_id);

DROP INDEX message_deliveries_agent;
-- The read the stream makes: what this client has already been sent.
CREATE INDEX message_deliveries_agent_client
	ON message_deliveries (agent_id, client_id, message_id);
