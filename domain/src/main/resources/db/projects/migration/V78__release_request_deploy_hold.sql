-- HOLD THIS REQUEST'S DEPLOY FOR A PERSON (qits-2268).
--
-- A per-request switch, set before the request releases: while it is on, the release still tags,
-- publishes and is posted to qits-deployments, but the deployment request it makes there waits for
-- somebody to approve it (`qits deployments requests approve --request <id>`). The switch reaches
-- qits-ci on SCMRelease.deployHold, which forwards it on SoftwareRelease; a DEPLOY rerun re-posts it.
--
--   deploy_hold     whether the deploy is held. Every request starts off -- a successor, a queued
--                   request, a moved source's next request are new rows and inherit nothing -- and
--                   a refold of the same request keeps it, since the hold is the request's.
--   deploy_hold_by  who last switched it, on or off: the caller's principal. Null on a request
--                   nobody has switched.
--   deploy_hold_at  when they did. Null with deploy_hold_by.
--
-- Switching it off keeps by/at naming who switched it off, so the request always shows who
-- decided last. Nothing is backfilled: every existing request is off and was switched by nobody.
alter table release_request add column deploy_hold boolean not null default false;
alter table release_request add column deploy_hold_by text;
alter table release_request add column deploy_hold_at timestamptz;
