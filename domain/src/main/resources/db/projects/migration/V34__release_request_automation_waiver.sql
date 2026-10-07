-- A PERSON'S WAIVER OF THE AUTOMATIONS GATE FOR ONE FOLD OF A RELEASE REQUEST (epic qits-978).
--
-- A release request holds until every release-request automation that applies (estate pins,
-- screenshot baselines, ...) is fresh for its merged sha. qits-maintenance runs them, and when
-- qits-maintenance itself is broken its own fix would otherwise hold behind the gate it is meant to
-- repair. A waiver is the escape: a person says "release this fold without them", once, durably.
--
-- A WAIVER IS ABOUT A SHA, exactly as release_request_approval's decision is: merged_sha is the
-- fold the person waived, and a re-fold moves the request to a sha the waiver does not name, so the
-- gate holds again for content nobody waived -- with no column to clear. The gate asks for a row at
-- the request's CURRENT merged_sha; older rows are history.
--
-- INSERT-ONLY, and NO FOREIGN KEY TO release_request, for release_request_approval's reasons: the
-- row is the record that a named person let a named sha through without its automations, and it
-- must survive the request row.
create table release_request_automation_waiver (
  id           varchar(255) not null,
  request_id   varchar(255) not null,
  -- The request's merged_sha the waiver is about. Not null: a waiver with no sha would be a
  -- standing waiver of whatever the request becomes next.
  merged_sha   varchar(255) not null,
  -- Who waived -- a verified person, never a machine.
  actor        varchar(255) not null,
  -- Why. Required at the door: a waiver nobody can explain later is the one worth refusing.
  reason       text         not null,
  waived_at    timestamp with time zone not null,
  causation_id uuid,
  constraint PK_release_request_automation_waiver primary key (id)
);

-- The gate's only query shape: the newest waiver for one request at one sha.
create index IX_release_request_automation_waiver_current
  on release_request_automation_waiver (request_id, merged_sha, waived_at desc);
