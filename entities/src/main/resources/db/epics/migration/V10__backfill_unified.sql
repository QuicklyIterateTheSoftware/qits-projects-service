-- THE BACKFILL. Every Epic, Ticket, Feature and Task row copied into `entity`, and every
-- `feature.epic_id` / `task.feature_id` relation copied into `entity_membership`.
--
-- WHAT THIS IS AND WHAT IT IS NOT. V9 created two empty tables and said out loud that nothing reads
-- them. This file fills them and still nothing reads them: the four old tables answer every route,
-- every service, every DTO and every audit entry exactly as they did an hour ago. Not one statement
-- here writes to Epic, Ticket, Feature or Task — no drop, no rename, no trigger, no column change,
-- no UPDATE, not even against the one row whose slug this file decides to spell differently in the
-- new table. They are READ, four times, and left byte for byte as they were. That is deliberate and
-- it is the recovery path: until the verification door has run clean against live data — a row
-- count per archetype, a membership count per relation, a column-by-column comparison — the four old
-- tables are the truth and `entity` is a copy that can be dropped and rebuilt by re-running this
-- file. A backfill that had already mutated its sources would have taken that away on the day it
-- was most wanted.
--
--   V9   the tables, empty
--   V10  this file — the copy, ids UNCHANGED
--   V11  the id settlement (the numeric id the merged model wants)
--   V12+ the drop of the four old tables, once nothing reads them
--
-- THE IDS ARE PRESERVED VERBATIM, and there is no mapping table. `entity.id` is the old row's id,
-- character for character. Every dossier page, every audit entry, every branch name, every workspace
-- on the platform and every URL somebody has sent to somebody else names one of these strings; a
-- copy under fresh ids would need a translation table that every one of those readers would then
-- have to consult for ever, and the first reader that forgot would be a silent wrong answer rather
-- than a failure. So the copy is addressable by exactly what already addresses the original, and
-- `auditentry.entity_id`, `dossier_page.epic_id` and `dossier_page.ticket_id` all keep meaning what
-- they mean with nothing to rewrite.
--
-- THE INSERT ORDER IS EPIC, TICKET, FEATURE, TASK, THEN MEMBERSHIPS, AND EACH ARCHETYPE IS ONE
-- STATEMENT. That is a requirement rather than a tidiness: `fk_entity_superseded_by` and
-- `fk_entity_depends_on` are self-foreign-keys, so a superseded epic points at its successor and a
-- feature points at the sibling it depends on, and either may be inserted after the row that names
-- it — or before it, or in a cycle of two. Postgres fires referential-integrity triggers at the END
-- OF THE STATEMENT, not per row, so every mutual reference inside one `insert ... select` resolves
-- whatever the physical order turns out to be. Splitting an archetype into batches, or ordering the
-- select "so the referenced row comes first", would reintroduce exactly the problem this property
-- removes. The memberships come last because both of their foreign keys point at `entity`, and
-- those are across statements.
--
-- `project_id` IS NOT NULL ON EVERY ROW, WHICH IS THE ONE PLACE THE COPY DERIVES RATHER THAN COPIES.
-- An epic and a ticket carry their own. A feature has none today — it reaches its project by
-- walking up to its epic — so it INHERITS its epic's, and a task walks task → feature → epic for
-- the same value. Both joins are inner and total: `feature.epic_id` and `task.feature_id` are
-- `not null` with real foreign keys (V1), so no row can fail to find its project and no row is
-- dropped by the join. That derivation is the whole reason V9 put the column on descendants: a
-- merged tree is read project-first, and a walk per row to answer "whose is this" would be a join
-- this column makes unnecessary.
--
-- `slug_scope` IS THE THING THE ROW BELONGS TO: the project id for an epic or a ticket, the epic id
-- for a feature, the feature id for a task. With `uq_entity_slug_scope_slug` that is literally
-- uq_epic_project_slug + uq_ticket_project_slug + uq_feature_epic_slug + uq_task_feature_slug at
-- once — which is also why the one narrowing below has to be answered here and not discovered.
--
-- ---------------------------------------------------------------------------------------------
-- THE EPIC/TICKET SLUG COLLISION: THE EPIC KEEPS ITS SLUG, THE TICKET IS RE-SLUGGED.
-- ---------------------------------------------------------------------------------------------
-- Today an epic and a ticket in the same project may hold the same slug — two tables, two
-- constraints, and the branch prefixes differ (`epic/<slug>` versus `ticket/<slug>`) so no branch
-- collides either. Under `slug_scope` they share the project id as their scope and can no longer.
-- One of the pair has to move, and WHICH one is not a coin toss:
--
--   * AN EPIC'S SLUG IS LOAD-BEARING FOR A WHOLE SUBTREE. It is the `slug_scope` of every feature
--     under it, and it is a path segment of every branch beneath it — `epic/<epic>`,
--     `feature/<epic>/<f>` and `task/<epic>/<f>/<t>`. Moving it moves a scope every descendant is
--     judged in and a tree of branch names that already exist on the git host.
--   * A TICKET'S SLUG NAMES EXACTLY ONE THING: itself, and the single branch `ticket/<slug>`.
--
-- Least blast radius wins, so the epic is left alone and the ticket is the one that moves. And it
-- moves IN THE NEW TABLE ONLY — the `Ticket` row keeps the slug it has, because this file does not
-- write to the old tables at all (see above) and because the old row is still what answers every
-- route today.
--
-- THE COST, STATED RATHER THAN HIDDEN: a ticket whose slug moves may already have a work branch cut
-- at `ticket/<old-slug>`, and THAT BRANCH DOES NOT MOVE. Nothing here renames a ref, and nothing
-- should — a rename on the git host is a push somebody's workspace is standing on. The consequence
-- is that once the readers move to `entity`, such a ticket derives a branch name one character
-- different from the one its work is on, and a person has to reconcile it. That is why every
-- re-slug is announced with `raise notice`: the deployment log is where an operator learns that a
-- branch name moved, and a silent re-mint would make it something they discovered from a confused
-- agent weeks later.
--
-- THE SUFFIXING IS `Slugs.unique`'s, arithmetic included. `-2`, `-3`, … against the 40-character
-- cap; when `base + suffix` would exceed 40 the base is trimmed to `40 - length(suffix)` and any
-- trailing dashes on the trimmed head are stripped, because a slug may not end in a dash. The
-- result therefore has to be a slug the application itself could have minted — anything else would
-- be a value the writer would refuse to produce sitting in a column the writer maintains.
--
-- THE "TAKEN" SET IS EVERY EPIC SLUG AND EVERY TICKET SLUG IN THAT PROJECT, PLUS EVERY SLUG THIS
-- PASS HAS ALREADY ASSIGNED. The first two halves are what the merged scope makes siblings; the
-- third is what stops two colliding tickets in one project from both landing on `-2`. That makes
-- the assignment SEQUENTIAL, which is why it is a plpgsql block and not a window function: each
-- answer changes the set the next one is judged against. It is deterministic all the same, because
-- it reads only the immutable old tables and walks them in a fixed `(project_id, created_at, id)`
-- order — the same input produces the same assignment on every run, on every database.
--
-- THE COLLISION IS DETECTED IN SQL AND NEVER ASSUMED ABSENT. The test database has whatever a test
-- put in it; the live database has whatever three months of use put in it, and a migration that
-- assumed the narrowing was theoretical would be discovered as a unique-constraint violation in a
-- deployment rather than as a notice in a log.
--
-- ---------------------------------------------------------------------------------------------
-- `on conflict (id) do nothing`, AND NOT `where not exists`
-- ---------------------------------------------------------------------------------------------
-- Flyway will not re-run a V10 that succeeded, so this is not about the ordinary case. It is about
-- the deployment that half-applied and is retried: a connection lost mid-file, a step container
-- killed, an operator re-running the migration by hand after a rollback.
--
-- `on conflict (id) do nothing` is one statement with no read-then-write window between the check
-- and the insert, and it says "already copied" in exactly the words the primary key already says
-- it: this id is in this table. A `where not exists (select 1 from entity ...)` is a second,
-- weaker spelling of the same predicate, evaluated at a different instant from the insert it
-- guards, and it invites the reader to believe it is comparing CONTENT when it is comparing
-- presence. It is not an upsert either, and must not become one: `do update` would let a re-run
-- overwrite a row the settlement (V11) or a reader had already touched, which is the one thing a
-- retry of a copy must never do.
--
-- ---------------------------------------------------------------------------------------------
-- `entity_membership.id` IS THE CHILD'S ID
-- ---------------------------------------------------------------------------------------------
-- Not `gen_random_uuid()`, and the reason is the retry again: a random id makes every re-run mint a
-- SECOND edge for the same pair, which `on conflict (id) do nothing` cannot see and only
-- `uq_entity_membership_one_parent_per_child` would catch — as a failed migration rather than as a
-- no-op. The child's id is deterministic, is already unique, and is unique in exactly the right
-- shape: that constraint says a child has at most one parent, so "one edge per child" and "one id
-- per child" are the same statement and the primary key and the unique constraint can never
-- disagree. It is also the only value in reach that means anything — an edge's identity IS the
-- child, because the child is the end of it that can only be in one.
--
-- ---------------------------------------------------------------------------------------------
-- `position` IS DERIVED WITH A WINDOW FUNCTION, NEVER FROM INSERTION ORDER
-- ---------------------------------------------------------------------------------------------
-- `row_number() over (partition by <parent> order by created_at, id) - 1` — dense and zero-based,
-- which is what V9 declares the column to be. That ordering is not invented here: it is exactly
-- `Sort.by("createdAt").and("id")`, the sort `FeatureRepository.listByEpic` and
-- `TaskRepository.listByFeature` already apply, so the positions this file writes reproduce the
-- order the SPA and every API caller see today. The `- 1` is what makes it zero-based, and the
-- partition is what makes it dense within each parent rather than global.
--
-- The alternative — numbering by the order rows come back from the table — is wrong in a way that
-- would not be noticed for months: a heap scan's order is a fact about physical layout, so it
-- changes with a VACUUM, with an UPDATE that moves a row, and between the test database and the
-- live one. It would produce a plausible-looking dense sequence that silently disagreed with the
-- listing on some parents and not others.

-- ---------------------------------------------------------------------------------------------
-- 0. The de-collision pass
-- ---------------------------------------------------------------------------------------------
-- The working table is TEMPORARY and `on commit drop`: it is scratch for this transaction and must
-- not survive it, and it must not exist in the schema afterwards for a reader to mistake for a
-- mapping table. A retry recomputes it from the same immutable sources and reaches the same answer.
create temporary table v10_ticket_slug_fix
(
    ticket_id varchar(255) not null primary key,
    slug      varchar(255) not null
) on commit drop;

do
$$
    declare
        colliding record;
        taken     text[];
        base      text;
        suffix    text;
        head      text;
        candidate text;
        n         int;
    begin
        for colliding in
            select t.id, t.project_id, t.slug
              from Ticket t
             where exists (select 1
                             from Epic e
                            where e.project_id = t.project_id
                              and e.slug = t.slug)
             order by t.project_id, t.created_at, t.id
            loop
                -- Every slug this ticket's new scope already holds: the project's epics, the
                -- project's tickets, and whatever earlier tickets in this same pass were given.
                select coalesce(array_agg(s), '{}'::text[])
                  into taken
                  from (select e.slug as s
                          from Epic e
                         where e.project_id = colliding.project_id
                        union all
                        select t2.slug
                          from Ticket t2
                         where t2.project_id = colliding.project_id
                        union all
                        select fix.slug
                          from v10_ticket_slug_fix fix
                                   join Ticket t3 on t3.id = fix.ticket_id
                         where t3.project_id = colliding.project_id) scope_slugs;

                -- Slugs.unique's arithmetic, transcribed. Its first branch ("not taken, keep it")
                -- cannot apply here: a row reaches this loop only because its slug IS taken.
                base := colliding.slug;
                n := 2;
                loop
                    suffix := '-' || n;
                    if length(base) + length(suffix) <= 40 then
                        head := base;
                    else
                        head := regexp_replace(substr(base, 1, 40 - length(suffix)), '-+$', '');
                    end if;
                    candidate := head || suffix;
                    exit when not (candidate = any (taken));
                    n := n + 1;
                end loop;

                insert into v10_ticket_slug_fix (ticket_id, slug)
                values (colliding.id, candidate);

                raise notice
                    'V10: project % ticket % shares slug "%" with an epic; its entity row takes "%" instead. The epic keeps the slug. A branch already cut at ticket/% does NOT move.',
                    colliding.project_id, colliding.id, colliding.slug, candidate, colliding.slug;
            end loop;
    end
$$;

-- ---------------------------------------------------------------------------------------------
-- 1. EPIC
-- ---------------------------------------------------------------------------------------------
insert into entity (id, causation_id, project_id, archetype, title, slug, slug_scope, description,
                    status, ticket_type, impetus, assignee, created_by, superseded_by_entity_id,
                    repository_id, implemented_at, depends_on_entity_id, created_at, updated_at)
select e.id,
       e.causation_id,
       e.project_id,
       'EPIC',
       e.title,
       e.slug,
       e.project_id, -- a root's scope is its project
       e.description,
       e.status,
       null, -- ticket_type
       null, -- impetus
       null, -- assignee
       null, -- created_by
       e.superseded_by_epic_id,
       null, -- repository_id
       null, -- implemented_at
       null, -- depends_on_entity_id
       e.created_at,
       e.updated_at
  from Epic e
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------------------------
-- 2. TICKET
-- ---------------------------------------------------------------------------------------------
-- `coalesce(fix.slug, t.slug)` is the only place the de-collision is applied, and it applies to the
-- new row alone. `ticket.type` becomes `ticket_type`: `type` in a table holding four archetypes
-- reads as the archetype, which is the one thing it is not.
insert into entity (id, causation_id, project_id, archetype, title, slug, slug_scope, description,
                    status, ticket_type, impetus, assignee, created_by, superseded_by_entity_id,
                    repository_id, implemented_at, depends_on_entity_id, created_at, updated_at)
select t.id,
       t.causation_id,
       t.project_id,
       'TICKET',
       t.title,
       coalesce(fix.slug, t.slug),
       t.project_id, -- a root's scope is its project
       t.description,
       t.status,
       t.type,
       t.impetus,
       t.assignee,
       t.created_by,
       null, -- superseded_by_entity_id
       null, -- repository_id
       null, -- implemented_at
       null, -- depends_on_entity_id
       t.created_at,
       t.updated_at
  from Ticket t
           left join v10_ticket_slug_fix fix on fix.ticket_id = t.id
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------------------------
-- 3. FEATURE
-- ---------------------------------------------------------------------------------------------
-- No status at all — a feature has never had one, and V9's column is nullable for exactly this
-- majority of its rows. `implemented_on` lands in `implemented_at`: one fact that had two names.
insert into entity (id, causation_id, project_id, archetype, title, slug, slug_scope, description,
                    status, ticket_type, impetus, assignee, created_by, superseded_by_entity_id,
                    repository_id, implemented_at, depends_on_entity_id, created_at, updated_at)
select f.id,
       f.causation_id,
       e.project_id, -- inherited from the epic: a feature carries no project today
       'FEATURE',
       f.title,
       f.slug,
       f.epic_id, -- a child's scope is its parent
       f.description,
       null, -- status
       null, -- ticket_type
       null, -- impetus
       null, -- assignee
       null, -- created_by
       null, -- superseded_by_entity_id
       null, -- repository_id
       f.implemented_on,
       f.depends_on_feature_id,
       f.created_at,
       f.updated_at
  from Feature f
           join Epic e on e.id = f.epic_id
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------------------------
-- 4. TASK
-- ---------------------------------------------------------------------------------------------
-- The project is two joins up (task → feature → epic), both of them total: `task.feature_id` and
-- `feature.epic_id` are `not null` with foreign keys, so no task can fail to name a project.
insert into entity (id, causation_id, project_id, archetype, title, slug, slug_scope, description,
                    status, ticket_type, impetus, assignee, created_by, superseded_by_entity_id,
                    repository_id, implemented_at, depends_on_entity_id, created_at, updated_at)
select tk.id,
       tk.causation_id,
       e.project_id, -- inherited by walking task -> feature -> epic
       'TASK',
       tk.title,
       tk.slug,
       tk.feature_id, -- a child's scope is its parent
       tk.description,
       null, -- status
       null, -- ticket_type
       null, -- impetus
       null, -- assignee
       null, -- created_by
       null, -- superseded_by_entity_id
       tk.repository_id,
       tk.implemented_at,
       tk.depends_on_task_id,
       tk.created_at,
       tk.updated_at
  from Task tk
           join Feature f on f.id = tk.feature_id
           join Epic e on e.id = f.epic_id
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------------------------
-- 5. The memberships
-- ---------------------------------------------------------------------------------------------
-- The edge's timestamps and causation are the CHILD'S. The edge came into being with the child —
-- `feature.epic_id` is `not null`, so there has never been a feature without its epic — and there
-- is no other honest timestamp to write: the parent's would claim the edge existed before the child
-- did, and `now()` would claim the relation was created by this migration, which is the one thing
-- it was not.
insert into entity_membership (id, causation_id, parent_id, child_id, position, created_at,
                               updated_at)
select f.id, -- the edge's id IS the child's; see the header
       f.causation_id,
       f.epic_id,
       f.id,
       row_number() over (partition by f.epic_id order by f.created_at, f.id) - 1,
       f.created_at,
       f.updated_at
  from Feature f
on conflict (id) do nothing;

insert into entity_membership (id, causation_id, parent_id, child_id, position, created_at,
                               updated_at)
select tk.id,
       tk.causation_id,
       tk.feature_id,
       tk.id,
       row_number() over (partition by tk.feature_id order by tk.created_at, tk.id) - 1,
       tk.created_at,
       tk.updated_at
  from Task tk
on conflict (id) do nothing;
