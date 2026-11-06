-- Retiring campaigns, milestones and dependsOn (epic qits-1899, qits-1908): ordering between work
-- items is one mechanism now, work_need (V40). The code that read and wrote the campaign rows, the
-- campaign memberships and their run record, the five milestone tables, campaign_start and
-- entity.depends_on_entity_id is deleted in this release, and every one of them is UNMAPPED here.
--
-- WHAT THIS DOES: the data that only the retired model could read goes, and the vocabularies stop
-- admitting it, so nothing can write a campaign again.
--
--   * The CAMPAIGN rows go (15 on the estate when this was written, every one DONE or DROPPED, none
--     inspired by an idea). Every foreign key into entity either cascades — entity_membership on
--     both ends (so the campaign edges go with their campaign), campaign_start, entity_comment,
--     dossier_page, dossier_asset, entity_tag, entity_acceptance_criterion, work_milestone,
--     work_need — or sets null (superseded_by, depends_on_entity_id, related_to), so one delete
--     reaches all of it. The CAMPAIGN edges are deleted explicitly first anyway: an edge is the one
--     row a reader of this file would otherwise have to prove reaches the cascade.
--   * ck_entity_archetype and ck_entity_membership_kind are restated without CAMPAIGN (the lists
--     are V37's and V18's, less the one word), and uq_entity_membership_campaign_child goes with the
--     kind it indexed.
--
-- WHAT STAYS, ON PURPOSE: ck_audit_entity_type keeps CAMPAIGN. The audit log is history, and the
-- rows written about the campaigns still read (AuditEntityType.CAMPAIGN is kept for them).
--
-- WHY NOTHING IS DROPPED YET (AGENTS.md, "Expand/contract"): a column or a table is dropped one
-- release after its last reader, never in the release that unmaps it, because a rolling deploy
-- drains the old instance against the new schema. The old instance still selects
-- entity.depends_on_entity_id on every entity read, reads the milestone tables on every work read
-- and runs the campaign sweep. So the drops are the NEXT migration, in the release after this one:
--
--   drop table campaign_membership_milestone, work_milestone, milestone_criterion, milestone_group,
--     milestone, campaign_start;
--   alter table entity_membership drop constraint ck_entity_membership_run_record_campaign_only,
--     drop column claimed_at, ... drop column dispatch_error;   -- V19's nine run-record columns
--   alter table entity drop column depends_on_entity_id;        -- fk_entity_depends_on with it
--
-- Until then they are written by nobody. Every unmapped column is nullable or has a default
-- (joined_running: not null default false), so an insert from this release that names none of them
-- still lands, and ck_entity_membership_run_record_campaign_only holds trivially: every edge left is
-- STRUCTURAL with an empty run record. fk_entity_depends_on keeps setting a pointer to a deleted row
-- null, which is all the in-service clear the delete used to make did.
delete from entity_membership where kind = 'CAMPAIGN';
delete from entity where archetype = 'CAMPAIGN';

drop index uq_entity_membership_campaign_child;

alter table entity_membership drop constraint ck_entity_membership_kind;
alter table entity_membership add constraint ck_entity_membership_kind
    check (kind in ('STRUCTURAL'));

alter table entity drop constraint ck_entity_archetype;
alter table entity add constraint ck_entity_archetype
    check (archetype in ('EPIC', 'TICKET', 'FEATURE', 'TASK', 'ISSUE'));

comment on column entity_membership.kind is
    'STRUCTURAL: the tree, one per child, id = child id. The CAMPAIGN kind was retired by qits-1899 (V41).';
comment on column entity.depends_on_entity_id is
    'Retired by qits-1899 (V41): read and written by nobody; dropped by the next migration.';
