-- A NAMED AGENT'S PITCHES (qits-2155): a mock snapshot of one screenshot of the UI builder, pitched
-- by a named agent (qits-2154) and kept on the server per agent, with every version of its HTML.
-- A pitch exists before any work item: a person compares it with the original and, liking it, asks
-- the agent to file a ticket or an idea that links it. It is a separate store from
-- refinement_design (V5), which belongs to a refinement and is rewritten in place.
--
-- pitch
--   id                the pitch
--   project_id        its project; the row goes with it (cascade)
--   agent_name        the named agent that pitched it, as control/NamedAgents spells it
--                     (ui-builder); the only agent that may add a version to it
--   repository_id     the repository whose UI the screenshot shows; it belongs to project_id, and
--                     the row goes with it (cascade)
--   route             the freeze's `route`
--   screenshot        the freeze's `file` key in manifest.json,
--                     <spec>/<name>[-<viewport>][-<scheme>].html
--   kind              DATA, NO_DATA, PENDING or ERROR: which state of the page the screenshot holds
--   viewport          PHONE, TABLET or DESKTOP
--   color_scheme      LIGHT, DARK, or null for an app with no schemes
--   manifest_version  the published @routes bundle version the screenshot came from: an opaque
--                     string the caller supplies, compared by the reader with the one it has open
--   title, note       what the pitch is, and anything more the agent says about it
--   archived_at       set once by an archive and never cleared; nothing is ever deleted
--   created_by        the token subject of the agent's credential that created it
--   created_at        when
--   updated_at        when it last changed: created, a version appended, archived
--
-- No check constraint on kind, viewport or color_scheme, like every enum column here but the
-- repository archetype: the doors validate them (a 400), and a third spelling in DDL is the one
-- nobody widens.
--
-- project_id and repository_id are varchar(255), not uuid: they reference project(id) and
-- repository(id), which are varchar(255) (V1).
create table pitch (
  id                uuid         not null,
  project_id        varchar(255) not null references project(id) on delete cascade,
  agent_name        text         not null,
  repository_id     varchar(255) not null references repository(id) on delete cascade,
  route             text         not null,
  screenshot        text         not null,
  kind              text         not null,
  viewport          text         not null,
  color_scheme      text         null,
  manifest_version  text         not null,
  title             text         not null,
  note              text         null,
  archived_at       timestamptz  null,
  created_by        text         not null,
  created_at        timestamptz  not null,
  updated_at        timestamptz  not null,
  causation_id      uuid         null,
  constraint PK_pitch primary key (id)
);

-- The UI builder asks for the pitches of the screenshot it has open.
create index IX_pitch_screenshot on pitch (project_id, repository_id, route, screenshot);

-- pitch_version: append-only, never rewritten. Version 1 is written with the pitch, and each later
-- one is n+1 under a lock on the pitch row.
--   pitch_id    the pitch; the versions go with it (cascade)
--   number      from 1
--   html        the whole document, styles inline; travels only as a JSON field, never as a page
--   html_bytes  its UTF-8 length, which the size limit counts
--   note        what changed, as the agent says it
--   created_at  when
create table pitch_version (
  pitch_id      uuid         not null references pitch(id) on delete cascade,
  number        int          not null,
  html          text         not null,
  html_bytes    int          not null,
  note          text         null,
  created_at    timestamptz  not null,
  causation_id  uuid         null,
  constraint PK_pitch_version primary key (pitch_id, number)
);
