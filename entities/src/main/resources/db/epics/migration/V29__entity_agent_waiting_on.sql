-- An agent session waiting on a DECLARED wait (qits-1153).
--
-- An agent can declare what it waits on — a release request, a work item's status, a CI run — and
-- the platform wakes it when that happens (qits-workspaces holds the waits). While its session
-- waits on one, it is not waiting for a person, so the derived block (V28) must not stand. The
-- session's frame names the wait's label, and it is kept here beside the wait it qualifies: every
-- answer then reads "waiting on <label>" instead of BLOCKED (EntityBlockState).
--
-- NULLABLE, NO BACKFILL: no session has declared a wait before this file.

alter table entity add column agent_waiting_on text;

comment on column entity.agent_waiting_on is
    'The label of the declared wait the agent session is waiting on, or null. Set with'
    ' agent_waiting_since by POST /work/{id}/agent-waiting (waitingOn); while set, the derived'
    ' block does not stand. Cleared with agent_waiting_since.';
