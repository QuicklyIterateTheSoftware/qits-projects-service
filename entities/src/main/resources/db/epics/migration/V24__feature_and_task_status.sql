-- Features and tasks hold the one lifecycle (qits-763). Until now a feature and a task had no status
-- of their own: they carried the two markers (implementing_at, implemented_at, V22) and took their
-- epic's status for everything else, so a task reached VERIFIED only when its epic did and every
-- sibling moved with it. From this release on control/Archetypes declares the same eight words for
-- them as for an epic and a ticket, the writer mints them REPORTED, and the column is not null.
--
-- THE BACKFILL. Every FEATURE and TASK row is given the status its markers and its epic imply, the
-- first rule that applies winning:
--
--   1. implemented_at set   -> IMPLEMENTED. The marker is the record that the work was done.
--   2. implementing_at set  -> IMPLEMENTING. The marker is the record that the work was started.
--   3. otherwise the status of the NEAREST STRUCTURAL ANCESTOR that has one -- for a feature its
--      epic, for a task its epic too, since a feature held none before this file -- capped at
--      REFINED when that ancestor is further on the walk: a piece nobody marked was not started,
--      whatever its epic has reached since. REPORTED stays REPORTED (the plan is a draft) and
--      DROPPED stays DROPPED (the plan was decided against, and so was every piece of it).
--   4. no ancestor with a status -> REPORTED, the status every new row is minted with.
--
-- The ancestor's status is read as it stood BEFORE this file: one UPDATE statement sees one
-- snapshot, so a task never inherits the status rule 1 or 2 has just given its feature. Only
-- STRUCTURAL edges are walked (V18) -- a campaign gathers work, it does not contain it -- and the
-- walk stops at the first ancestor holding a status, with a depth bound as a belt against a
-- malformed edge (the nesting rule makes a cycle impossible, and the tree is three levels deep).
--
-- updated_at is left alone on purpose: nobody edited these rows, and a board ordering by it must not
-- read every plan in the estate as touched today. No audit row either -- an audit entry records a
-- write somebody made through the service, and this is the shape of the table changing under them.
with recursive walk (id, ancestor_id, hops) as (
    select piece.id, edge.parent_id, 1
      from entity piece
      join entity_membership edge on edge.child_id = piece.id and edge.kind = 'STRUCTURAL'
     where piece.archetype in ('FEATURE', 'TASK')
       and piece.status is null
    union all
    select walk.id, edge.parent_id, walk.hops + 1
      from walk
      join entity ancestor on ancestor.id = walk.ancestor_id
      join entity_membership edge on edge.child_id = ancestor.id and edge.kind = 'STRUCTURAL'
     where ancestor.status is null
       and walk.hops < 8
),
nearest (id, status) as (
    select distinct on (walk.id) walk.id, ancestor.status
      from walk
      join entity ancestor on ancestor.id = walk.ancestor_id
     where ancestor.status is not null
     order by walk.id, walk.hops
)
update entity piece
   set status = case
           when piece.implemented_at is not null then 'IMPLEMENTED'
           when piece.implementing_at is not null then 'IMPLEMENTING'
           else coalesce(
               (select case
                           when nearest.status in ('REPORTED', 'REFINED', 'DROPPED') then nearest.status
                           else 'REFINED'
                       end
                  from nearest
                 where nearest.id = piece.id),
               'REPORTED')
       end
 where piece.archetype in ('FEATURE', 'TASK')
   and piece.status is null;

-- Every archetype holds a status now, so the constraint stops admitting null. Dropped and re-added by
-- name, as V15, V22 and V23 did; the eight words are V23's.
alter table entity drop constraint ck_entity_status;
alter table entity add constraint ck_entity_status check (status is not null and status in
    ('REPORTED', 'REFINED', 'IMPLEMENTING', 'IMPLEMENTED', 'VERIFYING', 'VERIFIED', 'DONE',
     'DROPPED'));

comment on column entity.status is
    'The one lifecycle''s word, on every archetype (features and tasks since V24, qits-763). Never null.';
