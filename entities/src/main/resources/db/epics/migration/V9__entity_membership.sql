-- ONE table for the four planning rows, and the parent/child relation as a row of its own.
--
-- WHAT THIS IS AND WHAT IT IS NOT. Epic, Ticket, Feature and Task are four tables today, and the
-- four are the same noun with different columns filled in: a titled, slugged, described thing that
-- belongs to a project, may hang under another one, and carries a handful of properties its own kind
-- cares about. The cost of that is paid in every direction at once — four services that repeat one
-- create, four repositories, four DTOs, four audit vocabularies, four slug rules, and a Feature that
-- can never be promoted to an Epic because promotion would mean moving a row between tables while
-- every id pointing at it stays behind. This table is the merge. `entity` holds the row; the
-- `archetype` column says which of the four it is; `entity_membership` holds the edge that used to
-- be `feature.epic_id` and `task.feature_id`.
--
-- NOTHING READS IT YET, AND THAT IS DELIBERATE. This file is additive in the strongest sense: not one
-- statement here touches Epic, Ticket, Feature, Task, dossier_page or auditentry, no existing column
-- changes type or nullability, and no row is copied. A later migration (V10) backfills; a later one
-- again (V11) settles the id; the four old tables are dropped last of all, after the readers have
-- moved. Until then the two shapes stand side by side and the old one is the one that answers.
--
--   V9   this file — the tables, empty
--   V10  the backfill — every Epic/Ticket/Feature/Task row copied in, ids UNCHANGED
--   V11  the id settlement (the numeric id the merged model wants)
--   V12+ the drop of the four old tables, once nothing reads them
--
-- THE IDS ARE THE SAME ID SPACE, which is the single most load-bearing decision in the file.
-- `entity.id` is `varchar(255)` exactly as `epic.id` is, because V10 copies each old row in under
-- the id it already has. Every dossier page, every audit entry, every branch name, every workspace
-- on the platform and every URL somebody has sent to somebody else names one of these strings, and a
-- merge that re-keyed the rows would break all of them at once for the sake of a tidier column. So
-- there is no identity column, no sequence and no mapping table: the old id IS the new id.
--
-- THE CONVENTIONS ARE V1'S AND V4'S, unchanged — `varchar(255)` application-assigned ids, `text` for
-- markdown, `timestamp(6) with time zone`, `causation_id uuid` born in the create table (V2 added it
-- to the four tables that predate it; a table created afterwards carries it from the start), named
-- ck_/uq_/fk_ constraints spelling out the closed vocabularies, and explicit indexes rather than
-- whatever a constraint happens to leave behind. Cross-database references stay plain indexed
-- Strings with NO foreign key — `project_id` reaches domain's Project and `repository_id` reaches
-- domain's Repository, and those live in another physical database, which is why V1 made the same
-- call for the same two columns.
--
-- THE NAMES ARE LOWER CASE HERE, not V1's unquoted mixed case. V1 relies on `create table Epic`
-- folding to `epic` so Hibernate's unquoted `Epic` finds it and no @Table annotation is needed. That
-- trick cannot be had here: the JPA class cannot be called `Entity`, because `jakarta.persistence`
-- already owns that word and an entity class named `Entity` would have to import its own annotation
-- under an alias in every file that mentioned it. The class is `WorkEntity` and carries
-- `@Table(name = "entity")`; the table is spelled in lower case to say so out loud rather than
-- relying on a fold that no longer matches a class name.

-- ---------------------------------------------------------------------------------------------
-- entity
-- ---------------------------------------------------------------------------------------------
create table entity
(
    id varchar(255) not null,

    -- The platform's uniform causation column (qits-eventstream's CausedRow): nullable, in no
    -- constraint, never a foreign key — the event it names lives in qits-events' store.
    causation_id uuid,

    -- The owning project. NO cross-database FK, the call V1 made for epic.project_id and
    -- ticket.project_id; existence is validated in `service`'s controllers, which see both
    -- databases. It is on the ROOT and on every descendant alike, unlike today's Feature and Task
    -- which reach the project by walking up to the epic: a merged tree is read project-first
    -- (the board, the listing, the SSE hint) and a walk per row to answer "whose is this" would be
    -- a join this column makes unnecessary.
    project_id varchar(255) not null,

    -- WHICH OF THE FOUR THIS ROW IS. A closed vocabulary behind a check constraint, exactly as
    -- ck_epic_status and ck_ticket_type are closed: the archetype decides which properties the row
    -- may carry, which statuses are legal on it and what may contain it, so a word nothing has
    -- declared is not an unknown kind but a row no rule applies to. Widening it (CAMPAIGN, above
    -- EPIC) is an ordinary drop-and-re-add of a NAMED constraint, which is why this one is named
    -- where V1 wrote its first one inline.
    archetype varchar(32) not null,

    title varchar(512) not null,

    -- Git-safe path segment, minted from the title at create and never changed — the rule
    -- epic.slug, ticket.slug, feature.slug and task.slug all already carry, and for their reason:
    -- it names a branch and sits in URLs people have already sent each other.
    slug varchar(255) not null,

    -- WHAT THE SLUG IS UNIQUE WITHIN, written down as a value instead of implied by a table.
    --
    -- Today there are three scopes in three tables: an epic's slug and a ticket's are unique per
    -- PROJECT (uq_epic_project_slug, uq_ticket_project_slug), a feature's per EPIC
    -- (uq_feature_epic_slug), a task's per FEATURE (uq_task_feature_slug). Merged into one table
    -- those become one rule over one column pair only if the scope is carried explicitly, because
    -- the thing a row is unique within is no longer the same column for every row. A single
    -- `unique (project_id, slug)` would forbid two features in two different epics from sharing a
    -- name, which is both wrong and immediately noticeable; a single `unique (slug)` is absurd.
    --
    -- THE RULE, and the service is what maintains it: slug_scope holds the PROJECT ID for a row
    -- with no parent (a root — an epic or a ticket today), and the PARENT ENTITY ID for a row that
    -- has one. So uq_entity_slug_scope_slug below is literally the three old constraints at once.
    --
    -- A REPARENT RECOMPUTES IT, and that is the path that needs care rather than the create. Moving
    -- a feature from one epic to another changes its scope, so its slug is judged against a new set
    -- of siblings and may collide with one of them. That collision is a VALIDATION REFUSAL the
    -- caller is told about, naming the slug and the new parent — never a constraint violation
    -- surfacing as a 500, and never a silent re-mint, because the slug is in branch names and in
    -- URLs. The path that owns it is a later task; this file owes it a column and an index that make
    -- the rule expressible, and owes the next reader the rule itself.
    --
    -- THE ONE NARROWING, stated rather than discovered: today an epic and a ticket in the SAME
    -- project may hold the same slug, because they are two tables with two constraints. Here they
    -- share the project id as their scope, so they no longer can. That is a real change and the
    -- backfill (V10) has to answer for it — a colliding pair must be re-slugged there, deliberately
    -- and with the branch names it moves accounted for, rather than being discovered as a failed
    -- migration. It is accepted because the alternative (folding the archetype into the root scope)
    -- buys a collision nobody wants and costs the property that makes this column readable: the
    -- scope of a row is the id of the thing it belongs to, and nothing else.
    slug_scope varchar(255) not null,

    description text,

    -- ONE status column for TWO lifecycles, and the archetype is what says which.
    --
    -- An EPIC's status is one of EpicStatus' five words and a TICKET's is one of TicketStatus' five;
    -- a FEATURE and a TASK have no status at all, which is why the column is NULLABLE — an absent
    -- status is the ordinary state of most rows in this table rather than a gap.
    --
    -- THE CHECK CONSTRAINT IS THE UNION AND CANNOT BE MORE THAN THAT. Nine words, because
    -- IMPLEMENTED is in both enums, and a database-level check has no way to say "these five when
    -- the archetype is EPIC and those five when it is TICKET" without a second, archetype-aware
    -- predicate that would then be a second place the vocabulary is written down. So the constraint
    -- keeps the databases honest about the closed vocabulary and the ARCHETYPE REGISTRY
    -- (control/Archetypes) is what refuses an EpicStatus on a ticket. That split is deliberate and
    -- is the same shape V1 used for the audit log: the constraint spells the vocabulary, the service
    -- spells the rule.
    status varchar(32),

    -- ---- ticket properties --------------------------------------------------------------------
    -- BUG or IMPROVEMENT (ticket.type). Named ticket_type here and not `type`: `type` in a table
    -- holding four archetypes reads as the archetype, which is the one thing it is not.
    ticket_type varchar(32),
    -- Why the ticket came about, in the reporter's words (V7). Never rewritten by a later phase.
    impetus text,
    -- Free text, not an id: the platform has no person table (ticket.assignee).
    assignee varchar(255),
    -- Stamped from the request identity, never client-supplied (ticket.created_by).
    created_by varchar(255),

    -- ---- epic property ------------------------------------------------------------------------
    -- The successor draft a superseded epic spawned (epic.superseded_by_epic_id), now pointing into
    -- this same table. Self-FK with `on delete set null` below, V1's safety net unchanged: deleting
    -- the successor clears the pointer rather than leaving it dangling.
    superseded_by_entity_id varchar(255),

    -- ---- task property ------------------------------------------------------------------------
    -- The concrete repository a task names (task.repository_id). Cross-database, so an indexed
    -- String with no FK, exactly as V1 has it.
    repository_id varchar(255),

    -- ---- the two merged columns -----------------------------------------------------------------
    -- ONE implemented marker for what were two: feature.implemented_on and task.implemented_at.
    -- They were never two facts — both mean "this is done, as of then" — and the two names are an
    -- accident of the two tables having been written a week apart. `implemented_at` wins because a
    -- timestamp answers "at", and because the epic lifecycle's guard already speaks of "the
    -- implemented markers" in the plural as one rule.
    implemented_at timestamp(6) with time zone,

    -- ONE sibling-dependency edge for what were two: feature.depends_on_feature_id and
    -- task.depends_on_task_id. Self-FK with `on delete set null`, V1's rule unchanged.
    --
    -- THIS IS NOT NESTING AND MUST NEVER BE VALIDATED AS NESTING. A dependency says "do that one
    -- first"; a membership says "this one is part of that one". They point in unrelated directions,
    -- they have unrelated cycle rules (a dependency cycle is a real and checked error; a membership
    -- cycle cannot occur at all under the depth rule), and a reader who treats this column as a
    -- parent pointer will conclude that a feature depending on its neighbour is nested inside it.
    -- The parent/child relation is entity_membership below and nothing else in this table.
    depends_on_entity_id varchar(255),

    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,

    primary key (id),
    constraint ck_entity_archetype check (archetype in ('EPIC', 'TICKET', 'FEATURE', 'TASK')),
    constraint ck_entity_status check (status is null or status in
        ('REFINING', 'IMPLEMENTATION', 'IMPLEMENTED', 'SUPERSEDED', 'ABANDONED',
         'REPORTED', 'REFINED', 'VERIFIED', 'DONE'))
);

-- The three indexes are today's three, carried over one for one. idx_entity_project_id is
-- idx_epic_project_id and idx_ticket_project_id merged; the (project_id, status) pair is
-- idx_epic_project_status and idx_ticket_project_status, in that column order because the listing
-- filters a project's rows by phase; idx_entity_repository_id is idx_task_repository_id, which
-- answers "what work names this repository" and is the one read that starts from the far side.
create index idx_entity_project_id on entity (project_id);
create index idx_entity_project_status on entity (project_id, status);
create index idx_entity_repository_id on entity (repository_id);

-- The three old slug constraints, expressed once. See the slug_scope comment above for the rule.
alter table entity add constraint uq_entity_slug_scope_slug unique (slug_scope, slug);

alter table entity
    add constraint fk_entity_superseded_by foreign key (superseded_by_entity_id)
        references entity (id) on delete set null;
alter table entity
    add constraint fk_entity_depends_on foreign key (depends_on_entity_id)
        references entity (id) on delete set null;

comment on table entity is 'The merged planning row: epic, ticket, feature or task, said by archetype.';
comment on column entity.slug_scope is 'What the slug is unique within: the project id for a root, the parent entity id otherwise.';
comment on column entity.depends_on_entity_id is 'A sibling ordering edge. NOT nesting — see entity_membership.';

-- ---------------------------------------------------------------------------------------------
-- entity_membership
-- ---------------------------------------------------------------------------------------------
--
-- THE RELATION AS A ROW, which is the second half of the merge and the half that makes promotion
-- possible. Today the edge is a column on the child (`feature.epic_id`, `task.feature_id`), so the
-- shape of the tree is baked into the child's table: a feature cannot become an epic, because an
-- epic has no `epic_id` and the row would have to move tables. Here the edge is its own row, so
-- re-archetyping a row and re-pointing its membership are two ordinary updates that a single
-- transaction can make together — which is exactly the operation the transition API exists to
-- offer, and exactly why the nesting rule has to be checked over a POST-STATE rather than one row
-- at a time (neither half of a promotion is legal on its own; see control/Nesting).
--
-- BOTH SIDES CASCADE. Deleting either end takes the edge with it, because an edge to a row that is
-- gone is not a fact about anything. The services still tear subtrees down in-service so each
-- removed row gets its own audit entry — V1's stated reading of fk_feature_epic, unchanged — and
-- this is the same safety net for a row removed outside them.
create table entity_membership
(
    id varchar(255) not null,
    causation_id uuid,

    parent_id varchar(255) not null,
    child_id varchar(255) not null,

    -- Dense and zero-based, the way dossier_page.position already is (V5): the service renumbers the
    -- affected span on a move and closes the gap a removal leaves, so the numbers are an ORDER and
    -- never a sparse key. DossierPageRepository.maxPosition/closeGapAfter and DossierService.move
    -- are the idiom to copy rather than to reinvent.
    --
    -- It is on the EDGE and not on the child, which is the placement the campaign epic needs: a row
    -- that belongs to two things has two positions, one per membership, and a column on the child
    -- could hold only one of them.
    position integer not null,

    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,

    primary key (id),

    -- AT MOST ONE PARENT PER CHILD: the hierarchy is a tree, and this is the constraint that says
    -- so. It is spelled as a unique index on child_id alone and NAMED FOR WHAT IT ASSERTS rather
    -- than for the column it happens to cover, because it is going to be relaxed and the name is
    -- what makes the relaxation readable.
    --
    -- WHAT RELAXES IT. The campaign epic adds an OVERLAPPING membership: a campaign gathers work
    -- that already hangs somewhere else, so a row will have its one structural parent and, beside
    -- it, one or more campaign memberships. That is a `kind` column on THIS table (STRUCTURAL,
    -- CAMPAIGN) and this constraint becoming partial — `unique (child_id) where kind = 'STRUCTURAL'`
    -- — which is a migration on one table and touches nothing else. It is a redesign only if the
    -- tree-ness is spread across the schema instead of standing in one named constraint, which is
    -- the whole reason it stands in one named constraint.
    constraint uq_entity_membership_one_parent_per_child unique (child_id)
);

create index idx_entity_membership_parent_position on entity_membership (parent_id, position);

alter table entity_membership
    add constraint fk_entity_membership_parent foreign key (parent_id)
        references entity (id) on delete cascade;
alter table entity_membership
    add constraint fk_entity_membership_child foreign key (child_id)
        references entity (id) on delete cascade;

comment on table entity_membership is 'The parent/child edge as a row, so a row can be re-archetyped and re-parented together.';
comment on column entity_membership.position is 'Dense, zero-based, maintained by the service — dossier_page.position''s rule.';
