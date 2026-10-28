-- WHO TOOK PART IN A RELEASE REQUEST, AND WHAT EACH SOURCE IS ABOUT (qits-1673, step 1: expand).
--
-- A request recorded who is behind it twice, release_request.requester and every source's
-- release_request_source.added_by, and both came from a body field any caller could fill in. An
-- agent could name another agent (who then received that request's failure notices, qits-1672) or
-- name dev-qits-maintenance (which made its red request "unattended": a MAINTENANCE gate ticket
-- instead of reaching the agent). Attribution routes messages now, so it is the caller's own
-- authenticated principal and nothing a body says.
--
-- Neither column could carry the answer on its own:
--   * the implied main source is always added_by null, even when main was the branch asked for, so
--     a main-only maintenance bump had no source naming anybody;
--   * a deleted branch hard-deletes its source row (ReleaseRequests.onBranchDeleted), so a request
--     whose agent's branch went away no longer named that agent anywhere but requester;
--   * requester is the first asker only.
--
-- release_request_participant is the set of principals that asked: one row per (request, principal),
-- inserted on every create, converge and join ask (an ask naming main included) and on every source
-- an internal path adds (a VERIFIED phase move, the hand-on after a release). Rows are NEVER deleted,
-- which is the whole point: a deleted branch keeps its author. Inserted with "on conflict do nothing",
-- so asking twice is asking once, and first_seen_at is when the principal first took part.
--
-- ON DELETE CASCADE for V12's reason: the suites delete request rows in bulk and nothing walks the
-- children first; a participant has no meaning without its request.
create table release_request_participant (
  request_id    varchar(255) not null,
  principal     varchar(255) not null,
  first_seen_at timestamp with time zone not null,
  constraint PK_release_request_participant primary key (request_id, principal),
  constraint FK_release_request_participant_request
    foreign key (request_id) references release_request (id) on delete cascade
);

-- The backfill: whoever the request was attributed to, and everybody who put a source on it that is
-- still there. What a deleted source row named is gone and cannot be recovered; requester covers the
-- opener of every such request, which is the live case (qits-qits-rr-223, qits-qits-rr-226).
insert into release_request_participant (request_id, principal, first_seen_at)
select id, requester, created_at
from release_request
where requester is not null
on conflict do nothing;

insert into release_request_participant (request_id, principal, first_seen_at)
select request_id, added_by, min(added_at)
from release_request_source
where added_by is not null
group by request_id, added_by
on conflict do nothing;

-- The label of one source, where it is not derivable on read: the head commit subject of a
-- maintenance/* branch ("bump(dependencies): 2 dependencies"), read from the git host when the
-- request folds and stored here so that a read never asks the git host. Null on every other source,
-- and on a maintenance source no fold has read yet; a reader then shows the branch name.
alter table release_request_source add column label varchar(512);
