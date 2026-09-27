-- CAMPAIGN joins the archetype vocabulary (epic f6c67e74, qits-411): an ordering of work that
-- already exists, declared in control/Archetypes as a root at depth -1 whose children are campaign
-- memberships rather than structural edges.
--
-- WHY A MIGRATION AT ALL. The archetype is stored as its enum name behind ck_entity_archetype, a
-- closed vocabulary on purpose: a fifth kind is meant to be a visible schema change and never a
-- silent new word in a column. V9 NAMED the constraint so that this would be a drop-and-re-add of a
-- known name rather than a guess at what postgres derived; this file takes that offer, as V14 and
-- V15 did for ck_entity_status.
--
-- WHY THE AUDIT CHECK MOVES WITH IT. A campaign is created and edited through WorkEntityService,
-- which audits every write under AuditEntityType.of(archetype) — the audit word is derived from the
-- archetype by name (AuditEntityTypeTest), so a campaign's audit rows say CAMPAIGN. V13 re-stated
-- ck_audit_entity_type over six words; without the seventh the first campaign create would be
-- refused by the audit insert inside its own transaction. Both vocabularies widen in one file so
-- they land in one Flyway transaction and cannot be half-applied.
--
-- WHAT THIS DOES NOT DO. No row is rewritten, inserted or deleted: no campaign exists yet, and every
-- word the constraints permitted before they still permit. The status vocabulary (V15's six words)
-- is unchanged — a campaign walks the same lifecycle as an epic and a ticket.
alter table entity drop constraint ck_entity_archetype;
alter table entity add constraint ck_entity_archetype
    check (archetype in ('CAMPAIGN', 'EPIC', 'TICKET', 'FEATURE', 'TASK'));

alter table auditentry drop constraint ck_audit_entity_type;
alter table auditentry add constraint ck_audit_entity_type
    check (entity_type in ('CAMPAIGN','EPIC','FEATURE','TASK','TICKET','TICKET_COMMENT','DOSSIER_PAGE'));
