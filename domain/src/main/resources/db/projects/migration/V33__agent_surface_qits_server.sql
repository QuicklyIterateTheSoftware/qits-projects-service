-- THE CENTRAL `qits` MCP SERVER IS ON FOR EVERY STORED SURFACE (qits-630).
--
-- `qits` joins the built-in servers (`AgentSurfaceDefaults.SERVER_QITS`): the qits CLI served over
-- MCP, one process at `<env>-qits-platform-access-mcp-service`, scoped by the caller's bearer rather
-- than by its url. The shipped default attaches it last on every surface. That reaches a surface with
-- no stored row by itself — an absent row reads as its shipped default — but every row the boot seed
-- or the editor already wrote holds its own attachment set, and there `qits` is simply absent.
--
-- WHY A MIGRATION AND NOT A READ-TIME DEFAULT. For a built-in server, the attachment row IS the
-- toggle: present is on, absent is off, and that is how an operator turns `observability` off today.
-- A rule reading "no `qits` row" as on would make `qits` the one server that cannot be turned off,
-- since a save that drops it stores exactly the state the rule reads as on. Absence is ambiguous only
-- once, now, when no row can hold `qits` because no write could attach it (the store refused the key
-- until this release). So this runs once and settles it: every stored surface gets the attachment,
-- and from here on an absent `qits` means somebody turned it off.
--
-- THE SAME ATTACHMENT THE SHIPPED DEFAULT CARRIES: no narrowing (the server is scoped by the bearer),
-- no read-only mark (the library leaves the `qits` url alone on unattended runs, so the mark would
-- claim a fence nobody applies), and an empty tool list (the daemon's own; Claude pre-approves
-- `mcp__qits__*` wherever `qits` is attached). Appended after whatever the surface already attaches,
-- so the existing servers keep their render order.
--
-- IDEMPOTENT: a surface already holding `qits` is skipped, which the unique (surface_key,
-- server_key) constraint would otherwise refuse. No revision is written, as V30 wrote none: the trail
-- records edits a person made, and the first edit after this starts from the row as it now stands.
insert into agent_surface_mcp_attachment
    (id, surface_key, server_key, position, narrow_project, narrow_repository, narrow_workspace,
     read_only, allowed_tools, causation_id)
select gen_random_uuid()::text,
       c.surface_key,
       'qits',
       coalesce((select max(a.position) + 1
                 from agent_surface_mcp_attachment a
                 where a.surface_key = c.surface_key), 0),
       false, false, false, false, '', null
from agent_surface_configuration c
where not exists (select 1
                  from agent_surface_mcp_attachment q
                  where q.surface_key = c.surface_key
                    and q.server_key = 'qits');
