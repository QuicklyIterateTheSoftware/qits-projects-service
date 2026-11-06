-- What a work item needs (epic qits-1899, qits-1903): "item B needs item A at a status", one row per
-- (waiting item, needed item), the needed status beside it. It replaces the three ordering
-- mechanisms the epic retires — dependsOn, campaign ordering and milestones — with one, item to
-- item. A condition that is not ordinary work becomes a work item tagged CONDITION (widened below),
-- and is needed like any other.
--
-- WHY A SET OF ROWS, and not a column or a JSON array: an item may need several others, the
-- dispatch gate and the event both read the edge from the NEEDED end ("who waits on the item that
-- just moved?", ix_work_need_needed), and a cascade on either foreign key is what keeps a deleted
-- item from leaving a need pointing at nothing. Written whole by the work doors (create, child
-- create, PATCH), at every status, outside every scope freeze — like entity_tag (V36), and for its
-- reason it is NOT A REGISTRY PROPERTY: the whole-state transition would clear it on every reshape
-- that did not restate it. A transition never touches this table.
--
-- WHY NO LATCH: whether a need is met is read off the needed item's status at the time it is
-- asked (NeedRule.reached), never stored. A milestone latched its date and then needed an unmet
-- event to un-latch it; a need that goes unmet again (the needed item moved back) simply reads
-- unmet, and only the flip to met is announced (WorkNeedMet), because that is what a desk waits on.
--
-- WHY THE STATUS IS CHECKED HERE: the walk's eight words and DROPPED are the only targets
-- NeedRule.reached can answer; an issue's OPEN/RESOLVED/OBSOLETE are refused because an issue is
-- never needed. causation_id is the lineage's uniform column (V2), as work_milestone carries it.
--
-- NO BACKFILL: nothing open uses dependsOn or a milestone for ordering (owner, 2026-11-06), so no
-- existing row has a need to invent. entity.depends_on_entity_id stays as stored, read-only, until
-- qits-1908 drops it.
create table work_need (
    entity_id        varchar(255) not null,
    needed_entity_id varchar(255) not null,
    status           varchar(32)  not null,
    causation_id     uuid,
    created_at       timestamp with time zone not null default now(),
    constraint pk_work_need primary key (entity_id, needed_entity_id),
    constraint fk_work_need_entity foreign key (entity_id) references entity (id) on delete cascade,
    constraint fk_work_need_needed foreign key (needed_entity_id) references entity (id) on delete cascade,
    constraint ck_work_need_not_self check (entity_id <> needed_entity_id),
    constraint ck_work_need_status check (status in ('REPORTED','REFINED','READY_FOR_DEV','IMPLEMENTING','IMPLEMENTED','VERIFYING','VERIFIED','DONE','DROPPED'))
);
create index ix_work_need_needed on work_need (needed_entity_id);

comment on table work_need is
    'What a work item needs (qits-1903): entity_id waits until needed_entity_id reaches status.'
    ' Written whole by POST/PATCH /work at every status; never touched by a transition.';

-- CONDITION (qits-1903): a condition other work waits for, not work of its own. No agent is
-- dispatched onto it; a person or an agent moves its status.
alter table entity_tag drop constraint ck_entity_tag_tag;
alter table entity_tag add constraint ck_entity_tag_tag check (tag in ('RESERVED_FOR_HUMAN','CONDITION'));
