-- THE PER-SERVICE MCP SERVERS ARE RETIRED; `qits` IS THE ONE PLATFORM SERVER (qits-1174).
--
-- qits-projects no longer serves its `repository` MCP server, and `observability` and `actions` are
-- gone too. Every agent reaches the platform through the central `qits` server. The store refuses
-- the three keys from this release on, so this removes the rows that still name them.
--
-- What it does, in this order:
--
-- 1. Every surface gets the `qits` attachment if it has none, appended after what it holds. A
--    surface that relied on `repository` for its platform tools would otherwise be left with none.
-- 2. `qits` is read-only marked on every surface that read-only marked a retired server
--    (`epic.autonomous` and `ticket.dispatch` as shipped). The central server honours the mark and
--    serves only its read tools, so the unattended fence stays where it was.
-- 3. Every attachment to `repository`, `observability` or `actions` is deleted. Their narrowing and
--    tool lists are columns of the same row and go with it.
-- 4. Positions are renumbered from 0 per surface, so the render order has no gaps.
--
-- IDEMPOTENT: a second run finds no retired rows, adds no `qits` row a surface already holds, and
-- renumbers to the same positions. No revision is written, as V30 and V33 wrote none: the trail
-- records edits a person made. Older revision snapshots keep naming the retired servers; they are
-- history, and nothing renders from them.
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

update agent_surface_mcp_attachment q
set read_only = true
where q.server_key = 'qits'
  and exists (select 1
              from agent_surface_mcp_attachment r
              where r.surface_key = q.surface_key
                and r.server_key in ('repository', 'observability', 'actions')
                and r.read_only);

delete from agent_surface_mcp_attachment
where server_key in ('repository', 'observability', 'actions');

update agent_surface_mcp_attachment a
set position = ordered.position
from (select id, row_number() over (partition by surface_key order by position) - 1 as position
      from agent_surface_mcp_attachment) ordered
where a.id = ordered.id
  and a.position <> ordered.position;
