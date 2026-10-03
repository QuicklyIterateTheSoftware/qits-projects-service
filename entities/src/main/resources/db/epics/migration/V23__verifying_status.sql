-- VERIFYING, between IMPLEMENTED and VERIFIED (qits-749): the mirror of IMPLEMENTING (V22), one
-- phase later. The platform sets it when the verify phase is started -- at the dispatch press, or
-- on the FLOW hand-off that delivers the verify turn -- so, like IMPLEMENTING, it records a fact
-- (a verification was started) rather than a claim somebody keeps current by hand, and the verify
-- phase leaves it by the move to VERIFIED it always made. It is skippable: IMPLEMENTED -> VERIFIED
-- stays a legal move (a SKIP in EntityStateMachine). V22's header states the rule both "-ING"
-- statuses rest on; it is not repeated here.
--
-- No backfill: every existing row stays where it is. No marker column either -- features and tasks
-- carry an implementing marker (V22) and nothing for verifying.

-- The constraint widens by one word, dropped and re-added by name as V15 and V22 did. Null stays
-- legal: a feature and a task hold no status.
alter table entity drop constraint ck_entity_status;
alter table entity add constraint ck_entity_status check (status is null or status in
    ('REPORTED', 'REFINED', 'IMPLEMENTING', 'IMPLEMENTED', 'VERIFYING', 'VERIFIED', 'DONE',
     'DROPPED'));
