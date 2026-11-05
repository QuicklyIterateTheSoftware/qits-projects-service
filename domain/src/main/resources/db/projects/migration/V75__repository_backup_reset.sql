-- A PERSON'S RESET OF A REPOSITORY'S BACKUP TWIN (qits-1885).
--
-- The backup (RepositoryService.backupToTwin) pushes main and the tags to the forge twin without
-- force, so a twin whose main holds history unrelated to ours -- a forge's README-only seed commit
-- -- refuses every backup as non-fast-forward, forever. POST /repositories/{repoId}/backup-reset
-- lets a person, or the project's delivery desk, say once that the twin's main may be replaced:
-- the door probes the twin, fast-forwards it when it is merely behind, force-updates main under a
-- lease when the histories share no commit at all, and refuses everything else. This row says who
-- decided, when, why and what moved.
--
--   id               the reset
--   repository_id    the repository whose twin was reset
--   decided_by       the verified person, or the desk's token subject (PersonCheck)
--   decided_at       when
--   reason           why, as they said it; required and never blank
--   kind             FAST_FORWARD (a plain push) or UNRELATED_RESET (main force-updated under a
--                    lease on twin_sha_before)
--   twin_sha_before  the twin's main before the push; null when the twin had no main
--   pushed_sha       our main, which the twin holds now
--
-- NO FOREIGN KEY TO repository, as release_request_upstream_override keeps none to the rows it
-- names: the row is the record of a person's decision about a forge outside the platform, and
-- outlives the repository row it names.
create table repository_backup_reset (
  id               varchar(255) not null,
  repository_id    varchar(255) not null,
  decided_by       varchar(255) not null,
  decided_at       timestamp with time zone not null,
  reason           text         not null,
  kind             varchar(32)  not null,
  twin_sha_before  varchar(64),
  pushed_sha       varchar(64)  not null,
  causation_id     uuid,
  constraint PK_repository_backup_reset primary key (id)
);

-- GET /repositories/{repoId}/backup-resets lists a repository's resets, newest first.
create index IX_repository_backup_reset_repository
  on repository_backup_reset (repository_id, decided_at);
