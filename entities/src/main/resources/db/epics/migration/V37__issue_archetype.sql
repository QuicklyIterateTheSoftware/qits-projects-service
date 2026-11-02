-- ISSUE joins the archetype vocabulary (qits-1819): one thing the delivery desk cannot resolve on
-- its own, for a person to review. A root that stands alone, with no branch, workspace or phase.
--
-- Its statuses are its own: OPEN, then RESOLVED or OBSOLETE. control/Archetypes keeps them apart
-- from the lifecycle of every other kind; ck_entity_status only spells all the words.
--
-- related_to is the one work item of the same project an issue relates to. The service judges the
-- project; a constraint cannot read the row it points at.
--
-- No row is rewritten: every word the constraints permitted before they still permit.

alter table entity drop constraint ck_entity_archetype;
alter table entity add constraint ck_entity_archetype
    check (archetype in ('CAMPAIGN', 'EPIC', 'TICKET', 'FEATURE', 'TASK', 'ISSUE'));

alter table entity drop constraint ck_entity_status;
alter table entity add constraint ck_entity_status check (status is not null and status in
    ('REPORTED', 'REFINED', 'READY_FOR_DEV', 'IMPLEMENTING', 'IMPLEMENTED', 'VERIFYING', 'VERIFIED',
     'DONE', 'DROPPED', 'OPEN', 'RESOLVED', 'OBSOLETE'));

alter table auditentry drop constraint ck_audit_entity_type;
alter table auditentry add constraint ck_audit_entity_type
    check (entity_type in ('CAMPAIGN','EPIC','FEATURE','TASK','TICKET','COMMENT','DOSSIER_PAGE','ISSUE'));

alter table entity add column related_to varchar(255);

alter table entity
    add constraint fk_entity_related_to foreign key (related_to) references entity (id) on delete set null;

create index idx_entity_related_to on entity (related_to);

comment on column entity.related_to is
    'The one work item of the same project an ISSUE relates to; null for none and on every other archetype.';
