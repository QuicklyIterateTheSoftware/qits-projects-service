-- A REFINEMENT NAMES AN ENTITY OF ANY ARCHETYPE, NOT AN EPIC (qits-395).
--
-- The refine action opens a room on any entity with a lifecycle — an epic or a ticket — so the key
-- V4 called epic_id becomes entity_id. It is a RENAME and nothing else: entity ids are one id space
-- across archetypes (the epics lineage's `entity` table, V9 onward), so every value already standing
-- in the column is the id of the epic it names and stays exactly as it is. The unique constraint
-- rides the rename — one room per entity is still the rule, and still what makes find-or-create
-- race-safe — and is renamed with it so its name does not lie.
--
-- NO ARCHETYPE COLUMN, deliberately. The entity lives in another database; the only reader that
-- needs its archetype is the open, which reads the entity live anyway (status gate, slug), and a
-- copy here would go stale the day `transition_entities` re-archetypes a row. The service refuses a
-- feature or a task with a 409 before any row is written.
alter table refinement rename column epic_id to entity_id;
alter table refinement rename constraint refinement_epic_id_key to refinement_entity_id_key;

comment on table refinement is 'One entity''s refinement container (an epic or a ticket), hosted by this service.';
comment on column refinement.entity_id is 'The refined entity (epics module''s database) — a key, not a relation. Was epic_id until V29.';
