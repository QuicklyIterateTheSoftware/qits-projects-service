-- HOW A PROJECT'S FRONT DESK IS KEPT (qits-767).
--
-- Declared in the project's wrapper at `.config/qits/project.yml` as `front_desk.lifecycle`
-- (ProjectConfigParser) and re-read whenever the wrapper's main moves, at boot and on the manual
-- reconcile — the same pass that already stores `supports_environments` (V28).
--
-- NOT NULL DEFAULT 'ON_DEMAND', for V28's reason: every row that exists predates the key, and an
-- absent key means ON_DEMAND, so the default backfills them correctly. The check constraint keeps
-- the column to the two values FrontDeskLifecycle names.
alter table Project add column front_desk_lifecycle text not null default 'ON_DEMAND'
    check (front_desk_lifecycle in ('ALWAYS_ON', 'ON_DEMAND'));
