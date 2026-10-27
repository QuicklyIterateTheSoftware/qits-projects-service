-- AN AGENT HEARS WHEN ITS RELEASE REQUEST FAILS ITS BUILD (qits-1672).
--
-- When a fold is REJECTED because a CI run or a release-request automation FAILED, every agent that
-- filed the request or added a source to it is told once: `fyi: your <qualified id> failed its
-- build`. One row per (request, fold, agent). The primary key is the once-per-fold rule: a retry
-- that fails the same fold again inserts nothing, so nothing is said twice.
--
-- The row is also the outbox. It is written in the transaction that rejects the fold, and the
-- delivery after the commit (or the sweep, if that is lost) fills in the rest.
--
--   agent_key    the agent id's first three groups, as its token subject carries them
--   subject      the token subject that filed or joined, as the request holds it
--   owed_at      when the rejection was written
--   claimed_at   when a delivery last took the row; a stale claim is taken again
--   attempts     how many deliveries were tried
--   agent_id     the ACTIVE agent the key resolved to; null until resolved
--   work_id      that agent's work item, which the delivery names
--   outcome      DELIVERED, LAUNCHED or RESUMED when told; NO_AGENT, NOT_ACTIVE or GAVE_UP when
--                not; null while still owed
--   told_at      when the agent was told; null when it was not
--   detail       the far side's sentence, or why nothing was said
--
-- No foreign key to release_request, release_request_approval_notice's reasoning: it is a record of
-- something said, and must not vanish with a bulk delete of requests.
create table release_request_failure_notice (
  request_id   varchar(255) not null,
  merged_sha   varchar(255) not null,
  agent_key    varchar(64)  not null,
  subject      varchar(255) not null,
  owed_at      timestamp with time zone not null,
  claimed_at   timestamp with time zone,
  attempts     integer not null default 0,
  agent_id     varchar(64),
  work_id      varchar(255),
  outcome      varchar(32),
  told_at      timestamp with time zone,
  detail       text,
  constraint PK_release_request_failure_notice
    primary key (request_id, merged_sha, agent_key)
);

-- The sweep reads only what is still owed.
create index IX_release_request_failure_notice_owed
  on release_request_failure_notice (owed_at) where outcome is null;
