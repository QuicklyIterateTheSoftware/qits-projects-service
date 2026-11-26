-- The error issue a ticket was made from (qits-2538): the qits-observability fingerprint, 32
-- lower-case hex digits. The reference lives on the ticket, the way inspired_by names an idea, so
-- making a ticket from an error issue is one atomic write and qits-observability keeps no copy; one
-- error issue may have any number of tickets.
--
-- No foreign key and no check against qits-observability: a fingerprint is an external string id,
-- like a repository id read from telemetry. The service judges the shape and that only a TICKET
-- carries one.
--
-- Partial, because almost every row holds none: the index only serves "the tickets made from this
-- error issue" (GET /projects/api/projects/{project}/work?errorIssue=).
--
-- No row is rewritten: every existing row holds none.
alter table entity add column error_issue varchar(32);

create index entity_error_issue on entity (error_issue) where error_issue is not null;

comment on column entity.error_issue is
    'TICKET only: the qits-observability error issue (its 32-hex fingerprint) the ticket was made'
    ' from; null for none and on every other archetype.';
