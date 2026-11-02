-- Tags on work entities (qits-634): a closed vocabulary of facts about the work that are neither its
-- kind nor where it stands. The first and only one is RESERVED_FOR_HUMAN — the work cannot be
-- completed by an agent, a person has to do it — which the dispatch refuses and the phase advance
-- skips.
--
-- WHY A TABLE OF ROWS, and not an array column or a converter: a set is rows here, as the acceptance
-- criteria are a list of rows (V26), and an AttributeConverter/UserType is a native-image risk
-- (HibernateBeanContainerWarmup warns about it). The rows are WorkEntity's element collection, so
-- every reader of the row reads its tags with it; a write replaces the set as a whole.
--
-- WHY THE KEY IS (entity_id, tag): a tag has no identity beyond its word, and an entity carries a
-- word at most once. The pair is the primary key, which is also the uniqueness a set needs.
--
-- WHY A CHECK CONSTRAINT, unlike entity.ticket_type: a tag is something the platform acts on, so a
-- word nobody wrote code for would be a label that silently does nothing. The vocabulary is closed
-- in WorkTag and closed here too; a new tag widens ck_entity_tag_tag in the migration that adds it.
--
-- NOT A REGISTRY PROPERTY, for entity.blocked's reason: transition_entities is full-state PUT over
-- the registry's properties, so a property there would be cleared by every reshape that did not
-- restate it. A transition never touches this table, and neither does a status move.
--
-- NO BACKFILL: an entity without tags has none.
create table entity_tag (
    entity_id varchar(255) not null,
    tag       varchar(32)  not null,
    constraint pk_entity_tag primary key (entity_id, tag),
    constraint fk_entity_tag_entity foreign key (entity_id)
        references entity (id) on delete cascade,
    constraint ck_entity_tag_tag check (tag in ('RESERVED_FOR_HUMAN'))
);

comment on table entity_tag is
    'The tags of a work entity (qits-634), from the closed vocabulary WorkTag. Written whole by PATCH'
    ' /work/{qualifiedId} and at create, at every status; never touched by a transition.';
