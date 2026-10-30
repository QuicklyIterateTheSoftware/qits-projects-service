-- THE EXTERNAL MCP CATALOG'S CREDENTIALS, HELD HERE (qits-1394, epic qits-765).
--
-- A catalog entry (V18, agent_mcp_catalog_entry) names a credential_key and never a value. Until now
-- that key was read from qits-configuration, under the reserved application `qits-agent-mcp`; that
-- service is being removed, and it held no `qits-agent-mcp` application at all when this landed, so
-- there is nothing to migrate and this table starts empty.
--
-- One row per key. The value is written by PUT /projects/api/agent-mcp-catalog/credentials/{key}
-- (the `qits agent-mcp credentials set` verb), removed by the DELETE beside it, and read in exactly
-- one place: when a container's document is built and the attached servers are resolved. The
-- listing (GET .../credentials) answers keys and who set them, NEVER a value, and no log line, event,
-- revision snapshot or exception message carries one.
--
--   credential_key  `env.<VAR>` — the grammar the catalog already enforces on credential_key, kept
--                   so every reference an entry already holds stays valid unchanged
--   value           the header value, verbatim
--   updated_at      when it was last set
--   updated_by      the principal that set it
create table agent_mcp_credential (
    credential_key text        not null primary key,
    value          text        not null,
    updated_at     timestamptz not null,
    updated_by     text,
    causation_id   uuid
);

comment on table agent_mcp_credential is 'Values behind the external MCP catalog''s credential keys. Write-only over the API: the listing answers keys, never values.';
comment on column agent_mcp_credential.value is 'Secret material. Read only when a container''s document is built; never answered, logged or snapshotted.';

-- V18's comments named qits-configuration as the store; they are rewritten rather than left stale.
comment on table agent_mcp_catalog_entry is 'External MCP servers, defined once platform-wide and attached per surface. The credential is a reference into agent_mcp_credential, never material.';
comment on column agent_mcp_catalog_entry.credential_key is 'A key in agent_mcp_credential. The value is resolved when a document is built and is never stored or logged here.';
