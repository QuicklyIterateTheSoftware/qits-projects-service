-- The ticket lifecycle gains one word and the ticket gains one flag. Two changes, one file,
-- because they are the same ticket's two halves: an exit for work that is dropped, and a marker
-- for work that is stuck.
--
-- WHY THE WORD IS A STATUS AND THE FLAG IS NOT. A status says what has been ACHIEVED (V7's
-- reading, unchanged). DROPPED says a decision was taken not to do the work — nothing was
-- implemented, nothing was verified, and nothing is expected to be — which is an outcome and
-- therefore a status. "Blocked" is not an outcome: it says the phase that is running cannot
-- finish right now, which is a fact about the moment rather than about the work, and the phase to
-- resume must survive it. A BLOCKED status would overwrite REFINED and lose exactly that. So the
-- one is a word in the vocabulary and the other is a boolean beside it, and a ticket that is
-- blocked still holds the status whose phase is blocked.
--
-- WHY THIS FILE HAS TO EXIST AT ALL. ck_entity_status spells the vocabulary and control/Archetypes
-- spells the rule — the split V9 made and docs/unified-entity-model.md argues. So a lifecycle that
-- gains a word is two changes rather than one: the enum, and a migration that widens this
-- constraint to match it. Without this file TicketStatus.DROPPED exists in Java and every attempt
-- to store it is refused by the database.

-- ---------------------------------------------------------------------------------------------
-- THE VOCABULARY WIDENS BY ONE WORD
-- ---------------------------------------------------------------------------------------------
-- Dropped and re-added named, exactly as V4, V7 and V12 each had to do for a constraint of their
-- own, so the next widening is an ordinary drop rather than a round of guessing what postgres
-- derived. V9 wrote this one named, so the drop names it directly.
--
-- It stays the UNION of both lifecycles' words and is deliberately NOT narrowed by archetype: a
-- check constraint has no way to say "these ones when the archetype is EPIC" without becoming a
-- second place the vocabulary is written down. The nine words V9 spelled are carried over
-- unchanged and DROPPED is appended — EpicStatus' five, TicketStatus' six, overlapping on
-- IMPLEMENTED.
--
-- No row is rewritten. DROPPED is reached by a transition somebody makes, never by a backfill: a
-- migration cannot know which of today's tickets somebody decided against.
alter table entity drop constraint ck_entity_status;
alter table entity add constraint ck_entity_status check (status is null or status in
    ('REFINING', 'IMPLEMENTATION', 'IMPLEMENTED', 'SUPERSEDED', 'ABANDONED',
     'REPORTED', 'REFINED', 'VERIFIED', 'DONE', 'DROPPED'));

-- ---------------------------------------------------------------------------------------------
-- THE FLAG
-- ---------------------------------------------------------------------------------------------
-- `not null default false` and the default STAYS, which is where this differs from V8 of the
-- projects lineage (`gating`, which drops its default so a writer that forgets the value fails
-- loudly). The two want opposite things. `gating` is a fact the writer is told by an event and
-- must carry; this is a flag whose absence is its ordinary state — every row that exists is
-- unblocked, every row created hereafter starts unblocked, and "nobody has said this is blocked"
-- is the honest reading of a missing value rather than a writer's omission. Keeping the default
-- is also what lets the entity say nothing about it at insert.
--
-- NO BACKFILL, and there is nothing to backfill: the column is born false on every existing row,
-- which is what was true of all of them before the column existed.
--
-- IT IS NOT AN ARCHETYPE PROPERTY, deliberately, and this column comment is the only place in the
-- schema that says so. control/Archetypes declares what each kind requires and permits, and
-- `transition_entities` is a full-state PUT over exactly those declarations — an omitted property
-- is CLEARED. A registry property here would therefore silently unblock every row of any reshape
-- that did not restate it, which is a write nobody asked for arriving through a door about
-- something else. created_at and updated_at are the precedent: real columns the registry is
-- indifferent to.
alter table entity add column blocked boolean not null default false;

comment on column entity.blocked is
    'TICKET only: the phase its status starts cannot finish right now. Not a status — the status'
    ' is what has been achieved and is the phase to resume. Cleared by every transition, because a'
    ' block is scoped to the phase it blocks.';
