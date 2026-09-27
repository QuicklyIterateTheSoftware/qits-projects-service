-- DELETE THE RETIRED `project.epics` AND `project.tickets` SURFACE ROWS (qits-404).
--
-- The two desks `project.work` replaced (qits-310, launch cut over qits-403) have had no reader
-- left since: every session has launched as `project.work` since qits-403, and V30 already copied
-- whatever `project.epics` held onto that key, so any operator edit to either old desk survives on
-- `project.work` regardless of what happens to the old rows. The two keys were kept resolving for a
-- time (`AgentSurfaceDefaults.RETIRING`) only so a container born before the switch — one that read
-- its configuration document once, at boot, and named `project.epics` or `project.tickets` in it —
-- kept getting an answer for the rest of its life. By now nothing launches with either key and
-- nothing holds a document naming them, so their rows are dead weight.
--
-- WHY THIS IS SAFE TO DELETE OUTRIGHT RATHER THAN LEAVE FOR A FURTHER RELEASE: the seed
-- (`startup/AgentSurfaceSeed`) is insert-if-absent over `AgentSurfaceDefaults.SURFACES`, which never
-- named either retired key, and this same change deletes `AgentSurfaceDefaults.PROJECT_EPICS` and
-- `PROJECT_TICKETS` — the constants a seed would need in order to write a row back — so nothing on
-- this estate can resurrect what this migration removes. A surface with no row still never 404s:
-- `AgentSurfaceConfigurationService` falls through to `AgentSurfaceDefaults.shippedDefault`, which
-- now has no entry for either key and answers the neutral default instead of the old shipped one —
-- exactly the same "an absent row is never a 404" rule every other unrecognised surface gets.
--
-- CHILDREN BEFORE THE PARENT, for the foreign keys `agent_surface_mcp_attachment.surface_key` and
-- `agent_surface_external_mcp_attachment.surface_key` both carry (V16, V18) — both `on delete
-- cascade`, so the two attachment deletes below are belt-and-braces rather than load-bearing, but
-- explicit is what a reviewer of this file can trust without checking the constraint definitions.
--
-- `agent_surface_configuration_revision` IS LEFT ALONE, DELIBERATELY: it carries no foreign key to
-- `agent_surface_configuration` (V16 says so explicitly — "a surface that is retired from the
-- vocabulary takes its row with it, and the trail of what it used to be configured as has to
-- survive that"), so the revision history for `project.epics` and `project.tickets` is exactly the
-- kind of row that table exists to keep after the row it describes is gone.
delete from agent_surface_external_mcp_attachment
where surface_key in ('project.epics', 'project.tickets');

delete from agent_surface_mcp_attachment
where surface_key in ('project.epics', 'project.tickets');

delete from agent_surface_configuration
where surface_key in ('project.epics', 'project.tickets');
