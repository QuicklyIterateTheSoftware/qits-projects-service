-- One lifecycle for every archetype that has one (qits-392). EpicStatus is deleted, and the
-- ticket's six words — REPORTED, REFINED, IMPLEMENTED, VERIFIED, DONE, DROPPED, walked reversibly —
-- are the lifecycle of an epic too. This file moves every epic row onto those words and then
-- narrows ck_entity_status from the ten-word union V14 left to the six that survive.
--
-- ORDER IS THE WHOLE RISK. The rows are rewritten FIRST and the constraint narrowed SECOND: narrow
-- first and every REFINING row in the table violates the new constraint the moment it is added,
-- which fails the migration and refuses boot. Both halves are in one file so they land in one
-- Flyway transaction and cannot be half-applied.

-- ---------------------------------------------------------------------------------------------
-- THE BACKFILL: four words move, one stays
-- ---------------------------------------------------------------------------------------------
-- REFINING       -> REPORTED   the subject has been raised and refinement is the phase that runs
-- IMPLEMENTATION -> REFINED    the brief exists and implementation is the phase that runs
-- IMPLEMENTED    -> IMPLEMENTED the same word, already shared — untouched
-- ABANDONED      -> DROPPED    "a decision was taken not to do this work", V14's own definition
-- SUPERSEDED     -> DROPPED    and superseded_by_entity_id is left EXACTLY as it is
--
-- SUPERSEDED loses nothing by folding. It said two things at once — this work will not be done,
-- and here is what replaced it — and only the first is a status. The second is already the
-- superseded_by_entity_id column, which this file does not touch: it is what still says a DROPPED
-- row was superseded rather than abandoned.
--
-- Guarded by archetype = 'EPIC' although only epics ever held these words (the registry refused
-- them on anything else): the rewrite says whose vocabulary it is retiring, and a stray word on
-- another kind would rather fail the constraint below loudly than be silently translated.
-- No row is inserted or deleted.
update entity set status = 'REPORTED' where archetype = 'EPIC' and status = 'REFINING';
update entity set status = 'REFINED'  where archetype = 'EPIC' and status = 'IMPLEMENTATION';
update entity set status = 'DROPPED'  where archetype = 'EPIC' and status = 'ABANDONED';
update entity set status = 'DROPPED'  where archetype = 'EPIC' and status = 'SUPERSEDED';

-- ---------------------------------------------------------------------------------------------
-- THE VOCABULARY NARROWS TO SIX WORDS
-- ---------------------------------------------------------------------------------------------
-- Dropped and re-added named, exactly as V14 did, so the next change is an ordinary drop rather
-- than a round of guessing what postgres derived.
--
-- It is no longer a union of two lifecycles, because there is only one: EntityStatus' six words,
-- the same set control/Archetypes declares for EPIC and TICKET alike. Null stays legal — a feature
-- and a task hold no status.
alter table entity drop constraint ck_entity_status;
alter table entity add constraint ck_entity_status check (status is null or status in
    ('REPORTED', 'REFINED', 'IMPLEMENTED', 'VERIFIED', 'DONE', 'DROPPED'));
