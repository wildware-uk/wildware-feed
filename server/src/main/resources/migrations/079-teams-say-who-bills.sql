-- Migration 079: who takes a hosted team's money, as the control plane says (docs/HOSTING.md §7):
-- 'web' (its website, Stripe) or 'apple' (an in-app purchase on iPhone). NULL is nobody: a free or
-- beta team, or no control plane. The apps use it to say where the plan is managed.
ALTER TABLE teams ADD COLUMN billing TEXT CHECK (billing IN ('web', 'apple'));
