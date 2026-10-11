-- Why a DROPPED work item was closed (qits-248).
--
-- DROPPED says one thing — this work will not be done — and until this file the reason survived
-- only as prose on the thread: a duplicate, a ticket another one fixed, a subject that went away, a
-- probe and a decision against all read as the same word. The outcome says which, and three of the
-- six name the item that answers for it through the existing superseded_by_entity_id (V9), which
-- is permitted on every archetype from now on rather than on the epic alone.
--
-- WHERE THE RULES LIVE. The database closes the vocabulary and states the one invariant a row can
-- carry on its own: an outcome exists only on a DROPPED row. Everything about a MOVE — every move
-- into DROPPED carries an outcome, three outcomes require supersededBy and three refuse it, a
-- reopen clears both — is the move's (WorkEntityService.move, EntityTransitionService), because a
-- constraint cannot tell a legacy row from a new drop.
--
-- NULLABLE, NO BACKFILL. The rows DROPPED before this file keep a null outcome, which reads as
-- "unrecorded": the reason is in their threads, and a migration cannot know which of them was a
-- duplicate and which a decision.

alter table entity add column outcome text;

alter table entity add constraint ck_entity_outcome check (outcome is null or outcome in
    ('DUPLICATE', 'RESOLVED_ELSEWHERE', 'SUPERSEDED', 'OBSOLETE', 'INVALID', 'WONT_DO'));

alter table entity add constraint ck_entity_outcome_dropped check
    (outcome is null or status = 'DROPPED');

comment on column entity.outcome is
    'Why a DROPPED row was closed: DUPLICATE, RESOLVED_ELSEWHERE or SUPERSEDED (each naming the item'
    ' in superseded_by_entity_id), OBSOLETE, INVALID or WONT_DO (naming nothing). Set only by a move'
    ' into DROPPED, which must carry one; cleared by the reopen. Null on every other status, and on'
    ' rows dropped before V30 — "unrecorded", never backfilled.';

-- V9 wrote "the successor draft a superseded epic spawned"; since V30 it is any archetype's.
comment on column entity.superseded_by_entity_id is
    'The item a DROPPED row names as what answers for it: the duplicate it repeats, the item whose'
    ' change resolved it, or the successor that replaced it (an epic''s supersede writes its'
    ' successor draft here). Written with the drop, on every archetype; cleared by the reopen.';
