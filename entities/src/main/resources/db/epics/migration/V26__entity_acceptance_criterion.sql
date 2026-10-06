-- Acceptance criteria (qits-887, qits-934): a list of short Markdown statements an epic or a ticket
-- is accepted against, the first quality gate on entering REFINED and READY_FOR_DEV.
--
-- WHY A TABLE OF ORDERED ROWS, and not a converter or an array column: a list is ordered rows here,
-- as the campaign criteria are (V19), and an AttributeConverter/UserType is a native-image risk
-- (HibernateBeanContainerWarmup warns about it). The rows are WorkEntity's element collection,
-- so every reader of the row reads its criteria with it; a write replaces the list as a whole.
--
-- WHY THE KEY IS (entity_id, position): an item has no identity of its own — nothing names one, a
-- write restates the whole list, and the position is what Hibernate's ordered collection keys an
-- item by. The pair is the primary key, which is also the uniqueness the list needs.
--
-- Which kinds may carry them (EPIC and TICKET) and the item rules (no line break, at most one '.',
-- fewer than 20 whitespace characters) are the service's (Archetypes, AcceptanceCriteria): the
-- database holds non-blank text and nothing more.
--
-- NO BACKFILL: an entity without criteria has none, and the gate is what asks for them.
create table entity_acceptance_criterion (
    entity_id varchar(255) not null,
    position  integer      not null,
    text      text         not null,
    constraint pk_entity_acceptance_criterion primary key (entity_id, position),
    constraint fk_entity_acceptance_criterion_entity foreign key (entity_id)
        references entity (id) on delete cascade,
    constraint ck_entity_acceptance_criterion_text check (length(btrim(text)) > 0)
);
