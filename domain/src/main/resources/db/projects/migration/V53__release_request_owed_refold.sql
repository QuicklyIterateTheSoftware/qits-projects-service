-- A RE-FOLD AN AUTOMATION'S COMMIT IS OWED, AND GATE 3'S INTERRUPT AT MOST ONCE PER FOLD (qits-1570).
--
-- Every push to a branch an open request names re-folds it at once, and a re-fold that replaces a
-- sha cancels the request's running QA, restarts its pre-run and lapses its approval. qits-maintenance
-- commits onto the request's own maintenance/automations/<kind>/<request> branch once per member
-- release, so during a release wave a request was re-folded every few minutes and never reached
-- READY (qits-qits-rr-190 went green five times; qits-projects-service-rr-342 had 8 of 12 gate runs
-- cancelled). A move of an automation branch while the current fold is IN FLIGHT -- QA announced at
-- it and no verdict yet, or green and waiting on its approval -- is now deferred: the fold is left
-- alone and this row says a re-fold is owed. It is paid once the fold is no longer in flight (a red
-- verdict, a decline, QA un-announced, the sweep's backstop); any other re-fold pays it for free,
-- since a fold reads every source's head; READY and RELEASED never pay it (the release ships the
-- fold that was gated, and the next request's automations re-plan whatever the branch carried).
--
--   refold_owed_at      when the first deferred move arrived; null when nothing is owed. Several
--                       moves during one run coalesce onto it -- one re-fold pays them all.
--   refold_owed_reason  the newest deferred move's sentence ("a push moved <branch> to <sha>"),
--                       carried into the owed re-fold's own sentence.
--
-- qa_interrupted_sha is the fold gate 3's interrupt (qits-909) has already cancelled QA at once. The
-- interrupt cleared qa_announced_sha and cancelled the request's QA runs every time an upstream
-- started building -- unbounded per fold. With this stamp a second upstream build at the same fold
-- holds nothing: the QA run the first hold re-asked for keeps running.
--
-- NOTHING IS BACKFILLED: nothing was owed before this column existed, and every fold on record may
-- still be interrupted once, which is what the interrupt did until now.
alter table release_request add column refold_owed_at timestamp with time zone;
alter table release_request add column refold_owed_reason text;
alter table release_request add column qa_interrupted_sha varchar(255);
