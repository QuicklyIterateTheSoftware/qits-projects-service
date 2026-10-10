-- The fold whose QA this service has already asked for (qits-1133). A fold is announced twice:
-- preRun=PENDING the moment it lands, while its automations are still being settled, and
-- preRun=DONE once every applicable automation is fresh or waived at it — which is the
-- announcement qits-ci starts the QA run on. This column is the at-most-once record of the second:
-- it is written under the request's row lock in the same transaction that decides to announce, so a
-- restart, a sweep and a verdict racing each other announce DONE once per sha and never twice.
--
-- Backfilled with merged_sha: before this column every fold was announced exactly once, with no
-- preRun field, and qits-ci built it. Leaving the column null would announce DONE again for every
-- open request on the first sweep after the deploy and build every one of them a second time.
alter table release_request add column qa_announced_sha varchar(255);
update release_request set qa_announced_sha = merged_sha where merged_sha is not null;
