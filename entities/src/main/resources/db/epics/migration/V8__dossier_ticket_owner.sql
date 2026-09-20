-- The dossier gains a SECOND OWNER: a ticket, beside the epic it already had.
--
-- WHY AT ALL. The ticket lifecycle (V7) starts with a refine phase: an agent reads the code, works
-- out the root cause and writes the result into the ticket's `description`. Where the situation is
-- too tangled for prose -- an error scenario crossing four services, a sequence worth a figure --
-- it needs a page rather than a longer paragraph. That is what a dossier already is, so this gives
-- the dossier a second owner rather than building a second dossier.
--
-- TWO NULLABLE OWNER COLUMNS AND A CHECK, NOT A POLYMORPHIC PAIR. The tempting shape is
-- owner_type/owner_id, and it would cost the one thing V5 went out of its way to keep: a REAL
-- foreign key. Ticket and Epic are both in THIS database -- the same fact V5 leaned on when it made
-- epic_id a real FK unlike the cross-database project_id -- so both owner columns are real FKs with
-- `on delete cascade`, and deleting a ticket takes its pages exactly as deleting an epic always
-- did. A type column would give that up, and would give it up for a third owner nobody has asked
-- for.
--
-- EXACTLY ONE IS SET, and the database says so rather than the service. num_nonnulls() is postgres'
-- own answer and reads as the sentence it enforces; a row with two owners or with none is refused
-- here, so no code path -- a migration, a fixture, a future writer -- can invent a page belonging to
-- both or to nothing.
--
-- THE SLUG INDEX BECOMES ONE PER OWNER. V5's `ux_dossier_page_slug on (epic_id, slug)` is unique
-- over a column that is now nullable, and in postgres nulls are distinct, so it would stop
-- constraining ticket-owned pages entirely. Two PARTIAL unique indexes say the intended rule
-- instead: a slug is unique within its owner, and the same slug under an epic and under a ticket is
-- two different addresses that never meet. The position index splits the same way, so each owner's
-- listing still reads one index.
--
-- DOSSIER_ASSET IS DELIBERATELY NOT WIDENED, and this file is where that is written down. Assets
-- (V6) are copies of the refining route's sketches and designs -- a route an epic has and a ticket
-- does not. A ticket's pages are prose with inlined markdown, so `dossier_asset.epic_id` stays
-- `not null` and epic-only. What that costs is one guard rather than a feature: DossierService
-- skips DossierAssetService.syncReferences for a ticket-owned page instead of handing it a null
-- epic id, so a ticket page whose markdown happens to name an asset id gets no copy and no
-- dossier_page_asset row.
--
-- THE AUDIT LOG NEEDS NO CHANGE, which is V4's reading of auditentry.epic_id paying off: that column
-- is the SUBTREE KEY, not literally an epic -- a TICKET row is its own root and carries its own id
-- there. So a ticket-owned page's entries carry the TICKET's id, and "the whole history of this
-- ticket" keeps including what its pages did, with no schema change and no second answer.
--
-- Conventions are V5's: unquoted mixed-case `Ticket` folding to lower case, varchar(255)
-- application-assigned ids, named ck_ constraints, explicit indexes.

alter table dossier_page alter column epic_id drop not null;

alter table dossier_page add column ticket_id varchar(255) references Ticket (id) on delete cascade;

alter table dossier_page add constraint ck_dossier_page_owner
    check (num_nonnulls(epic_id, ticket_id) = 1);

drop index ux_dossier_page_slug;
create unique index ux_dossier_page_epic_slug
    on dossier_page (epic_id, slug) where epic_id is not null;
create unique index ux_dossier_page_ticket_slug
    on dossier_page (ticket_id, slug) where ticket_id is not null;

drop index ix_dossier_page_epic;
create index ix_dossier_page_epic_position
    on dossier_page (epic_id, position) where epic_id is not null;
create index ix_dossier_page_ticket_position
    on dossier_page (ticket_id, position) where ticket_id is not null;

comment on table dossier_page is 'The long form of an epic OR of a ticket: a flat, ordered list of markdown pages.';
comment on column dossier_page.ticket_id is 'Set when a ticket owns the page; exactly one of epic_id/ticket_id is.';
