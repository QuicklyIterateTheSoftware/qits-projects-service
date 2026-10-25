-- A REFINEMENT ROOM IS A SESSION IN ITS PROJECT'S FRONT DESK (epic qits-770, task qits-1312).
--
-- A new room no longer gets a container, a volume, a commissioned client or a `refining/<slug>`
-- branch: it is one more session key in the front desk's daemon (the multiplex), keyed by the
-- entity id, launched from the `sessions` frame this service sends and reported back in the
-- daemon's `sessionReport`. The legacy container rooms that still stand keep their branch until
-- qits-1316 removes the container machinery; THE BRANCH IS THE RULE THAT TELLS THEM APART — a row
-- with a branch is a container room, a row without one is a session room.
--
--   branch, parent      now nullable: a session room cuts no branch and forks nothing. Every
--                       existing row keeps its values, so every existing room stays a container room.
--   last_demand_at      when somebody last asked for the room's session (the open, the ensure door,
--                       the session's own activity); the session is desired RUNNING while this is
--                       younger than qits.projects.agent-idle-timeout, STOPPED otherwise. The stop
--                       door clears it. Null on every existing row: nothing has asked for a session.
--   session_id          the harness session id the daemon last reported for the key, kept after
--                       the session stops so the next launch resumes it (`resumeSessionId`).
--   session_state       the key's last reported state: STARTING | RUNNING | STOPPED | FAILED
--                       (DaemonProtocol.ReportedState); null until the daemon first reports it.
--   session_detail      the report's sentence for a person (why STARTING or FAILED), or null.
--   session_command_id  the daemon command running the session while RUNNING, else null — the id
--                       the desk's terminal and chat sockets know it by.
--
-- NOTHING IS BACKFILLED: no existing row is a session room, so none has anything to report.
alter table refinement alter column branch drop not null;
alter table refinement alter column parent drop not null;
alter table refinement add column last_demand_at timestamptz;
alter table refinement add column session_id text;
alter table refinement add column session_state text;
alter table refinement add column session_detail text;
alter table refinement add column session_command_id text;
