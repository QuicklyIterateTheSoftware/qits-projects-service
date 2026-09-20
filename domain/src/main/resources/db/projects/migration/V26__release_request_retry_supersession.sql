-- A GREEN CI RETRY ANSWERS THE REJECTION IT RE-FIRES (ticket qits-309).
--
-- `qits ci retry` mints a NEW run at the SAME fold, carrying the id of the run it re-fires. Two
-- things then went wrong here, and either one alone was enough to strand a release request:
--
--   1. The ledger kept BOTH rows. commit_build_status is keyed on the run, and every reader folds a
--      commit's rows with any-red-wins, so the retry's green verdict landed beside the red one it
--      answered and the CI gate went on reading FAILED for ever.
--   2. Nothing could re-evaluate the request. The fold never moved -- that is what a retry IS -- so
--      no push was coming to re-arm it, the verdict path looked only at PENDING requests, and the
--      sweep dropped REJECTED into its default arm. The request sat REJECTED with a green build
--      behind it and a green QA phase drawn above it.
--
-- Two nullable columns, one per half. Neither has a foreign key and neither can: one names a run in
-- qits-ci's own database, the other names a row this very change deletes on purpose.

-- HALF ONE: THE LINEAGE. A retry names the immediately previous run only, so a retry of a retry is
-- a chain rather than a star, and clearing the whole ancestry means walking it link by link. The
-- link has to be ON the surviving row for the walk to have anything to follow: in the ordinary
-- in-order case each write deletes its predecessor and the ancestry is one row deep, but that is an
-- invariant of a delivery order nothing guarantees -- a redelivered frame, or a catch-up after this
-- service was down, leaves a gap, and a walk with no link stops at the gap and leaves everything
-- behind it red. Null means "not a retry", which is every row written before this migration and
-- every row written by a qits-ci that has not released the field yet.
alter table commit_build_status add column retry_of_run_id varchar(255);

-- Indexed because it is read in the forward direction too: before persisting a verdict the ledger
-- asks whether some row already names that run as the one IT superseded, which is what stops a
-- redelivered frame resurrecting a verdict a retry has already answered. That is a lookup per
-- verdict on a table that only grows.
create index IX_commit_build_status_retry_of
  on commit_build_status (retry_of_run_id)
  where retry_of_run_id is not null;

-- HALF TWO: WHICH RUN REJECTED THE REQUEST. REJECTED has two causes -- a red build and a person
-- declining the approval -- and a CI verdict must never undo the second. So the rejection is not
-- re-opened by state; it is re-opened by RUN: only a verdict that superseded the exact run named
-- here takes it back, and a decline names no run and never will. `detail` is not that
-- discriminator and could not be, because matching a run id back out of a sentence is a parser
-- standing in for a key.
alter table release_request add column rejecting_run_id varchar(255);

-- NOTHING IS BACKFILLED, and that is the honest answer rather than the lazy one.
--
-- For commit_build_status there is nothing to backfill from: no retry lineage was ever recorded, so
-- every existing row is "not a retry" as far as anything can know, which is exactly what null says.
--
-- For release_request it is a decision. An already-REJECTED request could have its run read out of
-- `detail` -- the sentence does contain it -- but that is the parser this column exists to avoid,
-- and it cannot tell a red build's sentence from a decline's with any rule that stays true. A null
-- here reads as "this rejection is answered by a push", which is precisely how every one of those
-- requests already behaves; the next red verdict at the next fold writes the column and the request
-- joins the new path from there. Getting the discrimination wrong in the other direction would mean
-- a CI event re-opening a request a person declined, which is the one outcome this whole change is
-- written to make impossible.
