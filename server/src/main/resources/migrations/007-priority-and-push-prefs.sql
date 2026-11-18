
ALTER TABLE updates ADD COLUMN priority TEXT NOT NULL DEFAULT 'medium';

ALTER TABLE push_subscriptions ADD COLUMN prefs TEXT;
