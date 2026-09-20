-- THE FOUR OUTWARD FOREIGN KEYS MOVE FROM THE OLD TABLES TO `entity`. Nothing else changes.
--
-- Four live keys still name the two tables the write-behind mirror existed to keep populated:
-- dossier_page.epic_id, dossier_page.ticket_id, dossier_asset.epic_id and ticketcomment.ticket_id.
-- Each is dropped and re-added against `entity (id)`, `on delete cascade` in every case. No column
-- is renamed, no column is dropped, ck_dossier_page_owner is untouched, the four old tables are
-- untouched, and not one row is written or read by this file.
--
--   V9   the tables, empty
--   V10  the backfill — every Epic/Ticket/Feature/Task row copied in, ids UNCHANGED
--   V11  the id settlement (the numeric id the merged model wants)
--   V12  this file — the outward keys repointed at `entity`, which retires the mirror
--   V13+ the drop of the four old tables, once nothing reads them
--
-- THIS IS SAFE BECAUSE THE IDS ARE ONE ID SPACE, and that is the only reason it is. V10 copied
-- every epic, ticket, feature and task into `entity` under the id it already had — character for
-- character, no mapping table, no re-keying — so every value standing in these four columns today
-- already resolves in `entity`. The `alter table ... add constraint` therefore VALIDATES against
-- rows that are all present rather than failing on the first orphan, and it does so without a
-- backfill, an update or a `not valid` escape hatch. Re-keyed ids would have made this file
-- impossible to write; the decision was made in V9 and this is where it pays.
--
-- ---------------------------------------------------------------------------------------------
-- WHY THIS RATHER THAN KEEPING THE MIRROR
-- ---------------------------------------------------------------------------------------------
-- The alternative — option (a) — was to leave the keys where they are and go on writing the legacy
-- `epic` and `ticket` rows behind every entity write, which is what EpicService.mirrorLegacyRow and
-- TicketService.mirrorLegacyRow did. That keeps two tables with a writer and no reader, populated
-- purely to satisfy a constraint. It is exactly the state the descendants' cutover already refused
-- for `feature` and `task` (docs/unified-entity-model.md: "There is NO feature/task mirror, and
-- that is a decision"): a half-live table looks authoritative to anyone who opens it, it drifts the
-- first time a path forgets to write it, and the drift is invisible because no reader would notice.
--
-- Repointing takes the writer away instead of adding a second reason for it. Afterwards the four
-- old tables have NO WRITER AND NO REFERENT, which is a cleaner thing for the verification door to
-- compare against than a mirror that was still moving under it.
--
-- ---------------------------------------------------------------------------------------------
-- WHY IT DOES NOT COMPROMISE THE VERIFICATION DOOR, AND THE ONE THING THAT DOOR MUST NOT ASSUME
-- ---------------------------------------------------------------------------------------------
-- That door (the epic's "Migration verification, against live data" feature) compares the old
-- tables against the unified model and checks that dossier pages, dossier assets, audit entries,
-- ticket comments and work branches STILL RESOLVE TO AN ENTITY. This file changes no column on any
-- of the four old tables and copies nothing; all it changes is which table those outward references
-- are CONSTRAINED against — and the direction it constrains them in is precisely the one the door
-- asserts. The check it makes is now enforced by the schema rather than merely verified by it.
--
-- WHAT THE DOOR MUST NOT ASSUME IS THE REVERSE DIRECTION: "no entity has an id no old row had".
-- That has been false since FeatureService and TaskService stopped writing legacy rows — a feature
-- created after that cutover has no `feature` row at all — and with the mirror gone it is equally
-- false for epics and tickets. THE OLD FOUR TABLES ARE A FROZEN SNAPSHOT OF THE ESTATE AS V10 FOUND
-- IT, NOT A LIVE MIRROR. The comparison is forward and only forward: every old row resolves to an
-- entity, or to the DELETE audit row that says it was removed through the new model. A door written
-- the other way round would report every row created since the cutover as a defect.
--
-- ---------------------------------------------------------------------------------------------
-- THE NARROWING THE FOREIGN KEY LOSES, STATED RATHER THAN HIDDEN
-- ---------------------------------------------------------------------------------------------
-- dossier_page.epic_id used to be constrained to an actual epic, and dossier_page.ticket_id to an
-- actual ticket. Against `entity (id)` each is constrained to a row of ANY archetype: the database
-- can no longer tell an epic's id from a ticket's, because they are one table and one id space.
--
-- Two things stand where that stood. ck_dossier_page_owner still enforces exactly-one-owner
-- (num_nonnulls(epic_id, ticket_id) = 1), so the shape of the row is unchanged. And DossierService
-- resolves an owner BY ID AND ARCHETYPE, refusing a row of the wrong kind with a 404 before it
-- writes anything — the same check EpicService.entity and TicketService.entity already make. So the
-- rule is enforced one layer up, which is where ck_entity_status already put the equivalent
-- question: the constraint spells the vocabulary and `control/Archetypes` spells the rule (V9).
--
-- ---------------------------------------------------------------------------------------------
-- THE CASCADE IS PRESERVED AND ITS SOURCE MOVES
-- ---------------------------------------------------------------------------------------------
-- Deleting an epic used to take its dossier pages and its assets with it through the LEGACY row's
-- cascade, fired by the mirror's own delete. It takes them through the `entity` row's cascade now —
-- which is the row EpicService actually deletes, so the safety net hangs off the real write instead
-- of off a bookkeeping one. Same for a ticket and its comments. TicketService still deletes a
-- ticket's comments in-service first, so each removed remark keeps getting its own DELETE audit
-- row; the cascade stays what V4 called it, a safety net for a row removed outside the service.
--
-- ---------------------------------------------------------------------------------------------
-- THE CONSTRAINT NAMES, VERIFIED AGAINST THE FILES THAT WROTE THEM
-- ---------------------------------------------------------------------------------------------
-- Three of the four were written INLINE (`... references Epic (id) on delete cascade`), so postgres
-- holds them under the name it derives, `<table>_<column>_fkey` — hence the literals below. V4 and
-- V5 both had to do this dance for auditentry's inline check constraint, and this is the same drop
-- their headers explain. The fourth was named when it was written and keeps its name.
--
--   dossier_page.epic_id     dossier_page_epic_id_fkey     inline in V5
--   dossier_page.ticket_id   dossier_page_ticket_id_fkey   inline in V8 (add column ... references)
--   dossier_asset.epic_id    dossier_asset_epic_id_fkey    inline in V6
--   ticketcomment.ticket_id  fk_ticket_comment_ticket      named, V4
--
-- EVERY REPLACEMENT IS NAMED, so the next change to any of them is an ordinary drop rather than a
-- second round of guessing what postgres derived. fk_ticket_comment_ticket keeps its name on
-- purpose: it is already the right word and the referent is the same id, spelled in the table that
-- holds it now.
--
-- The conventions are V4's and V8's: unquoted mixed-case `TicketComment` folds to lower case, named
-- fk_ constraints, nothing quoted.

-- dossier_page: both owner columns.
alter table dossier_page drop constraint dossier_page_epic_id_fkey;
alter table dossier_page add constraint fk_dossier_page_owner_epic
    foreign key (epic_id) references entity (id) on delete cascade;

alter table dossier_page drop constraint dossier_page_ticket_id_fkey;
alter table dossier_page add constraint fk_dossier_page_owner_ticket
    foreign key (ticket_id) references entity (id) on delete cascade;

-- dossier_asset: epic-only, and deliberately still so (V8's decision is untouched here).
alter table dossier_asset drop constraint dossier_asset_epic_id_fkey;
alter table dossier_asset add constraint fk_dossier_asset_epic
    foreign key (epic_id) references entity (id) on delete cascade;

-- TicketComment: the same name over the same id, one table to the left.
alter table TicketComment drop constraint fk_ticket_comment_ticket;
alter table TicketComment add constraint fk_ticket_comment_ticket
    foreign key (ticket_id) references entity (id) on delete cascade;

comment on column dossier_page.epic_id is 'The owning EPIC entity; ck_dossier_page_owner says exactly one owner is set.';
comment on column dossier_page.ticket_id is 'The owning TICKET entity; exactly one of epic_id/ticket_id is.';
comment on column dossier_asset.epic_id is 'The owning EPIC entity. Assets are epic-only by V8''s decision.';
comment on column TicketComment.ticket_id is 'The TICKET entity this remark is on.';
