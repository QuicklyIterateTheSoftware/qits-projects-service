-- Milestones (epic qits-615, qits-1293): a foreseeable "needs to be met", lifted out of the campaign
-- membership into an entity of its own. A milestone is project-scoped, holds or does not, and is
-- shared by any number of work entities (work_milestone) and campaign memberships
-- (campaign_membership_milestone).
--
-- THE SHAPE IS V19's DNF, OWNED BY THE MILESTONE. OR'd groups (milestone_group), each of AND'd
-- criteria (milestone_criterion, campaign_criterion's columns unchanged). Every level keeps its own
-- date: a criterion's satisfied_at is its latch; a group's is when it came to hold (its last
-- criterion's latch, so the max of its criteria's); the milestone's is the earliest date of its
-- holding groups. Dates mean "met since": an edit that unlatches a criterion clears the group's and
-- the milestone's date when they stop holding.
--
-- WHY THE TARGET ID IS STILL NOT A FOREIGN KEY. V19's reason holds: an ENTITY_STATUS target lives
-- in the predicate JSON, and a deleted target must leave its criterion behind as unsatisfiable.
--
-- THE EVIDENCE CHECK widens by one signature: 'CURRENT_STATE', a latch read off the target's state
-- when the criterion was written or edited (there is no campaign start any more). Migrated latches
-- keep their 'STATE_AT_START'.
--
-- WHAT THIS DOES TO EXISTING ROWS. Every CAMPAIGN membership with a condition (one or more
-- campaign_criterion_group rows) becomes ONE milestone of the campaign's project, named
-- 'Before "<member title>" in "<campaign title>"' (titles, not qualified ids: the project slug lives
-- in the projects database, which this lineage cannot read; the campaign is in the name because one
-- entity can be in two campaigns). Its OR'd groups and their criteria are copied WITH THEIR IDS,
-- latches and evidence; the dates of the groups and the milestone are computed from the copied
-- latches. The membership waits on it (campaign_membership_milestone, generated_condition = true:
-- the milestone the condition doors read and write) and the member entity is attached too
-- (work_milestone), so the work item shows what it waits on. A membership with no groups has no
-- condition and gets no milestone: no milestone is what "dispatchable at once" means now.
--
-- WHAT THIS DOES NOT DO. The campaign_criterion* tables are left exactly as they are; nothing reads
-- them after this, and a later migration (qits-1301) drops them.

create table milestone (
    id           varchar(255) primary key,
    project_id   varchar(255) not null,
    display_name text         not null,
    causation_id uuid,
    created_at   timestamptz  not null,
    satisfied_at timestamptz,
    constraint ck_milestone_display_name check (length(trim(display_name)) > 0)
);
create index idx_milestone_project on milestone (project_id, created_at);

create table milestone_group (
    id           varchar(255) primary key,
    milestone_id varchar(255) not null,
    position     integer      not null,
    causation_id uuid,
    created_at   timestamptz  not null,
    satisfied_at timestamptz,
    constraint fk_milestone_group_milestone foreign key (milestone_id)
        references milestone (id) on delete cascade
);
create index idx_milestone_group_milestone on milestone_group (milestone_id, position);

create table milestone_criterion (
    id                 varchar(255) primary key,
    group_id           varchar(255) not null,
    position           integer      not null,
    kind               varchar(32)  not null,
    predicate          text         not null,   -- canonical JSON, one shape per kind
    seeded             boolean      not null default false,
    satisfied_at       timestamptz,
    evidence_event_id  uuid,                    -- null for APPROVAL and for a state latch
    evidence_signature varchar(128),            -- the event signature, 'CURRENT_STATE' or 'STATE_AT_START'
    evidence_summary   text,
    approved_by        varchar(255),
    approval_note      text,
    causation_id       uuid,
    created_at         timestamptz  not null,
    constraint fk_milestone_criterion_group foreign key (group_id)
        references milestone_group (id) on delete cascade,
    constraint ck_milestone_criterion_kind
        check (kind in ('ENTITY_STATUS', 'DEPLOYMENT_ACTIVE', 'SCM_RELEASE', 'APPROVAL')),
    -- the latch carries its evidence: an event, a person, or (ENTITY_STATUS only) a state
    constraint ck_milestone_criterion_evidence check (
        satisfied_at is null
        or (kind = 'APPROVAL' and approved_by is not null)
        or (kind <> 'APPROVAL' and evidence_event_id is not null)
        or (kind = 'ENTITY_STATUS' and evidence_signature in ('STATE_AT_START', 'CURRENT_STATE')))
);
create index idx_milestone_criterion_group on milestone_criterion (group_id, position);
create index idx_milestone_criterion_outstanding on milestone_criterion (kind) where satisfied_at is null;

-- A work entity waits on N milestones, AND'd.
create table work_milestone (
    entity_id    varchar(255) not null,
    milestone_id varchar(255) not null,
    causation_id uuid,
    created_at   timestamptz  not null,
    constraint pk_work_milestone primary key (entity_id, milestone_id),
    constraint fk_work_milestone_entity foreign key (entity_id)
        references entity (id) on delete cascade,
    constraint fk_work_milestone_milestone foreign key (milestone_id)
        references milestone (id) on delete cascade
);
create index idx_work_milestone_milestone on work_milestone (milestone_id);

-- A campaign membership waits on N milestones, AND'd; the executor dispatches it once every one is
-- met. generated_condition marks the one milestone the membership's condition doors
-- (PUT .../members/{id}/condition, POST .../criteria/{id}/approve) read and write.
create table campaign_membership_milestone (
    membership_id       varchar(255) not null,
    milestone_id        varchar(255) not null,
    generated_condition boolean      not null default false,
    causation_id        uuid,
    created_at          timestamptz  not null,
    constraint pk_campaign_membership_milestone primary key (membership_id, milestone_id),
    constraint fk_campaign_membership_milestone_membership foreign key (membership_id)
        references entity_membership (id) on delete cascade,
    constraint fk_campaign_membership_milestone_milestone foreign key (milestone_id)
        references milestone (id) on delete cascade
);
create index idx_campaign_membership_milestone_milestone
    on campaign_membership_milestone (milestone_id);
create unique index uq_campaign_membership_milestone_generated
    on campaign_membership_milestone (membership_id) where generated_condition;

-- ---- the existing conditions, one milestone per membership --------------------------------------

create temporary table v38_condition on commit drop as
select m.id                  as membership_id,
       m.child_id            as entity_id,
       gen_random_uuid()::text as milestone_id,
       campaign.project_id   as project_id,
       'Before "' || coalesce(member.title, m.child_id) || '" in "'
           || coalesce(campaign.title, m.parent_id) || '"' as display_name,
       (select min(g.created_at) from campaign_criterion_group g where g.membership_id = m.id)
                             as created_at
  from entity_membership m
  join entity campaign on campaign.id = m.parent_id
  join entity member on member.id = m.child_id
 where m.kind = 'CAMPAIGN'
   and exists (select 1 from campaign_criterion_group g where g.membership_id = m.id);

insert into milestone (id, project_id, display_name, created_at)
select milestone_id, project_id, display_name, created_at from v38_condition;

insert into milestone_group (id, milestone_id, position, causation_id, created_at, satisfied_at)
select g.id, c.milestone_id, g.position, g.causation_id, g.created_at,
       -- a group holds when every criterion has latched, since its last latch; an empty group
       -- holds vacuously (the rule of Conditions), since it was written
       case
           when not exists (select 1 from campaign_criterion k where k.group_id = g.id)
               then g.created_at
           when not exists (select 1 from campaign_criterion k
                             where k.group_id = g.id and k.satisfied_at is null)
               then (select max(k.satisfied_at) from campaign_criterion k where k.group_id = g.id)
       end
  from campaign_criterion_group g
  join v38_condition c on c.membership_id = g.membership_id;

insert into milestone_criterion (id, group_id, position, kind, predicate, seeded, satisfied_at,
                                 evidence_event_id, evidence_signature, evidence_summary,
                                 approved_by, approval_note, causation_id, created_at)
select k.id, k.group_id, k.position, k.kind, k.predicate, k.seeded, k.satisfied_at,
       k.evidence_event_id, k.evidence_signature, k.evidence_summary,
       k.approved_by, k.approval_note, k.causation_id, k.created_at
  from campaign_criterion k
  join campaign_criterion_group g on g.id = k.group_id
  join v38_condition c on c.membership_id = g.membership_id;

-- the milestone holds since the earliest of its holding groups
update milestone ms
   set satisfied_at = (select min(g.satisfied_at) from milestone_group g
                        where g.milestone_id = ms.id)
 where ms.id in (select milestone_id from v38_condition);

insert into campaign_membership_milestone (membership_id, milestone_id, generated_condition, created_at)
select membership_id, milestone_id, true, created_at from v38_condition;

insert into work_milestone (entity_id, milestone_id, created_at)
select entity_id, milestone_id, created_at from v38_condition;

comment on table milestone is
    'A foreseeable "needs to be met" (epic qits-615): OR''d milestone_group rows of AND''d'
    ' milestone_criterion rows. satisfied_at is "met since", the earliest date of its holding groups.';
comment on table campaign_membership_milestone is
    'The milestones a CAMPAIGN membership waits on; dispatchable when every one is met. A claimed'
    ' membership is frozen and ignores un-meeting.';
