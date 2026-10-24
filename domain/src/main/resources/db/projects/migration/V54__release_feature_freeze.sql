-- THE SOFT FEATURE FREEZE FOR RELEASE REQUESTS (epic qits-1580, task qits-1584).
--
-- A request that already carries as many feature sources as its project allows stops taking more:
-- a further feature branch waits in a queued request behind it instead of widening a fold that is
-- already being gated. A fix (a BUG or MAINTENANCE ticket) and a branch naming no work item are
-- never features and always join. This file is only the storage; the queueing reads it later.
--
--   Project.release_feature_freeze      how many feature sources an active request takes, declared
--                                       in the wrapper's `.config/qits/project.yml` under
--                                       `release_requests.feature_freeze` (ProjectConfigParser) and
--                                       stored by the same reconcile as V48's delivery settings.
--                                       NOT NULL with the value an absent key means, for V28's and
--                                       V35's reason: every row that exists predates the key. The
--                                       range (1..20) is the parser's to refuse -- a malformed file
--                                       keeps the stored value -- so no check constraint restates it.
--   release_request.queued_behind       the id of the active request a queued one waits for; null
--                                       on every request that is not queued. No foreign key, for
--                                       superseded_by's reason (V23): the row it names is in this
--                                       very table, and a delete of it must not cascade into the
--                                       record of what waited for what. Indexed, because the queue
--                                       is read from the active request's side.
--   release_request_source.feature      whether the work item the source's branch names is a
--                                       feature: a ticket that is neither a BUG nor MAINTENANCE, an
--                                       epic or a task. Stamped when the source is added.
--
-- NOTHING IS BACKFILLED: every existing source counts as a non-feature, so no request on record is
-- over any freeze, and no existing request is queued behind anything.
alter table Project add column release_feature_freeze int not null default 3;
alter table release_request add column queued_behind varchar(255) null;
create index IX_release_request_queued_behind on release_request (queued_behind);
alter table release_request_source add column feature boolean not null default false;
