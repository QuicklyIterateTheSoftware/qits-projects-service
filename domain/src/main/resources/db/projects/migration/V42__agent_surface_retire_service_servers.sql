-- THE PER-SERVICE MCP SERVERS ARE RETIRED; `qits` IS THE ONE PLATFORM SERVER (qits-1174).
--
-- qits-projects no longer serves its `repository` MCP server, and `observability` and `actions` are
-- gone too. Every agent reaches the platform through the central `qits` server. The store refuses
-- the three keys from this release on, so this removes the rows that still name them.
--
-- What it does, in this order:
--
-- 1. `qits` is read-only marked on every surface that read-only marked a retired server
--    (`epic.autonomous` and `ticket.dispatch` as shipped). The central server honours the mark and
--    serves only its read tools, so the unattended fence stays where it was.
-- 2. Every attachment to `repository`, `observability` or `actions` is deleted. Their narrowing and
--    tool lists are columns of the same row and go with it.
-- 3. Positions are renumbered from 0 per surface, so the render order has no gaps.
--
-- NO `qits` ROW IS ADDED. For a built-in server the row is the toggle: present is on, absent is off.
-- V33 gave every stored surface a `qits` row, so a stored surface without one now is a surface an
-- operator switched `qits` off on, and that choice stands. A surface that held only retired servers
-- is left with no platform server until somebody turns `qits` on in the editor.
--
-- IDEMPOTENT: a second run finds no retired rows and renumbers to the same positions. No revision is
-- written, as V30 and V33 wrote none: the trail records edits a person made. Older revision
-- snapshots keep naming the retired servers; they are history, and nothing renders from them.
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
