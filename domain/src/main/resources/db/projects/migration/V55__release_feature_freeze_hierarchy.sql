-- THE FEATURE FREEZE'S THRESHOLD HIERARCHY, AND THE EXPLICIT JOIN PAST IT (epic qits-1580).
--
-- The owner's rulings on V54's design: the threshold is the most specific value SET -- the
-- repository's own, else its project's, else the platform's 3 -- and 0 turns the freeze off at the
-- level that says so. Once the active request is frozen, every ask through the create door queues
-- behind it; only a join that names the frozen request through the sources door is admitted, and
-- the source row says so.
--
--   Project.release_feature_freeze      V54's column, now NULLABLE with no default: null is "the
--                                       project's project.yml declares no release_requests.
--                                       feature_freeze", which a stored 3 could not tell apart
--                                       from a declared 3. Every row is reset to null: V54 shipped
--                                       on no release, so every stored value is V54's default
--                                       rather than a declaration, and the wrapper reconcile
--                                       (boot, and every move of a wrapper's main) writes back
--                                       whatever a project.yml does declare. The range (0..20) is
--                                       the parser's to refuse, as in V54.
--   Repository.release_feature_freeze   `feature_freeze` in the repository's own
--                                       `.config/qits/release-requests.yml` at its default branch,
--                                       which overrides its project's; null where absent. Written
--                                       by ReleaseGates, which reads that file from main anyway for
--                                       manual-review, so a request read never asks the git host.
--   release_request_source.explicit_join
--                                       whether the source joined its request by name through the
--                                       sources door while the request was frozen -- the one way
--                                       past the freeze. False on every other row.
--
-- NOTHING ELSE IS BACKFILLED: no repository has declared a freeze yet, and no source on record
-- joined past one.
alter table Project alter column release_feature_freeze drop not null;
alter table Project alter column release_feature_freeze drop default;
update Project set release_feature_freeze = null;
alter table Repository add column release_feature_freeze int null;
alter table release_request_source add column explicit_join boolean not null default false;
