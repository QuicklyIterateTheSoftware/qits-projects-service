-- Ideas, and the work they inspire (qits-1387, epic qits-888).
--
-- An IDEA is not a work archetype and does not live in `entity`. It has no phases, no dispatch, no
-- transitions, no thread, no assignee, no children and no acceptance criteria: it is a titled,
-- short note owned by a project that work can name as its inspiration. Putting it in `entity` would
-- hand it every one of those through the archetype registry and the doors, and each would then have
-- to be refused again; a table of its own owes nothing to any of them.
--
-- THE STATUS IS DERIVED, AND STORED. PLANNED while no counted work names the idea, QUEUED while at
-- least one does and not all of them are DONE, DONE once every counted one is DONE (VERIFIED is not
-- enough). DROPPED work is not counted. The service recomputes it in the same transaction as every
-- write that can change it (control/IdeaStatusRecompute), and a boot reconcile repairs drift. It is
-- stored rather than computed per read so a listing can filter by it.
--
-- ENABLED_BY is one layer deep: an idea may name ONE other idea of its project as what enables it,
-- and that idea may not itself be enabled by another, nor may an idea that already enables others be
-- given one. The database states only what a single row can: no self-reference. The depth and the
-- project rule are the service's, because a constraint cannot read the row it points at.
--
-- NO HARD DELETE. An idea is archived with a reason and can be unarchived; nothing removes the row,
-- so the foreign keys below never need an `on delete` clause to stay honest. They carry `set null`
-- anyway, as V9's self-references do, so a manual cleanup cannot strand a dangling pointer.
--
-- project_id is a plain string with no foreign key, V1's rule for every cross-database reference:
-- the project lives in the `projects` database, and its existence is validated in `service`.
create table idea (
    id varchar(255) not null,

    -- The platform's uniform causation column (qits-eventstream's CausedRow), as on `entity`.
    causation_id uuid,

    project_id varchar(255) not null,

    title varchar(512) not null,

    -- At most three paragraphs and 600 characters, enforced by the service.
    description text,

    enabled_by varchar(255),

    status varchar(32) not null,

    archived boolean not null default false,
    archive_reason text,
    archived_at timestamp(6) with time zone,

    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,

    primary key (id),
    constraint ck_idea_status check (status in ('PLANNED', 'QUEUED', 'DONE')),
    constraint ck_idea_enabled_by_not_self check (enabled_by is null or enabled_by <> id)
);

alter table idea
    add constraint fk_idea_enabled_by foreign key (enabled_by) references idea (id) on delete set null;

-- The listing reads a project's ideas, optionally by status.
create index idx_idea_project_status on idea (project_id, status);
-- "Which ideas does this one enable", and the one-layer check on every enabledBy write.
create index idx_idea_enabled_by on idea (enabled_by);

comment on table idea is
    'A project''s idea: a short titled note work can name as its inspiration (entity.inspired_by).'
    ' Not a work archetype: no lifecycle, dispatch, thread or children. Never deleted, only archived.';
comment on column idea.status is
    'Derived and stored: PLANNED (no counted work names it), QUEUED (some counted work, not all DONE),'
    ' DONE (every counted work item DONE). DROPPED work is not counted. Recomputed by the service in'
    ' the transaction of every write that can change it.';
comment on column idea.enabled_by is
    'The one idea of the same project that enables this one; one layer deep (the service refuses a'
    ' target that is itself enabled, and an idea that enables others being given one).';
comment on column idea.archived is
    'Archived ideas are hidden from the default listing. Archiving keeps the status as it was.';

-- The work an idea inspired: an EPIC, a TICKET or a CAMPAIGN may name one idea of its project.
alter table entity add column inspired_by varchar(255);

alter table entity
    add constraint fk_entity_inspired_by foreign key (inspired_by) references idea (id) on delete set null;

create index idx_entity_inspired_by on entity (inspired_by);

comment on column entity.inspired_by is
    'The idea of the same project this EPIC, TICKET or CAMPAIGN was inspired by; null for none and on'
    ' every other archetype. Every write of it, and every status move of a row carrying it,'
    ' recomputes that idea''s status in the same transaction.';
