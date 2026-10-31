-- The order a person wants ideas taken up in (qits-1791).
--
-- Until this file a project's ideas had one order, the order they were written in. PRIORITY is the
-- ranking a person states by dragging an idea above or below another (PUT
-- /ideas/{id}/priority), or by writing the number outright (PATCH /ideas/{id}).
--
-- THE ORDER RULE, which no index or constraint here states: priority DESCENDING, a null after every
-- number, then created_at ascending, then the id. Higher means sooner. GET /ideas answers in it.
--
-- NULLABLE, NO DEFAULT, NO BACKFILL. A null is "nobody ranked it": it sorts after every ranked idea,
-- oldest first, which is exactly the order the list answered before this column existed. Archiving
-- an idea clears it; an archived or DONE idea is never written one.

alter table idea add column priority integer;

comment on column idea.priority is
    'The ranking among a project''s rankable ideas (not archived, not DONE): higher sorts first,'
    ' null after every number, then by created_at and id. Written by PATCH /ideas/{id} and by the'
    ' drop door PUT /ideas/{id}/priority; cleared by the archive. Null on every row that predates V35.';
