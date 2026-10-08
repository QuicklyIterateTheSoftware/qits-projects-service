-- THE FRONT-DESK RUNNERS (qits-767): one row per machine an operator declared to host project
-- front desks. A runner registers with this service, holds a socket to it and runs the desks it is
-- given on its own node.
--
-- A copy of qits-workspaces-service's `workspace_runner` (its V12 plus V14), minus the workspace
-- memory columns, plus `login_state`. What reading a row means is DeskRunner's javadoc:
--
--   registration_token_id / registration_token_subject  the one-use token qits-idp commissioned
--       at create (or the last rotation): the id so it can be deleted, the subject because the
--       register door compares the caller's `sub` to it. The token's value is on no column.
--   client_id / registered_at  set once, by the register door.
--   quarantined_at / quarantine_reason  set and cleared together; a runner is quarantined from
--       registration until its first health check passes.
--   capabilities  the runner's own word about itself, merged key by key on every report.
--   login_state  the node's agent login as the runner last probed it.
--
-- `slots` is how many desks it may hold at once; at least one, eight unless the operator says.
--
-- causation_id is uuid, as on every other caused table here (CausedRow carries a UUID).
--
-- This migration precedes the `front_desk` one, whose `runner_id` references this table.
create table desk_runner (
  id                         uuid        not null,
  name                       text        not null,
  description                text,
  slots                      integer     not null default 8,
  capabilities               jsonb,
  registration_token_id      text,
  registration_token_subject text,
  client_id                  text,
  quarantined_at             timestamptz,
  quarantine_reason          text,
  last_health_check_at       timestamptz,
  last_health_check_ok       boolean,
  login_state                text,
  registered_at              timestamptz,
  last_seen_at               timestamptz,
  created_at                 timestamptz not null default now(),
  causation_id               uuid,
  constraint pk_desk_runner primary key (id),
  constraint uq_desk_runner_name unique (name),
  constraint ck_desk_runner_slots check (slots > 0),
  constraint ck_desk_runner_login_state check (login_state in ('PRESENT', 'ABSENT'))
);
