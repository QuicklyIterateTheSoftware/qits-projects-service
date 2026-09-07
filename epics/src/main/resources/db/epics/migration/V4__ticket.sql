-- Tickets: the small-scoped work items that were never worth an epic, and the comments on them.
--
-- WHY A SECOND ROOT RATHER THAN A ROW UNDER EPIC. An epic is a plan — a scope that gets frozen,
-- superseded and declared shipped, with features and tasks beneath it. "The button is the wrong
-- colour" has none of that, and pushed through the epic shape it becomes a one-feature epic that no
-- phase describes and that clutters the board it was filed against. So Ticket sits BESIDE Epic,
-- project-scoped like it, with a lifecycle of its own that is two words wide. Nothing joins the two
-- tables and nothing should: a ticket that turns out to need a plan is an epic somebody writes, not
-- a foreign key somebody sets.
--
-- The conventions are V1's, unchanged: unquoted mixed-case table names that fold to lower case and
-- still match the entity simple names (no @Table anywhere), varchar(255) application-assigned ids,
-- `text` for markdown, `timestamp(6) with time zone`, named uq_/ck_ constraints, check constraints
-- spelling out the closed enum vocabularies, and explicit indexes rather than whatever a constraint
-- happens to leave behind. causation_id is born in the create table here — V2 added it to the four
-- tables that predate it; a table created afterwards carries it from the start, the way domain's
-- agent_credential did.

-- A ticket, owned by a project (project_id: no cross-DB FK, just an indexed String — the epics
-- database cannot reach domain's).
create table Ticket (
    id varchar(255) not null,
    causation_id uuid,
    project_id varchar(255) not null,
    title varchar(512) not null,
    -- Minted from the title at create and never changed: a stable address for the row, so retitling
    -- a ticket does not move it. Unique within the project, like an epic's.
    slug varchar(255) not null,
    -- BUG or IMPROVEMENT, and no third word. A ticket is small-scoped by definition, so "something
    -- is wrong" and "something could be better" is the whole distinction it has to carry.
    type varchar(32) not null,
    -- OPEN <-> RESOLVED, both ways, and there is no terminal status: a ticket resolved by mistake
    -- reopens, because the alternative is a second row saying the same thing.
    status varchar(32) not null,
    -- Free text, not an id: the platform has no person table and a name written down beats a
    -- reference to a directory this database cannot read.
    assignee varchar(255),
    -- STAMPED FROM THE REQUEST IDENTITY, never client-supplied. The same value the row's CREATE
    -- audit entry carries; it is duplicated here so a ticket list has a reporter without a join
    -- against the log. Nullable, because an unattributed caller is an ordinary caller.
    created_by varchar(255),
    description text,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id),
    constraint ck_ticket_type check (type in ('BUG','IMPROVEMENT')),
    constraint ck_ticket_status check (status in ('OPEN','RESOLVED'))
);
create index idx_ticket_project_id on Ticket (project_id);
-- The project ticket list filters by status (open work is what a board is for), so the index
-- carries both columns in that order — the shape idx_epic_project_status already has.
create index idx_ticket_project_status on Ticket (project_id, status);
alter table Ticket add constraint uq_ticket_project_slug unique (project_id, slug);

-- One remark on a ticket. Read OLDEST FIRST — the opposite of the audit log's newest-first, and
-- deliberately: a log is scanned from the top, a conversation is read from the start.
create table TicketComment (
    id varchar(255) not null,
    causation_id uuid,
    ticket_id varchar(255) not null,
    -- Stamped from the request identity like ticket.created_by, and nullable for the same reason.
    author varchar(255),
    body text not null,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id)
);
-- The listing is (ticket_id, created_at) with the id as tie-break, so the index carries the pair.
create index idx_ticket_comment_ticket on TicketComment (ticket_id, created_at);

-- The intra-module FK, and a SAFETY NET rather than the mechanism: TicketService deletes a ticket's
-- comments in a loop first, so every removed comment gets its own DELETE audit row — the same
-- decision EpicService.delete makes about features and tasks, and the reason V1's fk_feature_epic
-- carries this clause too. The cascade catches a row removed outside the service and nothing else.
alter table TicketComment
    add constraint fk_ticket_comment_ticket foreign key (ticket_id) references Ticket (id) on delete cascade;

-- The audit log's entity vocabulary widens to cover both new tables. V1 wrote this one INLINE and
-- unnamed, so postgres holds it under the name it derives from `<table>_<column>_check` — hence the
-- literal below, where V3 could name ck_epic_status outright. The replacement is NAMED, so the next
-- widening is an ordinary drop.
--
-- What travels with the two words is the reading of auditentry.epic_id: it is the SUBTREE KEY, not
-- a foreign key to an epic, and a ticket is its own root exactly as an epic is — a TICKET row and
-- every TICKET_COMMENT row under it carry the ticket's id there, which is what keeps "the whole
-- history of this thing" one indexed query after the live rows are gone. The column is not renamed:
-- renaming it across an applied lineage and a live log buys a better word and nothing else.
alter table auditentry drop constraint auditentry_entity_type_check;
alter table auditentry add constraint ck_audit_entity_type
    check (entity_type in ('EPIC','FEATURE','TASK','TICKET','TICKET_COMMENT'));
