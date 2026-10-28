-- A BRANCH'S OWNING AGENT HEARS WHEN THE BRANCH FAILS ITS BUILD OR MOVES (qits-1722).
--
-- Replaces the request-keyed release_request_failure_notice (V61, qits-1672), which is left in
-- place as history and no longer written. A notice is keyed by the branch, and addressed to the
-- ACTIVE agent of the request's project that owns the branch by the git_refs rule — the agent whose
-- wrapper branch is the branch, or is the branch's `<wrapper>-` prefix — never to whoever filed the
-- request or added the source.
--
-- One row per notice. The key is the dedupe rule, and the row is inserted `on conflict do nothing`
-- by control/BranchDeliveryNotices in the transaction that took the branch's step:
--
--   FAILED:<requestId>:<foldSha>:<repoId>:<branch>         once per fold and branch
--   MOVED:<repoId>:<branch>:<fromRequestId>:<toRequestId>  once per (branch, from, to)
--
-- The agent is NOT part of the key, unlike V61's: who owns a branch is an HTTP question to
-- qits-workspaces, and that may only be asked after the commit (BranchDeliveryStepListener runs
-- under the caller's row locks). So the row is owed with agent_id null and the delivery fills it
-- in. A branch has at most one owner — the longest matching wrapper branch wins — so one row per
-- notice is one message per notice.
--
-- `step` is plain text with no check: qits-1725 adds CONFLICTED and further steps to this table.
--
--   notice_key    the dedupe key above
--   agent_id      the ACTIVE agent the branch resolved to at delivery; null while owed, and when no
--                 agent owns the branch
--   project_id    the project the agent is looked for in
--   repo_id       the repository the step was taken in
--   branch        the source branch, ticket/qits-1615
--   step          the branch's step: FAILED, MOVED (and, later, others)
--   request_id    the request carrying the branch at the step (the target, on MOVED)
--   fold_sha      that request's merged sha at the step; what the desk's request-failed reads a
--                 fold's notices by
--   text          the message, exactly as it is said
--   work_id       the matched agent's work item, which the delivery names
--   outcome       DELIVERED, LAUNCHED or RESUMED when told; NO_AGENT, NOT_ACTIVE or GAVE_UP when
--                 not; null while still owed
--   attempts      how many deliveries were tried
--   claimed_at    when a delivery last took the row; a stale claim is taken again
--   created_at    when the step was written
--   delivered_at  when the agent was told; null when it was not
--   detail        the far side's sentence, or why nothing was said
--
-- Ids are varchar like branch_delivery's (V67). No foreign keys: a record of something said must
-- not vanish with a bulk delete of requests.
create table branch_delivery_notice (
  notice_key   text         not null,
  agent_id     varchar(64),
  project_id   varchar(255) not null,
  repo_id      varchar(255) not null,
  branch       text         not null,
  step         varchar(32)  not null,
  request_id   varchar(255) not null,
  fold_sha     text,
  text         text         not null,
  work_id      varchar(255),
  outcome      varchar(32),
  attempts     integer      not null default 0,
  claimed_at   timestamp with time zone,
  created_at   timestamp with time zone not null,
  delivered_at timestamp with time zone,
  detail       text,
  constraint PK_branch_delivery_notice primary key (notice_key)
);

-- The sweep reads only what is still owed.
create index IX_branch_delivery_notice_owed
  on branch_delivery_notice (created_at) where outcome is null;

-- The desk's request-failed reads a fold's notices.
create index IX_branch_delivery_notice_fold
  on branch_delivery_notice (request_id, fold_sha);
