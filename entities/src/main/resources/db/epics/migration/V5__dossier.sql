-- The dossier: the epic's long form. The epic's own description is the value pitch; a dossier page
-- is the breakdown, with examples, images and framed designs inlined into it.
--
-- WHY IT IS IN THIS DATABASE AND NOT BESIDE THE REFINEMENT THAT PRODUCED IT. A dossier is refined
-- on the refining route, using the rest of that route — sketches and designs are pasted into it —
-- so the tempting place is `refinement`, in domain's database, where the prompt draft and the
-- attachments live. That would be wrong in one decisive way: a refinement is a container, and
-- discarding it cascades everything hanging off it away. The plan must outlive the container it was
-- written in, and implementation reads it months later, when no refinement is open at all. So it
-- belongs to the EPIC, and being in the epics module is not a filing decision but the whole point:
-- these rows inherit the REFINING-only mutation guard that already freezes features and tasks, the
-- AuditEntry written beside every change, and the CausationStamp listener.
--
-- epic_id IS A REAL FOREIGN KEY here, unlike the cross-module keys in `projects` (project_id on
-- Epic and Ticket are indexed strings, because that table is in another physical database). This is
-- the epics database, Epic is in it, and the cascade is wanted: deleting an epic takes its dossier
-- with it.
--
-- NO parent_id, AND NOTHING SHOULD ADD ONE. Pages are a flat, ordered list. The second level of the
-- navigation is the CURRENT page's own h1/h2/h3, derived in the browser from the rendered markdown
-- and stored nowhere -- so it cannot drift from the page, because it IS the page. A stored
-- hierarchy would be a second answer to a question the document already answers.
--
-- Conventions are V1's and V4's: unquoted mixed-case names that fold to lower case and still match
-- the entity simple names, varchar(255) application-assigned ids, `text` for markdown,
-- `timestamp(6) with time zone`, explicit indexes, and causation_id born in the create table
-- (V2 added it to the four tables that predate it; a table created afterwards carries it).
create table dossier_page
(
    id           varchar(255) not null primary key,
    epic_id      varchar(255) not null references Epic (id) on delete cascade,
    -- Minted from the title at create and NEVER changed afterwards, the rule Epic.slug follows and
    -- for the same reason: it is in URLs people have already sent each other (?tab=dossier&page=…).
    -- Renaming a page changes the title alone.
    slug         varchar(255) not null,
    title        varchar(512) not null,
    -- Dense and zero-based; the service renumbers the affected span on a move.
    position     integer      not null,
    body         text         not null,
    -- Starts at 0 and is bumped BY THE SERVICE, never by a trigger. Nobody accepts a write on this
    -- route, so a person editing in the SPA while an agent writes from a prompt is the ordinary
    -- case: a write carrying a stale version is refused with the current page, never merged.
    version      bigint       not null,
    created_at   timestamp(6) with time zone not null,
    updated_at   timestamp(6) with time zone not null,
    causation_id uuid
);

-- Unquoted mixed-case `Epic` above folds to lower case -- the same convention V1 relies on.

create unique index ux_dossier_page_slug on dossier_page (epic_id, slug);
create index ix_dossier_page_epic on dossier_page (epic_id, position);

-- The audit log's entity vocabulary widens for the new row kind. V4 replaced V1's inline unnamed
-- constraint with ck_audit_entity_type, so this widening is the ordinary drop it promised.
--
-- A DOSSIER_PAGE row carries the OWNING EPIC's id in auditentry.epic_id, unlike a ticket, which is
-- its own root: a dossier page is part of an epic's subtree, so "the whole history of this epic"
-- keeps including what its pages did.
alter table auditentry drop constraint ck_audit_entity_type;
alter table auditentry add constraint ck_audit_entity_type
    check (entity_type in ('EPIC','FEATURE','TASK','TICKET','TICKET_COMMENT','DOSSIER_PAGE'));

comment on table dossier_page is 'The epic''s long form: a flat, ordered list of markdown pages.';
comment on column dossier_page.slug is 'Minted at create, never changed: it is in URLs people have sent each other.';
comment on column dossier_page.version is 'Bumped by the service on every write; a stale write is refused with 409.';
