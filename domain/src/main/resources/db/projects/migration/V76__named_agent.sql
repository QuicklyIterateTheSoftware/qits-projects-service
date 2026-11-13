-- A PROJECT'S NAMED AGENTS (qits-2154): further kept Claude sessions in the project's front desk
-- container, beside the desk's own, each with its own name, surface, credential and session. The
-- names are a closed registry in code (control/NamedAgents): `desk` is the front desk itself and
-- keeps its `front_desk` row (no row here, ever), `ui-builder` (surface project.ui-builder) is the
-- first one that is not the desk. Every project has every name; a row is created on first use --
-- the first prompt to that agent -- so an agent nobody prompted costs nothing, not even a token.
--
--   project_id       the project; the row goes with it (cascade). Its token is revoked before the
--                    delete by the project deletion hook, and FrontDeskTokenReconcile reaps one a
--                    failed revoke left
--   name             the agent's name, as the registry spells it ([a-z][a-z0-9-]{0,31}); its
--                    session key on the control socket is `agent:<name>`
--   surface          the agent surface its session launches as (project.ui-builder)
--   session_id       the harness session id the daemon last reported for `agent:<name>`, kept after
--                    the session stops so the next launch resumes it, as front_desk.session_id is
--   token_*          its own qits_tok_ (idp kind `named-agent`, qits:agent only, claims
--                    {project}): the id to revoke it, the value the sessionCredentials frame
--                    carries, the subject that tells its writes apart from the desk's
--   last_demand_at   the last prompt (and the session's own activity, throttled): the agent's
--                    session is asked for only while this is inside qits.projects.agent-idle-timeout
--   pending_prompt   a prompt taken while the desk container was not connected, delivered once the
--                    agent's session is asked for; null when none waits. One at a time: a second
--                    prompt while one waits is a 409
--   created_at       when the row was created
--
-- project_id is varchar(255), not uuid: it references project(id), which is varchar(255) (V1), as
-- front_desk.project_id does.
--
-- No causation_id: NamedAgent is @Uncaused, like FrontDesk -- machine state written by doors,
-- sweeps and socket frames alike.
create table named_agent (
  project_id      varchar(255) not null references project(id) on delete cascade,
  name            varchar(32)  not null,
  surface         text         not null,
  session_id      text         null,
  token_id        text         null,
  token_value     text         null,
  token_subject   text         null,
  last_demand_at  timestamptz  null,
  pending_prompt  text         null,
  created_at      timestamptz  not null default now(),
  constraint PK_named_agent primary key (project_id, name)
);
