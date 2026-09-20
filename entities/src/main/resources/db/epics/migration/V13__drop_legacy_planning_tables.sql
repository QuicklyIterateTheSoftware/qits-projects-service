-- THE FOUR OLD PLANNING TABLES ARE DROPPED. Epic, Ticket, Feature and Task go; nothing else moves.
--
--   V9   the tables, empty
--   V10  the backfill — every Epic/Ticket/Feature/Task row copied in, ids UNCHANGED
--   V11  the per-project numeric id, backfilled, and the allocator's counter seeded
--   V12  the four outward foreign keys repointed at `entity`, which retired the mirror
--   V13  this file — the four old tables dropped, and ck_audit_entity_type re-stated
--
-- WHAT THIS DOES. Drops `Task`, `Feature`, `Ticket` and `Epic`, in that order, and drops and re-adds
-- ck_audit_entity_type over an UNCHANGED set of words. It writes no row, reads no row, renames no
-- column and touches no other table.
--
-- ---------------------------------------------------------------------------------------------
-- THE EVIDENCE THAT AUTHORISES IT
-- ---------------------------------------------------------------------------------------------
-- The admin-only migration verification door (epics/.../migration/, shipped in 2026.920.12314 for
-- exactly this moment) was run against the LIVE production database on 2026-09-20T01:32:01Z:
--
--   verdict: CLEAN, discrepancies: 0
--   309 old rows compared against 309 entity rows
--   208 entity_membership edges accounted for
--   all ten verification categories: 0 findings
--
-- The report is committed at docs/migration-verification-2026-09-20.json. The door's own package
-- carried the instruction "DELETE THIS WHOLE PACKAGE WITH THE FOUR OLD TABLES, IN V13" — it was
-- built to be deleted, and it is deleted in the same change as this file, together with
-- service/.../epics/api/MigrationVerificationController and its two test classes. A door whose
-- comparison target no longer exists can only ever answer about nothing.
--
-- ---------------------------------------------------------------------------------------------
-- WHY IT IS SAFE
-- ---------------------------------------------------------------------------------------------
-- NO WRITER. FeatureService and TaskService stopped writing `feature` and `task` at the descendants'
-- cutover, and EpicService.mirrorLegacyRow / TicketService.mirrorLegacyRow went with V12. Since
-- then no class under src/main constructs, persists, updates or deletes a row in any of the four.
-- The four entity classes, their four repositories and their four mappers are deleted in this same
-- change, so there is no longer a mapping that could be persisted by accident.
--
-- NO REFERENT. V12 is what makes that true, and it was read before these drops were written: it
-- repoints fk_ticket_comment_ticket, fk_dossier_page_owner_epic, fk_dossier_page_owner_ticket and
-- fk_dossier_asset_epic at `entity (id)`, so every foreign key that used to name `Epic` or `Ticket`
-- names the merged table. Nothing on this platform ever foreign-keyed to `feature` or to `task`.
-- What is left pointing at the four is therefore only their own V1 keys, among themselves:
--
--   fk_epic_superseded_by   Epic.superseded_by_epic_id     -> Epic (id)     on delete set null
--   fk_feature_epic         Feature.epic_id                -> Epic (id)     on delete cascade
--   fk_feature_depends_on   Feature.depends_on_feature_id  -> Feature (id)  on delete set null
--   fk_task_feature         Task.feature_id                -> Feature (id)  on delete cascade
--   fk_task_depends_on      Task.depends_on_task_id        -> Task (id)     on delete set null
--
-- THE ORDER BELOW IS THAT LIST READ LEAVES-FIRST, and it is why no `cascade` clause is needed on
-- any drop: Task is referenced by nothing once its own self-key goes with it, Feature by nothing
-- once Task is gone, Ticket by nothing at all since V12, Epic by nothing once Feature is gone. A
-- `drop table ... cascade` would have worked too and would have been the wrong instruction to leave
-- behind: it would silently drop a constraint somebody added later, which is the one thing a reader
-- of this file needs to be told did not happen.
--
-- THE ROWS ARE NOT LOST, they are superseded. V10 copied every one of them into `entity` under the
-- id it already had, and the audit log — which is not foreign-keyed to anything and outlives the
-- rows it describes — holds the history either way. The recovery path this snapshot was kept as has
-- been exercised: the verification run above IS that comparison, made once against live data, and
-- keeping a frozen snapshot past a clean verdict is keeping a table that looks authoritative to
-- anyone who opens it and is three months stale.
--
-- ---------------------------------------------------------------------------------------------
-- WHAT THIS DELIBERATELY DOES NOT TOUCH
-- ---------------------------------------------------------------------------------------------
--   * `entity` and `entity_membership` — the live model, not read or written here.
--   * `TicketComment` — keyed on `entity (id)` since V12, `ticket_id` column name included. It is
--     not an archetype of the merged model and nothing about it moves.
--   * `dossier_page`, `dossier_asset`, `dossier_page_asset` — V12 repointed their keys; ck_dossier_
--     page_owner is untouched.
--   * `auditentry` — every column, every row and every index. Only the check constraint's DDL is
--     re-stated, and the permitted set is identical; see below.
--   * `entity_number_sequence` — the allocator's counters stand.
--
-- ---------------------------------------------------------------------------------------------
-- ck_audit_entity_type: THE WORDS DO NOT CHANGE. WHERE THEY COME FROM DOES.
-- ---------------------------------------------------------------------------------------------
-- The permitted set is EXACTLY what V5 left: EPIC, FEATURE, TASK, TICKET, TICKET_COMMENT and
-- DOSSIER_PAGE. Six words before, the same six words after. THIS IS NOT A WIDENING and nothing
-- here permits a value the column did not already permit.
--
-- What changes is on the Java side: AuditEntityType's four planning words were a hand-written
-- parallel list of the four kinds, translated by a hand-written switch in EntityTransitionService.
-- They are DERIVED from `Archetype` now — AuditEntityType.of(archetype) — so the four kinds are
-- declared once, in control/Archetypes, and AuditEntityTypeTest fails the build if an archetype
-- ever gains a constant without a matching audit word. TICKET_COMMENT and DOSSIER_PAGE stay
-- hand-written because they are not archetypes: neither is a row in `entity` at all.
--
-- The constraint is re-stated rather than left alone so that this file is the one place a reader
-- goes to see the vocabulary as it stands after the merge, beside the tables whose names it used to
-- echo. V4 replaced V1's inline unnamed constraint with the named ck_audit_entity_type precisely so
-- that every later statement of it would be an ordinary drop; V5 took that offer and so does this.
-- The conventions are V4's and V5's throughout: unquoted mixed case (which folds to lower case and
-- still matches the entity simple names), named ck_ constraints, nothing quoted.

-- Leaves first: each table is unreferenced by the time it is dropped. No `cascade`, deliberately.
drop table Task;
drop table Feature;
drop table Ticket;
drop table Epic;

-- The same six words, re-stated. See the header: this is not a widening.
alter table auditentry drop constraint ck_audit_entity_type;
alter table auditentry add constraint ck_audit_entity_type
    check (entity_type in ('EPIC','FEATURE','TASK','TICKET','TICKET_COMMENT','DOSSIER_PAGE'));

comment on column auditentry.entity_type is
    'What the entry concerns. The four planning words are Archetype''s, derived by AuditEntityType.of; TICKET_COMMENT and DOSSIER_PAGE are not archetypes.';
