-- Migration 069: channels and projects keep the order people drag them into. position is a
-- channel's place among the channels of its project (or among those in no project), and a project's
-- place among the projects. NULL is not arranged yet: those come first, in the old order (newest
-- channel first, projects by name), so a new one shows at the top until someone moves it.
ALTER TABLE projects ADD COLUMN position INTEGER;
ALTER TABLE project_groups ADD COLUMN position INTEGER;
