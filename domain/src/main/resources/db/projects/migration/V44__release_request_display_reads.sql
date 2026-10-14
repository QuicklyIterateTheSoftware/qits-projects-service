-- WHAT THE RELEASE-REQUEST LIST SHOWS IS READ FROM HERE, NOT FROM GIT (qits-1201).
--
-- The list and single reads (ReleaseRequests.render) used to ask two things live whenever an
-- in-memory memory missed: each repository's gate set (a tree read and up to two file reads at
-- qits-githost) and, per open request, the approval question's content half (a mirror fetch and
-- about ten git subprocesses per fold). Both memories were emptied by every movement of main and by
-- every restart, and N concurrent list reads each redid the same folds. Both answers are now
-- written down by background work and the read path only reads them.
--
-- 1. The approval question's content half, on the request it is about.
--
--    approval_content_reasons  a JSON array of the reasons ApprovalPolicy.readContent gave
--                              (`[]` when the fold neither changes .config/qits/ nor adds nothing to
--                              main) — only a COMPLETE reading is stored; one about an outage is not
--    approval_content_fold     the merged_sha those reasons were read for. A row whose fold differs
--                              from its merged_sha (or is null) has not been read yet at its current
--                              fold, and the list fails closed for it until it has
--
--    Written by ApprovalContentRecorder only, in its own transaction, and only while merged_sha still
--    equals the fold it read. A display value and never a decision: the executor, the gates and
--    approve/decline keep reading the fold afresh. Nothing is backfilled — every open row is read
--    lazily the first time a list meets it.
alter table release_request add column approval_content_reasons text;
alter table release_request add column approval_content_fold varchar(255);

-- 2. Each repository's gate set as last read from its main.
--
--    gate_set  {"known":true,"kinds":["CI","APPROVAL"],"detail":null} — ReleaseGates' own JSON, read
--              with a tree walk so no class needs registering for a native image. Only a KNOWN set is
--              ever written: "could not read the configuration" is never kept
--    read_at   when it was read; older than gate-freshness-ms, the list still shows it and starts a
--              background re-read
--
--    repo_id is a plain string with no foreign key, release_request's own reasoning (V9): the row is
--    a derived reading keyed by the repository and a stale one for a deleted repository is never read.
create table release_gate_set (
  repo_id   varchar(255) not null primary key,
  gate_set  text not null,
  read_at   timestamp with time zone not null
);
