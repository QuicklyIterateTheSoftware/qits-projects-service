# The unified entity model

Epic, Ticket, Feature and Task become **one table discriminated by an archetype**, with the
parent/child relation as a row of its own. This file is the contract the rest of that work builds
against: what shipped in V9, which old field became which column, what each archetype declares, and
every decision that had to be made along the way.

**The two roots read and write the new model now**; the two descendants do not. V9 was additive in
the strongest sense — no existing table, column, entity, service or controller touched, no row
copied — and V10 copied the rows and still read none of them. What changed after that is "The
cutover of the two roots" below: `EpicService`, `TicketService`, `EpicLifecycle` and
`TicketLifecycle` answer every read and judge every rule against `entity`, while `FeatureService`,
`TaskService` and `DossierService` are untouched and still read and write the four old tables.

## Reserved migration versions

| version | what it is | state |
| --- | --- | --- |
| `V9__entity_membership.sql` | the two tables, empty | **shipped** |
| `V10__backfill_unified.sql` | the backfill — every Epic/Ticket/Feature/Task row copied in, **ids unchanged** | **shipped** |
| `V11` | the id settlement (the numeric id the merged model wants) | reserved |
| `V12+` | the drop of the four old tables, once nothing reads them | reserved |

The ids are the **same id space**: `entity.id` is `varchar(255)` exactly as `epic.id` is, because
V10 copies each old row in under the id it already has. Every dossier page, audit entry, branch
name, workspace and URL on the platform names one of those strings, so a merge that re-keyed the
rows would break all of them at once for the sake of a tidier column.

## The DDL as shipped

```sql
create table entity
(
    id varchar(255) not null,
    causation_id uuid,
    project_id varchar(255) not null,          -- no cross-DB FK, indexed String
    archetype varchar(32) not null,            -- ck_entity_archetype
    title varchar(512) not null,
    slug varchar(255) not null,
    slug_scope varchar(255) not null,          -- uq_entity_slug_scope_slug, with slug
    description text,
    status varchar(32),                        -- ck_entity_status, the UNION of both enums
    ticket_type varchar(32),
    impetus text,
    assignee varchar(255),
    created_by varchar(255),
    superseded_by_entity_id varchar(255),      -- self-FK, on delete set null
    repository_id varchar(255),                -- no cross-DB FK, indexed String
    implemented_at timestamp(6) with time zone,
    depends_on_entity_id varchar(255),         -- self-FK, on delete set null. NOT nesting.
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id)
);
create index idx_entity_project_id     on entity (project_id);
create index idx_entity_project_status on entity (project_id, status);
create index idx_entity_repository_id  on entity (repository_id);

create table entity_membership
(
    id varchar(255) not null,
    causation_id uuid,
    parent_id varchar(255) not null,           -- fk -> entity(id) on delete cascade
    child_id varchar(255) not null,            -- fk -> entity(id) on delete cascade
    position integer not null,                 -- dense, zero-based
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id),
    constraint uq_entity_membership_one_parent_per_child unique (child_id)
);
create index idx_entity_membership_parent_position on entity_membership (parent_id, position);
```

`ck_entity_status` spells nine words: `REFINING, IMPLEMENTATION, IMPLEMENTED, SUPERSEDED,
ABANDONED, REPORTED, REFINED, VERIFIED, DONE` — the union of `EpicStatus` and `TicketStatus`, which
overlap on `IMPLEMENTED`. A check constraint has no way to say "these five when the archetype is
EPIC" without becoming a second place the vocabulary is written down, so **the constraint spells the
vocabulary and `control/Archetypes` spells the rule**. That split is V1's, applied again.

## The column-per-property map

| old table.column | new column | note |
| --- | --- | --- |
| `epic.id`, `ticket.id`, `feature.id`, `task.id` | `entity.id` | unchanged values; one id space |
| `epic.project_id`, `ticket.project_id` | `entity.project_id` | now on **every** row, descendants included |
| *(derived by walking up)* | `entity.project_id` | a feature/task no longer reaches its project by a join |
| — | `entity.archetype` | new: the discriminator |
| `*.title` | `entity.title` | |
| `*.slug` | `entity.slug` | still minted at create, never re-derived |
| *(implied by the table)* | `entity.slug_scope` | new: see below |
| `*.description` | `entity.description` | |
| **`epic.status` + `ticket.status`** | **`entity.status`** | **merged.** One column, two vocabularies, a `String` in Java |
| `ticket.type` | `entity.ticket_type` | renamed: `type` in a four-archetype row reads as the archetype |
| `ticket.impetus` | `entity.impetus` | |
| `ticket.assignee` | `entity.assignee` | |
| `ticket.created_by` | `entity.created_by` | still stamped, never client-supplied |
| `epic.superseded_by_epic_id` | `entity.superseded_by_entity_id` | self-FK within the merged table |
| `task.repository_id` | `entity.repository_id` | |
| **`feature.implemented_on` + `task.implemented_at`** | **`entity.implemented_at`** | **merged.** One fact that had two names |
| **`feature.depends_on_feature_id` + `task.depends_on_task_id`** | **`entity.depends_on_entity_id`** | **merged.** A sibling ordering edge, **not** nesting |
| `feature.epic_id`, `task.feature_id` | `entity_membership` row | the relation becomes a row |
| `*.causation_id`, `*.created_at`, `*.updated_at` | same | unchanged |

### The three merges, argued

- **`implemented_on` / `implemented_at` → `implemented_at`.** These were never two facts; both mean
  "this is done, as of then", and the two names are an accident of the two tables having been
  written apart. `implemented_at` wins because a timestamp answers "at", and because
  `EpicLifecycle` already speaks of "the implemented markers" in the plural as one rule.
- **`depends_on_feature_id` / `depends_on_task_id` → `depends_on_entity_id`.** One property spelled
  twice because it lived in two tables. **It is not nesting and must never be validated as
  nesting**: a dependency says "do that one first", a membership says "this one is part of that
  one". They have unrelated cycle rules — a dependency cycle is a real error the services already
  check for, a membership cycle cannot occur at all under the depth rule.
- **`EpicStatus` / `TicketStatus` → `status`.** Stored as the enum's own `name()`. In Java it is a
  `String` and **deliberately not a merged nine-word enum**: that would give `IMPLEMENTED` a single
  identity across two lifecycles that mean different things by it, and would leave every existing
  switch over the two real enums with a third vocabulary to translate from.

## The archetype table

Declared in `epics/control/Archetypes.java`, as data. Nothing else re-decides any of it.

| archetype | depth | may be a root | requires | permits (beyond required) | legal statuses |
| --- | --- | --- | --- | --- | --- |
| `EPIC` | 0 | yes | `TITLE` | `SLUG`, `DESCRIPTION`, `STATUS`, `SUPERSEDED_BY` | the five `EpicStatus` words |
| `TICKET` | 0 | yes | `TITLE`, `TICKET_TYPE`, `IMPETUS`, `STATUS` | `SLUG`, `DESCRIPTION`, `ASSIGNEE`, `CREATED_BY` | the five `TicketStatus` words |
| `FEATURE` | 1 | no | `TITLE` | `SLUG`, `DESCRIPTION`, `DEPENDS_ON`, `IMPLEMENTED_AT` | none |
| `TASK` | 2 | no | `TITLE`, `REPOSITORY_ID` | `SLUG`, `DESCRIPTION`, `DEPENDS_ON`, `IMPLEMENTED_AT` | none |

`required` is a subset of `permitted` for every archetype, and a kind permits `STATUS` exactly when
it declares status words. Both invariants are asserted at **class-initialisation** time, so a bad
declaration is a refusal to start naming the archetype rather than a rule that can never fire.

**What a check answers.** `Archetypes.validate(EntityState)` returns **every** violation, never the
first, in vocabulary order. Three questions, all three always asked: every required property
present; no property the archetype has no slot for; and the status word, when there is one, in this
archetype's lifecycle. **A foreign property is refused, not silently dropped** — a value arriving on
a kind that cannot hold it means the caller and the model disagree about what is being written, and
dropping it would lose the value and the disagreement together.

Violations are structured (`ArchetypeViolation`: archetype, property, reason, detail) so a caller
can put the complaint on the offending field, with `message()` for a log line or a plain error body.

## The cutover of the two roots

`EpicService`, `TicketService`, `EpicLifecycle` and `TicketLifecycle` read and write `entity`. That
table is the source of truth for every value a caller sees and for every rule either service
applies: the status a transition is judged against, the slugs a create mints around, the two
listings the board draws, and the `createdAt`/`updatedAt` a write answers with. Nothing above them
moved by one byte — `EpicDto` and `TicketDto` keep every field, in order, under the same JSON names;
the five epic and ticket controllers, both mappers, the MCP tools, `DossierService` and
`EpicChangeHints` are untouched; and every route, status code and error body is what it was.

What makes that possible is that the two services still **return** `Epic` and `Ticket`. They are
built by `control/WorkEntityProjections` as detached projections of the `entity` row — a fresh
object that is never persisted, merged or attached — and a projection is only ever taken after an
explicit flush, because `@CreationTimestamp` and `@UpdateTimestamp` are populated at flush and a
create is promised a `createdAt` the moment it returns. The same projection is what goes into the
audit log as the snapshot, so the JSON in `auditentry.snapshot` keeps exactly the shape it had.

### The descendants stay on the old tables, and the oracle is why

The subtree walks — `stampImplemented`, `supersede`'s deep copy with its `dependsOn` remap, and
`delete`'s manual cascade with one DELETE audit row per feature and task — still run over
`FeatureRepository` and `TaskRepository`. The alternative was to walk `entity_membership` instead,
and the existing tests refuse it rather than anybody preferring it this way: `FeatureService` and
`TaskService` are out of this task's scope and still write only the old `feature` and `task` tables,
so a feature created after this change has **no `entity` row at all**. A membership-driven walk would
find nothing, silently, and `EpicServiceTest.deleteCascadesToFeaturesAndTasks` and
`deleteRecordsAuditForWholeSubtreeAndSurvivesDeletion` would both go red. The walks move in the task
that moves those two services.

One membership-aware addition is made anyway, because it costs nothing and prevents debris:
`EpicService.delete` also removes the descendant `entity` rows reachable from the epic through
`entity_membership`, level by level with `childrenOfAll` and `listByIds` — bulk reads, never one
query per row. V10 backfilled every feature and task into `entity`, so without it a delete would
leave orphans with nothing above them. The membership rows themselves go with the FK's
`on delete cascade`, and no audit row is written for them: the feature/task DELETE entries the same
method already records are about the same planning rows.

### The legacy row is a write-behind MIRROR

`EpicService.mirrorLegacyRow` and `TicketService.mirrorLegacyRow` write the old `epic` and `ticket`
row from the entity row, after it, in every path. **Nothing in either service reads what it wrote**,
and neither row is ever returned to a caller.

It is there because three things still name those two tables and none of them is part of this
change. `fk_feature_epic` (`feature.epic_id` → `Epic.id`) and `fk_ticket_comment_ticket`
(`ticketcomment.ticket_id` → `Ticket.id`) are live foreign keys, as are the dossier's two owner
columns; and `FeatureService`, `TaskService` and `DossierService` all read the old row — the first
two to ask `EpicLifecycle` what the epic's phase permits, the third to resolve a page's owner.
Dropping the write breaks every one of them on the first create. **The mirror is deleted in the next
task of this epic**, with the three readers and the two tables.

### An epic and a ticket now share one slug scope

Both archetypes mint their slug with `slug_scope = projectId`, against `WorkEntityRepository.slugsInScope`.
That is the narrowing "The one narrowing, stated rather than discovered" below already argues for and
V10 already answered for in the backfill; this is the writer's half of it. No existing test depended
on the two being independent scopes. `Slugs.slugify` and `Slugs.unique` are unchanged, 40-character
cap included, so a slug minted now is a slug either old writer would have minted.

### The registry judges the ordinary write, with one named exception

`Archetypes.validate(WorkEntity)` runs on every create and every update in both services, and a
candidate the registry refuses is a 400 whose message joins every violation — which is what
`validate` returning all of them rather than the first is for.

**The exception is `IMPETUS` on the ticket update path alone.** The registry declares it *required*
of a `TICKET`, which is right about intake and is enforced at create; V7 made the column nullable on
purpose, because rows that predate it have no impetus and because clearing one is asserted behaviour
(`TicketServiceTest.theClearFlagsAreWhatEmptyTheNullableFields`,
`TicketApiTest.theClearFlagsAreWhatEmptyTheNullableFields`). Enforcing the registry there would turn
an accepted write into a refusal, which is a contract change and does not belong in a task about
storage. So `TicketService.theImpetusTheColumnStillAllowsToBeAbsent` tolerates exactly that property
with exactly the missing-required reason, on exactly that path, as a named and commented predicate
rather than a silent skip. Everything else — creates, every epic write, a foreign property, a status
word from the other lifecycle — is refused with no exception. **A later task reconciles the registry
and the column**, and the predicate goes with it.

### What did not change

The retry seams are where they were. `ReadPatience.hold` still wraps the list reads outside any
transaction, `WritePatience.hold`/`run` still replaces `@Transactional` on the write seams and
flushes the `epics` persistence unit last, nothing gained a `@Transactional`, validations that need
no row still run before the wrap, and ids and slugs are still minted inside it. Both listings are
still one query and nothing is resolved per row.

Two test fixtures moved with the storage and no assertion did: `ConnectionLosingEpics` now severs
`WorkEntityRepository.listByProjectAndArchetype` and `FailingEpicWrites` now fails after
`WorkEntityRepository.persist`, because those are the read and the write an epic list and an epic
create actually make now. `EpicsTestSupport.wipe()` clears the two new tables, children first.

## The nesting rule

`epics/control/Nesting.java` — one place, sitting on the registry, naming no archetype anywhere.

> **A membership is legal when the parent's depth is strictly less than the child's.**

- **Levels may be skipped.** An epic holding a task directly is legal (0 < 2). The rule is about
  containment, not about a fixed number of rungs; forbidding this would mean inventing a feature
  nobody wanted every time an epic needs one concrete piece of work.
- **Equal or above is refused**: epic-under-epic, ticket-under-ticket, task-under-task, and
  ticket-under-epic — the last because the two roots are declared at the same depth, which is V4's
  "nothing joins the two tables and nothing should" expressed as a rule instead of as prose.
- **A root has no membership**, and whether a kind may be one is declared (see below).
- **Cycle safety.** A graph legal by depth cannot cycle — every edge strictly increases depth, so a
  walk upwards terminates — and it is asserted anyway, as a bounded walk over facts already in hand.

### It is judged over a post-state

`Nesting.check(Collection<EntityFact>, EntityFacts)` — one call, every violation.

An `EntityFact` is `(id, archetype, parentId)`; a null parent means a root and never "unchanged",
because a partial fact would make detaching an entity impossible to express. Stated facts override
the store; **everything else is read from it, in both directions**:

- **upwards**, because a child needs its untouched parent's archetype;
- **downwards**, because re-archetyping a row re-judges every child it already has.

`EntityFacts` is the seam for that reading — bulk by shape, with an in-memory implementation
(`EntityFacts.of`) so the rules are unit-testable with no database and no Quarkus application.

**Why a post-state at all.** The operation this exists for is a promotion: a feature becomes an epic
and stops hanging under the one it was part of. Neither half is legal alone — re-archetype first and
there is an epic under an epic; reparent first and there is a feature at the root — and only the two
together are a legal tree. A row-by-row validator refuses an operation that is correct, whichever
order it is handed. `NestingTest.aPromotionIsLegalOnlyWhenItsHalvesAreAppliedTogether` asserts all
three of those.

## The `slug_scope` rule

`slug_scope` holds the **project id** for a row with no parent, and the **parent entity id** for a
row that has one. With `unique (slug_scope, slug)` that is literally today's three constraints at
once: `uq_epic_project_slug` + `uq_ticket_project_slug` (roots), `uq_feature_epic_slug` (features in
an epic), `uq_task_feature_slug` (tasks in a feature).

A single `unique (project_id, slug)` would forbid two features in two different epics from sharing a
name; a single `unique (slug)` is absurd. The scope has to be carried explicitly because the thing a
row is unique within is no longer the same column for every row.

**The service maintains it on write, and a reparent recomputes it.** `slug` stays immutable
(`@Column(updatable = false)`) and `slug_scope` does not — that separation is the point: a move
leaves the slug alone, so the branch names and URLs it is in do not move, and the slug is simply
judged against a new set of siblings. **A collision found there is a validation refusal** naming the
slug and the new parent, never a constraint violation surfacing as a 500 and never a silent re-mint.
The path that owns that refusal is a later task; V9 owes it a column and an index that make the rule
expressible.

## Decisions this document is the record of

Everything below was open and is now settled.

### 1. `CAMPAIGN` goes to depth **-1**; the existing depths do not move

Depths are **relative and only their order matters**. The nesting rule is "strictly less than" and
there is no arithmetic on depths anywhere — no "one level down", no maximum, no count — so a kind
declared above the epics takes a negative number and `EPIC 0 / FEATURE 1 / TASK 2` stay exactly as
they are. Renumbering the estate (campaign 0, epic 1, …) would be a second answer to a question
every reader has already been given, and would silently invalidate anything that had written `1`
down as "feature".

Depth is a **declared field on `ArchetypeSpec`**, never `Archetype.ordinal()`: an ordinal-derived
depth would require inserting a constant at position zero of an enum whose names are in a check
constraint and in every row of a table.

### 2. Rootness is a declared flag, not derived from depth

`ArchetypeSpec.mayBeRoot`. Depth answers "what may contain what"; rootness answers "what may stand
alone". They agree today only by accident — the two roots happen to be the two shallowest kinds —
and they come apart the moment a campaign is declared above them: an epic stops being shallowest and
must go on being a legal root. Deriving rootness from "is the minimum depth" would revoke
epic-as-root on the day the campaign lands, with nothing in the diff saying so.

So the root archetype set **is** constrained, and widening it is one word.

### 3. Violations are records, not strings, and come back all at once

`ArchetypeViolation(archetype, property, reason, detail)` over an `EntityProperty` vocabulary, and
`NestingViolation(entityId, archetype, parentId, parentArchetype, reason)`. Both carry a
`message()`. A caller fixing one problem per round trip is the failure mode both exist to avoid, and
it is worse for nesting, where the fixes are moves: told one at a time, a caller walks a tree
through several invalid shapes to reach a valid one.

A nesting violation always names the **child**. A parent is never at fault for what somebody hung
under it.

### 4. The nesting validator is its own class

`control/Nesting`, not a method group on `Archetypes`. One place either way; a separate class because
`Archetypes` answers questions about **one row** and `Nesting` answers one about **a graph**, and
they take different inputs (`EntityState` vs a post-state plus a store). Both callers — the ordinary
write and the multi-entity transition — use both, so neither is hidden behind the other.

### 5. `WorkEntity`, not `Entity`

`jakarta.persistence.Entity` owns the word. A class named `Entity` would have to import its own
annotation under an alias in every file that mentioned it, and `import ...epics.entity.Entity` could
not be read without checking which of the two was meant. The **table** is `entity` — that name is
right and it is what a reader of the SQL sees — so the class carries `@Table(name = "entity")`.

This is the one place in the module where a table name and a class simple name deliberately
disagree; V1's other tables rely on unquoted mixed case folding to lower case and need no `@Table` at
all. The table in V9 is therefore spelled in lower case, to say so out loud rather than to rely on a
fold that no longer matches a class name.

### 6. `entity.status` is a `String` in Java

See "the three merges" above. `EntityState` carries it beside the property set and normalises the
two against each other, so `STATUS`-present and a non-null status cannot disagree.

### 7. `SLUG` is permitted everywhere and required nowhere

It is minted by the writer from the title (`Slugs.slugify`), not supplied by a caller. Declaring it
required would fail every create before the writer had run; declaring it absent would let a caller's
slug through unexamined.

### 8. `STATUS` is required of a ticket and merely permitted on an epic

This asymmetry looks like an oversight and is not. An epic's phase is minted by the writer — every
epic starts `REFINING`, set by `EpicService.create` and never by a caller — so demanding it of a
candidate would fail every create, exactly as for `SLUG`. A ticket's status is a statement the
intake surfaces already make and the transition API moves; the five words are the ticket's whole
lifecycle and a ticket without one is not a ticket in any phase.

The column is nullable either way, because features and tasks have no status at all.

### 9. Position lives on the **edge**, not on the child

`entity_membership.position`, dense and zero-based, `dossier_page.position`'s rule and
`DossierPageRepository.maxPosition`/`closeGapAfter` + `DossierService.move` as the idiom to copy.
On the edge because the overlapping campaign membership needs it there: a row that belongs to two
things has two positions, one per membership, and a column on the child could hold only one.

### 10. One parent per child, named for what it asserts

`uq_entity_membership_one_parent_per_child unique (child_id)`. The campaign epic adds an overlapping
membership kind, so the relaxation is a `kind` column on this table (`STRUCTURAL`, `CAMPAIGN`) and
this constraint becoming partial — `unique (child_id) where kind = 'STRUCTURAL'`. That is a
migration on one table, which is only true because the tree-ness stands in **one named constraint**
rather than being spread across the schema.

## The one narrowing, stated rather than discovered

**Today an epic and a ticket in the same project may hold the same slug.** They are two tables with
two constraints, and nothing stops it; the branch prefixes differ (`epic/<slug>` versus
`ticket/<slug>`) so no branch collides either.

Under `slug_scope` they share the project id as their scope and **can no longer**. That is a real
change, it is live for every epic and ticket written since the cutover above, and **V10 answers for
it** in the rows that predate it (see "The backfill, as shipped" below): the colliding pair is
detected in SQL, the epic keeps its slug and the ticket's `entity` row is re-slugged, deliberately
and with the branch name it moves written into the deployment log — rather than being discovered as
a migration that will not apply against the live database.

It is accepted because the alternative — folding the archetype into the root scope
(`<projectId>:EPIC`) — buys a collision nobody wants and costs the property that makes the column
readable: **the scope of a row is the id of the thing it belongs to, and nothing else.** If the
backfill finds the collision is common rather than theoretical, that escape hatch is one line in the
writer and one sentence here.

## The backfill, as shipped

`V10__backfill_unified.sql` is the copy and nothing more: four `insert ... select` statements into
`entity`, two into `entity_membership`, and a plpgsql block that decides one ticket slug. It reads
the four old tables and **writes to none of them** — no drop, no rename, no trigger, no column
change, no UPDATE, not even against the row whose slug it spells differently on the other side. They
are the recovery path until the verification door has run clean against live data: `entity` can be
emptied and rebuilt by re-running the file.

**Each archetype is ONE statement, in the order EPIC, TICKET, FEATURE, TASK.** That is a
requirement, not tidiness: `fk_entity_superseded_by` and `fk_entity_depends_on` are self-foreign-keys
and a row may be inserted before the row it names, or in a mutual pair. Postgres fires RI triggers at
the end of the statement, so every reference inside one `insert ... select` resolves whatever the
physical order turns out to be. The memberships come last because both of their keys reach `entity`
across statements.

**`project_id` is derived exactly once, upwards.** A feature inherits its epic's, a task walks
task → feature → epic. Both joins are inner and total (`feature.epic_id` and `task.feature_id` are
`not null` with real FKs), so no row is lost to the join.

### The de-collision rule: the epic keeps, the ticket moves

A colliding epic/ticket pair in one project is detected in SQL — never assumed absent, because the
live database is not the test database — and **the epic keeps its slug**. An epic's slug is the
`slug_scope` of every feature under it and a path segment of `epic/<e>`, `feature/<e>/<f>` and
`task/<e>/<f>/<t>`; a ticket's slug names one thing and one branch, `ticket/<slug>`. Least blast
radius wins.

The ticket's **`entity` row** takes the next free `-2`, `-3`, … under the 40-character cap, with the
base trimmed to `40 - length(suffix)` and trailing dashes stripped from the trimmed head — that is
`Slugs.unique`'s arithmetic transcribed, so the result is a slug the writer itself could have minted.
The taken set is every epic slug and every ticket slug in the project **plus every value this pass
has already assigned**, which is what stops two tickets whose slugs trim to one head from both
landing on `-2`; that makes the assignment sequential, hence a `do $$ … $$` block walking the
colliding rows in `(project_id, created_at, id)` order into a `on commit drop` working table the
ticket insert reads through `coalesce(fix.slug, t.slug)`. It is deterministic because it reads only
the immutable old tables in a fixed order.

**The cost, stated rather than hidden:** a ticket whose slug moves may already have work on a branch
cut at `ticket/<old-slug>`, and **that branch does not move**. Nothing here renames a ref and nothing
should. Every re-slug is announced with `raise notice` naming the project, the ticket, the old slug
and the new one, because the deployment log is where an operator learns that a branch name moved.

### Idempotence is `on conflict (id) do nothing`

Flyway will not re-run a V10 that succeeded, so this is about the half-applied deployment that was
retried. `on conflict (id) do nothing` is one statement with no read-then-write window, and it says
"already copied" in the words the primary key already says it: this id is in this table. A
`where not exists` is a weaker second spelling of the same predicate, evaluated at a different
instant from the insert it guards. It is deliberately **not** an upsert: `do update` would let a
retry overwrite a row the settlement or a reader had already touched.

### `entity_membership.id` is the child's id

Not `gen_random_uuid()` — a random id would make every re-run mint a second edge for the same pair,
which the `on conflict (id)` clause cannot see and only
`uq_entity_membership_one_parent_per_child` would catch, as a failed migration rather than a no-op.
The child's id is deterministic and unique by construction, since that constraint already says a
child has at most one parent; and it is the only value in reach that means anything, because an
edge's identity **is** the child — the end of it that can only be in one.

`position` is `row_number() over (partition by <parent> order by created_at, id) - 1`, which is
`Sort.by("createdAt").and("id")` — `FeatureRepository.listByEpic`'s and `TaskRepository.listByFeature`'s
own sort — made dense and zero-based. Never insertion order: a heap scan's order changes with a
VACUUM and between databases, and would produce a plausible dense sequence that silently disagreed
with the listing.

## Where the code is

| | |
| --- | --- |
| migrations | `epics/src/main/resources/db/epics/migration/V9__entity_membership.sql`, `V10__backfill_unified.sql` |
| entities | `epics/…/entity/Archetype.java`, `WorkEntity.java`, `EntityMembership.java` |
| repositories | `epics/…/persistence/WorkEntityRepository.java`, `EntityMembershipRepository.java` |
| the registry | `epics/…/control/Archetypes.java`, `ArchetypeSpec.java`, `EntityProperty.java`, `EntityState.java`, `ArchetypeViolation.java` |
| the two cut-over services | `epics/…/control/EpicService.java`, `TicketService.java`, `WorkEntityProjections.java` |
| the nesting rule | `epics/…/control/Nesting.java`, `EntityFact.java`, `EntityFacts.java`, `NestingViolation.java` |
| tests | `epics/src/test/…/control/ArchetypesTest.java`, `NestingTest.java`; `…/persistence/WorkEntityPersistenceTest.java`; `…/migration/EntityMembershipMigrationTest.java`, `…/migration/UnifiedBackfillMigrationTest.java` |

The rule tests are plain JUnit and boot no application: a `@TestProfile` is a whole Quarkus app at
roughly 125 MB of retained metaspace inside a 4 GB CI step, and rules that are pure functions should
cost none of it. The persistence test is the one that has to be a `@QuarkusTest` — Flyway owns the
DDL and `database.generation` is `none`, so a column name that disagrees with V9 is invisible until
the first query — and it adds no profile of its own.
