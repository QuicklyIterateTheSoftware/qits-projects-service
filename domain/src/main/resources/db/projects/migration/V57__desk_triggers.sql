-- DESK TRIGGERS (epic qits-1604, task qits-1608).
--
-- The delivery desk is prompted by what happens, not only by a person: a ticket reaching VERIFIED, a
-- release request waiting on the desk's approval. Each such fact is ADMITTED into its project's open
-- window; the window closes a fixed time after it opened (qits.projects.desk-triggers.window), and
-- what it collected is sent to the desk as ONE prompt. What reading a row means is
-- DeskTriggerWindow's and DeskTriggerEvent's javadoc.
--
--   desk_trigger_window   one collection window of one project. OPEN while it admits; OWED once it
--                         closed and its message is rendered (`message`, `closed_at`) but the desk
--                         has not taken it; DELIVERED or QUEUED once the desk's daemon accepted it
--                         (`accepted_at`, `command_id`, `queue_position`); FAILED when it never will
--                         (a 400, or still owed past qits.projects.desk-triggers.give-up). `attempts`
--                         and `last_*` say how the delivery has gone so far.
--   desk_trigger_event    one line of a window's message: one item, from one source.
--
-- AT MOST ONE OPEN WINDOW PER PROJECT is the partial unique index, and it is what admit races on: an
-- insert of a second OPEN row does nothing, and the loser re-reads the winner's.
--
-- A REPEAT IS ONE LINE: the unique key (window_id, source, item_ref, label) is the insert's
-- `on conflict do nothing`, so a redelivered frame, or the same item reached twice inside one
-- window, admits nothing new.
--
-- project_id is varchar(255), not uuid: it references Project(id), which is varchar(255) (V1), and a
-- foreign key needs the same type on both ends (V37's reason). The cascade drops a project's windows
-- with it, and a window's events with the window.
--
-- No causation_id: both entities are @Uncaused -- bookkeeping written by a bus consumer and a sweep,
-- where no causation scope stands.
create table desk_trigger_window (
  id              uuid primary key,
  project_id      varchar(255) not null references project(id) on delete cascade,
  opened_at       timestamptz not null,
  closes_at       timestamptz not null,
  state           text not null,
  message         text,
  closed_at       timestamptz,
  attempts        int not null default 0,
  last_attempt_at timestamptz,
  last_status     int,
  last_detail     text,
  accepted_at     timestamptz,
  command_id      text,
  queue_position  int
);
create unique index desk_trigger_window_one_open on desk_trigger_window(project_id) where state = 'OPEN';
create index desk_trigger_window_project_opened on desk_trigger_window(project_id, opened_at desc);

create table desk_trigger_event (
  id          uuid primary key,
  window_id   uuid not null references desk_trigger_window(id) on delete cascade,
  source      text not null,
  item_ref    text not null,
  item_id     text,
  label       text,
  cause       text,
  event_id    uuid not null,
  occurred_at timestamptz not null,
  admitted_at timestamptz not null,
  unique (window_id, source, item_ref, label)
);
