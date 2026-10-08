-- A PROJECT'S FRONT DESK (qits-767): one row per project whose agent container — its front desk —
-- exists or is wanted, placed on a front-desk runner (V36) by pull: a runner's `reserve` claims the
-- oldest QUEUED row with a compare-and-swap on `runner_id is null`, and from then on the desk is
-- sticky to that runner. What reading a row means is FrontDesk's javadoc.
--
--   runner_id        null = never placed, or unplaced by a DELETE of the desk
--   desired          RUNNING or STOPPED, recomputed from the project's front_desk_lifecycle (V35)
--                    and the demand stamp
--   token_*          the desk's own qits_tok_ (idp kind agent-container): the id to revoke it, the
--                    value because the spec carries it and must be reproducible, the subject because
--                    the control socket and the dial-back compare the bearer's `sub` to it
--   spec_json/hash   the spec last SENT to the runner (the applied spec), and its sha256 over the
--                    canonical JSON
--   reported_*       the runner's last inventory of the desk
--
-- project_id is varchar(255), not uuid: it references Project(id), which is varchar(255) (V1), and
-- a foreign key needs the same type on both ends. The cascade drops the desk with its project.
--
-- No causation_id: FrontDesk is @Uncaused — machine state derived from the project's lifecycle and
-- from demand, written by sweeps and socket frames where no request scope stands.
create table front_desk (
  project_id         varchar(255) primary key references project(id) on delete cascade,
  runner_id          uuid null references desk_runner(id),
  desired            text not null default 'STOPPED' check (desired in ('RUNNING','STOPPED')),
  last_demand_at     timestamptz null,
  queued_at          timestamptz null,
  placed_at          timestamptz null,
  token_id           text null,
  token_value        text null,
  token_subject      text null,
  spec_json          jsonb null,
  spec_hash          text null,
  reported_state     text null,
  reported_spec_hash text null,
  reported_at        timestamptz null,
  failure_detail     text null,
  created_at         timestamptz not null default now()
);
create index front_desk_runner on front_desk(runner_id);
