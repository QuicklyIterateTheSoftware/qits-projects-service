-- THE EXTERNAL MCP SERVER CATALOG: DEFINED ONCE PLATFORM-WIDE, ATTACHED PER SURFACE.
--
-- Beside the three platform servers a surface toggles (`repository`, `observability`, `actions`,
-- V16) sits a catalog of servers this platform does not own: a key, a display name, a URL, an
-- optional header name with a qits-configuration key holding its value, and the tools pre-approved
-- for that server. Defined once and attached many times — a server is not re-entered, with its
-- token, for each of eight surfaces.
--
-- URL TRANSPORT ONLY, AND THAT IS THE RENDER PATH'S CONSTRAINT RATHER THAN A PREFERENCE. Kimi
-- carries servers protocol-native over ACP as `(key, url, tools)` with nowhere to put a stdio
-- command, and a stdio server would need its binary inside the workspace image anyway. So there is
-- no `command` column here and there must not be one.
--
-- THE CREDENTIAL IS A REFERENCE AND NEVER MATERIAL. `credential_key` names a qits-configuration
-- entry; the value is read when a container's document is built and is not stored in this database,
-- not written to a revision snapshot and not logged. When qits-configuration grows its `secret`
-- entry class the same key is served as a secret and nothing in this table moves — the reference is
-- the whole point, and a `plain` entry today is accepted deliberately.
--
-- THE KEY NAMESPACE, SETTLED HERE. qits-configuration addresses a value by (env, application, key)
-- and its key grammar is CLOSED: `ConfigurationKeys.requireKey` accepts `env.<VAR>` and the four
-- indexed families and refuses everything else, so a `secrets.` or `mcp.` prefix could not be
-- written there at all without changing that service. The axis that IS open is the application
-- segment, and it is also the axis the worry is actually about — "an MCP credential is not an env
-- var of any app". So the reference is `env.<VAR>` under the reserved application name
-- **`qits-agent-mcp`**, which no deployment deploys and therefore whose keys no deployer ever
-- renders into a container's environment. It is an operator-class entry spelled with the one key
-- grammar the store has. See `control/AgentMcpCatalog` and AGENTS.md.
create table agent_mcp_catalog_entry
(
    -- The server key as it is rendered into `mcpServers`. Primary key: one definition per key,
    -- platform-wide. `repository`, `observability` and `actions` are REFUSED on write — an external
    -- entry claiming one would silently displace a platform server in the rendered object, and the
    -- session would look normal while talking to somebody else's server. A validation, never a
    -- merge; deliberately in Java (`control/AgentMcpCatalogService`) and not a check constraint, so
    -- the reserved list stays one constant beside the built-ins rather than a copy in DDL.
    catalog_key    text        not null primary key,

    -- What an operator reads in the editor's list. Never rendered into a command.
    display_name   text        not null,

    -- http:// or https://, validated on write. There is nothing else this column may hold.
    url            text        not null,

    -- The header the credential is presented in, typically `Authorization`. EMPTY MEANS THE SERVER
    -- TAKES NO CREDENTIAL, which is a supported entry: a public read-only MCP server needs none.
    header_name    text        not null,

    -- The qits-configuration key holding the header's value — `env.<VAR>` under the reserved
    -- `qits-agent-mcp` application, per the namespace above. Empty exactly when `header_name` is:
    -- a header with no key and a key with no header are both refused on write, because either is a
    -- server that will 401 on the agent's first tool call with nothing to say why.
    credential_key text        not null,

    -- Newline-delimited pre-approved tool ids, as `agent_surface_mcp_attachment.allowed_tools` is.
    -- UNLIKE the built-ins', these ARE operator-editable: the platform ships no constant for a
    -- server it has never heard of, so the person entering the server is the only one who can say
    -- which of its tools may be auto-approved. Empty is a value and means "pre-approve nothing".
    allowed_tools  text        not null,

    updated_by     text,
    created_at     timestamptz not null,
    updated_at     timestamptz not null,
    causation_id   uuid
);

comment on table agent_mcp_catalog_entry is 'External MCP servers, defined once platform-wide and attached per surface. The credential is a qits-configuration reference, never material.';
comment on column agent_mcp_catalog_entry.credential_key is 'A qits-configuration key under the reserved `qits-agent-mcp` application. The value is resolved when a document is built and is never stored or logged here.';
comment on column agent_mcp_catalog_entry.allowed_tools is 'Newline-delimited. Operator-editable, unlike the built-in servers'' shipped lists.';

-- WHICH CATALOG ENTRIES A SURFACE ATTACHES.
--
-- A SIBLING TABLE RATHER THAN A KIND COLUMN ON agent_surface_mcp_attachment, and the reason is that
-- the two attachments have almost nothing in common. A built-in attachment carries three narrowing
-- booleans, a read-only marker and a per-attachment tool list, because the platform builds those
-- urls and fences them per surface. An external attachment carries NONE of them: the url is fixed by
-- the catalog entry, there is no query narrowing this platform could apply to somebody else's
-- server, `agentReadOnly=true` is qits-projects' own marker and means nothing over there, and the
-- tool list belongs to the entry rather than to the surface. A shared table would have been five
-- columns that are always null for half its rows, and a `kind` column deciding which half of the
-- schema applies to which row is the shape that eventually gets read wrong.
--
-- The uniqueness that matters — no key attached twice to one surface, across BOTH kinds — is not
-- expressible as one constraint over two tables and is enforced in `AgentSurfaceConfigurationService`
-- instead, together with the reserved-key rule that keeps the two vocabularies disjoint in the first
-- place.
create table agent_surface_external_mcp_attachment
(
    id           text    not null primary key,
    surface_key  text    not null references agent_surface_configuration (surface_key) on delete cascade,

    -- NOT foreign-keyed to agent_mcp_catalog_entry, deliberately. Deleting a catalog entry that a
    -- surface still attaches must be a refusal an operator reads ("three surfaces attach it"), not a
    -- constraint violation surfacing as a 500 — and the check that produces that message lives in
    -- the service. An FK would make the good message unreachable and the bad one inevitable.
    catalog_key  text    not null,

    position     integer not null,
    causation_id uuid,

    constraint uq_agent_surface_external_mcp unique (surface_key, catalog_key)
);

create index ix_agent_surface_external_mcp_surface on agent_surface_external_mcp_attachment (surface_key);
create index ix_agent_surface_external_mcp_catalog on agent_surface_external_mcp_attachment (catalog_key);

comment on table agent_surface_external_mcp_attachment is 'The catalog entries one surface attaches. Attachment order is render order, after the built-ins.';
