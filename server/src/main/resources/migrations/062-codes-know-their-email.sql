-- Migration 062: a one-time code remembers the email it was sent to. Redeeming a setup link proves
-- that email only, and only while the account still has it; a code is refused once the account's
-- email has moved on. Codes made before now don't know their email, so they go: someone part way
-- through setting up asks for a new link, and a sign-in ticket lasts a minute anyway.
ALTER TABLE auth_codes ADD COLUMN email TEXT;
DELETE FROM auth_codes;
