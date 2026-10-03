-- IMPLEMENTING, between REFINED and IMPLEMENTED (qits-749), and the implementing marker of a feature
-- or a task beside the implemented one.
--
-- THE READING THIS REPLACES. V7's header (and EntityStatus until this release) said a status names
-- what has been ACHIEVED and never what is being done -- no IN_PROGRESS, ever -- because a status a
-- person sets by hand to say "somebody is on it" is stale the moment it is written. V7 is applied
-- history and is never edited (a comment changes its checksum and refuses boot), so the new reading
-- is stated here instead: a status names what has been achieved OR a fact the platform recorded.
-- IMPLEMENTING is the second kind. The platform sets it at the dispatch press (or an agent's first
-- mark_task_implementing), so nobody keeps it current, and what it records -- an implementation was
-- started -- does not go stale. The implement phase leaves it by the move to IMPLEMENTED it always
-- made. It is skippable: REFINED -> IMPLEMENTED stays a legal move (a SKIP in EntityStateMachine).
--
-- No backfill: every existing row stays where it is. A REFINED row being worked on today reads as
-- REFINED until its next dispatch or its move to IMPLEMENTED.

-- The constraint widens by one word, dropped and re-added by name as V15 did. Null stays legal: a
-- feature and a task hold no status.
alter table entity drop constraint ck_entity_status;
alter table entity add constraint ck_entity_status check (status is null or status in
    ('REPORTED', 'REFINED', 'IMPLEMENTING', 'IMPLEMENTED', 'VERIFIED', 'DONE', 'DROPPED'));

-- The implementing marker: when a feature's or a task's implementation was started. Nullable and
-- never backfilled -- a marker nobody stamped is a fact nobody recorded. Kept as history once
-- implemented_at is set; consumers rank implemented_at over it.
alter table entity add column implementing_at timestamptz;
