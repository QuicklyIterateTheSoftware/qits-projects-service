-- THE REFINEMENT ROW STOPS STORING A RENDER OF THE EPIC.
--
-- `preamble` was written once at create as the epic's title, description and whole feature/task
-- outline (`EpicOutline.render(epic, "Refine")`). It was never recomputed, and it copied the very
-- draft the refining agent spends its session editing -- so after an hour of refinement the stored
-- text described an epic that no longer existed.
--
-- It had exactly one reader: the prompt-rewrite helper, which passes it to the daemon as that one
-- model call's context. That context is now derived from the epic at the moment the call is made,
-- by the page that has already resolved the epic to draw its header. The row keeps naming its
-- scope the way it always did -- `epic_id` is `text not null unique`, it IS the key -- so nothing
-- is added here, only the stale copy removed.
--
-- No backfill and nothing to preserve: every value in this column is a snapshot of a row that is
-- one lookup away and more current.
alter table refinement
    drop column preamble;
