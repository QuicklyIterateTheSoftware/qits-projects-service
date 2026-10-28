-- WHERE EACH BRANCH STANDS ON ITS WAY TO PRODUCTION (qits-1719).
--
-- One row per (project, repository, branch): the last delivery step the branch reached in that
-- repository, written by control/BranchDeliveries in the transaction that took the step, and the
-- ledger every BranchDeliveryChanged is deduplicated against and its `everywhere` computed from.
--
--   step          PUSHED | JOINED | MOVED | FAILED | CONFLICTED | WITHDRAWN | RELEASED | LIVE.
--                 PUSHED is ledger-only: the branch exists in the repository and no request carries
--                 it yet, which is what keeps a branch's `everywhere` from reading LIVE while one of
--                 its repositories has not even asked for a release.
--   request_id    the request carrying the branch at that step (the target, on MOVED); null on PUSHED
--   qualified_id  that request's qualified id, <repo>-rr-<n>
--   fold_sha      the request's merged sha at the step; null before its first fold
--   run_id        FAILED only: the CI run, or the newest automation run, that failed the fold
--   version       RELEASED and LIVE: the tag
--
-- A step equal to the row's (step, request_id, fold_sha) is not published again: FAILED and
-- CONFLICTED once per fold, JOINED once per request.
--
-- Ids are varchar like every other id in this schema (release_request.id, repository.id): they are
-- strings the services mint, and nothing here may refuse one that is not a uuid.
create table branch_delivery (
  project_id   varchar(255) not null,
  repo_id      varchar(255) not null,
  repo_name    text         not null,
  branch       text         not null,
  step         varchar(32)  not null,
  request_id   varchar(255),
  qualified_id text,
  fold_sha     text,
  run_id       text,
  version      varchar(64),
  changed_at   timestamp with time zone not null,
  constraint PK_branch_delivery primary key (project_id, repo_id, branch)
);

-- The read door and `everywhere` ask by branch name, across a project's repositories.
create index branch_delivery_branch on branch_delivery (branch);
