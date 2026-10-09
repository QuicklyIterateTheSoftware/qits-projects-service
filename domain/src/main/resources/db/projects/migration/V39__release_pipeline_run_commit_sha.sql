-- The commit a phase run built. The QA phase is the run at the request's current fold
-- (mergedSha), so the read needs this to tell the current fold's run from a superseded
-- fold's run whose late CANCELLED frame is newer. Null on rows written before this column:
-- such a row cannot be told apart and stays a candidate.
alter table release_pipeline_run add column commit_sha varchar(255);
