-- THE LIVE READ MODEL OF A RELEASE PIPELINE'S PHASE RUNS.
--
-- A release is ONE pipeline of three phases with gates between them, and two of those phases are
-- qits-ci runs:
--
--   P1 . QA        a run at release/<id>@mergedSha, caused by ReleaseRequestChanged
--     |  gates     the CI verdict, and APPROVAL where the repository's policy asks for one
--   P2 . Publish   a run at <version>@commitSha, caused by SCMRelease
--     |  gate      PUBLISH -- that run green
--   P3 . Deploy    a qits-deployments deployment request, which owns no row here
--
-- Nothing about the release request's own state machine changes: RELEASED is still mid-pipeline and
-- FINALIZED is still the end, this table decides no gate, and no row here is ever read as a verdict.
-- It exists so a surface can say WHICH PHASE IS RUNNING RIGHT NOW, which `commit_build_status`
-- structurally cannot answer: that ledger is fed from BuildSuccessful/BuildFailed, qits-ci announces
-- only TERMINAL runs on those, and "queued" and "running" are precisely the states a live view is
-- about. So this is fed from BuildStatusChanged instead -- every transition of the run's own row --
-- and the two tables are complements rather than copies. Do not fold either into the other: a
-- verdict about a commit and the position of a run are different facts with different readers, which
-- is qits-ci's own stated reason for publishing both.
--
-- ONE ROW PER RUN, keyed on qits-ci's run id, upserted on every transition. Not one row per
-- (request, phase): a phase can be re-run (the rerun doors are a later feature) and collapsing the
-- runs here would bake one reader's "newest wins" policy into the storage. The DTO folds.
--
-- release_request_id IS THE CORRELATION AND IT IS NOT A FOREIGN KEY, the platform's usual reasoning
-- inverted: the request row IS in this database, but a run is another context's fact and a row about
-- it must outlive the request the way commit_build_status' rows outlive their repository. A request
-- deleted under a run in flight leaves an orphan nothing reads, which is cheaper than a cascade that
-- erases the account of what ran.
--
-- phase HOLDS QITS-CI'S OWN WORD -- RELEASE_REQUEST or RELEASE -- and never the DTO's (QA, PUBLISH).
-- The stored word is what arrived; the translation belongs at the read, where a vocabulary this
-- service does not own can grow without a migration. No check constraint, for that same reason and
-- the one status already carries here.
--
-- status is qits-ci's CiRunStatus word verbatim: QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED,
-- CONFIG_ERROR, TIMED_OUT today. No check constraint; every historical row keeps what it was
-- written with.
--
-- started_at and finished_at ARE DERIVED FROM THE TRANSITION, not bound from the payload, because
-- BuildStatusChanged carries neither: its occurredAt IS the row's own timestamp for the state it
-- just reached -- createdAt for QUEUED, startedAt for RUNNING, finishedAt for every terminal one --
-- and it rides the envelope rather than the payload. So a RUNNING frame fills started_at and a
-- terminal frame fills finished_at, each from that same instant. Both nullable: a run seen first at
-- SUCCESS (a catch-up that missed the earlier frames) has no start to invent.
--
-- updated_at IS THE ORDERING FACT and it is the frame's occurredAt, never this row's write time. The
-- bus is at-least-once and catch-up pages the log, so a QUEUED frame can arrive after the SUCCESS
-- frame of the same run; the writer refuses any frame not newer than what the row already holds, so
-- a terminal row can never be walked backwards into RUNNING by a late delivery. That comparison is
-- the whole of the convergence guarantee and it is why this column is not nullable.
--
-- causation_id is the platform's uniform column (qits-eventstream's CausedRow), set EXPLICITLY by
-- the listener from the consumed frame's own id rather than by the stamp -- the write happens under
-- the durable funnel's dispatch, where no ambient scope stands. Nullable, in no constraint, never a
-- foreign key.
create table release_pipeline_run (
  run_id             varchar(255) not null,
  release_request_id varchar(255) not null,
  repo_id            varchar(255) not null,
  phase              varchar(32) not null,
  status             varchar(32) not null,
  started_at         timestamp with time zone,
  finished_at        timestamp with time zone,
  updated_at         timestamp with time zone not null,
  causation_id       uuid,
  constraint PK_release_pipeline_run primary key (run_id)
);

-- The one question this table is asked: every phase run of a request. The listing read asks it for a
-- whole page of requests at once, which is an `in (...)` over exactly this index.
create index IX_release_pipeline_run_request on release_pipeline_run (release_request_id);

-- No backfill, and none is possible: the phase word is what makes a run a phase run, qits-ci began
-- carrying it on 2026-09-16, and nothing in this database records which historical qits-ci run was
-- which half of which release. A request open across that cutover therefore has no rows here and its
-- answer is an ABSENT pipeline block rather than an empty one -- see ReleasePipelineAssembler, which
-- states why those two must not be collapsed.
