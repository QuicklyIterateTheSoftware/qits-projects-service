-- The pre-approval a person's Dispatch press gives (qits-1075): who said, by pressing Dispatch on a
-- REPORTED ticket or epic, that the work may be scheduled (REFINED → READY_FOR_DEV) as soon as its
-- refine phase lands REFINED — so one press takes it from REPORTED all the way to VERIFIED.
--
-- WHY IT IS ON THE ENTITY. dispatch_continues' reason (V16): the press that grants it and the
-- transition that spends it are different requests, minutes or hours apart, and the entity is the
-- one row here that represents the run of work.
--
-- WHY A NAME AND NOT A FLAG. The scheduling it stands in for is a person's move (PERSON_APPROVAL),
-- and the audit, the transition event and the thread name that person as the mover. The value is
-- only ever stamped from a caller a door verified as a person — never from a body, a tool or a
-- machine — so a stored name is as good as the proof that wrote it.
--
-- NULLABLE, AND NULL FOR EVERY EXISTING ROW. Null is "no pre-approval", the state every row was in
-- before this column existed; nothing is invented for them.
alter table entity add column pre_approved_by varchar(255);

comment on column entity.pre_approved_by is
    'EPIC and TICKET: the person whose Dispatch (FLOW) press pre-approved scheduling the entity once'
    ' its refine phase lands REFINED; null for none. Written only by a person''s FLOW press at REPORTED'
    ' or REFINED; cleared when it is spent (the automatic schedule, or a press at REFINED), by a PHASE'
    ' press, and on a move to DROPPED.';
