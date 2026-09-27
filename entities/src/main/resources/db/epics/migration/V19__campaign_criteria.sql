-- A campaign membership's run record, its criteria, and a campaign's start (epic f6c67e74, qits-413).
--
-- WHY THE RUN RECORD IS ON THE MEMBERSHIP. Everything about a member's participation in a campaign
-- lives on its CAMPAIGN edge; nothing is stored on the member entity. An entity may be gathered by
-- several campaigns, and each of them claims and dispatches it on its own account, so the record has
-- to be per (campaign, member) — which is exactly what a CAMPAIGN edge is. A STRUCTURAL edge is the
-- tree and is never run, so ck_entity_membership_run_record_campaign_only keeps its columns empty.
--
-- WHY THE CONDITION IS A MONOTONE DNF. A membership waits on OR'd groups, each of AND'd criteria, and
-- a criterion only ever latches (satisfied_at goes from null to a time, never back). No groups means
-- satisfied at start. The latch carries its evidence, and ck_campaign_criterion_evidence says what
-- counts: an event, a person (APPROVAL), or — ENTITY_STATUS only — the target's state when the
-- campaign started ('STATE_AT_START', which has no event id).
--
-- WHY THE TARGET ID IS NOT A FOREIGN KEY. An ENTITY_STATUS target lives in the predicate JSON. A
-- deleted target must leave its criterion behind as unsatisfiable, where a person can see it; a
-- cascade would make the criterion vanish, and a vanished criterion silently makes its member
-- runnable.
--
-- WHAT THIS DOES NOT DO. No row is rewritten: every edge standing at V18 gets the run-record defaults
-- (null, and false for joined_running), which is what the check asks of a STRUCTURAL edge, and no
-- campaign has a criterion or a start yet.
alter table entity_membership
    add column claimed_at            timestamptz,
    add column joined_running        boolean not null default false,
    add column dispatched_at         timestamptz,
    add column dispatch_workspace_id varchar(255),
    add column dispatch_branch       varchar(255),
    add column dispatch_agent_launch varchar(32),
    add column dispatch_refusal      text,
    add column dispatch_refused_at   timestamptz,
    add column dispatch_error        text;

alter table entity_membership add constraint ck_entity_membership_run_record_campaign_only check (
    kind = 'CAMPAIGN' or (claimed_at is null and not joined_running and dispatched_at is null
        and dispatch_workspace_id is null and dispatch_branch is null and dispatch_agent_launch is null
        and dispatch_refusal is null and dispatch_refused_at is null and dispatch_error is null));

-- Monotone DNF: a membership waits on OR'd groups, each of AND'd criteria. No groups = satisfied at start.
create table campaign_criterion_group (
    id            varchar(255) primary key,
    membership_id varchar(255) not null,
    position      integer      not null,
    causation_id  uuid,
    created_at    timestamptz  not null,
    constraint fk_campaign_criterion_group_membership foreign key (membership_id)
        references entity_membership (id) on delete cascade
);
create index idx_campaign_criterion_group_membership on campaign_criterion_group (membership_id, position);

create table campaign_criterion (
    id                 varchar(255) primary key,
    group_id           varchar(255) not null,
    position           integer      not null,
    kind               varchar(32)  not null,
    predicate          text         not null,   -- canonical JSON, one shape per kind
    seeded             boolean      not null default false,
    satisfied_at       timestamptz,
    evidence_event_id  uuid,                    -- null for APPROVAL and for STATE_AT_START
    evidence_signature varchar(128),            -- the event signature, or 'STATE_AT_START'
    evidence_summary   text,
    approved_by        varchar(255),
    approval_note      text,
    causation_id       uuid,
    created_at         timestamptz  not null,
    constraint fk_campaign_criterion_group foreign key (group_id)
        references campaign_criterion_group (id) on delete cascade,
    constraint ck_campaign_criterion_kind
        check (kind in ('ENTITY_STATUS', 'DEPLOYMENT_ACTIVE', 'SCM_RELEASE', 'APPROVAL')),
    -- the latch carries its evidence: an event, a person, or (ENTITY_STATUS only) the state at start
    constraint ck_campaign_criterion_evidence check (
        satisfied_at is null
        or (kind = 'APPROVAL' and approved_by is not null)
        or (kind <> 'APPROVAL' and evidence_event_id is not null)
        or (kind = 'ENTITY_STATUS' and evidence_signature = 'STATE_AT_START'))
);
create index idx_campaign_criterion_outstanding on campaign_criterion (kind) where satisfied_at is null;

-- A campaign's start. One row per campaign that has ever been started; the executor acts only while
-- active and the campaign is REFINED. first_started_at is the forward-only floor and never moves.
-- active is cleared by the pause hook in the same transaction as the campaign leaving REFINED.
create table campaign_start (
    campaign_id      varchar(255) primary key,
    first_started_at timestamptz  not null,
    started_at       timestamptz  not null,
    started_by       varchar(255) not null,
    active           boolean      not null,
    causation_id     uuid,
    constraint fk_campaign_start_campaign foreign key (campaign_id)
        references entity (id) on delete cascade
);
