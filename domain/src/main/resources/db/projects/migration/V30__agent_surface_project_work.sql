-- THE ONE FRONT DESK GETS ITS OWN ROW: `project.work`, COPIED FROM `project.epics` (qits-403).
--
-- The epics and tickets desks merged into one desk at `:project/work` (qits-310), and from this
-- release every desk session launches with the surface key `project.work`. This migration gives that
-- key the configuration the epics desk had on this estate, operator edits included, so switching the
-- key changes nothing about how a desk session runs.
--
-- COPIED FROM `project.epics`, NOT `project.tickets`, because the merged desk inherits the
-- refinement surface's configuration: the shipped default for `project.work`
-- (`AgentSurfaceDefaults.PROJECT_WORK`, and the library's `AgentSurface.PROJECT_WORK`) is the epics
-- desk's — an empty system prompt and the one project-narrowed `repository` server — not the
-- tickets desk's triage prompt, and a copy of the tickets row would steer the one desk as the
-- tickets desk alone.
--
-- AN INSERT, NEVER AN UPDATE (and never a rename of the key). A container reads its configuration
-- document once, at boot, and every container born before this release names `project.epics` and
-- `project.tickets` for as long as it lives. Rewriting the epics row in place would pull the
-- configuration out from under every live session: those containers keep resolving the key their
-- document names, and the store — and the next document built from it — would no longer hold it.
-- So both old rows stay exactly where they are, and retire with their keys in a later change once
-- nothing launches or holds them.
--
-- THE WHOLE CONFIGURATION, NOT ONLY THE ROW. A surface's configuration is the row plus its built-in
-- MCP attachments (`agent_surface_mcp_attachment`) plus its catalog attachments
-- (`agent_surface_external_mcp_attachment`); all three are copied, with fresh ids. `updated_by` rides
-- along because it attributes the content, which is what was copied; `created_at`/`updated_at` are
-- now, because the row is new. No revision is written: the trail is keyed by surface and records
-- edits, and the first edit of `project.work` starts its own.
--
-- IDEMPOTENT, AND GUARDED AS ONE UNIT. Every insert hangs off the `copied` CTE, which yields a row
-- only when `project.epics` exists and `project.work` does not — so a `project.work` row that
-- already exists (written by the editor, or by `AgentSurfaceSeed` on some other path) is left
-- untouched along with its attachments, and nothing is ever merged into it. Running the body twice
-- inserts nothing the second time. The attachment inserts read `copied` rather than testing for
-- `project.work` themselves, because inside one statement every CTE sees the same snapshot: their
-- own `not exists` would not see the row the first CTE is inserting, and the foreign keys are
-- checked at the end of the statement, after all three inserts.
--
-- ORDERING AGAINST THE BOOT SEED. `AgentSurfaceSeed` writes shipped defaults insert-if-absent, and
-- it runs from Quarkus' StartupEvent — after `quarkus.flyway.projects.migrate-at-start` has already
-- applied this file. So on the boot that first knows `project.work`, this copy lands first and the
-- seed finds the row and writes nothing. The reverse order would have let the seed write the shipped
-- default first and this guard decline to overwrite it, losing the epics desk's edits. On a fresh
-- estate there is no `project.epics` row yet (the seed has never run, and no longer seeds it), so
-- this inserts nothing and the seed writes `project.work` from a shipped default that equals the
-- epics desk's.
--
-- `surface_key` STILL CARRIES NO CHECK CONSTRAINT, deliberately — see V16. This is a data copy, not
-- a vocabulary change in the schema.
with copied as (
    insert into agent_surface_configuration
        (surface_key, harness, model, effort, remote_control, permission_mode, activity_tracking,
         system_prompt, initial_prompt, updated_by, created_at, updated_at, causation_id)
    select 'project.work', e.harness, e.model, e.effort, e.remote_control, e.permission_mode,
           e.activity_tracking, e.system_prompt, e.initial_prompt, e.updated_by, now(), now(), null
    from agent_surface_configuration e
    where e.surface_key = 'project.epics'
      and not exists (select 1 from agent_surface_configuration w where w.surface_key = 'project.work')
    returning surface_key
),
built_in as (
    insert into agent_surface_mcp_attachment
        (id, surface_key, server_key, position, narrow_project, narrow_repository, narrow_workspace,
         read_only, allowed_tools, causation_id)
    select gen_random_uuid()::text, c.surface_key, a.server_key, a.position, a.narrow_project,
           a.narrow_repository, a.narrow_workspace, a.read_only, a.allowed_tools, null
    from agent_surface_mcp_attachment a
    cross join copied c
    where a.surface_key = 'project.epics'
    returning id
)
insert into agent_surface_external_mcp_attachment (id, surface_key, catalog_key, position, causation_id)
select gen_random_uuid()::text, c.surface_key, x.catalog_key, x.position, null
from agent_surface_external_mcp_attachment x
cross join copied c
where x.surface_key = 'project.epics';
