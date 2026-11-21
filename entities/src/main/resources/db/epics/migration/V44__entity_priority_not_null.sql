-- Every row holds a priority (qits-2496). V34 left it nullable, with null after every number, so
-- no ranking could put work below the unranked backlog. Now 0 is the default: a positive priority
-- sorts above unranked work, a negative one below it, and the order is plain priority descending,
-- then number, then id.
--
-- The backfill writes 0 where V34 left null; a patch that sends null now writes 0 too.
update entity set priority = 0 where priority is null;

alter table entity alter column priority set default 0;
alter table entity alter column priority set not null;

comment on column entity.priority is
    'The ranking among siblings (the roots of a project, campaigns excluded, or the children of one'
    ' structural parent): higher sorts first; 0 by default, so a negative priority sorts below'
    ' unranked work; then by number and id. Written by PATCH /work/{qualifiedId} (null writes 0)'
    ' and by the drop door PUT /work/{qualifiedId}/priority; refused on a DONE or DROPPED row.';
