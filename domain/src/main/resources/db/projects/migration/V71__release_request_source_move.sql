-- A SOURCE MOVED OUT OF A RELEASE REQUEST INTO THE ONE QUEUED AFTER IT (qits-1665).
--
-- One source alone can hold a whole request back: it conflicts, its check is red, or it is not
-- ready. Until now nothing removed a single source short of a release (detachKept, settleReleased),
-- so the way out was to force-push the branch back to main or withdraw every other source with it.
-- POST /repositories/{repoId}/release-requests/{requestId}/sources/move takes the source off the
-- request, under the repository's row lock, and puts it on the request queued behind it (minted
-- where there is none); the request it left refolds without it.
--
-- One row per move, written in the move's transaction, and the audit of it: who moved which branch,
-- from where, to where and why. It is read back as ReleaseRequestDto.sourceMoves, the moves into or
-- out of a request, so both requests say what happened.
--
--   id               the move
--   repository_id    the repository both requests belong to
--   from_request_id  the request the source left
--   to_request_id    the request queued after it, which holds the source now
--   branch           the source branch, ticket/qits-1660
--   moved_by         the authenticated principal that moved it
--   reason           why, as the mover said it; required and never blank
--   moved_at         when
--
-- ON DELETE CASCADE for V12's reason: the suites delete request rows in bulk and nothing walks the
-- children first; a move has no meaning without the requests it names.
create table release_request_source_move (
  id               varchar(255) not null,
  repository_id    varchar(255) not null,
  from_request_id  varchar(255) not null,
  to_request_id    varchar(255) not null,
  branch           varchar(512) not null,
  moved_by         varchar(255),
  reason           text         not null,
  moved_at         timestamp with time zone not null,
  constraint PK_release_request_source_move primary key (id),
  constraint FK_release_request_source_move_from
    foreign key (from_request_id) references release_request (id) on delete cascade,
  constraint FK_release_request_source_move_to
    foreign key (to_request_id) references release_request (id) on delete cascade
);

-- A request's read lists the moves into or out of it.
create index IX_release_request_source_move_from on release_request_source_move (from_request_id);
create index IX_release_request_source_move_to on release_request_source_move (to_request_id);
