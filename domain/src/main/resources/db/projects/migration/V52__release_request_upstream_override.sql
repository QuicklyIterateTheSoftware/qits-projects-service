-- A PERSON'S OVERRIDE OF GATE 3 FOR ONE UPSTREAM REQUEST (epic qits-897, qits-914).
--
-- Gate 3 holds a release request before QA while a request in a repository upstream of it is
-- building (a QA run going at its fold, READY, or publishing). A person may let the downstream
-- request start QA anyway, for one named upstream request: this row says who, when and why.
--
-- AN OVERRIDE IS ABOUT A PAIR OF REQUESTS, NOT A FOLD. Unlike release_request_approval and
-- release_request_automation_waiver it carries no merged_sha: a re-fold of either request keeps
-- it, and so does the upstream going inactive and building again. The person judged that this
-- downstream request need not wait for that upstream request, not that one content may ship.
-- Hence the key: one row per (request, upstream request), written again (an upsert) when a person
-- overrides the same pair once more.
--
-- NO FOREIGN KEY TO release_request, for the reasons ReleaseRequest.supersededBy states: the row is
-- the record of a person's decision and must survive the request rows it names.
create table release_request_upstream_override (
  request_id          varchar(255) not null,
  upstream_request_id varchar(255) not null,
  -- Who overrode -- a verified person (PersonCheck), never a machine.
  decided_by          varchar(255) not null,
  decided_at          timestamp with time zone not null,
  -- What they said, or null for nothing.
  note                text,
  causation_id        uuid,
  constraint PK_release_request_upstream_override primary key (request_id, upstream_request_id)
);
