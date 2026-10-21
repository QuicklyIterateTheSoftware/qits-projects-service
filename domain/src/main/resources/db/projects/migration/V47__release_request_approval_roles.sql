-- APPROVAL ROLES AND THE APPROVAL-REQUIREMENT NOTICES (epic qits-1038, task qits-1043).
--
-- A release request no longer has one yes/no "needs a person" answer: it has a list of approval
-- requirements, each naming the approval ROLES that may satisfy it -- `person`, `agent` (an agent of
-- the request's own project) or `agent@<project slug>`. An approval now records the roles its
-- approver held, because a requirement is satisfied only by an approver holding a role it admits.

-- THE ROLES THE APPROVER HELD, rendered, sorted and space-separated ("person", "agent",
-- "agent@qits"), relative to the request's project: an agent of the request's own project is
-- written `agent`. Every row before this file was made by a verified person (PersonCheck was the
-- only way through the doors), so `person` is both the backfill and the default -- the default is
-- what makes the add a single statement on a populated table, and every insert names the column
-- anyway.
alter table release_request_approval
  add column approver_roles text not null default 'person';

-- ONCE PER (request, fold, requirement, state): what has been ANNOUNCED about an approval
-- requirement. The event `ApprovalRequirementChanged` (OPEN, SATISFIED, DECLINED, VOID) and the
-- work-item comment an OPEN requirement earns are both made only for a row this table did not hold
-- yet, so a restart, a sweep and a door racing one another announce each state once. The primary
-- key IS that uniqueness; an insert that meets it inserts nothing and announces nothing.
--
-- reason, admits, excludes_authors and detail are the requirement as it was announced, so a VOID --
-- announced after a re-fold, when nothing reads the old fold's requirements any more -- can say
-- what it voids. admits is rendered like approver_roles above.
--
-- No foreign key to release_request, release_request_approval's reasoning: a notice is a record of
-- something said on the bus, and it must not vanish with a bulk delete of requests.
create table release_request_approval_notice (
  request_id       varchar(255) not null,
  merged_sha       varchar(255) not null,
  requirement_id   varchar(255) not null,
  state            varchar(32)  not null,
  reason           text,
  admits           text,
  excludes_authors boolean      not null default false,
  detail           text,
  announced_at     timestamp with time zone not null,
  constraint PK_release_request_approval_notice
    primary key (request_id, merged_sha, requirement_id, state)
);

-- Every read is "this request's notices" -- the current fold's states and the older folds' to void.
create index IX_release_request_approval_notice_request
  on release_request_approval_notice (request_id);
