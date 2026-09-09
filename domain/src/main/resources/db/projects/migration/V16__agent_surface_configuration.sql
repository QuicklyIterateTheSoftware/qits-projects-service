-- WHAT AN AGENT SESSION IS CONFIGURED WITH, ONE ROW PER SESSION SURFACE.
--
-- A session surface is WHERE IN THE PRODUCT a session was started from — `project.epics`,
-- `epic.chat`, `ticket.dispatch` — and until now everything about how such a session runs was a
-- constant, a `switch` arm or a Java text block inside the two daemons' `qits-coding-agents`
-- modules. This is where those constants become rows: the harness, the model, the effort level,
-- remote control, the permission mode, activity tracking, the system prompt, the initial prompt and
-- the built-in MCP servers the session attaches with their narrowing.
--
-- PLATFORM-WIDE, NOT PER PROJECT. There is one configuration per surface for the whole platform,
-- which is why no project_id appears anywhere below and why the surface key is the primary key
-- rather than half of a composite one. Per-project overrides are a later epic; a column added here
-- for them now would be a column nothing reads.
--
-- THE VOCABULARY IS OPEN AND DELIBERATELY UNCONSTRAINED. `surface_key` carries NO check constraint
-- listing the eight surfaces the platform has today, because adding a surface must be an additive
-- change in one Java constant and not a migration. The shipped defaults
-- (`control/AgentSurfaceDefaults`) are the vocabulary, and a surface with no row reads as its
-- shipped default rather than 404ing — so a daemon that knows a surface this store has not been
-- told about still launches.
--
-- NOTHING IS SEEDED HERE, AND THAT IS THE SAME DECISION. The eight rows are written at boot by
-- `startup/AgentSurfaceSeed` from those shipped defaults, insert-if-absent, the way
-- `ProjectAnnounceBackfill` walks its column. One source of truth for what a surface ships as, a
-- seed a unit test can assert without a database, and a ninth surface that costs a constant rather
-- than a V17. A DDL seed would have made the constants and the rows two copies that can disagree.
--
-- EMPTY IS A VALUE, WHICH IS WHY THE TEXT COLUMNS ARE NOT NULL. `project.epics` steers with an
-- EMPTY system prompt — deliberately, and it is the reason the desk axis could be added without
-- touching a running launch — so "" and "unset" must not be spellable as two different things here.
-- The same reading applies to `model` and `effort`: empty means the harness's own default, which is
-- what every launch but one renders today.
create table agent_surface_configuration
(
    surface_key       text        not null primary key,
    harness           text        not null check (harness in ('CLAUDE', 'KIMI')),
    model             text        not null,
    effort            text        not null,
    remote_control    boolean     not null,
    permission_mode   text        not null check (permission_mode in ('SKIP_PERMISSIONS', 'PROMPT')),
    activity_tracking boolean     not null,
    system_prompt     text        not null,
    initial_prompt    text        not null,
    updated_by        text,
    created_at        timestamptz not null,
    updated_at        timestamptz not null,
    causation_id      uuid
);

comment on table agent_surface_configuration is 'One configuration per session surface, platform-wide; an absent surface reads as its shipped default.';
comment on column agent_surface_configuration.model is 'Empty means the harness''s own default — not "unset".';
comment on column agent_surface_configuration.effort is 'Empty means no --effort is rendered; a harness with no effort concept ignores it.';
comment on column agent_surface_configuration.system_prompt is 'Appended to the harness''s own. Empty is a first-class value: it is what the epics desk steers with.';
comment on column agent_surface_configuration.updated_by is 'The principal behind the most recent edit; null on a row nobody has edited since it was seeded.';
comment on column agent_surface_configuration.causation_id is 'The platform event that caused this row, if one was in scope at persist.';

-- WHICH OF THE PLATFORM'S OWN MCP SERVERS A SURFACE ATTACHES, AND HOW NARROW EACH ONE IS.
--
-- Three servers exist — `repository` (qits-projects), `observability` (qits-observability) and
-- `actions` — and today's `AgentLaunchService.serversFor` decides per daemon and per scope which of
-- them are wired and what query narrowing each url carries. Those two facts are what this table
-- holds; nothing else about a server is configurable.
--
-- THE NARROWING IS THREE BOOLEANS AND NOT AN ORDERED LIST, because every url the two daemons build
-- today appends its parameters in ONE canonical order — projectId, then repositoryId, then
-- workspaceId — and the rendered command line is asserted as a literal on both harnesses. Three
-- flags in a fixed order reproduce all five of today's shapes exactly (`?projectId`,
-- `?projectId&repositoryId`, `?projectId&repositoryId&workspaceId`, `?repositoryId&workspaceId`,
-- `?repositoryId`) and cannot express an order that never existed.
--
-- `read_only` is the `agentReadOnly=true` marker an autonomous run appends, which puts
-- qits-projects' own tool filter in front of every mutating tool. It is a property of the surface —
-- `epic.autonomous` and `ticket.dispatch` carry it and nothing else does — rather than of the
-- server, which is why it sits on the attachment.
--
-- `allowed_tools` IS STORED THOUGH IT IS NOT OPERATOR-EDITABLE. The pre-approval lists stay shipped
-- constants and the editor never writes them; they are here because the two daemons' lists for the
-- SAME server key genuinely differ — the workspace daemon's `repository` list carries four write
-- exceptions the projects daemon's does not — and a per-server constant could not express that. It
-- is stored per attachment, seeded from the daemons' own lists, and left off the editor's write
-- door. Newline-delimited in one column rather than a fourth table: it is an ordered list of opaque
-- ids nothing joins on or queries by.
create table agent_surface_mcp_attachment
(
    id                text    not null primary key,
    surface_key       text    not null references agent_surface_configuration (surface_key) on delete cascade,
    server_key        text    not null,
    position          integer not null,
    narrow_project    boolean not null,
    narrow_repository boolean not null,
    narrow_workspace  boolean not null,
    read_only         boolean not null,
    allowed_tools     text    not null,
    causation_id      uuid,
    constraint uq_agent_surface_mcp_attachment unique (surface_key, server_key)
);

create index ix_agent_surface_mcp_attachment_surface on agent_surface_mcp_attachment (surface_key);

comment on table agent_surface_mcp_attachment is 'The built-in MCP servers one surface attaches, with the narrowing each url carries.';
comment on column agent_surface_mcp_attachment.position is 'Render order. Both harnesses interpolate the serialized server set into a shell argument, so order is part of the contract.';
comment on column agent_surface_mcp_attachment.read_only is 'Append agentReadOnly=true — the autonomous fence, a property of the surface rather than the server.';
comment on column agent_surface_mcp_attachment.allowed_tools is 'Newline-delimited pre-approved tool ids. Shipped, not operator-editable.';

-- THE REVISION TRAIL: WHO CHANGED A PROMPT, WHEN, AND TO WHAT.
--
-- Modelled on `AuditEntry` in the epics lineage, which is this estate's shape for edited text with a
-- history and an author — append-only, one row per write, the acting principal beside a JSON
-- snapshot of the entity as it stood. The reasons that shape was chosen there hold here too: a
-- system prompt is text somebody edits and later wants to read the earlier wording of, and the
-- question asked afterwards is always "who changed this and what did it say before".
--
-- DELIBERATELY NOT FOREIGN-KEYED to agent_surface_configuration, exactly as AuditEntry is not keyed
-- to the epic it describes: a surface that is retired from the vocabulary takes its row with it, and
-- the trail of what it used to be configured as has to survive that. `surface_key` here is a key,
-- not a relation.
--
-- The snapshot is the WHOLE configuration after the change, MCP attachments included, so a revision
-- reads on its own without reconstructing it from the ones before it. A diff is the reader's job.
create table agent_surface_configuration_revision
(
    id           text        not null primary key,
    surface_key  text        not null,
    changed_by   text,
    changed_at   timestamptz not null,
    snapshot     text        not null,
    causation_id uuid
);

create index ix_agent_surface_revision_surface on agent_surface_configuration_revision (surface_key, changed_at);

comment on table agent_surface_configuration_revision is 'Append-only history of surface configuration edits — who, when, and the whole configuration afterwards.';
comment on column agent_surface_configuration_revision.changed_by is 'The authenticated principal that made the change; null when the write was unattributed (the boot seed).';
comment on column agent_surface_configuration_revision.snapshot is 'JSON of the whole configuration as it stood after this change, attachments included.';
