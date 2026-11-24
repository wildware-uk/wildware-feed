-- Migration 077: every member of a team describes themselves (spec/13 "Profiles"): a short role
-- line (up to 60 characters, like "Development") and a description (up to 500: who they are, what
-- they look after, when to contact them), and a picture (a media id, served as /media/<id>/avatar).
-- A person's is on their membership, so it is per team; an agent's is on the agent. NULL is none yet.
ALTER TABLE memberships ADD COLUMN profile_role TEXT;
ALTER TABLE memberships ADD COLUMN profile_description TEXT;
ALTER TABLE memberships ADD COLUMN avatar_media_id TEXT;
ALTER TABLE agents ADD COLUMN profile_role TEXT;
ALTER TABLE agents ADD COLUMN profile_description TEXT;
ALTER TABLE agents ADD COLUMN avatar_media_id TEXT;
