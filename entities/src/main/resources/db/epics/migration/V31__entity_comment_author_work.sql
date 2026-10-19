-- The work item of the agent that wrote a comment (qits-1448).
--
-- Since qits-1213 an agent's bearer carries an `agent` claim, and a comment written with it is
-- authored by that agent id. qits-workspaces knows which work item the agent works on; that is
-- resolved ONCE, at write time, and stored here, so a thread read costs no cross-service hop and the
-- wire can say "qits-1402's agent" (authorAgentOf, rendered at read time from this id).
--
-- NO FOREIGN KEY: the agent's work item can be deleted without touching the remark, and a missing
-- row reads as null on the wire. NO BACKFILL: comments written before this file have no agent claim
-- to resolve, and keep their author as it was.

alter table entity_comment add column author_work_id varchar(255);

comment on column entity_comment.author_work_id is
  'The work item of the agent that wrote the comment, resolved from qits-workspaces at write time; null for a person, a machine, or an agent that could not be resolved.';
