-- Close the open pieces of DONE work (qits-1685).
--
-- Until this release an epic's move to DONE moved none of its features and tasks, so a person who
-- accepted an epic left its plan at VERIFIED or IMPLEMENTED: open work below a closed epic, which
-- nobody can finish because DONE has no exits. From this release WorkEntityService closes every
-- open descendant in the same transaction as the move to DONE. This file closes the rows the old
-- code left behind, so the estate holds no half state.
--
-- THE BACKFILL. Every row with a DONE ancestor (STRUCTURAL edges only: a campaign gathers work, it
-- does not contain it) that is neither DONE nor DROPPED becomes DONE. DROPPED stays: it was decided
-- against. The depth bound is a belt against a malformed edge; the tree is three levels deep.
--
-- updated_at and the audit log are left alone, as V24 did: nobody moved these rows through the
-- service, the shape of the data is corrected under them.
with recursive below_done (id, hops) as (
    select edge.child_id, 1
      from entity_membership edge
      join entity parent on parent.id = edge.parent_id
     where edge.kind = 'STRUCTURAL'
       and parent.status = 'DONE'
    union all
    select edge.child_id, below_done.hops + 1
      from below_done
      join entity_membership edge on edge.parent_id = below_done.id and edge.kind = 'STRUCTURAL'
     where below_done.hops < 8
)
update entity piece
   set status = 'DONE'
 where piece.id in (select id from below_done)
   and piece.status not in ('DONE', 'DROPPED');
