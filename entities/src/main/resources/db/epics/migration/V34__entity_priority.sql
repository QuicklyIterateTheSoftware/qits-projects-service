-- The order a person wants work done in (qits-753).
--
-- A board shows siblings — a project's roots (its epics and tickets together; campaigns are not
-- ordered among them), or the children of one STRUCTURAL parent — and until this file the only
-- order they had was the order they were filed in. PRIORITY is the ranking a person states by
-- dragging an item above or below another one (PUT /work/{qualifiedId}/priority), or by writing the
-- number outright (PATCH /work/{qualifiedId}).
--
-- THE ORDER RULE, which no index or constraint here states: priority DESCENDING, a null after every
-- number, then the qualified id's number ascending, then the id. Higher means sooner. The drop door
-- renumbers only the rows at and above the drop, so the numbers are not dense and need not be.
--
-- NULLABLE, NO DEFAULT, NO BACKFILL. A null is "nobody ranked it", which is a real answer and the
-- one every existing row gives: it sorts after every ranked row, oldest first, exactly the order the
-- boards showed before this column existed. No check constraint either — a negative number is an
-- odd ranking, not a corrupt row.

alter table entity add column priority integer;

comment on column entity.priority is
    'The ranking among siblings (the roots of a project, campaigns excluded, or the children of one'
    ' structural parent): higher sorts first, null after every number, then by number and id.'
    ' Written by PATCH /work/{qualifiedId} and by the drop door PUT /work/{qualifiedId}/priority;'
    ' refused on a DONE or DROPPED row. Null on every row that predates V34.';
