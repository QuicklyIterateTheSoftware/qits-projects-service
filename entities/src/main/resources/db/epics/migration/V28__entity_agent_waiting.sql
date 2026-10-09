-- Two sources of BLOCKED on one row, one effective flag (qits-895).
--
-- WHAT `blocked` IS NOW. The EXPLICIT block: somebody — a person, or an agent through block_entity —
-- said the phase the status starts cannot finish right now. Nothing about it changes: every gate
-- that reads it (the dispatch refusal, the phase advance, the campaign executor's claim and sweep)
-- keeps reading exactly this column, and only this column.
--
-- WHAT IS NEW BESIDE IT. The DERIVED block: the agent session working the entity ended its turn with
-- nothing in flight and is waiting for a person. Its source is a session, not a statement, so it is
-- held in columns of its own and never written into `blocked` — a derived block must never refuse a
-- dispatch or withhold a phase turn, and a gate that read one column for both could not tell them
-- apart. The two are OR'd at read time (EntityBlockState in the entities module), and only there.
--
-- WHY THE EXPLICIT REASON IS A COLUMN AFTER ALL. V14's argument stands — the thread is where the
-- blocker is said, with its author and its time, and it is still written there first. What changed
-- is that every answer now says WHY an entity is blocked beside the flag (`blockSource`,
-- `blockReason`, `blockedBy`), a listing included, and a listing cannot read every thread. So the
-- door that writes the comment also keeps its last sentence and author here; both are cleared by an
-- unblock and by every transition, with the flag they explain.
--
-- NULLABLE, NO BACKFILL. A row blocked before this file has no stored reason, and none is invented:
-- its thread still says it. Every row starts with no agent waiting, which is what was true of all
-- of them.

alter table entity add column blocked_by text;
alter table entity add column blocked_reason text;

alter table entity add column agent_waiting_since timestamptz;
alter table entity add column agent_waiting_cause text;
alter table entity add column agent_activity_at timestamptz;

-- The sweep (AgentWaitingSweep) asks for the rows whose wait crossed the debounce since its last
-- pass. Almost every row has no agent waiting, so a partial index keeps that question small.
create index idx_entity_agent_waiting_since on entity (agent_waiting_since)
    where agent_waiting_since is not null;

-- V14 wrote "TICKET only"; qits-592 widened the block to epics and campaigns and the comment stayed.
comment on column entity.blocked is
    'TICKET, EPIC and CAMPAIGN: the EXPLICIT block — somebody said the phase its status starts cannot'
    ' finish right now. Not a status — the status is what has been achieved and is the phase to'
    ' resume. Cleared by every transition, because a block is scoped to the phase it blocks. The only'
    ' block a gate reads; the effective flag an answer carries is this OR the derived agent wait.';

comment on column entity.blocked_by is
    'Who set the explicit block (blocked); null while it is not set.';

comment on column entity.blocked_reason is
    'The stated blocker of the explicit block, as it was said on the thread; null while it is not set'
    ' and on rows blocked before V28.';

comment on column entity.agent_waiting_since is
    'When the agent session working the entity began waiting for a person, or null. The derived'
    ' block is effective once it has stood for the debounce (qits.projects.agent-waiting.debounce).'
    ' Written only by POST /work/{id}/agent-waiting; cleared by its waiting=false, by an explicit'
    ' unblock and by every transition.';

comment on column entity.agent_waiting_cause is
    'Why the session says it is waiting (the hook that reported it), or null.';

comment on column entity.agent_activity_at is
    'The newest session frame applied, or the last transition. A frame older than this, less a skew'
    ' tolerance, is ignored, so a frame from before a transition cannot re-derive at the new status.';
