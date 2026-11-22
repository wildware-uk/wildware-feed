-- Migration 072 for Postgres, the twin of migrations/072-row-security-on-newer-tables.sql. Tables
-- made after row security was first switched on (066 card_secrets, 067 feedback, 068 channel_access
-- and access_audit) got their team_rows policy but were never switched on, as the server only did
-- that when the setting changed. Where row security is on (any table here forced), switch these
-- on too. Where it is off, leave them off: the server then tells no connection its team.
DO 'DECLARE t text; BEGIN
	IF EXISTS (SELECT 1 FROM pg_class WHERE relnamespace = current_schema()::regnamespace AND relkind = ''r'' AND relforcerowsecurity) THEN
		FOREACH t IN ARRAY ARRAY[''access_audit'', ''card_secrets'', ''channel_access'', ''feedback''] LOOP
			EXECUTE format(''ALTER TABLE %I ENABLE ROW LEVEL SECURITY'', t);
			EXECUTE format(''ALTER TABLE %I FORCE ROW LEVEL SECURITY'', t);
		END LOOP;
	END IF;
END';
