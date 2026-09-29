-- One comment thread per entity, of every archetype (qits-551). A ticket was the only kind with a
-- thread; an epic, a feature, a task and a campaign had nowhere to write, which left an agent
-- implementing a REFINED epic — whose scope and dossier are frozen — with nowhere to record what it
-- found. The thread is the writable log beside the frozen plan.
--
-- WHY A RENAME AND NOT A NEW TABLE. The rows are already archetype-agnostic in everything but their
-- names: V12 repointed fk_ticket_comment_ticket at entity (id), so a comment's key has named ANY
-- entity since then and only the code refused to write one on anything but a ticket. Every existing
-- ticket comment stays exactly where it is, under the same id, on the same entity.
--
-- THE NAMES. V4 created the table unquoted as `TicketComment`, which postgres folds to
-- `ticketcomment`, and the primary key took postgres' derived name `ticketcomment_pkey`. The table
-- becomes snake_case `entity_comment` — the convention of every table added since (entity,
-- entity_membership, dossier_page) — and the key, the index and the FK follow it. The FK keeps its
-- ON DELETE CASCADE: it is the safety net under WorkEntityService.delete, which removes a subtree's
-- comments in-service so that each gets its own DELETE audit row.
--
-- THE AUDIT WORD. TICKET_COMMENT named the table's old scope; a comment on an epic is not a ticket
-- comment, so the word becomes COMMENT and the rows already written move with it — one vocabulary,
-- rather than a history in which the same kind of row is spelled two ways. ck_audit_entity_type
-- (V17's form) is dropped first so the update is not refused, then re-stated over the new word.
-- The snapshots those rows carry are left as they were written (`ticketId` in the JSON): an audit
-- snapshot is what the row looked like at the time, and rewriting it would be rewriting history.
alter table TicketComment rename to entity_comment;
alter table entity_comment rename column ticket_id to entity_id;
alter table entity_comment rename constraint ticketcomment_pkey to entity_comment_pkey;
alter index idx_ticket_comment_ticket rename to idx_entity_comment_entity;
alter table entity_comment rename constraint fk_ticket_comment_ticket to fk_entity_comment_entity;

comment on table entity_comment is
    'One remark on an entity of any archetype. Read oldest first: a conversation is read from the start.';
comment on column entity_comment.entity_id is 'The entity this remark is on, of any archetype.';

alter table auditentry drop constraint ck_audit_entity_type;
update auditentry set entity_type = 'COMMENT' where entity_type = 'TICKET_COMMENT';
alter table auditentry add constraint ck_audit_entity_type
    check (entity_type in ('CAMPAIGN','EPIC','FEATURE','TASK','TICKET','COMMENT','DOSSIER_PAGE'));

comment on column auditentry.entity_type is
    'What the entry concerns. The five planning words are Archetype''s, derived by AuditEntityType.of; COMMENT and DOSSIER_PAGE are not archetypes.';
