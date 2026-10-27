-- FRONT DESKS RUN AS DESK LEASES ON EXECUTION TARGETS (epic qits-1481, qits-1551).
--
-- A project's front desk no longer runs on a front-desk runner this service registered and talked
-- to over its own socket: it runs in a DESK slot of an execution target, under a lease qits-system
-- grants. The runner registry goes in this same file, and a desk records the lease it runs under
-- instead of the runner that held it.
--
-- 1. legacy_placement, FIRST: every desk a runner held, copied before front_desk.runner_id and
--    desk_runner go. The desk's first lease adopts the runner's container and volume, named
--    qits-projects-desk-runner-desk-<projectId>-<oldRunnerId8>, and is pinned to the target on the
--    same docker engine. The adoption reads this table and deletes the entry once that lease is
--    RUNNING.
--
--    engine_id and hostname are read from the runner's capabilities under the keys a target's
--    runner reports them by (engineId, hostname). qits-projects-desk-runner-daemon never reported
--    either (its capabilities held version, docker, arch, os, login and health), so both are null on
--    every row this copies today, and the runner's own name is copied beside them as the last way to
--    find the target on the same node — qits-workspaces' V30, for the same reason.
--
--    The key is the project, and the entry goes with it. Not a CausedRow (ArchRulesTest): the
--    migration writes every row.
create table legacy_placement (
  project_id    varchar(255) not null references project(id) on delete cascade,
  old_runner_id uuid         not null,
  engine_id     text,
  hostname      text,
  runner_name   text,
  constraint pk_legacy_placement primary key (project_id)
);

insert into legacy_placement (project_id, old_runner_id, engine_id, hostname, runner_name)
select d.project_id,
       d.runner_id,
       r.capabilities ->> 'engineId',
       r.capabilities ->> 'hostname',
       r.name
  from front_desk d
  left join desk_runner r on r.id = d.runner_id
 where d.runner_id is not null;

-- 2. The lease a desk runs under, and the target it was granted on. Both null while the desk has no
--    live lease (lease_id is cleared once the lease is ENDED). No foreign key: both name rows in
--    qits-system's database. token_* (V37) stays, and now holds the current lease's token.
--
--    adopted_volume: the retired runner's volume this desk's work was adopted from. Every lease of
--    the desk names it in adopt{container, volume}, because the runner mounts an adopted volume only
--    when the lease says so. Cleared with the desk.
--
--    lease_detail: why a QUEUED lease waits, as qits-system last said it (or why the desk waits for
--    the target that holds its legacy volume). reported_state, reported_spec_hash and reported_at
--    now hold the lease's state and the spec hash qits-system last answered, and placed_at when the
--    current lease was asked for.
alter table front_desk add column lease_id uuid;
alter table front_desk add column target_id uuid;
alter table front_desk add column adopted_volume text;
alter table front_desk add column lease_detail text;

-- 3. What the runner last reported is no lease's state: every desk starts from no lease. A desk a
--    runner held RUNNING is still running on that runner's node until its first lease adopts it, and
--    reads QUEUED (wanted, no lease) until then — never RUNNING with nothing to revise it. A launch
--    failure the runner reported is that runner's, and goes with it.
update front_desk
   set reported_state = null, reported_spec_hash = null, reported_at = null, queued_at = null;
update front_desk
   set failure_detail = null
 where failure_detail is not null
   and failure_detail not like 'EDGE_PLANE_UNCONFIGURED%'
   and failure_detail not like 'TOKEN_UNAVAILABLE%';

-- 4. The runner registry goes.
drop index front_desk_runner;
alter table front_desk drop column runner_id;
drop table desk_runner;

-- The lease reconcile and the event listener read desks by their lease.
create index ix_front_desk_lease
  on front_desk (lease_id)
  where lease_id is not null;
