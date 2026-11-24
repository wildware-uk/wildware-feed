-- Migration 075: the plan the control plane puts a hosted team on (docs/TEAMS.md §7): 'free' or
-- 'team'. NULL is no plan at all, as for the home team and every team made before plans: no plan
-- limits. The free plan's limits are the server's own (Teams.Free); nothing is deleted by them.
ALTER TABLE teams ADD COLUMN plan TEXT CHECK (plan IN ('free', 'team'));
