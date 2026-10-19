-- AN ELEVENTH ARCHETYPE: AGENT_CONFIG, THE PROJECT'S AGENT CONFIGURATION REPOSITORY (qits-1382).
--
-- `qits-agent-config` holds what a project's Claude Code agents inherit -- memories, skills, enabled
-- plugins -- one branch per agent, merged into `main` by an approved release request. It is platform
-- state rather than software, so none of the seven component kinds fits it, and folding it into one
-- of them would hand it a wrapper entry, a backup twin and a CI recipe it must not have. So it is a
-- value of its own.
--
-- AGENT_CONFIG is NOT a component of its project (RepositoryArchetype.AGENT_CONFIG(false)), which is
-- what every exemption rests on: the wrapper never declares it, the reconcile never reports it
-- UNDECLARED, the write guard and the wrapper removal on delete skip it, and the ordinary create door
-- refuses it. Only POST .../agent-config-repository mints one.

-- The constraint is inline and NAMED in V1__init.sql, so widening it is a drop and a re-add over the
-- wider set, exactly as V27 did for APP -- the two statements below are one change, and Flyway runs
-- the file in one transaction.
alter table Repository drop constraint CK_repository_archetype;

-- The final eleven: V27's ten, and AGENT_CONFIG.
alter table Repository add constraint CK_repository_archetype check (archetype in
  ('PROJECT', 'SERVICE', 'DAEMON', 'LIBRARY', 'FRONTEND', 'APP', 'CLI', 'IMAGE', 'SERVICE_TEMPLATE',
   'FORK', 'AGENT_CONFIG'));

-- Nothing is backfilled: no row carries AGENT_CONFIG until the door that mints one is pressed, and no
-- name derives it, so there is no existing row a rule could turn into one.
