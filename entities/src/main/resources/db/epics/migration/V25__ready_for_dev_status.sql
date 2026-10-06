-- READY_FOR_DEV, between REFINED and IMPLEMENTING (qits-887): a person scheduled the work. REFINED
-- says the entity says what to do; it no longer starts the implement phase by itself, but waits for
-- a person to schedule it, and implement runs from READY_FOR_DEV. Scheduling can be taken back
-- (READY_FOR_DEV -> REFINED) until the work starts; there is no READY_FOR_DEV -> REPORTED, and
-- IMPLEMENTING has no move back at all. The moves themselves are EntityStateMachine's.
--
-- NO BACKFILL, and that is the rule rather than an omission (owner, 2026-10-05): every existing
-- REFINED row stays REFINED and must be scheduled by a person before it can be dispatched. Moving
-- them here would be the platform claiming a person's decision nobody took. Rows already
-- IMPLEMENTING or further were started and stay where they are.

-- The constraint widens by one word, dropped and re-added by name as V15, V22, V23 and V24 did.
-- Not null stays: every archetype holds a status since V24.
alter table entity drop constraint ck_entity_status;
alter table entity add constraint ck_entity_status check (status is not null and status in
    ('REPORTED', 'REFINED', 'READY_FOR_DEV', 'IMPLEMENTING', 'IMPLEMENTED', 'VERIFYING', 'VERIFIED',
     'DONE', 'DROPPED'));

comment on column entity.status is
    'The one lifecycle''s word, on every archetype (features and tasks since V24, qits-763; READY_FOR_DEV since V25, qits-887). Never null.';
