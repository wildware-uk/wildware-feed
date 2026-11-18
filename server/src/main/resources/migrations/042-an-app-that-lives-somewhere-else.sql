
-- The page to frame, for an app that lives somewhere else. NULL for the ones
-- that are a document we hold; exactly one of the two is set, which the domain
-- enforces because SQLite's CHECK cannot be widened later without a rebuild.
ALTER TABLE apps ADD COLUMN url TEXT;
